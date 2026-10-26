package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * forecast S10 的后台接口：风控告警列表、数量、改状态（权限点 risk_alert:view / risk_alert:handle，审计）；客户的关联账户；
 * 员工账户登记 / 取消（customer:edit，高危，原因必填，审计里有退回的下注）；参数设置的"风控"一组和 KYC 阈值（betting_params:edit，AC-66）；菜单角标。
 */
class AdminRiskTest extends AdminTestSupport {

  final ObjectMapper json = new ObjectMapper();

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  long customer() {
    String email = unique("risk") + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    jdbc.update("INSERT INTO ledger_account (user_id) VALUES (?)", id);
    return id;
  }

  long alert(long uid, String kind, String status) {
    String ref = unique("T:");
    jdbc.update("INSERT INTO riskalert_alert (kind, user_id, market_id, ref_key, amount, detail, status, created_at) "
        + "VALUES (?, ?, NULL, ?, 100, '{\"withdrawalNo\":\"1\",\"deposit\":\"500.00\"}', ?, UTC_TIMESTAMP(3))", kind, uid, ref, status);
    return jdbc.queryForObject("SELECT id FROM riskalert_alert WHERE ref_key = ?", Long.class, ref);
  }

  @Test
  void 风控告警_查看要risk_alert_view_改状态要risk_alert_handle_已处理和误报要写说明_写审计() throws Exception {
    long uid = customer();
    long id = alert(uid, "DEPOSIT_NO_BET", "NEW");
    Admin nobody = withRoles(role(Permission.CUSTOMER_VIEW));
    assertThat(call(get("/api/admin/risk-alerts"), nobody.cookie(), null).getStatus()).isEqualTo(403);
    assertThat(call(get("/api/admin/risk-alerts/counts"), nobody.cookie(), null).getStatus()).isEqualTo(403);
    Admin viewer = withRoles(role(Permission.RISK_ALERT_VIEW));
    MockHttpServletResponse r = call(get("/api/admin/risk-alerts?kind=deposit_no_bet&status=new&keyword=" + jdbc.queryForObject("SELECT email FROM account_user WHERE id = ?", String.class, uid)), viewer.cookie(), null);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode page = body(r);
    assertThat(page.get("total").asLong()).isEqualTo(1);
    JsonNode a = page.get("items").get(0);
    assertThat(a.get("id").asString()).isEqualTo(String.valueOf(id));
    assertThat(a.get("kind").asString()).isEqualTo("deposit_no_bet");
    assertThat(a.get("status").asString()).isEqualTo("new");
    assertThat(a.get("userId").asString()).isEqualTo(String.valueOf(uid));
    assertThat(a.get("amount").asString()).isEqualTo("100");
    assertThat(a.get("detail").get("deposit").asString()).isEqualTo("500.00");
    assertThat(call(get("/api/admin/risk-alerts?kind=nope"), viewer.cookie(), null).getStatus()).isEqualTo(400);
    JsonNode counts = body(call(get("/api/admin/risk-alerts/counts"), viewer.cookie(), null));
    assertThat(counts.get("open").asLong()).isPositive();
    assertThat(counts.get("openByKind").has("late_large_bet")).isTrue();
    assertThat(body(call(get("/api/admin/menu-badges"), viewer.cookie(), null)).get("riskAlerts").asLong()).isPositive();
    assertThat(body(call(get("/api/admin/menu-badges"), nobody.cookie(), null)).get("riskAlerts").asLong()).isZero();

    assertThat(call(post("/api/admin/risk-alerts/" + id + "/status"), viewer.cookie(), "{\"status\":\"seen\"}").getStatus()).isEqualTo(403);
    Admin handler = withRoles(role(Permission.RISK_ALERT_VIEW, Permission.RISK_ALERT_HANDLE));
    r = call(post("/api/admin/risk-alerts/" + id + "/status"), handler.cookie(), "{\"status\":\"seen\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).get("status").asString()).isEqualTo("seen");
    assertThat(body(r).get("handledBy").asString()).isEqualTo(handler.username());
    r = call(post("/api/admin/risk-alerts/" + id + "/status"), handler.cookie(), "{\"status\":\"handled\"}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("note");
    r = call(post("/api/admin/risk-alerts/" + id + "/status"), handler.cookie(), "{\"status\":\"false_positive\",\"note\":\"客户刚充值就改了主意\"}");
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(body(r).get("note").asString()).isEqualTo("客户刚充值就改了主意");
    assertThat(jdbc.queryForList("SELECT CONCAT(target, ' ', before_json, ' ', after_json) FROM admin_audit_log WHERE action = 'risk_alert.status' AND target = ? ORDER BY id",
        String.class, String.valueOf(id))).hasSize(2).last().asString().contains("SEEN").contains("FALSE_POSITIVE");
    assertThat(call(post("/api/admin/risk-alerts/999999999/status"), handler.cookie(), "{\"status\":\"seen\"}").getStatus()).isEqualTo(404);
  }

  @Test
  void 客户详情_员工登记要customer_edit_原因必填_写审计_关联账户() throws Exception {
    long uid = customer();
    long other = customer();
    jdbc.update("INSERT INTO riskalert_link (user_a, user_b, reason, evidence, first_seen_at) VALUES (?, ?, 'IP', '45.1.2.3', UTC_TIMESTAMP(3))", Math.min(uid, other),
        Math.max(uid, other));
    Admin viewer = withRoles(role(Permission.CUSTOMER_VIEW));
    JsonNode links = body(call(get("/api/admin/customers/" + uid + "/links"), viewer.cookie(), null));
    assertThat(links).hasSize(1);
    assertThat(links.get(0).get("userId").asString()).isEqualTo(String.valueOf(other));
    assertThat(links.get(0).get("reason").asString()).isEqualTo("ip");
    assertThat(links.get(0).get("evidence").asString()).isEqualTo("45.1.2.3");
    assertThat(body(call(get("/api/admin/customers/" + uid), viewer.cookie(), null)).get("staff").asBoolean()).isFalse();
    assertThat(call(post("/api/admin/customers/" + uid + "/staff"), viewer.cookie(), "{\"staff\":true,\"reason\":\"x\"}").getStatus()).isEqualTo(403);

    Admin editor = withRoles(role(Permission.CUSTOMER_VIEW, Permission.CUSTOMER_EDIT));
    assertThat(call(post("/api/admin/customers/" + uid + "/staff"), editor.cookie(), "{\"staff\":true}").getStatus()).as("原因必填").isEqualTo(400);
    MockHttpServletResponse r = call(post("/api/admin/customers/" + uid + "/staff"), editor.cookie(), "{\"staff\":true,\"reason\":\"运营同事的账户\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).get("customer").get("staff").asBoolean()).isTrue();
    assertThat(body(r).get("refunded").get("bets").asInt()).isZero();
    assertThat(jdbc.queryForObject("SELECT is_staff FROM account_user WHERE id = ?", Boolean.class, uid)).isTrue();
    List<String> audit = jdbc.queryForList("SELECT CONCAT(result, ' ', high_risk, ' ', reason, ' ', before_json, ' ', after_json) FROM admin_audit_log "
        + "WHERE action = 'customer.staff' AND target = ?", String.class, String.valueOf(uid));
    assertThat(audit).hasSize(1);
    assertThat(audit.getFirst()).startsWith("SUCCESS 1 运营同事的账户").contains("\"staff\":false").contains("\"staff\":true").contains("\"refundedBets\":0");
    r = call(post("/api/admin/customers/" + uid + "/staff"), editor.cookie(), "{\"staff\":false,\"reason\":\"登记错了\"}");
    assertThat(body(r).get("customer").get("staff").asBoolean()).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log WHERE action = 'customer.unstaff' AND target = ?", Integer.class, String.valueOf(uid)))
        .isEqualTo(1);
    assertThat(call(post("/api/admin/customers/999999999/staff"), editor.cookie(), "{\"staff\":true,\"reason\":\"x\"}").getStatus()).isEqualTo(404);
  }

  @Test
  void AC66_参数设置的风控一组和KYC阈值_原因必填_前后写审计_同时修改后保存的冲突() throws Exception {
    Admin a = withRoles(role(Permission.BETTING_PARAMS_EDIT));
    JsonNode items = body(call(get("/api/admin/params/betting"), a.cookie(), null));
    List<String> risk = new java.util.ArrayList<>();
    for (JsonNode it : items) if ("risk".equals(it.get("group").asString())) risk.add(it.get("key").asString() + "=" + it.get("value").asString());
    // S16（SQ-24）：尾盘大额加单笔最低金额，默认 100 USDT
    assertThat(risk).containsExactly("risk.lateBet.minutes=5", "risk.lateBet.poolPct=30", "risk.lateBet.minAmount=100", "risk.longshot.accountDays=7", "risk.longshot.minAmount=1000",
        "risk.longshot.minOdds=5", "risk.link.maxUsersPerKey=10");
    try {
      assertThat(call(put("/api/admin/params/betting/risk.lateBet.poolPct"), a.cookie(), "{\"value\":\"35\"}").getStatus()).as("原因必填").isEqualTo(400);
      assertThat(call(put("/api/admin/params/betting/risk.lateBet.poolPct"), a.cookie(), "{\"value\":\"101\",\"reason\":\"x\"}").getStatus()).isEqualTo(400);
      MockHttpServletResponse r = call(put("/api/admin/params/betting/risk.lateBet.poolPct"), a.cookie(), "{\"value\":\"35\",\"reason\":\"误报太多\",\"expected\":\"30\"}");
      assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
      assertThat(call(put("/api/admin/params/betting/risk.lateBet.poolPct"), a.cookie(), "{\"value\":\"40\",\"reason\":\"x\",\"expected\":\"30\"}").getStatus())
          .as("别人刚改过").isEqualTo(409);
      r = call(put("/api/admin/params/betting/kyc.depositThreshold"), a.cookie(), "{\"value\":\"1500\",\"reason\":\"调高认证门槛\",\"expected\":\"1000\"}");
      assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
      List<String> audit = jdbc.queryForList("SELECT CONCAT(before_json, ' ', after_json) FROM admin_audit_log WHERE reason = '调高认证门槛' ORDER BY id DESC LIMIT 1",
          String.class);
      assertThat(audit.getFirst()).contains("1000").contains("1500");
    } finally {
      jdbc.update("UPDATE common_config SET value = '30' WHERE config_key = 'risk.lateBet.poolPct'");
      jdbc.update("UPDATE common_config SET value = '1000' WHERE config_key = 'kyc.depositThreshold'");
      config.refresh();
    }
  }
}
