package com.forecast.app.riskalert.core;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.ledger.api.CustomerFlowApi;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.pool.api.Odds;
import com.forecast.app.pool.api.PoolApi;
import com.forecast.app.settle.api.SettleApi;
import com.forecast.app.wallet.api.AutoApprove;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 风控的判定（forecast S10，需求说明书第 8.1 节）。每个方法处理一条扫描到的数据，在调用方（{@code scan.RiskScanner}）的事务里，
 * 只写 riskalert 的表（线索、关联、告警）和员工邮件；同一条数据处理两次结果一样（线索、关联、告警都按唯一键只记一次）。
 *
 * <h2>关联账户（第 8.1 节"同一人多账号"①）</h2>
 * 同设备号、同 IP（注册和登录）、同充值来源地址、同提现白名单地址的客户两两关联（{@code riskalert_link}）。同一个值的客户超过
 * {@link ConfigKey#RISK_LINK_MAX_USERS}（默认 10）个时不再按它关联：公共网络、交易所的出金地址会把大量无关的客户连在一起（超出需求的部分，见开发记录 S10）。
 * 本机和内网 IP（127.0.0.1、10.x、192.168.x 等）不算线索（只会是测试或代理没配好）。
 *
 * <h2>告警</h2>
 * <ul>
 *   <li><b>尾盘大额</b> LATE_LARGE_BET：收到下注的时间在截止前 N 分钟内（[截止 − N, 截止)），且单笔 ≥ 下注后这个市场奖池合计的 X%，
 *       且单笔 ≥ 最低金额（默认 5 分钟、30%、100 USDT，参数设置可改；最低金额是 S16 按 SQ-24 加的，人少时截止前几十 USDT 的小注不再告警，0 = 不限）。</li>
 *   <li><b>关联账户对押</b> LINKED_OPPOSITE：同一市场里，本客户和他的关联账户的有效下注押了不同的选项（一有新下注、一有新关联都检查；新关联只看近 30 天的市场）。
 *       本客户和对方各记一条。</li>
 *   <li><b>新账户押中冷门</b> NEW_ACCOUNT_LONGSHOT：市场派彩完成后，赢方选项的赔率（扣费后，按结算单的奖池和费率，同客户端显示的算法 {@link Odds#multiplier}）
 *       ≥ 倍数，且赢方里单笔 ≥ 金额、收到下注时注册不满 N 天的下注（默认 5 倍、1,000 USDT、7 天）。</li>
 *   <li><b>充了不玩就提</b> DEPOSIT_NO_BET：提现申请时，近 30 天的充值（扣冲正）以后的有效下注本金 &lt; 充值 × 提现自动审批 R8 的倍数（和 R8 同一个口径、同一个倍数；
 *       倍数是 0 时不检查）。R8 关着也照样告警。</li>
 * </ul>
 */
@Service
public class RiskRules {

  private static final Logger log = LoggerFactory.getLogger(RiskRules.class);
  /** 新关联时回看多久的市场 */
  static final Duration LINK_LOOKBACK = Duration.ofDays(30);
  /** "充了不玩就提"看多久的充值（同 R8） */
  static final Duration DEPOSIT_WINDOW = Duration.ofDays(30);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final RiskMapper mapper;
  private final AlertBook alerts;
  private final SystemConfig config;
  private final MarketApi markets;
  private final PoolApi pool;
  private final AccountApi accounts;
  private final CustomerFlowApi flows;

  RiskRules(RiskMapper mapper, AlertBook alerts, SystemConfig config, MarketApi markets, PoolApi pool, AccountApi accounts, CustomerFlowApi flows) {
    this.mapper = mapper;
    this.alerts = alerts;
    this.config = config;
    this.markets = markets;
    this.pool = pool;
    this.accounts = accounts;
    this.flows = flows;
  }

  // =====================================================================
  // 关联账户
  // =====================================================================

  /**
   * 一条线索：某客户用过某个值。第一次见到时，和同样用过它的客户两两关联（不超过上限时），每条新关联再检查两人有没有对押。
   *
   * @return 新建了几条关联
   */
  public int onSignal(String kind, String value, long userId, LocalDateTime at) {
    if (value == null || value.isBlank()) return 0;
    if ("IP".equals(kind) && !publicIp(value)) return 0;
    String v = value.length() > 128 ? value.substring(0, 128) : value;
    if (mapper.insertSignal(kind, v, userId, at) != 1) return 0;
    int max = config.integer(ConfigKey.RISK_LINK_MAX_USERS, 10);
    List<Long> users = mapper.signalUsers(kind, v, max + 1);
    if (users.size() > max) {
      log.info("线索 {} 已有超过 {} 个客户使用，不再按它关联（客户 {}）", kind, max, userId);
      return 0;
    }
    int fresh = 0;
    for (Long other : users) {
      if (other == userId) continue;
      long a = Math.min(other, userId);
      long b = Math.max(other, userId);
      if (mapper.insertLink(a, b, kind, v, at) == 1) {
        fresh++;
        checkPair(a, b, at);
      }
    }
    return fresh;
  }

  /** 公网 IP 才算线索（本机、内网、链路本地、组播、格式不对的不算） */
  static boolean publicIp(String ip) {
    String s = ip.strip();
    if (!s.matches("[0-9a-fA-F:.]+")) return false;
    try {
      java.net.InetAddress a = java.net.InetAddress.ofLiteral(s);
      return !(a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()
          || (a instanceof java.net.Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** 新关联的两人：近 30 天都下过注的市场里押了不同的选项 → 两人各一条"关联账户对押" */
  void checkPair(long a, long b, LocalDateTime at) {
    for (Long marketId : pool.commonMarkets(a, b, at.minus(LINK_LOOKBACK))) {
      Map<Long, Set<Long>> opts = pool.optionsOf(marketId, List.of(a, b));
      if (AlertBook.opposite(opts.getOrDefault(a, Set.of()), opts.getOrDefault(b, Set.of()))) {
        linkedOpposite(a, marketId, List.of(b), opts);
        linkedOpposite(b, marketId, List.of(a), opts);
      }
    }
  }

  private void linkedOpposite(long userId, long marketId, List<Long> others, Map<Long, Set<Long>> opts) {
    Map<String, String> d = new LinkedHashMap<>();
    d.put("linkedUsers", join(others));
    d.put("options", join(opts.getOrDefault(userId, Set.of())));
    List<String> theirs = new ArrayList<>();
    for (Long o : others) theirs.add(o + ":" + join(opts.getOrDefault(o, Set.of())));
    d.put("linkedOptions", String.join(";", theirs));
    alerts.raise("LINKED_OPPOSITE", userId, marketId, "M:" + marketId, null, d,
        new String[] {"和关联账户 " + join(others) + " 在同一市场押了不同的选项", "Bet on a different option from linked account(s) " + join(others)});
  }

  private static String join(java.util.Collection<Long> ids) {
    return String.join(",", ids.stream().map(String::valueOf).toList());
  }

  // =====================================================================
  // 下注：尾盘大额、关联账户对押
  // =====================================================================

  public void onBet(PoolApi.BetSignal bet) {
    lateLarge(bet);
    List<Long> linked = mapper.linkedUsers(bet.userId());
    if (linked.isEmpty()) return;
    List<Long> all = new ArrayList<>(linked);
    all.add(bet.userId());
    Map<Long, Set<Long>> opts = pool.optionsOf(bet.marketId(), all);
    Set<Long> mine = opts.getOrDefault(bet.userId(), Set.of());
    List<Long> hits = new ArrayList<>();
    for (Long l : linked) if (AlertBook.opposite(mine, opts.getOrDefault(l, Set.of()))) hits.add(l);
    if (hits.isEmpty()) return;
    linkedOpposite(bet.userId(), bet.marketId(), hits, opts);
    for (Long l : hits) linkedOpposite(l, bet.marketId(), List.of(bet.userId()), opts);
  }

  void lateLarge(PoolApi.BetSignal bet) {
    MarketApi.MarketRef m = markets.findById(bet.marketId()).orElse(null);
    if (m == null || bet.poolAfter() == null || bet.poolAfter().signum() <= 0) return;
    int minutes = config.integer(ConfigKey.RISK_LATE_BET_MINUTES, 5);
    BigDecimal pct = config.decimal(ConfigKey.RISK_LATE_BET_POOL_PCT, BigDecimal.valueOf(30));
    BigDecimal minAmount = config.decimal(ConfigKey.RISK_LATE_BET_MIN_AMOUNT, BigDecimal.valueOf(100));
    LocalDateTime close = LocalDateTime.ofInstant(m.closeAt(), ZoneOffset.UTC);
    LocalDateTime from = close.minusMinutes(minutes);
    if (bet.receivedAt().isBefore(from) || !bet.receivedAt().isBefore(close)) return;
    // SQ-24：单笔不到最低金额的不算（正好等于算）
    if (bet.amount().compareTo(minAmount) < 0) return;
    // 单笔 ≥ 奖池 × X%：用乘法比较，不做除法舍入
    if (bet.amount().multiply(HUNDRED).compareTo(bet.poolAfter().multiply(pct)) < 0) return;
    Map<String, String> d = new LinkedHashMap<>();
    d.put("betNo", String.valueOf(bet.publicNo()));
    d.put("optionId", String.valueOf(bet.optionId()));
    d.put("poolAfter", AlertBook.money(bet.poolAfter()));
    d.put("pct", bet.amount().multiply(HUNDRED).divide(bet.poolAfter(), 1, RoundingMode.DOWN).toPlainString());
    d.put("limitPct", pct.stripTrailingZeros().toPlainString());
    d.put("limitAmount", AlertBook.money(minAmount));
    d.put("secondsBeforeClose", String.valueOf(Duration.between(bet.receivedAt(), close).toSeconds()));
    d.put("limitMinutes", String.valueOf(minutes));
    alerts.raise("LATE_LARGE_BET", bet.userId(), bet.marketId(), "M:" + bet.marketId(), bet.amount(), d,
        new String[] {"截止前 " + minutes + " 分钟内单笔 " + AlertBook.money(bet.amount()) + "，占下注后奖池 " + d.get("pct") + "%",
            "Single bet of " + AlertBook.money(bet.amount()) + " within " + minutes + " minutes before close, " + d.get("pct") + "% of the pool"});
  }

  // =====================================================================
  // 派彩完成：新账户押中冷门
  // =====================================================================

  public void onSettled(SettleApi.SettledRun run) {
    if (run.winnerPool() == null || run.winnerPool().signum() <= 0) return;
    BigDecimal rate = run.feeRate() == null ? BigDecimal.ZERO : run.feeRate();
    String oddsText = Odds.multiplier(run.winnerPool(), run.poolTotal(), rate);
    if (Odds.NONE.equals(oddsText)) return;
    BigDecimal odds = new BigDecimal(oddsText);
    BigDecimal minOdds = config.decimal(ConfigKey.RISK_LONGSHOT_MIN_ODDS, BigDecimal.valueOf(5));
    if (odds.compareTo(minOdds) < 0) return;
    BigDecimal minAmount = config.decimal(ConfigKey.RISK_LONGSHOT_MIN_AMOUNT, BigDecimal.valueOf(1000));
    int days = config.integer(ConfigKey.RISK_LONGSHOT_ACCOUNT_DAYS, 7);
    for (PoolApi.ActiveBet b : pool.activeBetsOn(run.marketId(), run.winningOptionId(), minAmount)) {
      LocalDateTime registered = accounts.registeredAt(b.userId());
      if (!b.receivedAt().isBefore(registered.plusDays(days))) continue;
      Map<String, String> d = new LinkedHashMap<>();
      d.put("betNo", String.valueOf(b.publicNo()));
      d.put("odds", oddsText);
      d.put("limitOdds", minOdds.stripTrailingZeros().toPlainString());
      d.put("limitAmount", AlertBook.money(minAmount));
      d.put("accountDays", String.valueOf(Duration.between(registered, b.receivedAt()).toHours() / 24));
      d.put("limitDays", String.valueOf(days));
      alerts.raise("NEW_ACCOUNT_LONGSHOT", b.userId(), run.marketId(), "M:" + run.marketId(), b.amount(), d,
          new String[] {"注册不满 " + days + " 天，单笔 " + AlertBook.money(b.amount()) + " 押中 " + oddsText + " 倍的结果",
              "Account younger than " + days + " days won " + oddsText + "x with a single bet of " + AlertBook.money(b.amount())});
    }
  }

  // =====================================================================
  // 提现申请：充了不玩就提
  // =====================================================================

  public void onWithdrawal(com.forecast.app.wallet.api.WalletSignalsApi.WithdrawalRequest w) {
    AutoApprove.Settings s = AutoApprove.parse(config.snapshot());
    BigDecimal multiple = s.param(AutoApprove.Rule.R8, "multiple").orElse(BigDecimal.ONE);
    if (multiple.signum() == 0) return;
    CustomerFlowApi.Deposits dep = flows.depositsSince(w.userId(), w.at().minus(DEPOSIT_WINDOW));
    if (dep.net().signum() <= 0 || dep.firstAt() == null) return;
    BigDecimal stake = pool.effectiveStakeSince(w.userId(), dep.firstAt());
    if (stake.compareTo(dep.net().multiply(multiple)) >= 0) return;
    Map<String, String> d = new LinkedHashMap<>();
    d.put("withdrawalNo", String.valueOf(w.publicNo()));
    d.put("deposit", AlertBook.money(dep.net()));
    d.put("stake", AlertBook.money(stake));
    d.put("multiple", multiple.stripTrailingZeros().toPlainString());
    alerts.raise("DEPOSIT_NO_BET", w.userId(), null, "W:" + w.id(), w.amount(), d,
        new String[] {"近 30 天充值 " + d.get("deposit") + "，之后有效下注只有 " + d.get("stake") + "（要求 " + d.get("multiple") + " 倍）就申请提现",
            "Requested a withdrawal after depositing " + d.get("deposit") + " in 30 days with only " + d.get("stake") + " in effective bets (required "
                + d.get("multiple") + "x)"});
  }
}
