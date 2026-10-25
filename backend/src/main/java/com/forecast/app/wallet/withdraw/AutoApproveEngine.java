package com.forecast.app.wallet.withdraw;

import com.forecast.app.account.api.KycStatus;
import com.forecast.app.account.api.SecurityEvent;
import com.forecast.app.wallet.api.AutoApprove;
import com.forecast.app.wallet.api.AutoApprove.Rule;
import com.forecast.app.wallet.api.AutoApprove.Settings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提现自动审批的 15 条规则判定（复制源的技术方案 v2-提现自动审批 第 4 节；forecast S10 按需求说明书第 8.2 节改 R8～R10、R13，加 R14、R15）。纯计算：数据经 {@link Facts} 取，测试里可以换成假的。
 * <ul>
 *   <li>"现在"= 判定时刻（UTC）。时间窗口都是滚动的，R11 例外（按 UTC 日历日）。金额都用 {@link BigDecimal} 精确比较。</li>
 *   <li>边界：写"≤"的等于算通过，写"≥"的等于算通过；"N 小时内没有"指 (现在 − N 小时, 现在] 里没有，正好 N 小时前的不算"内"。</li>
 *   <li>规则关闭时不判定（状态 disabled）；判定时出错、数据读不到、配置值不合法：这条记"无法判定"（unknown），<b>按不通过处理</b>，整笔转人工。</li>
 * </ul>
 */
final class AutoApproveEngine {

  static final String PASSED = "passed";
  static final String FAILED = "failed";
  static final String DISABLED = "disabled";
  static final String UNKNOWN = "unknown";

  static final String AUTO_APPROVED = "AUTO_APPROVED";
  static final String MANUAL = "MANUAL";
  static final String MASTER_OFF = "MASTER_OFF";
  static final String ERROR = "ERROR";

  /** 热钱包余额超过这么久没查成功算"查不到"（AA-10） */
  static final Duration HOT_BALANCE_MAX_AGE = Duration.ofMinutes(10);

  private AutoApproveEngine() {}

  /** 要判定的提现：amount 是申请金额（含手续费，客户冻结的金额） */
  record Withdrawal(long id, long userId, String network, String address, BigDecimal amount, BigDecimal fee, LocalDateTime createdAt) {

    static Withdrawal of(WithdrawalRow w) {
      return new Withdrawal(w.id, w.userId, w.network, w.address, w.amount, w.fee, w.createdAt);
    }

    /** 链上到账金额 */
    BigDecimal net() {
      return amount.subtract(fee);
    }
  }

  /**
   * 热钱包 USDT 余额（热钱包余额告警定时查好的值，判定不访问网络）。
   *
   * @param balance 最近一次查询成功的余额；没有时为空
   * @param checkedAt 最近一次查询成功的时间
   * @param error 最近一次查询失败的原因（最近一次成功时为空）
   */
  record HotBalance(BigDecimal balance, Instant checkedAt, String error) {}

  /** 判定要用的数据。任何一个方法抛异常，用到它的规则记"无法判定" */
  interface Facts {
    /** 自动通过（review_mode = AUTO）、auto_approved_at 在 (since, 现在] 的金额合计；userId 为空表示全平台。所有状态都算（AA-3） */
    BigDecimal autoApprovedSince(Long userId, LocalDateTime since);

    /** 该客户已完成的提现笔数 */
    long completedCount(long userId);

    /** 该客户往同一网络、同一地址（以太坊体系不分大小写）已完成的提现笔数 */
    long completedToAddress(long userId, String network, String address);

    /** (since, 现在] 里最近一条安全事件 */
    Optional<SecurityEvent> lastSecurityEventSince(long userId, LocalDateTime since);

    LocalDateTime registeredAt(long userId);

    KycStatus kycStatus(long userId);

    /** 从 since 起入账的充值：净额（扣冲正）和最早一笔的入账时间 */
    com.forecast.app.ledger.api.CustomerFlowApi.Deposits depositsSince(long userId, LocalDateTime since);

    /** 从 since 起（按收到下注的时间）的有效下注本金（退回的、取消 / 作废市场里的不算；R8） */
    BigDecimal effectiveStakeSince(long userId, LocalDateTime since);

    /** 不是驳回、撤销、失败已退回的提现金额合计（含本笔和进行中的） */
    BigDecimal withdrawnTotal(long userId);

    /** 累计充值 − 冲正 */
    BigDecimal netDeposits(long userId);

    /** 已结算的盈亏（派彩、补偿、已结算本金、抽成）；since 为空表示从开户到现在（R9、R10、R13） */
    com.forecast.app.ledger.api.CustomerFlowApi.Realized realized(long userId, LocalDateTime since);

    /** R14：从 since 起有派彩、且有关联账户押了其他选项的市场和这部分盈利 */
    com.forecast.app.riskalert.api.RiskAlertApi.LinkedOpposite linkedOpposite(long userId, LocalDateTime since);

    /** R15：从 since 起还没处理的"新账户押中冷门""尾盘大额"告警条数 */
    long openSuspiciousAlerts(long userId, LocalDateTime since);

    /** 已完成提现的金额合计 */
    BigDecimal completedAmount(long userId);

    /** 该客户 created_at 在 [from, to) 里、id ≤ 本笔的提现笔数，客户自己撤销的不算 */
    long countOnDay(long userId, LocalDateTime from, LocalDateTime to, long upToId);

    /** 该链热钱包 USDT 余额；没有配置这条链的热钱包时为空 */
    Optional<HotBalance> hotBalance(String network);

    /** 同一条链上"已审核待上链"和"延迟中"的到账金额合计（金额 − 手续费） */
    BigDecimal queuedNet(String network);

    /** 待处理的彩池对账差异条数（forecast S5：R12 新增"彩池对账没有未处理的差异"，需求第 8.2 节、AC-38） */
    long openPoolReconIssues();
  }

  /** 一条规则的结果 */
  record Result(String code, String status, Map<String, String> values) {}

  /** 整笔的判定 */
  record Evaluation(List<Result> results, List<String> failed, String outcome) {}

  static Evaluation evaluate(Settings s, Withdrawal w, LocalDateTime now, Instant nowInstant, Facts f) {
    List<Result> results = new ArrayList<>();
    for (Rule r : Rule.values()) results.add(one(s, r, w, now, nowInstant, f));
    List<String> failed = results.stream().filter(x -> FAILED.equals(x.status()) || UNKNOWN.equals(x.status())).map(Result::code).toList();
    String outcome;
    if (!s.masterEnabled()) outcome = MASTER_OFF;
    else if (results.stream().anyMatch(x -> UNKNOWN.equals(x.status()))) outcome = ERROR;
    else if (!failed.isEmpty()) outcome = MANUAL;
    else outcome = AUTO_APPROVED;
    return new Evaluation(List.copyOf(results), failed, outcome);
  }

  /** 配置值不合法 */
  static final class BadConfig extends RuntimeException {
    BadConfig() {
      super("配置值不合法");
    }
  }

  private static Result one(Settings s, Rule r, Withdrawal w, LocalDateTime now, Instant nowInstant, Facts f) {
    if (!s.rule(r).enabled()) return new Result(r.name(), DISABLED, Map.of());
    try {
      Checked c = check(s, r, w, now, nowInstant, f);
      return new Result(r.name(), c.passed ? PASSED : FAILED, c.values);
    } catch (BadConfig e) {
      return new Result(r.name(), UNKNOWN, Map.of("why", "配置值不合法"));
    } catch (RuntimeException e) {
      return new Result(r.name(), UNKNOWN, Map.of("why", "数据读取失败"));
    }
  }

  private record Checked(boolean passed, Map<String, String> values) {}

  private static BigDecimal param(Settings s, Rule r, String key) {
    return s.param(r, key).orElseThrow(BadConfig::new);
  }

  private static String p(BigDecimal v) {
    return AutoApprove.plain(v);
  }

  private static Map<String, String> vals(String... kv) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
    return m;
  }

  private static Checked check(Settings s, Rule r, Withdrawal w, LocalDateTime now, Instant nowInstant, Facts f) {
    return switch (r) {
      case R1 -> {
        BigDecimal limit = param(s, r, "maxAmount");
        yield new Checked(w.amount().compareTo(limit) <= 0, vals("amount", p(w.amount()), "limit", p(limit)));
      }
      case R2 -> {
        BigDecimal limit = param(s, r, "maxUser24h");
        BigDecimal used = f.autoApprovedSince(w.userId(), now.minusHours(24));
        BigDecimal total = used.add(w.amount());
        yield new Checked(total.compareTo(limit) <= 0, vals("used", p(used), "amount", p(w.amount()), "total", p(total), "limit", p(limit)));
      }
      case R3 -> {
        BigDecimal limit = param(s, r, "maxPlatform24h");
        BigDecimal used = f.autoApprovedSince(null, now.minusHours(24));
        BigDecimal total = used.add(w.amount());
        yield new Checked(total.compareTo(limit) <= 0, vals("used", p(used), "amount", p(w.amount()), "total", p(total), "limit", p(limit)));
      }
      case R4 -> {
        long n = f.completedCount(w.userId());
        yield new Checked(n >= 1, vals("count", String.valueOf(n)));
      }
      case R5 -> {
        long n = f.completedToAddress(w.userId(), w.network(), w.address());
        yield new Checked(n >= 1, vals("count", String.valueOf(n)));
      }
      case R6 -> {
        BigDecimal hours = param(s, r, "hours");
        Optional<SecurityEvent> e = f.lastSecurityEventSince(w.userId(), now.minusHours(hours.longValueExact()));
        if (e.isEmpty()) yield new Checked(true, vals("hours", p(hours)));
        BigDecimal ago = BigDecimal.valueOf(Duration.between(e.get().at(), now).toMinutes()).divide(BigDecimal.valueOf(60), 1, RoundingMode.DOWN);
        yield new Checked(false, vals("hours", p(hours), "event", e.get().type().toLowerCase(), "ago", ago.max(BigDecimal.ZERO).toPlainString()));
      }
      case R7 -> {
        BigDecimal days = param(s, r, "days");
        Duration age = Duration.between(f.registeredAt(w.userId()), now);
        KycStatus kyc = f.kycStatus(w.userId());
        boolean ok = age.compareTo(Duration.ofHours(24 * days.longValueExact())) >= 0 && kyc == KycStatus.APPROVED;
        yield new Checked(ok, vals("days", String.valueOf(Math.max(0, age.toDays())), "limit", p(days), "kyc", kyc.name().toLowerCase()));
      }
      case R8 -> {
        BigDecimal multiple = param(s, r, "multiple");
        var d = f.depositsSince(w.userId(), now.minusDays(30));
        if (d.net().signum() <= 0 || d.firstAt() == null) {
          yield new Checked(true, vals("volume", "0", "deposit", p(d.net().max(BigDecimal.ZERO)), "ratio", "—", "limit", p(multiple), "note", "noDeposit"));
        }
        BigDecimal volume = f.effectiveStakeSince(w.userId(), d.firstAt());
        BigDecimal ratio = volume.divide(d.net(), 2, RoundingMode.DOWN);
        yield new Checked(volume.compareTo(d.net().multiply(multiple)) >= 0,
            vals("volume", p(volume), "deposit", p(d.net()), "ratio", p(ratio), "limit", p(multiple)));
      }
      case R9 -> {
        BigDecimal withdrawn = f.withdrawnTotal(w.userId());
        BigDecimal deposit = f.netDeposits(w.userId());
        BigDecimal pnl = f.realized(w.userId(), null).net();
        BigDecimal cap = deposit.add(pnl);
        yield new Checked(withdrawn.compareTo(cap) <= 0, vals("withdrawn", p(withdrawn), "deposit", p(deposit), "pnl", p(pnl), "cap", p(cap)));
      }
      case R10 -> {
        BigDecimal limitAbs = param(s, r, "maxProfit7d");
        BigDecimal limitPct = param(s, r, "maxProfitPct");
        BigDecimal profit = f.realized(w.userId(), now.minusDays(7)).net();
        BigDecimal principal = f.netDeposits(w.userId()).subtract(f.completedAmount(w.userId())).max(BigDecimal.ZERO);
        BigDecimal pctCap = principal.multiply(limitPct).divide(BigDecimal.valueOf(100));
        boolean ok = profit.signum() <= 0 || profit.compareTo(limitAbs) <= 0 || profit.compareTo(pctCap) <= 0;
        String pct = principal.signum() == 0 ? "—" : profit.multiply(BigDecimal.valueOf(100)).divide(principal, 1, RoundingMode.HALF_UP).toPlainString();
        yield new Checked(ok, vals("profit", p(profit), "principal", p(principal), "pct", pct, "limitAbs", p(limitAbs), "limitPct", p(limitPct)));
      }
      case R11 -> {
        BigDecimal max = param(s, r, "maxCount");
        LocalDate day = w.createdAt().toLocalDate();
        long n = f.countOnDay(w.userId(), day.atStartOfDay(), day.plusDays(1).atStartOfDay(), w.id());
        yield new Checked(n <= max.longValueExact(), vals("count", String.valueOf(n), "limit", p(max)));
      }
      case R12 -> {
        List<String> issues = new ArrayList<>();
        // 复制源的 R12 还检查"报价源偏差暂停"和"交易全局暂停"，差价合约删除后去掉（forecast S1，技术方案/v1-架构方案.md 第 1.2 节 wallet ③）；
        // forecast S5 加"彩池对账没有未处理的差异"（需求第 8.2 节、AC-38：有差异时转人工）
        long poolIssues = f.openPoolReconIssues();
        if (poolIssues > 0) issues.add("poolRecon");
        BigDecimal need = w.net().add(f.queuedNet(w.network()));
        Optional<HotBalance> hot = f.hotBalance(w.network());
        BigDecimal balance = hot.map(HotBalance::balance).orElse(null);
        boolean fresh = hot.isPresent() && balance != null && hot.get().error() == null && hot.get().checkedAt() != null
            && Duration.between(hot.get().checkedAt(), nowInstant).compareTo(HOT_BALANCE_MAX_AGE) <= 0;
        if (!fresh) issues.add("hotWalletUnknown");
        else if (balance.compareTo(need) < 0) issues.add("hotWallet");
        Map<String, String> v = vals("balance", fresh ? p(balance) : "—", "need", p(need));
        if (poolIssues > 0) v.put("poolIssues", String.valueOf(poolIssues));
        if (!issues.isEmpty()) v.put("issues", String.join(",", issues));
        yield new Checked(issues.isEmpty(), v);
      }
      case R13 -> {
        // 累计净盈利 = 派彩 + 补偿 − 已结算本金 − 抽成（S10）；"> 门槛"不通过，等于门槛算通过
        BigDecimal limit = param(s, r, "maxNetProfit");
        var np = f.realized(w.userId(), null);
        BigDecimal net = np.net();
        yield new Checked(net.compareTo(limit) <= 0, vals("net", p(net), "payout", p(np.payout()), "compensation", p(np.compensation()), "stake",
            p(np.stake()), "fee", p(np.fee()), "limit", p(limit)));
      }
      case R14 -> {
        // 关联账户对押（S10）："这部分盈利 > 门槛"不通过，等于门槛算通过；没有这样的市场算通过
        BigDecimal days = param(s, r, "days");
        BigDecimal limit = param(s, r, "minProfit");
        var lo = f.linkedOpposite(w.userId(), now.minusDays(days.longValueExact()));
        yield new Checked(lo.profit().compareTo(limit) <= 0, vals("profit", p(lo.profit()), "markets", String.valueOf(lo.marketIds().size()), "linked",
            String.valueOf(lo.linkedUsers().size()), "days", p(days), "limit", p(limit)));
      }
      case R15 -> {
        // 可疑押中（S10）：近 N 天有还没处理的"新账户押中冷门""尾盘大额"告警 → 不通过
        BigDecimal days = param(s, r, "days");
        long n = f.openSuspiciousAlerts(w.userId(), now.minusDays(days.longValueExact()));
        yield new Checked(n == 0, vals("count", String.valueOf(n), "days", p(days)));
      }
    };
  }
}
