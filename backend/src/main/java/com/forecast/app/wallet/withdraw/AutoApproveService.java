package com.forecast.app.wallet.withdraw;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.account.api.KycStatus;
import com.forecast.app.account.api.SecurityEvent;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.ledger.api.CustomerFlowApi;
import com.forecast.app.wallet.api.AutoApprove;
import com.forecast.app.wallet.api.AutoApprove.Settings;
import com.forecast.app.wallet.api.AutoApproveAdminApi;
import com.forecast.app.wallet.api.HotWalletApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 提现自动审批（技术方案/v2-提现自动审批.md）：提交时预判（只做参考、不占额度）、冷静期结束时复判（决定自动通过还是转人工，在 {@link WithdrawService} 的同一个事务里），
 * 判定记录和触发的规则写库，后台查看判定记录和当前配置。
 * <ul>
 *   <li>配置从系统配置一次读出（{@link SystemConfig#snapshot()}），整份原样存进判定记录（快照），以后改规则不影响历史记录的解读。</li>
 *   <li>判定不访问网络：热钱包余额读热钱包余额告警定时查好的值（{@link HotWalletApi}），其余都读数据库。</li>
 *   <li>并发（第 5 节）：复判时调用方先锁提现单，总开关打开时这里再锁全局锁那一行，R2 / R3 的累计在锁里用 SQL 现算；提交以后才释放，下一笔看到的累计就包含了这笔。
 *       顺序固定是"提现单 → 全局锁"，只有自动审批拿全局锁，不会死锁。预判和总开关关闭时不拿全局锁（不占额度）。</li>
 * </ul>
 */
@Service
public class AutoApproveService implements AutoApproveAdminApi {

  private static final Logger log = LoggerFactory.getLogger(AutoApproveService.class);
  private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {};

  static final String PRECHECK = "PRECHECK";
  static final String FINAL = "FINAL";

  private final AutoApproveMapper mapper;
  private final WithdrawMapper withdrawals;
  private final SystemConfig config;
  private final AccountApi account;
  private final CustomerFlowApi flows;
  private final HotWalletApi hot;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final Clock clock;

  AutoApproveService(AutoApproveMapper mapper, WithdrawMapper withdrawals, SystemConfig config, AccountApi account, CustomerFlowApi flows,
      HotWalletApi hot, ObjectMapper json, TransactionTemplate tx, Clock clock) {
    this.mapper = mapper;
    this.withdrawals = withdrawals;
    this.config = config;
    this.account = account;
    this.flows = flows;
    this.hot = hot;
    this.json = json;
    this.tx = tx;
    this.clock = clock;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  @Override
  public Settings settings() {
    return AutoApprove.parse(config.snapshot());
  }

  // ---------- 判定 ----------

  /**
   * 提交时预判（AA-1）：提交事务提交以后单独跑一次，不加全局锁、不占额度，写一条 PRECHECK 记录，后台冷静期中的提现显示"预判 · …"。
   * 出错只记日志，不影响提交。冷静期已经结束（冷静期设成 0 小时时可能和复判赛跑）或已经有判定记录时不写。
   */
  void precheck(long withdrawalId) {
    try {
      Settings s = settings();
      tx.executeWithoutResult(st -> {
        WithdrawalRow w = withdrawals.lock(withdrawalId);
        if (w == null || !"COOLING".equals(w.status) || w.lastCheckId != null) return;
        record(w, PRECHECK, s, evaluate(s, w), now());
      });
    } catch (RuntimeException e) {
      log.warn("提现 {} 的自动审批预判失败（只影响后台的参考信息）：{}", withdrawalId, e.toString());
    }
  }

  /** 复判的结果 */
  record Decision(long checkId, boolean approved, long configVersion) {}

  /**
   * 冷静期结束时复判（以这次为准）。调用方已经在事务里锁住了提现单、确认还在冷静期；总开关打开时这里锁全局锁。
   * 写 FINAL 判定记录（含配置快照），换掉预判时写的"触发的规则"。
   */
  Decision decideFinal(WithdrawalRow w, Settings s) {
    if (s.masterEnabled()) mapper.lockGlobal();
    AutoApproveEngine.Evaluation e = evaluate(s, w);
    long id = record(w, FINAL, s, e, now());
    return new Decision(id, AutoApproveEngine.AUTO_APPROVED.equals(e.outcome()), s.version());
  }

  /** 自动通过：review_mode = AUTO、自动通过时间（R2 / R3 按它累计）；在复判的同一个事务里 */
  void markApproved(long withdrawalId, LocalDateTime at, LocalDateTime delayEndsAt) {
    mapper.setAutoApproved(withdrawalId, at, delayEndsAt);
  }

  /** 复判整个出错（例如写库失败）以后的兜底：记一条 ERROR（每条规则都是"无法判定"），调用方随后转人工 */
  void recordError(WithdrawalRow w, Settings s, String why) {
    List<AutoApproveEngine.Result> rs = Arrays.stream(AutoApprove.Rule.values()).map(r -> new AutoApproveEngine.Result(r.name(), AutoApproveEngine.UNKNOWN,
        Map.of("why", why))).toList();
    record(w, FINAL, s, new AutoApproveEngine.Evaluation(rs, rs.stream().map(AutoApproveEngine.Result::code).toList(), AutoApproveEngine.ERROR), now());
  }

  private AutoApproveEngine.Evaluation evaluate(Settings s, WithdrawalRow w) {
    return AutoApproveEngine.evaluate(s, AutoApproveEngine.Withdrawal.of(w), now(), clock.instant(), facts);
  }

  private long record(WithdrawalRow w, String phase, Settings s, AutoApproveEngine.Evaluation e, LocalDateTime at) {
    AutoApproveMapper.CheckRow r = new AutoApproveMapper.CheckRow();
    r.withdrawalId = w.id;
    r.phase = phase;
    r.outcome = e.outcome();
    r.masterEnabled = s.masterEnabled();
    r.failedRules = String.join(",", e.failed());
    r.configVersion = s.version();
    Map<String, Object> cfg = new LinkedHashMap<>();
    cfg.put("masterEnabled", s.masterEnabled());
    cfg.put("version", s.version());
    cfg.put("rules", s.rules());
    r.configJson = json.writeValueAsString(cfg);
    r.resultsJson = json.writeValueAsString(e.results());
    r.decidedAt = at;
    mapper.insertCheck(r);
    List<Long> old = mapper.hitIds(w.id);
    if (!old.isEmpty()) mapper.deleteHits(old);
    if (!e.failed().isEmpty()) mapper.insertHits(r.id, w.id, e.failed());
    mapper.setLastCheck(w.id, r.id);
    w.lastCheckId = r.id;
    if (FINAL.equals(phase) && !AutoApproveEngine.AUTO_APPROVED.equals(e.outcome())) {
      log.info("提现 {} 复判转人工：{}（{}，配置第 {} 版）", w.id, e.outcome(), r.failedRules, s.version());
    }
    return r.id;
  }

  /** 判定用的数据：都读数据库或内存，不访问网络 */
  private final AutoApproveEngine.Facts facts = new AutoApproveEngine.Facts() {
    @Override
    public BigDecimal autoApprovedSince(Long userId, LocalDateTime since) {
      return mapper.autoApprovedSince(userId, since);
    }

    @Override
    public long completedCount(long userId) {
      return mapper.completedCount(userId);
    }

    @Override
    public long completedToAddress(long userId, String network, String address) {
      return mapper.completedToAddress(userId, network, address);
    }

    @Override
    public Optional<SecurityEvent> lastSecurityEventSince(long userId, LocalDateTime since) {
      return account.lastSecurityEventSince(userId, since);
    }

    @Override
    public LocalDateTime registeredAt(long userId) {
      return account.registeredAt(userId);
    }

    @Override
    public KycStatus kycStatus(long userId) {
      return account.kycStatus(userId);
    }

    @Override
    public CustomerFlowApi.Deposits depositsSince(long userId, LocalDateTime since) {
      return flows.depositsSince(userId, since);
    }

    @Override
    public BigDecimal openedNotionalSince(long userId, LocalDateTime since) {
      return flows.openedNotionalSince(userId, since);
    }

    @Override
    public BigDecimal withdrawnTotal(long userId) {
      return mapper.withdrawnTotal(userId);
    }

    @Override
    public BigDecimal netDeposits(long userId) {
      return flows.netDeposits(userId);
    }

    @Override
    public BigDecimal realizedPnl(long userId, LocalDateTime since) {
      return flows.realizedPnl(userId, since);
    }

    @Override
    public CustomerFlowApi.NetProfit netProfit(long userId) {
      return flows.netProfit(userId);
    }

    @Override
    public BigDecimal completedAmount(long userId) {
      return mapper.completedAmount(userId);
    }

    @Override
    public long countOnDay(long userId, LocalDateTime from, LocalDateTime to, long upToId) {
      return mapper.countOnDay(userId, from, to, upToId);
    }

    @Override
    public Optional<AutoApproveEngine.HotBalance> hotBalance(String network) {
      return hot.hotBalances().stream().filter(b -> b.chain().equals(network) && !b.gas()).findFirst()
          .map(b -> new AutoApproveEngine.HotBalance(b.balance(), b.checkedAt(), b.error()));
    }

    @Override
    public BigDecimal queuedNet(String network) {
      return mapper.queuedNet(network);
    }
  };

  // ---------- 后台查看 ----------

  private static Summary summary(AutoApproveMapper.CheckRow r) {
    List<String> failed = r.failedRules == null || r.failedRules.isBlank() ? List.of() : List.of(r.failedRules.split(","));
    return new Summary(r.id, r.phase, r.outcome, failed, r.decidedAt);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, Summary> latest(Collection<Long> withdrawalIds) {
    if (withdrawalIds == null || withdrawalIds.isEmpty()) return Map.of();
    return mapper.latest(withdrawalIds.stream().distinct().toList()).stream().collect(Collectors.toMap(r -> r.withdrawalId, AutoApproveService::summary));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Detail> detail(long withdrawalId) {
    WithdrawalRow w = withdrawals.byId(withdrawalId);
    if (w == null || w.lastCheckId == null) return Optional.empty();
    AutoApproveMapper.CheckRow r = mapper.check(w.lastCheckId);
    if (r == null) return Optional.empty();
    List<RuleResult> rules = json.readValue(r.resultsJson, LIST).stream().map(m -> {
      Map<String, String> values = new LinkedHashMap<>();
      if (m.get("values") instanceof Map<?, ?> v) v.forEach((k, x) -> values.put(String.valueOf(k), x == null ? null : String.valueOf(x)));
      return new RuleResult(String.valueOf(m.get("code")), String.valueOf(m.get("status")), values);
    }).toList();
    Summary pre = null;
    if (FINAL.equals(r.phase)) {
      AutoApproveMapper.CheckRow p = mapper.latestPrecheck(withdrawalId);
      if (p != null) pre = summary(p);
    }
    return Optional.of(new Detail(summary(r), r.masterEnabled, r.configVersion, rules, pre));
  }
}
