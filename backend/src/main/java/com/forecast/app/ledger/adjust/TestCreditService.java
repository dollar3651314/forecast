package com.forecast.app.ledger.adjust;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.id.Snowflake;
import com.forecast.app.common.push.UserEventsApi;
import com.forecast.app.ledger.api.AccountKind;
import com.forecast.app.ledger.api.AccountSnapshot;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.ledger.api.PlatformLedgerApi;
import com.forecast.app.ledger.api.TestCreditApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TestCreditApi} 的实现（forecast S17A）。一个事务（READ COMMITTED）：锁客户账户行 → 按请求号查重 → 写测试入金单 → 客户记账（TEST_CREDIT）→
 * 平台"测试入金"账户记账（TEST_CREDIT_EXPENSE，最后锁平台账户行，和补偿同一个顺序）。提交以后推一个 {"type":"account"} 给客户，客户端刷新余额。
 * <p>同一客户的请求在账户行锁上排队，所以同一个请求号的第二次一定能读到第一次写的单子；不同客户用了同一个请求号由唯一键挡住（409）。
 * 先锁一行确定存在的行（客户账户）再普通读请求号，不对可能不存在的行加锁读（项目地图约定 34）。
 */
@Service
class TestCreditService implements TestCreditApi {

  private static final Logger log = LoggerFactory.getLogger(TestCreditService.class);
  static final String BIZ = "TEST_CREDIT";
  /** 测试环境的环境标识（部署工具包 docker-compose.yml 固定写的 FC_ENV_LABEL=test） */
  static final String TEST_ENV = "test";
  /** 请求号：和追踪号同一个规则（项目地图约定 19） */
  static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

  private final AdjustMapper mapper;
  private final LedgerApi ledger;
  private final PlatformLedgerApi platform;
  private final SystemConfig config;
  private final Snowflake ids;
  private final UserEventsApi events;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final boolean enabled;

  TestCreditService(AdjustMapper mapper, LedgerApi ledger, PlatformLedgerApi platform, SystemConfig config, Snowflake ids, UserEventsApi events, Clock clock,
      PlatformTransactionManager tm, @Value("${forecast.env-label:}") String envLabel) {
    this.mapper = mapper;
    this.ledger = ledger;
    this.platform = platform;
    this.config = config;
    this.ids = ids;
    this.events = events;
    this.clock = clock;
    this.tx = new TransactionTemplate(tm);
    this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.enabled = TEST_ENV.equals(envLabel == null ? "" : envLabel.strip());
  }

  @Override
  public boolean enabled() {
    return enabled;
  }

  @Override
  public BigDecimal maxAmount() {
    return config.decimal(ConfigKey.TEST_CREDIT_MAX, new BigDecimal("10000"));
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  @Override
  public Result credit(long userId, BigDecimal rawAmount, String reason, String requestId, long adminId) {
    if (!enabled) throw new BizException("NOT_FOUND", "测试入金只在测试环境可用");
    ValidationException.Collector bad = new ValidationException.Collector();
    BigDecimal amount = null;
    if (rawAmount == null) bad.add("amount", "required");
    else if (rawAmount.signum() <= 0 || rawAmount.stripTrailingZeros().scale() > 8) bad.add("amount", "invalid");
    else amount = rawAmount.setScale(8, java.math.RoundingMode.UNNECESSARY);
    if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) bad.add("requestId", "invalid");
    if (reason == null || reason.isBlank()) bad.add("reason", "required");
    bad.throwIfAny();
    BigDecimal max = maxAmount();
    if (amount.compareTo(max) > 0) {
      throw new BizException("ADJUST_OVER_LIMIT", "超过测试入金的单笔上限 " + max.stripTrailingZeros().toPlainString() + " USDT（参数设置可改）");
    }
    BigDecimal amt = amount;
    Result r;
    try {
      r = tx.execute(st -> creditInTx(userId, amt, reason.strip(), requestId, adminId));
    } catch (DuplicateKeyException e) {
      throw reused();
    }
    if (!r.duplicate()) {
      events.send(userId, Map.of("type", "account"));
      log.info("测试入金 {}：客户 {} +{}（管理员 {}）", r.creditNo(), userId, amt.toPlainString(), adminId);
    }
    return r;
  }

  private Result creditInTx(long userId, BigDecimal amount, String reason, String requestId, long adminId) {
    AccountSnapshot before = ledger.lock(userId);
    AdjustMapper.TestCreditRow old = mapper.testCreditByRequest(requestId);
    if (old != null) {
      if (old.userId != userId || old.amount.compareTo(amount) != 0) throw reused();
      return result(old, true);
    }
    AdjustMapper.TestCreditRow row = new AdjustMapper.TestCreditRow();
    row.publicNo = ids.next();
    row.userId = userId;
    row.amount = amount;
    row.availableBefore = before.available();
    row.availableAfter = before.available().add(amount);
    row.reason = reason.length() <= 500 ? reason : reason.substring(0, 500);
    row.requestId = requestId;
    row.createdBy = adminId;
    row.createdAt = now();
    mapper.insertTestCredit(row);
    String bizId = String.valueOf(row.id);
    ledger.apply(new LedgerCommand(userId, Bucket.AVAILABLE, amount, LedgerEntryType.TEST_CREDIT, BIZ, bizId, "测试入金"));
    platform.apply(new PlatformLedgerApi.Command(AccountKind.PLATFORM_TEST_CREDIT, amount.negate(), LedgerEntryType.TEST_CREDIT_EXPENSE, BIZ, bizId,
        "测试入金（客户 " + userId + "）", null));
    return result(row, false);
  }

  private static BizException reused() {
    return new BizException("ADJUST_REQUEST_REUSED", "这个请求号已经用在另一笔操作上，请关闭确认框重新操作");
  }

  private static Result result(AdjustMapper.TestCreditRow r, boolean duplicate) {
    return new Result(r.publicNo, r.userId, r.amount, r.availableBefore, r.availableAfter, r.reason, r.createdBy, r.createdAt, duplicate);
  }

  @Override
  public Optional<Result> find(long creditNo) {
    AdjustMapper.TestCreditRow r = mapper.testCreditByNo(creditNo);
    return r == null ? Optional.empty() : Optional.of(result(r, false));
  }
}
