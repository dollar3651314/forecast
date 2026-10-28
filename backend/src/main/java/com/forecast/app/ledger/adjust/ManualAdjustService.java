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
import com.forecast.app.ledger.api.ManualAdjustApi;
import com.forecast.app.ledger.api.PlatformLedgerApi;
import com.forecast.app.notification.api.NotifyApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ManualAdjustApi} 的实现（forecast S17B）。一个事务（READ COMMITTED）：锁客户账户行 → 按请求号查重 → 检查（上限、扣款不超过可用余额）→
 * 写调账单 → 客户记账 → 平台"人工调账"账户记账（最后锁平台账户行，和补偿、测试入金同一个顺序）→ 通知 #24（同一事务，回滚就不发，约定 10）。
 * 提交以后推 {"type":"account"} 给客户。
 * <p>账本的来源业务（幂等键的 biz_type）："充值未到账补入"用 {@link #BIZ_DEPOSIT}，其他用 {@link #BIZ}——账本按它判断哪些人工加款当充值算
 * （{@code LedgerMapper.depositsSince}，SQ-58），不用去读调账单。
 */
@Service
class ManualAdjustService implements ManualAdjustApi {

  private static final Logger log = LoggerFactory.getLogger(ManualAdjustService.class);
  static final String BIZ = "ADJUST";
  static final String BIZ_DEPOSIT = "ADJUST_DEPOSIT";
  static final int REF_MAX = 128;

  /** 通知里的方向和类别：[简体, 英文, 繁体] */
  static final Map<Direction, String[]> DIR_TEXT = Map.of(
      Direction.CREDIT, new String[] {"加款", "Credit", "加款"},
      Direction.DEBIT, new String[] {"扣款", "Debit", "扣款"});
  static final Map<Category, String[]> CATEGORY_TEXT = Map.of(
      Category.DEPOSIT_MISSING, new String[] {"充值未到账补入", "Missing deposit credited", "充值未到帳補入"},
      Category.WRONG_CREDIT, new String[] {"误入账扣回", "Erroneous credit reversed", "誤入帳扣回"},
      Category.OTHER, new String[] {"其他", "Other", "其他"});

  private final AdjustMapper mapper;
  private final LedgerApi ledger;
  private final PlatformLedgerApi platform;
  private final NotifyApi notify;
  private final SystemConfig config;
  private final Snowflake ids;
  private final UserEventsApi events;
  private final Clock clock;
  private final TransactionTemplate tx;

  ManualAdjustService(AdjustMapper mapper, LedgerApi ledger, PlatformLedgerApi platform, NotifyApi notify, SystemConfig config, Snowflake ids,
      UserEventsApi events, Clock clock, PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.ledger = ledger;
    this.platform = platform;
    this.notify = notify;
    this.config = config;
    this.ids = ids;
    this.events = events;
    this.clock = clock;
    this.tx = new TransactionTemplate(tm);
    this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Override
  public BigDecimal maxAmount() {
    return config.decimal(ConfigKey.MANUAL_ADJUST_MAX, new BigDecimal("10000"));
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  @Override
  public Adjustment adjust(Request r) {
    ValidationException.Collector bad = new ValidationException.Collector();
    if (r.direction() == null) bad.add("direction", "required");
    if (r.category() == null) bad.add("category", "required");
    else if (r.direction() != null && !r.category().allows(r.direction())) bad.add("category", "invalid");
    BigDecimal amount = null;
    if (r.amount() == null) bad.add("amount", "required");
    else if (r.amount().signum() <= 0 || r.amount().stripTrailingZeros().scale() > 8) bad.add("amount", "invalid");
    else amount = r.amount().setScale(8, RoundingMode.UNNECESSARY);
    if (r.reason() == null || r.reason().isBlank()) bad.add("reason", "required");
    String refNo = r.refNo() == null || r.refNo().isBlank() ? null : r.refNo().strip();
    if (refNo != null && refNo.length() > REF_MAX) bad.add("refNo", "tooLong");
    if (r.requestId() == null || !TestCreditService.REQUEST_ID.matcher(r.requestId()).matches()) bad.add("requestId", "invalid");
    bad.throwIfAny();
    BigDecimal max = maxAmount();
    boolean overLimit = amount.compareTo(max) > 0;
    if (overLimit && !r.overLimitConfirmed()) {
      throw new BizException("ADJUST_OVER_LIMIT_UNCONFIRMED", "超过人工调账的单笔上限 " + plain(max) + " USDT：要在确认框里再勾\"我确认超过单笔上限\"");
    }
    BigDecimal amt = amount;
    Adjustment out;
    try {
      out = tx.execute(st -> adjustInTx(r, amt, refNo, overLimit));
    } catch (DuplicateKeyException e) {
      throw reused();
    }
    if (!out.duplicate()) {
      events.send(r.userId(), Map.of("type", "account"));
      log.info("人工调账 {}：客户 {} {} {}（{}，管理员 {}）", out.adjustNo(), r.userId(), r.direction(), plain(amt), r.category(), r.adminId());
    }
    return out;
  }

  private Adjustment adjustInTx(Request r, BigDecimal amount, String refNo, boolean overLimit) {
    AccountSnapshot before = ledger.lock(r.userId());
    AdjustMapper.AdjustRow old = mapper.adjustByRequest(r.requestId());
    if (old != null) {
      if (old.userId != r.userId() || !old.direction.equals(r.direction().name()) || !old.category.equals(r.category().name())
          || old.amount.compareTo(amount) != 0) {
        throw reused();
      }
      return adjustment(old, true);
    }
    boolean credit = r.direction() == Direction.CREDIT;
    if (!credit && before.available().compareTo(amount) < 0) {
      throw new BizException("ADJUST_INSUFFICIENT", "扣款不能超过可用余额（现在可用 " + plain(before.available()) + " USDT；下注中、提现冻结的钱不能扣）");
    }
    AdjustMapper.AdjustRow row = new AdjustMapper.AdjustRow();
    row.publicNo = ids.next();
    row.userId = r.userId();
    row.direction = r.direction().name();
    row.category = r.category().name();
    row.amount = amount;
    row.availableBefore = before.available();
    row.availableAfter = credit ? before.available().add(amount) : before.available().subtract(amount);
    row.refNo = refNo;
    String reason = r.reason().strip();
    row.reason = reason.length() <= 500 ? reason : reason.substring(0, 500);
    row.overLimit = overLimit;
    row.requestId = r.requestId();
    row.createdBy = r.adminId();
    row.createdAt = now();
    mapper.insertAdjust(row);
    String bizType = r.category() == Category.DEPOSIT_MISSING ? BIZ_DEPOSIT : BIZ;
    String bizId = String.valueOf(row.id);
    String remark = "人工调账（" + CATEGORY_TEXT.get(r.category())[0] + "）";
    ledger.apply(new LedgerCommand(r.userId(), Bucket.AVAILABLE, credit ? amount : amount.negate(), credit ? LedgerEntryType.MANUAL_CREDIT : LedgerEntryType.MANUAL_DEBIT,
        bizType, bizId, remark));
    platform.apply(new PlatformLedgerApi.Command(AccountKind.PLATFORM_MANUAL_ADJUST, credit ? amount.negate() : amount,
        credit ? LedgerEntryType.MANUAL_ADJUST_OUT : LedgerEntryType.MANUAL_ADJUST_IN, bizType, bizId, remark + "（客户 " + r.userId() + "）", null));
    Map<String, String> v = new LinkedHashMap<>();
    String[] d = DIR_TEXT.get(r.direction());
    String[] c = CATEGORY_TEXT.get(r.category());
    v.put("dir", d[0]);
    v.put("dirEn", d[1]);
    v.put("dirTw", d[2]);
    v.put("category", c[0]);
    v.put("categoryEn", c[1]);
    v.put("categoryTw", c[2]);
    v.put("amount", money(amount));
    v.put("available", money(row.availableAfter));
    v.put("adjustNo", String.valueOf(row.publicNo));
    notify.toUser(r.userId(), "BALANCE_ADJUSTED", v);
    return adjustment(row, false);
  }

  private static BizException reused() {
    return new BizException("ADJUST_REQUEST_REUSED", "这个请求号已经用在另一笔操作上，请关闭确认框重新操作");
  }

  static String plain(BigDecimal v) {
    return v.stripTrailingZeros().toPlainString();
  }

  static String money(BigDecimal v) {
    return v.setScale(2, RoundingMode.DOWN).toPlainString();
  }

  private static Adjustment adjustment(AdjustMapper.AdjustRow r, boolean duplicate) {
    return new Adjustment(r.publicNo, r.userId, Direction.valueOf(r.direction), Category.valueOf(r.category), r.amount, r.availableBefore, r.availableAfter,
        r.refNo, r.reason, r.overLimit, r.createdBy, r.createdAt, duplicate);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Adjustment> list(Query q, int offset, int limit) {
    return mapper.listAdjusts(q.userId(), name(q.direction()), name(q.category()), q.from(), q.to(), offset, limit).stream().map(r -> adjustment(r, false))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public long count(Query q) {
    return mapper.countAdjusts(q.userId(), name(q.direction()), name(q.category()), q.from(), q.to());
  }

  private static String name(Enum<?> e) {
    return e == null ? null : e.name();
  }

  @Override
  @Transactional(readOnly = true)
  public List<CreditSignal> creditsAfter(long afterId, int limit) {
    return mapper.creditsAfter(afterId, limit).stream().map(r -> new CreditSignal(r.id, r.publicNo, r.userId, r.amount, r.createdAt)).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public BigDecimal creditedBetween(long userId, LocalDateTime from, LocalDateTime to, long upToId) {
    BigDecimal v = mapper.creditedBetween(userId, from, to, upToId);
    return v == null ? BigDecimal.ZERO : v;
  }
}
