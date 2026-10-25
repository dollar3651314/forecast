package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.forecast.app.common.id.Snowflake;
import com.forecast.app.notification.api.MailApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.forecast.app.wallet.api.AutoApprove;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * v2 提现自动审批的后台接口（技术方案/v2-提现自动审批.md 第 7 节）：读取 / 保存规则（权限 withdrawal:auto_rule_edit、原因必填、版本冲突 409、范围和 R1 ≤ R2 ≤ R3、
 * 没有变化、审计前后值）、修改记录、总开关切换的告警邮件、出金列表的判定摘要和按规则筛选、详情的完整判定记录。
 */
@TestPropertySource(properties = "forecast.alert.emails=ops@example.com, risk@example.com")
class AdminAutoApproveTest extends AdminTestSupport {

  @MockitoBean MailApi mail;

  final ObjectMapper json = new ObjectMapper();

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  /** 规则配置恢复默认（总开关关闭），前后都做：不影响其他测试，也不受上一次中途失败的影响 */
  @BeforeEach
  @AfterEach
  void defaults() {
    AutoApprove.parse(Map.of()).toConfig().forEach((k, v) -> jdbc.update("UPDATE common_config SET value = ? WHERE config_key = ?", v, k));
    config.refresh();
  }

  /** 当前配置改几项后的保存请求体 */
  String saveBody(JsonNode settings, Boolean master, String reason, long expectedVersion, Map<String, String> params) {
    ObjectNode b = json.createObjectNode();
    b.put("masterEnabled", master == null ? settings.get("masterEnabled").asBoolean() : master);
    b.put("reason", reason);
    b.put("expectedVersion", expectedVersion);
    var rules = b.putArray("rules");
    for (JsonNode r : settings.get("rules")) {
      ObjectNode x = rules.addObject();
      String code = r.get("code").asString();
      x.put("code", code);
      x.put("enabled", params.containsKey(code + ".enabled") ? Boolean.parseBoolean(params.get(code + ".enabled")) : r.get("enabled").asBoolean());
      ObjectNode ps = x.putObject("params");
      for (var e : r.get("params").properties()) {
        String k = code + "." + e.getKey();
        ps.put(e.getKey(), params.getOrDefault(k, e.getValue().asString()));
      }
    }
    return b.toString();
  }

  @Test
  void 读取规则_默认总开关关闭_保存要新权限点_原因必填_版本冲突409_校验_没有变化_审计和修改记录() throws Exception {
    Admin s = superAdmin();
    JsonNode cur = body(call(get("/api/admin/withdraw-auto-approve"), s.cookie(), null));
    assertThat(cur.get("masterEnabled").asBoolean()).isFalse();
    assertThat(cur.get("rules")).hasSize(15);
    // R13 盈利客户（Amos 2026-10-18）：默认开启、门槛 0（迁移 V27）
    assertThat(cur.get("rules").get(12).toString()).isEqualTo("{\"code\":\"R13\",\"enabled\":true,\"params\":{\"maxNetProfit\":\"0\"}}");
    assertThat(cur.get("rules").get(0).get("code").asString()).isEqualTo("R1");
    long v = cur.get("version").asLong();

    // 只有查看权限：能读规则和修改记录，不能保存（AC-32 的扫描也覆盖）
    Admin viewer = withRoles(role(Permission.WITHDRAWAL_VIEW));
    assertThat(call(get("/api/admin/withdraw-auto-approve"), viewer.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(call(get("/api/admin/withdraw-auto-approve/changes"), viewer.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(call(put("/api/admin/withdraw-auto-approve"), viewer.cookie(), saveBody(cur, null, "x", v, Map.of("R1.maxAmount", "800"))).getStatus())
        .isEqualTo(403);
    // 系统配置页的 config:edit 也改不了（不在系统配置页）
    Admin cfg = withRoles(role(Permission.CONFIG_EDIT, Permission.CONFIG_VIEW, Permission.WITHDRAWAL_VIEW));
    assertThat(call(put("/api/admin/withdraw-auto-approve"), cfg.cookie(), saveBody(cur, null, "x", v, Map.of("R1.maxAmount", "800"))).getStatus()).isEqualTo(403);
    assertThat(call(put("/api/admin/configs/withdraw.autoApprove.enabled"), cfg.cookie(), "{\"value\":\"true\",\"reason\":\"绕过\"}").getStatus()).isEqualTo(404);

    Admin editor = withRoles(role(Permission.WITHDRAWAL_VIEW, Permission.WITHDRAWAL_AUTO_RULE_EDIT));
    MockHttpServletResponse noReason = call(put("/api/admin/withdraw-auto-approve"), editor.cookie(), saveBody(cur, null, " ", v, Map.of("R1.maxAmount", "800")));
    assertThat(noReason.getStatus()).isEqualTo(400);
    assertThat(noReason.getContentAsString()).contains("\"field\":\"reason\",\"code\":\"required\"");

    // R13 门槛不能是负数、最多 2 位小数
    MockHttpServletResponse neg = call(put("/api/admin/withdraw-auto-approve"), editor.cookie(), saveBody(cur, null, "门槛", v, Map.of("R13.maxNetProfit", "-1")));
    assertThat(neg.getStatus()).isEqualTo(400);
    assertThat(neg.getContentAsString()).contains("\"field\":\"rules.R13.maxNetProfit\",\"code\":\"invalid\"");
    assertThat(call(put("/api/admin/withdraw-auto-approve"), editor.cookie(), saveBody(cur, null, "门槛", v, Map.of("R13.maxNetProfit", "0.001"))).getStatus())
        .isEqualTo(400);

    MockHttpServletResponse stale = call(put("/api/admin/withdraw-auto-approve"), editor.cookie(), saveBody(cur, null, "放宽单笔", v - 1, Map.of("R1.maxAmount", "800")));
    assertThat(stale.getStatus()).isEqualTo(409);
    assertThat(stale.getContentAsString()).contains("CONFIG_CHANGED");

    MockHttpServletResponse cross = call(put("/api/admin/withdraw-auto-approve"), editor.cookie(),
        saveBody(cur, null, "放宽单笔", v, Map.of("R1.maxAmount", "3000", "R6.hours", "0", "R8.multiple", "1.234")));
    assertThat(cross.getStatus()).isEqualTo(400);
    assertThat(cross.getContentAsString()).contains("\"field\":\"rules.R6.hours\",\"code\":\"invalid\"").contains("\"field\":\"rules.R8.multiple\",\"code\":\"invalid\"");
    MockHttpServletResponse lt = call(put("/api/admin/withdraw-auto-approve"), editor.cookie(), saveBody(cur, null, "放宽单笔", v, Map.of("R1.maxAmount", "3000")));
    assertThat(lt.getContentAsString()).contains("\"field\":\"rules.R2.maxUser24h\",\"code\":\"ltR1\"");

    assertThat(call(put("/api/admin/withdraw-auto-approve"), editor.cookie(), saveBody(cur, null, "什么都没改", v, Map.of())).getContentAsString()).contains("NO_CHANGE");

    MockHttpServletResponse ok = call(put("/api/admin/withdraw-auto-approve"), editor.cookie(),
        saveBody(cur, null, "放宽单笔", v, Map.of("R1.maxAmount", "800.00", "R11.enabled", "false")));
    assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
    JsonNode saved = body(ok);
    assertThat(saved.get("version").asLong()).isEqualTo(v + 1);
    assertThat(saved.get("rules").get(0).get("params").get("maxAmount").asString()).as("规范化").isEqualTo("800");
    assertThat(saved.get("updatedBy").asString()).isEqualTo(editor.username());
    assertThat(saved.get("updatedAt").isNull()).isFalse();
    verify(mail, never()).send(anyString(), eq("OPS_AUTO_APPROVE_SWITCH"), org.mockito.ArgumentMatchers.anyMap());

    Map<String, Object> log = jdbc.queryForMap("SELECT target, high_risk, result, reason, before_json, after_json FROM admin_audit_log "
        + "WHERE action = 'withdrawal.auto_rule_update' AND result = 'SUCCESS' ORDER BY id DESC LIMIT 1");
    assertThat(log.get("target")).isEqualTo("v" + (v + 1));
    assertThat(log.get("high_risk")).isEqualTo(Boolean.TRUE);
    assertThat(log.get("reason")).isEqualTo("放宽单笔");
    assertThat(log.get("before_json").toString()).contains("\"R1.maxAmount\":\"500\"").contains("\"R11.enabled\":\"true\"").doesNotContain("R2.");
    assertThat(log.get("after_json").toString()).contains("\"R1.maxAmount\":\"800\"").contains("\"R11.enabled\":\"false\"");
    assertThat(audits("withdrawal.auto_rule_update")).contains("FAILURE " + editor.username() + " 放宽单笔（失败：CONFIG_CHANGED）");

    JsonNode changes = body(call(get("/api/admin/withdraw-auto-approve/changes"), viewer.cookie(), null));
    JsonNode first = changes.get(0);
    assertThat(first.get("version").asLong()).isEqualTo(v + 1);
    assertThat(first.get("adminName").asString()).isEqualTo(editor.username());
    assertThat(first.get("reason").asString()).isEqualTo("放宽单笔");
    List<String> fields = new ArrayList<>();
    for (JsonNode c : first.get("changes")) fields.add(c.get("field").asString() + " " + c.get("before").asString() + "→" + c.get("after").asString());
    assertThat(fields).containsExactly("R1.maxAmount 500→800", "R11.enabled true→false");

    // 用保存后的版本号可以接着改
    assertThat(call(put("/api/admin/withdraw-auto-approve"), editor.cookie(), saveBody(saved, null, "恢复默认", v + 1, Map.of("R1.maxAmount", "500", "R11.enabled", "true")))
        .getStatus()).isEqualTo(200);
  }

  @Test
  void 总开关打开和关闭都给告警邮箱发邮件_写明操作人和原因() throws Exception {
    Admin s = superAdmin();
    JsonNode cur = body(call(get("/api/admin/withdraw-auto-approve"), s.cookie(), null));
    long v = cur.get("version").asLong();
    clearInvocations(mail);
    assertThat(call(put("/api/admin/withdraw-auto-approve"), s.cookie(), saveBody(cur, true, "观察一周后打开", v, Map.of())).getStatus()).isEqualTo(200);
    @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(mail).send(eq("ops@example.com"), eq("OPS_AUTO_APPROVE_SWITCH"), p.capture());
    verify(mail).send(eq("risk@example.com"), eq("OPS_AUTO_APPROVE_SWITCH"), org.mockito.ArgumentMatchers.anyMap());
    assertThat(p.getValue()).containsEntry("stateZh", "打开").containsEntry("stateEn", "ON").containsEntry("version", String.valueOf(v + 1))
        .containsEntry("admin", s.username()).containsEntry("reason", "观察一周后打开");
    JsonNode on = body(call(get("/api/admin/withdraw-auto-approve"), s.cookie(), null));
    assertThat(on.get("masterEnabled").asBoolean()).isTrue();
    clearInvocations(mail);
    assertThat(call(put("/api/admin/withdraw-auto-approve"), s.cookie(), saveBody(on, false, "暂停", v + 1, Map.of())).getStatus()).isEqualTo(200);
    verify(mail).send(eq("ops@example.com"), eq("OPS_AUTO_APPROVE_SWITCH"), p.capture());
    assertThat(p.getValue()).containsEntry("stateZh", "关闭").containsEntry("reason", "暂停");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_template WHERE code = 'OPS_AUTO_APPROVE_SWITCH' AND audience = 'STAFF'", Integer.class)).isEqualTo(1);
  }

  @Test
  void 出金列表带判定摘要_按判定结果和触发的规则筛选_详情是完整判定记录() throws Exception {
    Admin s = superAdmin();
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "aaadm" + uid + "@example.com");
    long no = Snowflake.shared(1).next();
    jdbc.update("INSERT INTO wallet_withdrawal (public_no, user_id, network, address, amount, fee, status, cooling_ends_at, created_at) "
        + "VALUES (?, ?, 'TRC20', 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t', 800, 1, 'REVIEWING', '2026-10-14 08:00:00', '2026-10-14 06:00:00')", no, uid);
    long id = jdbc.queryForObject("SELECT id FROM wallet_withdrawal WHERE public_no = ?", Long.class, no);
    String results = "[{\"code\":\"R1\",\"status\":\"failed\",\"values\":{\"amount\":\"800\",\"limit\":\"500\"}},"
        + "{\"code\":\"R6\",\"status\":\"failed\",\"values\":{\"hours\":\"48\",\"event\":\"password_change\",\"ago\":\"1.5\"}},"
        + "{\"code\":\"R8\",\"status\":\"disabled\",\"values\":{}}]";
    jdbc.update("INSERT INTO wallet_withdraw_rule_check (withdrawal_id, phase, outcome, master_enabled, failed_rules, config_version, config_json, results_json, decided_at) "
        + "VALUES (?, 'PRECHECK', 'AUTO_APPROVED', 1, '', 3, '{}', '[]', '2026-10-14 06:00:00')", id);
    jdbc.update("INSERT INTO wallet_withdraw_rule_check (withdrawal_id, phase, outcome, master_enabled, failed_rules, config_version, config_json, results_json, decided_at) "
        + "VALUES (?, 'FINAL', 'MANUAL', 1, 'R1,R6', 0, '{}', ?, '2026-10-14 08:00:00')", id, results);
    long checkId = jdbc.queryForObject("SELECT MAX(id) FROM wallet_withdraw_rule_check WHERE withdrawal_id = ?", Long.class, id);
    jdbc.update("INSERT INTO wallet_withdraw_rule_hit (check_id, withdrawal_id, rule_code) VALUES (?, ?, 'R1'), (?, ?, 'R6')", checkId, id, checkId, id);
    jdbc.update("UPDATE wallet_withdrawal SET last_check_id = ? WHERE id = ?", checkId, id);

    String kw = "&keyword=" + no;
    JsonNode list = body(call(get("/api/admin/withdrawals?status=pending" + kw), s.cookie(), null));
    JsonNode item = list.get("items").get(0);
    assertThat(item.get("id").asString()).isEqualTo(String.valueOf(no));
    assertThat(item.get("autoReview").get("phase").asString()).isEqualTo("final");
    assertThat(item.get("autoReview").get("outcome").asString()).isEqualTo("manual");
    assertThat(item.get("autoReview").get("failed").toString()).isEqualTo("[\"R1\",\"R6\"]");
    assertThat(item.get("autoReview").get("decidedAt").asString()).isEqualTo("2026-10-14T08:00:00Z");

    assertThat(body(call(get("/api/admin/withdrawals?autoReview=R6" + kw), s.cookie(), null)).get("total").asLong()).isEqualTo(1);
    assertThat(body(call(get("/api/admin/withdrawals?autoReview=manual" + kw), s.cookie(), null)).get("total").asLong()).isEqualTo(1);
    assertThat(body(call(get("/api/admin/withdrawals?autoReview=R5" + kw), s.cookie(), null)).get("total").asLong()).isZero();
    assertThat(body(call(get("/api/admin/withdrawals?autoReview=auto_approved" + kw), s.cookie(), null)).get("total").asLong()).as("预判通过不算，看最近一次").isZero();
    assertThat(body(call(get("/api/admin/withdrawals?autoReview=none" + kw), s.cookie(), null)).get("total").asLong()).isZero();
    assertThat(call(get("/api/admin/withdrawals?autoReview=R16"), s.cookie(), null).getStatus()).isEqualTo(400);
    assertThat(body(call(get("/api/admin/withdrawals?autoReview=R13" + kw), s.cookie(), null)).get("total").asLong()).isZero();
    // 全部触发了 R6 的都真的触发了 R6
    JsonNode r6 = body(call(get("/api/admin/withdrawals?autoReview=R6&pageSize=100"), s.cookie(), null));
    for (JsonNode x : r6.get("items")) assertThat(x.get("autoReview").get("failed").toString()).contains("R6");

    JsonNode d = body(call(get("/api/admin/withdrawals/" + no), s.cookie(), null));
    JsonNode ar = d.get("autoReviewDetail");
    assertThat(ar.get("phase").asString()).isEqualTo("final");
    assertThat(ar.get("masterEnabled").asBoolean()).isTrue();
    assertThat(ar.get("configVersion").asLong()).isZero();
    assertThat(ar.get("configChangedBy").isNull()).as("第 0 版是初始值，没有修改人").isTrue();
    assertThat(ar.get("rules")).hasSize(3);
    assertThat(ar.get("rules").get(1).get("values").get("event").asString()).isEqualTo("password_change");
    assertThat(ar.get("precheck").get("outcome").asString()).isEqualTo("auto_approved");
    assertThat(ar.get("precheck").get("failed")).isEmpty();
    assertThat(d.get("withdrawal").get("autoReview").get("outcome").asString()).isEqualTo("manual");

    // 没有判定记录（上线前的提现）：两个字段都是空
    long no2 = Snowflake.shared(1).next();
    jdbc.update("INSERT INTO wallet_withdrawal (public_no, user_id, network, address, amount, fee, status, cooling_ends_at, created_at) "
        + "VALUES (?, ?, 'TRC20', 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t', 50, 1, 'REVIEWING', '2026-10-14 08:00:00', '2026-10-14 06:00:00')", no2, uid);
    JsonNode d2 = body(call(get("/api/admin/withdrawals/" + no2), s.cookie(), null));
    assertThat(d2.get("autoReviewDetail").isNull()).isTrue();
    assertThat(d2.get("withdrawal").get("autoReview").isNull()).isTrue();
    assertThat(body(call(get("/api/admin/withdrawals?autoReview=none&keyword=" + no2), s.cookie(), null)).get("total").asLong()).isEqualTo(1);

    // R13 盈利客户：只触发 R13 的，按 R13 筛选能找到，详情里有累计净盈利的组成
    long no3 = Snowflake.shared(1).next();
    jdbc.update("INSERT INTO wallet_withdrawal (public_no, user_id, network, address, amount, fee, status, cooling_ends_at, created_at) "
        + "VALUES (?, ?, 'TRC20', 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t', 300, 1, 'REVIEWING', '2026-10-14 08:00:00', '2026-10-14 06:00:00')", no3, uid);
    long id3 = jdbc.queryForObject("SELECT id FROM wallet_withdrawal WHERE public_no = ?", Long.class, no3);
    jdbc.update("INSERT INTO wallet_withdraw_rule_check (withdrawal_id, phase, outcome, master_enabled, failed_rules, config_version, config_json, results_json, decided_at) "
        + "VALUES (?, 'FINAL', 'MANUAL', 1, 'R13', 0, '{}', ?, '2026-10-14 08:00:00')", id3,
        "[{\"code\":\"R13\",\"status\":\"failed\",\"values\":{\"net\":\"1600\",\"realized\":\"1860\",\"fee\":\"212.4\",\"swap\":\"47.6\",\"limit\":\"0\"}}]");
    long check3 = jdbc.queryForObject("SELECT MAX(id) FROM wallet_withdraw_rule_check WHERE withdrawal_id = ?", Long.class, id3);
    jdbc.update("INSERT INTO wallet_withdraw_rule_hit (check_id, withdrawal_id, rule_code) VALUES (?, ?, 'R13')", check3, id3);
    jdbc.update("UPDATE wallet_withdrawal SET last_check_id = ? WHERE id = ?", check3, id3);
    JsonNode r13 = body(call(get("/api/admin/withdrawals?autoReview=R13&keyword=" + no3), s.cookie(), null));
    assertThat(r13.get("total").asLong()).isEqualTo(1);
    assertThat(r13.get("items").get(0).get("autoReview").get("failed").toString()).isEqualTo("[\"R13\"]");
    assertThat(body(call(get("/api/admin/withdrawals?autoReview=R1&keyword=" + no3), s.cookie(), null)).get("total").asLong()).isZero();
    JsonNode d3 = body(call(get("/api/admin/withdrawals/" + no3), s.cookie(), null)).get("autoReviewDetail");
    assertThat(d3.get("rules").get(0).get("values").get("net").asString()).isEqualTo("1600");
    assertThat(d3.get("rules").get(0).get("values").get("swap").asString()).isEqualTo("47.6");
  }
}
