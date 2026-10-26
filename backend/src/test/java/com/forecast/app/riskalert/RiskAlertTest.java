package com.forecast.app.riskalert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import com.forecast.app.account.device.DeviceLog;
import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.riskalert.api.RiskAlertAdminApi;
import com.forecast.app.riskalert.api.RiskAlertApi;
import com.forecast.app.riskalert.scan.RiskScanner;
import com.forecast.app.settle.SettleTestSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 风控告警（forecast S10，需求说明书第 8.1 节、AC-68；真实 MySQL、真实 Redis，C37）：关联账户（同设备、同 IP、同充值来源地址、同提现地址、上限、内网 IP）、
 * 四种告警（尾盘大额、关联账户对押、新账户押中冷门、充了不玩就提）各自的触发和边界、同一情况只告警一次、邮件（模板 RISK_ALERT）、
 * 提现自动审批 R14 / R15 读的数据（{@link RiskAlertApi}）。扫描直接调用 {@link RiskScanner#scanAll()}（测试里定时任务关着、scan-lag = 0）。
 * 扫描是全库的（别的测试留下的数据也会被扫到），断言都带本测试新建的客户或市场（C80）。
 */
class RiskAlertTest extends SettleTestSupport {

  @Autowired RiskScanner scanner;
  @Autowired DeviceLog devices;
  @Autowired RiskAlertApi riskApi;
  @Autowired RiskAlertAdminApi riskAdmin;

  /**
   * 账本的记账时间是数据库时间（现在），下注、提现的时间是测试时钟（固定在 2026-10-14）：把时钟拨到"现在 + 1 小时"，
   * 让这个测试里的充值（账本）都早于下注和提现，和生产一样（R8、R14 按账本时间比较）。每个测试结束时钟自动复原。
   */
  @BeforeEach
  void realTime() {
    Instant target = Instant.now().plus(Duration.ofHours(1));
    Instant now = Instant.now(clock);
    if (now.isBefore(target)) advance(Duration.between(now, target));
  }

  @AfterEach
  void restoreParams() {
    for (ConfigKey k : List.of(ConfigKey.RISK_LINK_MAX_USERS, ConfigKey.RISK_LATE_BET_MINUTES, ConfigKey.RISK_LATE_BET_POOL_PCT, ConfigKey.RISK_LONGSHOT_ACCOUNT_DAYS,
        ConfigKey.RISK_LONGSHOT_MIN_AMOUNT, ConfigKey.RISK_LONGSHOT_MIN_ODDS, ConfigKey.RISK_LATE_BET_MIN_AMOUNT)) {
      jdbc.update("UPDATE common_config SET value = ? WHERE config_key = ?", Map.of(ConfigKey.RISK_LINK_MAX_USERS, "10", ConfigKey.RISK_LATE_BET_MINUTES, "5",
          ConfigKey.RISK_LATE_BET_POOL_PCT, "30", ConfigKey.RISK_LONGSHOT_ACCOUNT_DAYS, "7", ConfigKey.RISK_LONGSHOT_MIN_AMOUNT, "1000",
          ConfigKey.RISK_LONGSHOT_MIN_ODDS, "5", ConfigKey.RISK_LATE_BET_MIN_AMOUNT, "100").get(k), k.key);
    }
    jdbc.update("UPDATE common_config SET value = '1' WHERE config_key = 'withdraw.autoApprove.R8.multiple'");
    config.refresh();
  }

  void param(ConfigKey k, String v) {
    jdbc.update("UPDATE common_config SET value = ? WHERE config_key = ?", v, k.key);
    config.refresh();
  }

  /** 扫到没有新数据为止 */
  void scan() {
    for (int i = 0; i < 200; i++) if (scanner.scanAll().total() == 0) return;
    throw new AssertionError("扫描停不下来");
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  /** 这个客户的告警：类型 → 状态 */
  List<String> alerts(long uid) {
    return jdbc.queryForList("SELECT CONCAT(kind, ' ', COALESCE(market_id, 0), ' ', status) FROM riskalert_alert WHERE user_id = ? ORDER BY id", String.class, uid);
  }

  List<String> links(long uid) {
    return riskAdmin.linksOf(uid).stream().map(l -> l.userId() + " " + l.reason()).toList();
  }

  static String dev() {
    return "dev-" + Long.toString(System.nanoTime(), 36) + "-abcdefgh";
  }

  /** 一个公网段（TEST-NET 之外的普通地址）里每次不同的 IP */
  static String ip() {
    long n = System.nanoTime();
    return "45." + (n % 200 + 1) + "." + (n / 200 % 250 + 1) + "." + (n / 50000 % 250 + 1);
  }

  void depositFrom(long uid, String chain, String from) {
    jdbc.update("INSERT INTO wallet_deposit (public_no, chain, tx_hash, log_index, block_number, block_hash, from_address, to_address, user_id, amount, status) "
        + "VALUES (?, ?, ?, 0, 1, 'h', ?, 'to', ?, 100, 'CREDITED')", System.nanoTime(), chain, "tx" + System.nanoTime(), from, uid);
  }

  void whitelist(long uid, String network, String address) {
    jdbc.update("INSERT INTO wallet_whitelist (user_id, network, address, address_key, label, active_at, created_at) VALUES (?, ?, ?, ?, 'x', ?, ?)", uid, network,
        address, address.toLowerCase(), now().plusDays(1), now());
  }

  @Test
  void 关联账户_同设备_同IP_同充值来源地址_同提现地址_内网IP不算_超过上限不再关联() throws Exception {
    User a = user("0");
    User b = user("0");
    User c = user("0");
    String d = dev();
    devices.record(a.id(), "REGISTER", d, "10.1.2.3");
    devices.record(b.id(), "LOGIN", d, "10.1.2.3");
    scan();
    assertThat(links(a.id())).as("同设备关联；内网 IP 不算线索").containsExactly(b.id() + " DEVICE");
    String ip = ip();
    devices.record(b.id(), "LOGIN", null, ip);
    devices.record(c.id(), "LOGIN", "bad id", ip);
    String from = "0xAbCdEf" + Long.toHexString(System.nanoTime());
    depositFrom(a.id(), "ERC20", from);
    depositFrom(c.id(), "ERC20", from.toLowerCase());
    String to = "T" + Long.toString(System.nanoTime(), 36) + "Addr";
    whitelist(a.id(), "TRC20", to);
    whitelist(b.id(), "TRC20", to);
    scan();
    assertThat(links(b.id())).containsExactly(a.id() + " DEVICE", a.id() + " WITHDRAW_TO", c.id() + " IP");
    assertThat(links(c.id())).as("以太坊地址不分大小写").containsExactly(a.id() + " DEPOSIT_FROM", b.id() + " IP");
    assertThat(jdbc.queryForObject("SELECT device_hash FROM account_device WHERE user_id = ? AND ip = ?", String.class, c.id(), ip)).as("格式不对的设备号当没带")
        .isNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_device WHERE device_hash = ?", Integer.class, d)).as("只存哈希，不存原值").isZero();

    // 上限 2：第三个用同一个 IP 的客户不再关联
    param(ConfigKey.RISK_LINK_MAX_USERS, "2");
    User e = user("0");
    devices.record(e.id(), "LOGIN", null, ip);
    scan();
    assertThat(links(e.id())).isEmpty();
    // 重复扫没有副作用
    scan();
    assertThat(links(b.id())).hasSize(3);
  }

  @Test
  void 公网IP的判断() throws Exception {
    User a = user("0");
    User b = user("0");
    for (String ip : List.of("127.0.0.1", "192.168.1.2", "172.16.0.9", "169.254.1.1", "::1", "fd00::1", "0.0.0.0", "unknown")) {
      devices.record(a.id(), "LOGIN", null, ip);
      devices.record(b.id(), "LOGIN", null, ip);
    }
    scan();
    assertThat(links(a.id())).isEmpty();
  }

  @Test
  void AC68_关联账户在同一市场押不同选项_各一条告警和邮件_押同一个选项不算_后来才关联的也查() throws Exception {
    User a = user("500");
    User b = user("500");
    User c = user("500");
    String d = dev();
    devices.record(a.id(), "LOGIN", d, null);
    devices.record(b.id(), "LOGIN", d, null);
    scan();
    Market m = market(3);
    bet(a.id(), m, 0, "10");
    bet(b.id(), m, 0, "10");
    scan();
    assertThat(alerts(a.id())).as("押同一个选项").isEmpty();
    clearInvocations(staff);
    bet(b.id(), m, 1, "20");
    scan();
    assertThat(alerts(a.id())).containsExactly("LINKED_OPPOSITE " + m.id() + " NEW");
    assertThat(alerts(b.id())).containsExactly("LINKED_OPPOSITE " + m.id() + " NEW");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(staff, atLeastOnce()).notifyStaff(eq("RISK_ALERT"), p.capture());
    assertThat(p.getAllValues()).anySatisfy(v -> {
      assertThat(v).containsEntry("kind", "关联账户对押").containsEntry("userId", String.valueOf(b.id())).containsEntry("market", m.no() + "（曼城 vs 利物浦 简）");
      assertThat(v.get("summary")).contains(String.valueOf(a.id()));
    });
    // 再下注不重复告警
    bet(b.id(), m, 2, "5");
    scan();
    assertThat(alerts(b.id())).hasSize(1);

    // c 先下注，后来才和 a 关联（提现白名单地址相同）：建关联时查近 30 天的市场
    bet(c.id(), m, 2, "10");
    scan();
    assertThat(alerts(c.id())).isEmpty();
    String to = "T" + Long.toString(System.nanoTime(), 36) + "Same";
    whitelist(a.id(), "TRC20", to);
    whitelist(c.id(), "TRC20", to);
    scan();
    assertThat(alerts(c.id())).containsExactly("LINKED_OPPOSITE " + m.id() + " NEW");
  }

  @Test
  void AC68_尾盘大额_截止前5分钟内单笔不小于奖池30百分比_边界() throws Exception {
    User big = user("5000", true);
    User small = user("5000", true);
    Market m = market(2, Instant.now(clock).plus(Duration.ofMinutes(20)), null);
    bet(small.id(), m, 1, "700");
    // 截止前 6 分钟：不在窗口里
    advance(Duration.ofMinutes(14));
    bet(big.id(), m, 0, "1000");
    scan();
    assertThat(alerts(big.id())).isEmpty();
    // 截止前 5 分钟整：在窗口里；300 / (1700 + 300) = 15% < 30%
    advance(Duration.ofMinutes(1));
    bet(big.id(), m, 0, "300");
    scan();
    assertThat(alerts(big.id())).isEmpty();
    // 正好 30%：奖池 2000 + 857.14 = 2857.14 × 30% = 857.142 > 857.14，不到；857.15 → 30.0004% ≥ 30%
    User late = user("5000", true);
    bet(late.id(), m, 1, "857.14");
    scan();
    assertThat(alerts(late.id())).isEmpty();
    User late2 = user("5000", true);
    clearInvocations(staff);
    bet(late2.id(), m, 1, "1606.13"); // 奖池 2857.14 + 1606.13 = 4463.27，30% = 1338.98
    scan();
    assertThat(alerts(late2.id())).containsExactly("LATE_LARGE_BET " + m.id() + " NEW");
    verify(staff).notifyStaff(eq("RISK_ALERT"), anyMap());
    Map<String, String> detail = riskAdmin.alerts("LATE_LARGE_BET", null, List.of(late2.id()), 0, 10).items().getFirst().detail();
    assertThat(detail).containsEntry("poolAfter", "4463.27").containsEntry("pct", "35.9").containsEntry("limitPct", "30").containsEntry("secondsBeforeClose", "300")
        .containsEntry("limitMinutes", "5").containsEntry("limitAmount", "100.00");
    // 参数可调：改成 40% 以后同样的下注不告警
    param(ConfigKey.RISK_LATE_BET_POOL_PCT, "40");
    User late3 = user("9000", true);
    bet(late3.id(), m, 0, "2000"); // 4463.27 + 2000 = 6463.27，30.9%
    scan();
    assertThat(alerts(late3.id())).isEmpty();
  }

  @Test
  void SQ24_尾盘大额_单笔最低金额默认100_不到不告警_正好等于告警_参数可改_0等于不限() throws Exception {
    // 人少的市场：奖池只有几十 USDT，截止前 5 分钟内的小注占比很高
    Market m = market(2, Instant.now(clock).plus(Duration.ofMinutes(10)), null);
    User early = user("1000", true);
    bet(early.id(), m, 1, "20");
    advance(Duration.ofMinutes(6)); // 截止前 4 分钟
    User small = user("1000", true);
    bet(small.id(), m, 0, "99.99"); // 99.99 / 119.99 = 83%，但不到 100
    scan();
    assertThat(alerts(small.id())).as("不到 100 USDT 不告警（SQ-24）").isEmpty();
    User exact = user("1000", true);
    bet(exact.id(), m, 1, "100"); // 100 / 219.99 = 45%，正好 100
    scan();
    assertThat(alerts(exact.id())).as("正好等于最低金额算").containsExactly("LATE_LARGE_BET " + m.id() + " NEW");
    // 参数改成 0：不限金额，小注也告警（另一个人少的市场）
    param(ConfigKey.RISK_LATE_BET_MIN_AMOUNT, "0");
    Market m2 = market(2, Instant.now(clock).plus(Duration.ofMinutes(4)), null);
    User e2 = user("1000", true);
    bet(e2.id(), m2, 1, "20");
    User tiny = user("1000", true);
    bet(tiny.id(), m2, 0, "30"); // 30 / 50 = 60%
    scan();
    assertThat(alerts(tiny.id())).as("0 = 不限：30 USDT 也告警").containsExactly("LATE_LARGE_BET " + m2.id() + " NEW");
    // 改成 200：199.99 不告警
    param(ConfigKey.RISK_LATE_BET_MIN_AMOUNT, "200");
    User mid = user("1000", true);
    bet(mid.id(), m2, 1, "199.99");
    scan();
    assertThat(alerts(mid.id())).isEmpty();
  }

  /** 改客户的注册时间（测试的固定时钟比数据库时间早） */
  void registeredDaysAgo(long uid, double days) {
    jdbc.update("UPDATE account_user SET created_at = ? WHERE id = ?", now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).minusMinutes((long) (days * 24 * 60)), uid);
  }

  @Test
  void AC68_新账户押中冷门_注册7天内_单笔不小于1000_赔率不小于5倍() throws Exception {
    User fresh = user("3000", true);
    User old = user("3000", true);
    User small = user("3000", true);
    User whale = user("20000", true);
    registeredDaysAgo(fresh.id(), 6.9);
    registeredDaysAgo(old.id(), 7);
    registeredDaysAgo(small.id(), 1);
    registeredDaysAgo(whale.id(), 100);
    Market m = shortMarket(2);
    bet(fresh.id(), m, 0, "1000");
    bet(old.id(), m, 0, "1000");
    bet(small.id(), m, 0, "999.99");
    bet(whale.id(), m, 1, "5000");
    bet(whale.id(), m, 1, "5000");
    // 赢方 2,999.99，奖池 12,999.99，费率 5%：(2999.99 + 10000 × 0.95) / 2999.99 = 4.16 < 5，不告警
    closeAndEnd(m);
    settle(m, 0);
    assertThat(alerts(fresh.id())).isEmpty();

    Market m2 = shortMarket(2);
    bet(fresh.id(), m2, 0, "1000");
    bet(old.id(), m2, 1, "1000");
    bet(whale.id(), m2, 1, "4000");
    // 赢方 1,000，奖池 6,000：(1000 + 5000 × 0.95) / 1000 = 5.75 ≥ 5
    clearInvocations(staff);
    closeAndEnd(m2);
    settle(m2, 0);
    assertThat(alerts(fresh.id())).containsExactly("NEW_ACCOUNT_LONGSHOT " + m2.id() + " NEW");
    verify(staff).notifyStaff(eq("RISK_ALERT"), anyMap());
    assertThat(riskAdmin.alerts("NEW_ACCOUNT_LONGSHOT", null, List.of(fresh.id()), 0, 10).items().getFirst().detail()).containsEntry("odds", "5.75")
        .containsEntry("accountDays", "6");
    assertThat(alerts(old.id())).isEmpty();
  }

  @Test
  void 新账户押中冷门_注册正好7天不算_金额正好1000算() throws Exception {
    User exact = user("3000", true);
    User seven = user("3000", true);
    User whale = user("20000", true);
    registeredDaysAgo(exact.id(), 1);
    registeredDaysAgo(seven.id(), 7);
    Market m = shortMarket(2);
    bet(exact.id(), m, 0, "1000");
    bet(seven.id(), m, 0, "1000");
    // (2000 + 9000 × 0.95) / 2000 = 5.27
    bet(whale.id(), m, 1, "4500");
    bet(whale.id(), m, 1, "4500");
    closeAndEnd(m);
    settle(m, 0);
    assertThat(alerts(exact.id())).containsExactly("NEW_ACCOUNT_LONGSHOT " + m.id() + " NEW");
    assertThat(alerts(seven.id())).isEmpty();
  }

  long withdrawal(long uid, String amount) {
    long no = System.nanoTime();
    jdbc.update("INSERT INTO wallet_withdrawal (public_no, user_id, network, address, amount, fee, status, cooling_ends_at, created_at) "
        + "VALUES (?, ?, 'TRC20', 'TAddr', ?, 1, 'COOLING', ?, ?)", no, uid, new BigDecimal(amount), now().plusHours(2), now());
    return jdbc.queryForObject("SELECT id FROM wallet_withdrawal WHERE public_no = ?", Long.class, no);
  }

  @Test
  void AC68_充了不玩就提_充值以后的有效下注本金不到充值乘R8倍数_退回的下注不算() throws Exception {
    User idle = user("500");
    User player = user("500");
    Market m = market(2);
    bet(player.id(), m, 0, "500");
    bet(idle.id(), m, 0, "499.99");
    long w1 = withdrawal(idle.id(), "100");
    long w2 = withdrawal(player.id(), "100");
    clearInvocations(staff);
    scan();
    assertThat(alerts(idle.id())).containsExactly("DEPOSIT_NO_BET 0 NEW");
    assertThat(alerts(player.id())).isEmpty();
    verify(staff).notifyStaff(eq("RISK_ALERT"), anyMap());
    RiskAlertAdminApi.Alert a = riskAdmin.alerts("DEPOSIT_NO_BET", null, List.of(idle.id()), 0, 10).items().getFirst();
    assertThat(a.amount()).isEqualByComparingTo("100");
    assertThat(a.detail()).containsEntry("deposit", "500.00").containsEntry("stake", "499.99").containsEntry("multiple", "1");
    assertThat(jdbc.queryForObject("SELECT ref_key FROM riskalert_alert WHERE id = ?", String.class, a.id())).isEqualTo("W:" + w1);
    assertThat(w2).isPositive();
    // R8 倍数是 0 时不检查
    param(ConfigKey.AUTO_APPROVE_R8_MULTIPLE, "0");
    User idle2 = user("500");
    withdrawal(idle2.id(), "100");
    scan();
    assertThat(alerts(idle2.id())).isEmpty();
  }

  @Test
  void R14_R15读的数据_关联账户对押的市场盈利_没处理的可疑告警() throws Exception {
    User a = user("3000", true);
    User b = user("3000", true);
    String d = dev();
    devices.record(a.id(), "LOGIN", d, null);
    devices.record(b.id(), "LOGIN", d, null);
    Market m = market(2); // 截止在 1 天后（不触发尾盘大额）
    bet(a.id(), m, 0, "100");
    bet(b.id(), m, 1, "300");
    closeAndEnd(m);
    settle(m, 0);
    scan();
    // a 赢：G = 300，抽成 15，派彩 400 − 15 → 净盈利 285
    RiskAlertApi.LinkedOpposite lo = riskApi.linkedOpposite(a.id(), now().minusDays(30));
    assertThat(lo.profit()).isEqualByComparingTo("285");
    assertThat(lo.marketIds()).containsExactly(m.id());
    assertThat(lo.linkedUsers()).containsExactly(b.id());
    assertThat(riskApi.linkedOpposite(b.id(), now().minusDays(30)).marketIds()).as("b 输了，没有派彩").isEmpty();
    assertThat(riskApi.linkedOpposite(a.id(), now().plusMinutes(1)).marketIds()).as("窗口之外").isEmpty();

    assertThat(riskApi.openSuspiciousAlerts(a.id(), now().minusDays(7))).isZero();
    jdbc.update("INSERT INTO riskalert_alert (kind, user_id, market_id, ref_key, detail, status, created_at) VALUES ('LATE_LARGE_BET', ?, ?, ?, '{}', 'SEEN', ?)",
        a.id(), m.id(), "M:" + m.id(), now().minusDays(6));
    jdbc.update("INSERT INTO riskalert_alert (kind, user_id, market_id, ref_key, detail, status, created_at) VALUES ('NEW_ACCOUNT_LONGSHOT', ?, ?, ?, '{}', 'HANDLED', ?)",
        a.id(), m.id(), "M:" + m.id(), now());
    jdbc.update("INSERT INTO riskalert_alert (kind, user_id, market_id, ref_key, detail, status, created_at) VALUES ('LINKED_OPPOSITE', ?, ?, ?, '{}', 'NEW', ?)",
        a.id(), m.id(), "M:x" + m.id(), now());
    assertThat(riskApi.openSuspiciousAlerts(a.id(), now().minusDays(7))).as("已查看还算没处理；已处理的、关联对押的不算").isEqualTo(1);
    assertThat(riskApi.openSuspiciousAlerts(a.id(), now().minusDays(5))).as("窗口之外").isZero();
  }

  @Test
  void 改状态_查列表_数量() throws Exception {
    User u = user("500");
    withdrawal(u.id(), "100");
    scan();
    RiskAlertAdminApi.Alert a = riskAdmin.alerts(null, "NEW", List.of(u.id()), 0, 10).items().getFirst();
    RiskAlertAdminApi.Alert before = riskAdmin.setStatus(a.id(), "SEEN", null, 7);
    assertThat(before.status()).isEqualTo("NEW");
    riskAdmin.setStatus(a.id(), "HANDLED", "已联系客户", 7);
    RiskAlertAdminApi.Alert now = riskAdmin.alert(a.id()).orElseThrow();
    assertThat(now.status()).isEqualTo("HANDLED");
    assertThat(now.note()).isEqualTo("已联系客户");
    assertThat(now.handledBy()).isEqualTo(7L);
    assertThat(riskAdmin.alerts(null, "NEW", List.of(u.id()), 0, 10).total()).isZero();
    assertThat(riskAdmin.alerts(null, null, List.of(), 0, 10).total()).as("客户筛选没有匹配").isZero();
    assertThat(riskAdmin.counts().get("DEPOSIT_NO_BET").get("HANDLED")).isPositive();
  }
}
