package com.forecast.app.wallet.withdraw;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.account.api.KycStatus;
import com.forecast.app.account.api.SecurityEvent;
import com.forecast.app.ledger.api.CustomerFlowApi;
import com.forecast.app.wallet.api.AutoApprove;
import com.forecast.app.wallet.api.AutoApprove.Rule;
import com.forecast.app.wallet.api.AutoApprove.RuleSetting;
import com.forecast.app.wallet.api.AutoApprove.Settings;
import com.forecast.app.wallet.withdraw.AutoApproveEngine.Evaluation;
import com.forecast.app.wallet.withdraw.AutoApproveEngine.Result;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 提现自动审批 13 条规则的判定（技术方案/v2-提现自动审批.md 第 4、10 节）：每条规则的通过、不通过、正好等于边界、时间窗口边界、规则关闭不参与、
 * 数据读不到或配置值不合法按"无法判定"（不通过）。纯计算，数据用假的。
 */
class AutoApproveEngineTest {

  static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 10, 0);
  static final Instant NOW_I = NOW.toInstant(ZoneOffset.UTC);

  /** 默认全部通过的假数据；测试里改需要的那一项 */
  static class FakeFacts implements AutoApproveEngine.Facts {
    BigDecimal userUsed = BigDecimal.ZERO;
    BigDecimal platformUsed = new BigDecimal("6420");
    long completed = 3;
    long toAddress = 2;
    SecurityEvent event;
    LocalDateTime registeredAt = NOW.minusDays(30);
    KycStatus kyc = KycStatus.APPROVED;
    BigDecimal deposit30 = new BigDecimal("1000");
    LocalDateTime firstDepositAt = NOW.minusDays(10);
    BigDecimal volume = new BigDecimal("2400");
    BigDecimal withdrawn = new BigDecimal("400");
    BigDecimal netDeposits = new BigDecimal("1000");
    BigDecimal pnlAll = new BigDecimal("310");
    BigDecimal pnl7 = new BigDecimal("100");
    BigDecimal completedAmount = new BigDecimal("300");
    /** R13：已实现 310 − 手续费 250 − 隔夜费 80 = −20（不盈利） */
    BigDecimal realized = new BigDecimal("310");
    BigDecimal fee = new BigDecimal("250");
    BigDecimal swap = new BigDecimal("80");
    long onDay = 1;
    AutoApproveEngine.HotBalance hot = new AutoApproveEngine.HotBalance(new BigDecimal("38500"), NOW_I.minusSeconds(60), null);
    BigDecimal queued = BigDecimal.ZERO;
    /** 抛异常的规则数据（模拟数据读不到） */
    String broken = "";
    final List<LocalDateTime> sinceAsked = new ArrayList<>();

    void maybeThrow(String what) {
      if (broken.equals(what)) throw new IllegalStateException("读不到 " + what);
    }

    @Override
    public BigDecimal autoApprovedSince(Long userId, LocalDateTime since) {
      maybeThrow(userId == null ? "platform" : "user");
      sinceAsked.add(since);
      return userId == null ? platformUsed : userUsed;
    }

    @Override
    public long completedCount(long userId) {
      maybeThrow("completed");
      return completed;
    }

    @Override
    public long completedToAddress(long userId, String network, String address) {
      return toAddress;
    }

    @Override
    public Optional<SecurityEvent> lastSecurityEventSince(long userId, LocalDateTime since) {
      maybeThrow("event");
      // 和 SQL 一样：窗口 (since, 现在]
      return event != null && event.at().isAfter(since) ? Optional.of(event) : Optional.empty();
    }

    @Override
    public LocalDateTime registeredAt(long userId) {
      return registeredAt;
    }

    @Override
    public KycStatus kycStatus(long userId) {
      return kyc;
    }

    @Override
    public CustomerFlowApi.Deposits depositsSince(long userId, LocalDateTime since) {
      return new CustomerFlowApi.Deposits(deposit30, deposit30.signum() > 0 ? firstDepositAt : null);
    }

    @Override
    public BigDecimal openedNotionalSince(long userId, LocalDateTime since) {
      return volume;
    }

    @Override
    public BigDecimal withdrawnTotal(long userId) {
      return withdrawn;
    }

    @Override
    public BigDecimal netDeposits(long userId) {
      return netDeposits;
    }

    @Override
    public BigDecimal realizedPnl(long userId, LocalDateTime since) {
      return since == null ? pnlAll : pnl7;
    }

    @Override
    public CustomerFlowApi.NetProfit netProfit(long userId) {
      maybeThrow("profit");
      return new CustomerFlowApi.NetProfit(realized, fee, swap);
    }

    @Override
    public BigDecimal completedAmount(long userId) {
      return completedAmount;
    }

    @Override
    public long countOnDay(long userId, LocalDateTime from, LocalDateTime to, long upToId) {
      return onDay;
    }

    @Override
    public Optional<AutoApproveEngine.HotBalance> hotBalance(String network) {
      maybeThrow("hot");
      return Optional.ofNullable(hot);
    }

    @Override
    public BigDecimal queuedNet(String network) {
      return queued;
    }
  }

  static Settings defaults(boolean master) {
    Settings d = AutoApprove.parse(Map.of());
    return new Settings(master, d.rules(), 0);
  }

  /** 改一条规则的开关或数值 */
  static Settings with(Settings s, Rule r, Boolean enabled, String key, String value) {
    List<RuleSetting> rules = new ArrayList<>();
    for (RuleSetting x : s.rules()) {
      if (!x.code().equals(r.name())) {
        rules.add(x);
        continue;
      }
      Map<String, String> params = new LinkedHashMap<>(x.params());
      if (key != null) params.put(key, value);
      rules.add(new RuleSetting(x.code(), enabled == null ? x.enabled() : enabled, params));
    }
    return new Settings(s.masterEnabled(), rules, s.version());
  }

  static AutoApproveEngine.Withdrawal wd(String amount) {
    return new AutoApproveEngine.Withdrawal(7, 100, "TRC20", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", new BigDecimal(amount), BigDecimal.ONE, NOW.minusHours(2));
  }

  static Evaluation eval(Settings s, String amount, FakeFacts f) {
    return AutoApproveEngine.evaluate(s, wd(amount), NOW, NOW_I, f);
  }

  static Result rule(Evaluation e, Rule r) {
    return e.results().stream().filter(x -> x.code().equals(r.name())).findFirst().orElseThrow();
  }

  static String status(Settings s, String amount, FakeFacts f, Rule r) {
    return rule(eval(s, amount, f), r).status();
  }

  final Settings on = defaults(true);

  @Test
  void 默认数据全部通过_总开关打开时自动通过_关闭时记总开关关闭但照样逐条判定() {
    Evaluation e = eval(on, "100", new FakeFacts());
    assertThat(e.outcome()).isEqualTo("AUTO_APPROVED");
    assertThat(e.failed()).isEmpty();
    assertThat(e.results()).hasSize(Rule.values().length).hasSize(13).allMatch(r -> r.status().equals("passed"));

    FakeFacts f = new FakeFacts();
    f.completed = 0;
    Evaluation off = eval(defaults(false), "800", f);
    assertThat(off.outcome()).as("总开关关闭：照常判定，记录如果打开会怎样").isEqualTo("MASTER_OFF");
    assertThat(off.failed()).containsExactly("R1", "R4");
    assertThat(eval(defaults(false), "100", new FakeFacts()).failed()).as("如果打开会自动通过").isEmpty();
  }

  @Test
  void R1_单笔金额_等于上限算通过() {
    FakeFacts f = new FakeFacts();
    assertThat(status(on, "500", f, Rule.R1)).isEqualTo("passed");
    assertThat(status(on, "500.01", f, Rule.R1)).isEqualTo("failed");
    assertThat(rule(eval(on, "800", f), Rule.R1).values()).containsEntry("amount", "800").containsEntry("limit", "500");
  }

  @Test
  void R2_R3_24小时自动通过累计含本笔_等于上限算通过_窗口是滚动24小时() {
    FakeFacts f = new FakeFacts();
    f.userUsed = new BigDecimal("1500");
    assertThat(status(on, "500", f, Rule.R2)).as("1500 + 500 = 2000").isEqualTo("passed");
    assertThat(status(on, "500.01", f, Rule.R2)).isEqualTo("failed");
    assertThat(rule(eval(on, "500", f), Rule.R2).values()).containsEntry("used", "1500").containsEntry("total", "2000").containsEntry("limit", "2000");
    f.platformUsed = new BigDecimal("19700");
    assertThat(status(on, "300", f, Rule.R3)).as("19700 + 300 = 20000").isEqualTo("passed");
    assertThat(status(on, "300.01", f, Rule.R3)).isEqualTo("failed");
    assertThat(f.sinceAsked).allMatch(t -> t.equals(NOW.minusHours(24)));
  }

  @Test
  void R4_R5_不是第一笔_地址成功提现过() {
    FakeFacts f = new FakeFacts();
    f.completed = 1;
    f.toAddress = 1;
    assertThat(status(on, "100", f, Rule.R4)).isEqualTo("passed");
    assertThat(status(on, "100", f, Rule.R5)).isEqualTo("passed");
    f.completed = 0;
    f.toAddress = 0;
    assertThat(status(on, "100", f, Rule.R4)).isEqualTo("failed");
    assertThat(rule(eval(on, "100", f), Rule.R5).values()).containsEntry("count", "0");
  }

  @Test
  void R6_正好48小时前的改密码通过_47小时59分不通过() {
    FakeFacts f = new FakeFacts();
    f.event = new SecurityEvent("PASSWORD_CHANGE", NOW.minusHours(48));
    assertThat(status(on, "100", f, Rule.R6)).isEqualTo("passed");
    f.event = new SecurityEvent("PASSWORD_CHANGE", NOW.minusHours(48).plusMinutes(1));
    Result r = rule(eval(on, "100", f), Rule.R6);
    assertThat(r.status()).isEqualTo("failed");
    assertThat(r.values()).containsEntry("event", "password_change").containsEntry("ago", "47.9").containsEntry("hours", "48");
    f.event = new SecurityEvent("FREEZE", NOW.minusMinutes(90));
    assertThat(rule(eval(on, "100", f), Rule.R6).values()).containsEntry("event", "freeze").containsEntry("ago", "1.5");
    // 窗口改成 1 小时：90 分钟前的不在窗口里
    assertThat(status(with(on, Rule.R6, null, "hours", "1"), "100", f, Rule.R6)).isEqualTo("passed");
  }

  @Test
  void R7_正好7乘24小时算通过_KYC必须已通过() {
    FakeFacts f = new FakeFacts();
    f.registeredAt = NOW.minusDays(7);
    assertThat(status(on, "100", f, Rule.R7)).isEqualTo("passed");
    f.registeredAt = NOW.minusDays(7).plusSeconds(1);
    Result r = rule(eval(on, "100", f), Rule.R7);
    assertThat(r.status()).isEqualTo("failed");
    assertThat(r.values()).containsEntry("days", "6").containsEntry("limit", "7").containsEntry("kyc", "approved");
    f.registeredAt = NOW.minusDays(30);
    f.kyc = KycStatus.PENDING;
    assertThat(status(on, "100", f, Rule.R7)).isEqualTo("failed");
    assertThat(status(with(on, Rule.R7, null, "days", "0"), "100", f, Rule.R7)).as("0 天也要 KYC 已通过").isEqualTo("failed");
  }

  @Test
  void R8_交易量等于充值乘倍数算通过_近30天没有充值通过_倍数0等于不查() {
    FakeFacts f = new FakeFacts();
    f.volume = new BigDecimal("1000");
    assertThat(status(on, "100", f, Rule.R8)).isEqualTo("passed");
    f.volume = new BigDecimal("999.99");
    Result r = rule(eval(on, "100", f), Rule.R8);
    assertThat(r.status()).isEqualTo("failed");
    assertThat(r.values()).containsEntry("volume", "999.99").containsEntry("deposit", "1000").containsEntry("ratio", "0.99").containsEntry("limit", "1");
    assertThat(status(with(on, Rule.R8, null, "multiple", "0"), "100", f, Rule.R8)).isEqualTo("passed");
    f.deposit30 = BigDecimal.ZERO;
    assertThat(rule(eval(on, "100", f), Rule.R8).values()).containsEntry("note", "noDeposit");
    assertThat(status(on, "100", f, Rule.R8)).isEqualTo("passed");
  }

  @Test
  void R9_累计提现等于充值加盈亏算通过() {
    FakeFacts f = new FakeFacts();
    f.withdrawn = new BigDecimal("1310");
    assertThat(status(on, "100", f, Rule.R9)).isEqualTo("passed");
    f.withdrawn = new BigDecimal("1310.01");
    Result r = rule(eval(on, "100", f), Rule.R9);
    assertThat(r.status()).isEqualTo("failed");
    assertThat(r.values()).containsEntry("deposit", "1000").containsEntry("pnl", "310").containsEntry("cap", "1310");
  }

  @Test
  void R10_满足其一就通过_金额上限或本金百分比_盈利不大于0直接通过() {
    FakeFacts f = new FakeFacts();
    f.netDeposits = new BigDecimal("20000");
    f.completedAmount = BigDecimal.ZERO;
    f.pnl7 = new BigDecimal("10000");
    assertThat(status(on, "100", f, Rule.R10)).as("等于本金 50%").isEqualTo("passed");
    f.pnl7 = new BigDecimal("10000.01");
    Result r = rule(eval(on, "100", f), Rule.R10);
    assertThat(r.status()).isEqualTo("failed");
    assertThat(r.values()).containsEntry("principal", "20000").containsEntry("pct", "50.0").containsEntry("limitAbs", "5000").containsEntry("limitPct", "50");
    f.netDeposits = new BigDecimal("1000");
    f.pnl7 = new BigDecimal("5000");
    assertThat(status(on, "100", f, Rule.R10)).as("等于金额上限").isEqualTo("passed");
    f.pnl7 = new BigDecimal("5000.01");
    assertThat(status(on, "100", f, Rule.R10)).isEqualTo("failed");
    f.pnl7 = new BigDecimal("-300");
    assertThat(status(on, "100", f, Rule.R10)).isEqualTo("passed");
    f.completedAmount = new BigDecimal("5000");
    f.pnl7 = new BigDecimal("6000");
    assertThat(rule(eval(on, "100", f), Rule.R10).values()).as("本金不小于 0").containsEntry("principal", "0").containsEntry("pct", "—");
  }

  @Test
  void R11_第3笔通过_第4笔不通过() {
    FakeFacts f = new FakeFacts();
    f.onDay = 3;
    assertThat(status(on, "100", f, Rule.R11)).isEqualTo("passed");
    f.onDay = 4;
    assertThat(rule(eval(on, "100", f), Rule.R11).values()).containsEntry("count", "4").containsEntry("limit", "3");
    assertThat(status(on, "100", f, Rule.R11)).isEqualTo("failed");
  }

  @Test
  void R12_热钱包余额要够付本笔和排在前面的_查不到或超过10分钟算不通过() {
    FakeFacts f = new FakeFacts();
    f.hot = new AutoApproveEngine.HotBalance(new BigDecimal("599"), NOW_I.minus(Duration.ofMinutes(10)), null);
    f.queued = new BigDecimal("500");
    Result ok = rule(eval(on, "100", f), Rule.R12);
    assertThat(ok.status()).as("余额 599 = 排在前面的 500 + 本笔到账 99；正好 10 分钟前查的还算新").isEqualTo("passed");
    assertThat(ok.values()).containsEntry("balance", "599").containsEntry("need", "599");
    f.queued = new BigDecimal("500.01");
    assertThat(rule(eval(on, "100", f), Rule.R12).values()).containsEntry("issues", "hotWallet");
    f.queued = BigDecimal.ZERO;
    f.hot = new AutoApproveEngine.HotBalance(new BigDecimal("99999"), NOW_I.minus(Duration.ofMinutes(10)).minusSeconds(1), null);
    assertThat(rule(eval(on, "100", f), Rule.R12).values()).containsEntry("issues", "hotWalletUnknown").containsEntry("balance", "—");
    f.hot = new AutoApproveEngine.HotBalance(new BigDecimal("99999"), NOW_I, "节点超时");
    assertThat(status(on, "100", f, Rule.R12)).as("最近一次查询失败").isEqualTo("failed");
    f.hot = null;
    assertThat(status(on, "100", f, Rule.R12)).as("这条链没有配置热钱包").isEqualTo("failed");
    f.hot = new AutoApproveEngine.HotBalance(new BigDecimal("99999"), NOW_I, null);
    assertThat(rule(eval(on, "100", f), Rule.R12).values()).as("复制源的报价偏差、全局暂停两项检查 forecast S1 已删除").doesNotContainKey("issues");
  }

  @Test
  void R13_累计净盈利等于门槛通过_大于门槛不通过_负数通过_快照写明组成() {
    FakeFacts f = new FakeFacts();
    // 310 − 230 − 80 = 0 = 门槛 0：通过
    f.fee = new BigDecimal("230");
    Result eq = rule(eval(on, "100", f), Rule.R13);
    assertThat(eq.status()).as("等于门槛算通过").isEqualTo("passed");
    assertThat(eq.values()).containsExactly(java.util.Map.entry("net", "0"), java.util.Map.entry("realized", "310"), java.util.Map.entry("fee", "230"),
        java.util.Map.entry("swap", "80"), java.util.Map.entry("limit", "0"));
    // 310 − 229.99 − 80 = 0.01 > 0：不通过
    f.fee = new BigDecimal("229.99");
    Evaluation over = eval(on, "100", f);
    assertThat(rule(over, Rule.R13).status()).as("大于门槛不通过").isEqualTo("failed");
    assertThat(rule(over, Rule.R13).values()).containsEntry("net", "0.01");
    assertThat(over.outcome()).isEqualTo("MANUAL");
    assertThat(over.failed()).containsExactly("R13");
    // 亏损客户：净盈利是负数，通过
    f.realized = new BigDecimal("-500");
    f.fee = new BigDecimal("12.5");
    assertThat(rule(eval(on, "100", f), Rule.R13).values()).containsEntry("net", "-592.5").containsEntry("realized", "-500");
    assertThat(status(on, "100", f, Rule.R13)).as("负数通过").isEqualTo("passed");
    // 门槛可以调：门槛 1000，净盈利 1000 通过、1000.01 不通过
    Settings t1000 = with(on, Rule.R13, null, "maxNetProfit", "1000");
    f.realized = new BigDecimal("1330");
    f.fee = new BigDecimal("250");
    assertThat(status(t1000, "100", f, Rule.R13)).isEqualTo("passed");
    assertThat(rule(eval(t1000, "100", f), Rule.R13).values()).containsEntry("net", "1000").containsEntry("limit", "1000");
    f.realized = new BigDecimal("1330.01");
    assertThat(status(t1000, "100", f, Rule.R13)).isEqualTo("failed");
    // 关闭时不参与
    assertThat(eval(with(on, Rule.R13, false, null, null), "100", f).outcome()).isEqualTo("AUTO_APPROVED");
  }

  @Test
  void R13_手续费和隔夜费都扣_收到的隔夜费是负数会加回来() {
    FakeFacts f = new FakeFacts();
    f.realized = new BigDecimal("100");
    f.fee = BigDecimal.ZERO;
    f.swap = BigDecimal.ZERO;
    assertThat(status(on, "100", f, Rule.R13)).as("不扣手续费、隔夜费就是盈利 100").isEqualTo("failed");
    f.fee = new BigDecimal("60");
    assertThat(status(on, "100", f, Rule.R13)).as("扣了手续费还盈利 40").isEqualTo("failed");
    f.swap = new BigDecimal("40");
    assertThat(status(on, "100", f, Rule.R13)).as("再扣隔夜费 40 正好 0").isEqualTo("passed");
    f.swap = new BigDecimal("-5");
    assertThat(rule(eval(on, "100", f), Rule.R13).values()).as("客户收到的隔夜费多于付出的").containsEntry("swap", "-5").containsEntry("net", "45");
    assertThat(status(on, "100", f, Rule.R13)).isEqualTo("failed");
  }

  @Test
  void R13_读不到账本记无法判定_整笔转人工_门槛不合法也是无法判定() {
    FakeFacts f = new FakeFacts();
    f.broken = "profit";
    Evaluation e = eval(on, "100", f);
    assertThat(rule(e, Rule.R13).status()).isEqualTo("unknown");
    assertThat(rule(e, Rule.R13).values()).containsEntry("why", "数据读取失败");
    assertThat(e.outcome()).isEqualTo("ERROR");
    assertThat(e.failed()).containsExactly("R13");
    Evaluation bad = eval(with(on, Rule.R13, null, "maxNetProfit", "-1"), "100", new FakeFacts());
    assertThat(rule(bad, Rule.R13).values()).containsEntry("why", "配置值不合法");
    assertThat(bad.outcome()).isEqualTo("ERROR");
    // 保存前校验：0 可以（默认），负数、3 位小数、超过 1,000,000 不行
    for (String ok : List.of("0", "0.00", "1000000", "12.5")) {
      List<AutoApprove.Problem> ps = new ArrayList<>();
      assertThat(AutoApprove.normalize(true, with(on, Rule.R13, null, "maxNetProfit", ok).rules(), ps)).as(ok).isNotNull();
    }
    for (String no : List.of("-1", "0.001", "1000000.01", "")) {
      List<AutoApprove.Problem> ps = new ArrayList<>();
      assertThat(AutoApprove.normalize(true, with(on, Rule.R13, null, "maxNetProfit", no).rules(), ps)).as(no).isNull();
      assertThat(ps).containsExactly(new AutoApprove.Problem("rules.R13.maxNetProfit", "invalid"));
    }
  }

  @Test
  void 规则关闭时不参与_数据读不到和配置值不合法记无法判定_整笔转人工() {
    FakeFacts f = new FakeFacts();
    f.completed = 0;
    Settings r4off = with(on, Rule.R4, false, null, null);
    Evaluation e = eval(r4off, "100", f);
    assertThat(rule(e, Rule.R4).status()).isEqualTo("disabled");
    assertThat(rule(e, Rule.R4).values()).isEmpty();
    assertThat(e.outcome()).isEqualTo("AUTO_APPROVED");

    f.broken = "hot";
    Evaluation err = eval(r4off, "100", f);
    assertThat(rule(err, Rule.R12).status()).isEqualTo("unknown");
    assertThat(rule(err, Rule.R12).values()).containsEntry("why", "数据读取失败");
    assertThat(err.outcome()).isEqualTo("ERROR");
    assertThat(err.failed()).containsExactly("R12");

    f.broken = "";
    Evaluation bad = eval(with(on, Rule.R1, null, "maxAmount", "abc"), "100", new FakeFacts());
    assertThat(rule(bad, Rule.R1).status()).isEqualTo("unknown");
    assertThat(rule(bad, Rule.R1).values()).containsEntry("why", "配置值不合法");
    assertThat(bad.outcome()).isEqualTo("ERROR");

    // 全部规则关闭也算"全部已启用的规则通过"
    Settings none = on;
    for (Rule r : Rule.values()) none = with(none, r, false, null, null);
    assertThat(eval(none, "9000", new FakeFacts()).outcome()).isEqualTo("AUTO_APPROVED");
  }

  @Test
  void 配置读取和保存前的校验_范围小数位_R1不大于R2不大于R3() {
    Map<String, String> cfg = new HashMap<>();
    assertThat(AutoApprove.parse(cfg).masterEnabled()).as("没有配置时总开关关闭").isFalse();
    cfg.put(AutoApprove.MASTER_KEY, "yes");
    assertThat(AutoApprove.parse(cfg).masterEnabled()).as("不是 true 一律算关闭").isFalse();
    cfg.put("withdraw.autoApprove.R3.enabled", "0");
    assertThat(AutoApprove.parse(cfg).rule(Rule.R3).enabled()).as("规则开关不是 false 一律算开").isTrue();

    List<AutoApprove.Problem> ps = new ArrayList<>();
    Settings ok = AutoApprove.normalize(true, on.rules(), ps);
    assertThat(ps).isEmpty();
    assertThat(ok.toConfig()).containsEntry("withdraw.autoApprove.R1.maxAmount", "500").containsEntry(AutoApprove.MASTER_KEY, "true")
        .containsEntry("withdraw.autoApprove.R13.enabled", "true").containsEntry("withdraw.autoApprove.R13.maxNetProfit", "0").hasSize(24);

    ps.clear();
    Settings s = with(with(with(on, Rule.R1, null, "maxAmount", "500.123"), Rule.R11, null, "maxCount", "2.5"), Rule.R6, null, "hours", "721");
    assertThat(AutoApprove.normalize(true, s.rules(), ps)).isNull();
    assertThat(ps).extracting(AutoApprove.Problem::field).containsExactly("rules.R1.maxAmount", "rules.R6.hours", "rules.R11.maxCount");

    ps.clear();
    Settings cross = with(with(on, Rule.R1, null, "maxAmount", "3000"), Rule.R3, null, "maxPlatform24h", "1000.5");
    assertThat(AutoApprove.normalize(true, cross.rules(), ps)).isNull();
    assertThat(ps).containsExactly(new AutoApprove.Problem("rules.R2.maxUser24h", "ltR1"), new AutoApprove.Problem("rules.R3.maxPlatform24h", "ltR2"));

    ps.clear();
    assertThat(AutoApprove.normalize(true, on.rules().subList(0, 11), ps)).isNull();
    assertThat(ps).containsExactly(new AutoApprove.Problem("rules.R12", "missing"), new AutoApprove.Problem("rules.R13", "missing"));

    ps.clear();
    Settings norm = AutoApprove.normalize(false, with(on, Rule.R8, null, "multiple", "1.50").rules(), ps);
    assertThat(norm.rule(Rule.R8).params()).containsEntry("multiple", "1.5");
  }
}
