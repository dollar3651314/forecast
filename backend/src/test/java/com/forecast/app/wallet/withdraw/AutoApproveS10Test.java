package com.forecast.app.wallet.withdraw;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.account.device.DeviceLog;
import com.forecast.app.ledger.api.CustomerFlowApi;
import com.forecast.app.riskalert.scan.RiskScanner;
import com.forecast.app.settle.SettleTestSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 提现自动审批的彩池口径（forecast S10，需求说明书第 8.2 节、AC-64）：用真实的下注、结算、退款、关联账户、告警数据跑一次判定
 * （{@link AutoApproveService#precheck}，读真实的账本、彩池、风控），核对 R8、R9、R10、R12、R13、R14、R15 的结果和数值。
 * 规则本身的边界在 {@link AutoApproveEngineTest}（纯计算）。
 */
class AutoApproveS10Test extends SettleTestSupport {

  @Autowired AutoApproveService auto;
  @Autowired CustomerFlowApi flows;
  @Autowired DeviceLog devices;
  @Autowired RiskScanner scanner;

  /** 账本是数据库时间、下注和提现是测试时钟：时钟拨到"现在 + 1 小时"，充值早于下注（同 RiskAlertTest） */
  @BeforeEach
  void realTime() {
    Instant target = Instant.now().plus(Duration.ofHours(1));
    Instant now = Instant.now(clock);
    if (now.isBefore(target)) advance(Duration.between(now, target));
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  /** 一笔冷静期中的提现，跑一次预判，返回每条规则：code → {status, values} */
  Map<String, JsonNode> judge(long uid, String amount) throws Exception {
    long no = System.nanoTime();
    jdbc.update("INSERT INTO wallet_withdrawal (public_no, user_id, network, address, amount, fee, status, cooling_ends_at, created_at) "
        + "VALUES (?, ?, 'TRC20', 'TAddrS10', ?, 1, 'COOLING', ?, ?)", no, uid, new BigDecimal(amount), now().plusHours(2), now());
    long id = jdbc.queryForObject("SELECT id FROM wallet_withdrawal WHERE public_no = ?", Long.class, no);
    auto.precheck(id);
    String results = jdbc.queryForObject("SELECT c.results_json FROM wallet_withdraw_rule_check c JOIN wallet_withdrawal w ON w.last_check_id = c.id WHERE w.id = ?",
        String.class, id);
    Map<String, JsonNode> out = new LinkedHashMap<>();
    for (JsonNode r : json.readTree(results)) out.put(r.get("code").asString(), r);
    // 这笔只用来判定：标成撤销，不影响后面的累计提现（R9）
    jdbc.update("UPDATE wallet_withdrawal SET status = 'CANCELLED' WHERE id = ?", id);
    return out;
  }

  static String status(Map<String, JsonNode> rs, String code) {
    return rs.get(code).get("status").asString();
  }

  static String value(Map<String, JsonNode> rs, String code, String key) {
    JsonNode v = rs.get(code).get("values").get(key);
    return v == null ? null : v.asString();
  }

  @Test
  void AC64_R8_充值后的有效下注本金_取消退回的不算() throws Exception {
    User u = user("1000");
    Market a = market(2);
    Market b = market(2);
    bet(u.id(), a, 0, "600");
    bet(u.id(), b, 1, "400");
    Map<String, JsonNode> rs = judge(u.id(), "100");
    assertThat(status(rs, "R8")).isEqualTo("passed");
    assertThat(value(rs, "R8", "volume")).isEqualTo("1000");
    admin.cancel(Long.parseLong(b.no()), 1, "测试取消");
    rs = judge(u.id(), "100");
    assertThat(status(rs, "R8")).as("取消市场退回的 400 不算").isEqualTo("failed");
    assertThat(value(rs, "R8", "volume")).isEqualTo("600");
    assertThat(value(rs, "R8", "deposit")).isEqualTo("1000");
    assertThat(value(rs, "R8", "ratio")).isEqualTo("0.6");
  }

  @Test
  void AC64_R9_R10_R13_赢家按派彩减本金减抽成_输家为负_退款为0() throws Exception {
    User win = user("1000", true);
    User lose = user("1000", true);
    User refunded = user("1000", true);
    Market m = market(2);
    bet(win.id(), m, 0, "100");
    bet(lose.id(), m, 1, "300");
    Market c = market(2);
    bet(refunded.id(), c, 0, "200");
    admin.cancel(Long.parseLong(c.no()), 1, "测试取消");
    closeAndEnd(m);
    settle(m, 0);

    // 赢家：G = 300，抽成 5% = 15，派彩 400（本金 100 + 盈利 300），净盈利 = 400 + 0 − 100 − 15 = 285
    CustomerFlowApi.Realized r = flows.realized(win.id(), null);
    assertThat(r.payout()).isEqualByComparingTo("400");
    assertThat(r.stake()).isEqualByComparingTo("100");
    assertThat(r.fee()).isEqualByComparingTo("15");
    assertThat(r.net()).isEqualByComparingTo("285");
    Map<String, JsonNode> rs = judge(win.id(), "100");
    assertThat(status(rs, "R13")).as("盈利客户转人工（门槛 0）").isEqualTo("failed");
    assertThat(rs.get("R13").get("values").toString().replace(" ", "")).contains("\"net\":\"285\"").contains("\"payout\":\"400\"").contains("\"stake\":\"100\"")
        .contains("\"fee\":\"15\"").contains("\"compensation\":\"0\"");
    assertThat(value(rs, "R9", "pnl")).isEqualTo("285");
    assertThat(value(rs, "R9", "cap")).isEqualTo("1285");
    assertThat(value(rs, "R10", "profit")).isEqualTo("285");

    rs = judge(lose.id(), "100");
    assertThat(value(rs, "R13", "net")).isEqualTo("-300");
    assertThat(status(rs, "R13")).isEqualTo("passed");
    assertThat(value(rs, "R9", "cap")).as("输的记 −本金").isEqualTo("700");
    rs = judge(refunded.id(), "100");
    assertThat(value(rs, "R13", "net")).as("退款记 0").isEqualTo("0");
    assertThat(value(rs, "R13", "stake")).isEqualTo("0");
    assertThat(status(rs, "R13")).as("等于门槛 0 算通过").isEqualTo("passed");

    // R9：累计提现超过充值 + 盈亏转人工（输家：1000 − 300 = 700）
    jdbc.update("INSERT INTO wallet_withdrawal (public_no, user_id, network, address, amount, fee, status, cooling_ends_at, created_at) "
        + "VALUES (?, ?, 'TRC20', 'TAddrS10', 650, 1, 'COMPLETED', ?, ?)", System.nanoTime(), lose.id(), now(), now());
    assertThat(status(judge(lose.id(), "50"), "R9")).as("650 + 50 = 700").isEqualTo("passed");
    assertThat(status(judge(lose.id(), "50.01"), "R9")).isEqualTo("failed");
  }

  @Test
  void AC64_R14_关联账户在赢的市场押了其他选项_盈利超过100转人工() throws Exception {
    User a = user("1000", true);
    User b = user("1000", true);
    User x = user("1000", true);
    String d = "dev-s10-" + Long.toString(System.nanoTime(), 36);
    devices.record(a.id(), "LOGIN", d, null);
    devices.record(b.id(), "LOGIN", d, null);
    for (int i = 0; i < 100 && scanner.scanAll().total() > 0; i++) {
      // 扫到没有新数据
    }
    Market m = market(2);
    bet(a.id(), m, 0, "100");
    bet(b.id(), m, 1, "110");
    bet(x.id(), m, 1, "100");
    closeAndEnd(m);
    settle(m, 0);
    // a：G = 210，抽成 10.5，净盈利 199.5 > 100
    Map<String, JsonNode> rs = judge(a.id(), "100");
    assertThat(status(rs, "R14")).isEqualTo("failed");
    assertThat(value(rs, "R14", "profit")).isEqualTo("199.5");
    assertThat(value(rs, "R14", "markets")).isEqualTo("1");
    assertThat(value(rs, "R14", "linked")).isEqualTo("1");
    assertThat(status(judge(b.id(), "100"), "R14")).as("b 输了").isEqualTo("passed");
    assertThat(status(judge(x.id(), "100"), "R14")).as("x 没有关联账户").isEqualTo("passed");
  }

  @Test
  void AC64_R15_近7天有没处理的尾盘大额或押中冷门告警转人工_处理以后通过() throws Exception {
    User u = user("1000", true);
    Map<String, JsonNode> rs = judge(u.id(), "100");
    assertThat(status(rs, "R15")).isEqualTo("passed");
    jdbc.update("INSERT INTO riskalert_alert (kind, user_id, market_id, ref_key, detail, status, created_at) VALUES ('NEW_ACCOUNT_LONGSHOT', ?, 1, 'M:1', '{}', 'NEW', ?)",
        u.id(), now().minusDays(1));
    rs = judge(u.id(), "100");
    assertThat(status(rs, "R15")).isEqualTo("failed");
    assertThat(value(rs, "R15", "count")).isEqualTo("1");
    jdbc.update("UPDATE riskalert_alert SET status = 'FALSE_POSITIVE' WHERE user_id = ?", u.id());
    assertThat(status(judge(u.id(), "100"), "R15")).as("误报以后通过").isEqualTo("passed");
  }

  @Test
  void AC64_R12_保留彩池对账有未处理差异() throws Exception {
    User u = user("1000", true);
    jdbc.update("INSERT INTO settle_pool_recon_issue (ref_key, check_date, kind, source, expected, actual, diff, detail, status, created_at) "
        + "VALUES ('TEST:S10', ?, 'POOL_VS_BETS', 'DAILY', 0, 1, 1, 'S10', 'OPEN', ?)", now().toLocalDate(), now());
    Map<String, JsonNode> rs = judge(u.id(), "100");
    assertThat(status(rs, "R12")).isEqualTo("failed");
    assertThat(value(rs, "R12", "issues")).contains("poolRecon");
    assertThat(rs).hasSize(15);
    assertThat(List.copyOf(rs.keySet())).endsWith("R13", "R14", "R15");
  }
}
