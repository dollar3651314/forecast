package com.forecast.app.wallet.sweep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.forecast.app.admin.security.Passwords;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.address.Create2;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台"资金归集"接口（方案第 4.9 节）：权限点（sweep:view / sweep:operate / sweep:config）、高危操作原因必填、审计、设置的校验和版本号、
 * 概览（TRON 下一阶段）、执行和批次详情。
 */
class SweepAdminTest extends SweepContextSupport {

  static final String PASSWORD = "Passw0rd-for-test";
  @Autowired MockMvc mvc;
  final ObjectMapper json = new ObjectMapper();

  Cookie login(Permission... perms) throws Exception {
    String name = "sw_" + Long.toString(System.nanoTime(), 36);
    jdbc.update("INSERT INTO admin_user (username, display_name, email, password_hash, status, must_change_password, created_at) "
        + "VALUES (?, ?, ?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(3))", name, name, name + "@example.com", Passwords.hash(PASSWORD));
    long id = jdbc.queryForObject("SELECT id FROM admin_user WHERE username = ?", Long.class, name);
    jdbc.update("INSERT INTO admin_role (code, name_zh, name_en, description, built_in) VALUES (?, ?, ?, '', 0)", name, name, name);
    long role = jdbc.queryForObject("SELECT id FROM admin_role WHERE code = ?", Long.class, name);
    for (Permission p : perms) jdbc.update("INSERT INTO admin_role_permission (role_id, permission) VALUES (?, ?)", role, p.code);
    jdbc.update("INSERT INTO admin_user_role (admin_id, role_id) VALUES (?, ?)", id, role);
    MockHttpServletResponse r = call(post("/api/admin/auth/login"), null, "{\"username\":\"" + name + "\",\"password\":\"" + PASSWORD + "\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return r.getCookie("fc_admin_session");
  }

  MockHttpServletResponse call(MockHttpServletRequestBuilder b, Cookie c, String body) throws Exception {
    b = b.with(r -> {
      r.setRemoteAddr("10.0.0.5");
      return r;
    });
    if (c != null) b = b.cookie(c);
    if (body != null) b = b.contentType(MediaType.APPLICATION_JSON).content(body);
    return mvc.perform(b).andReturn().getResponse();
  }

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  @Test
  void 权限点_查看不能执行和改设置() throws Exception {
    Cookie view = login(Permission.SWEEP_VIEW);
    assertThat(call(get("/api/admin/sweep/overview"), view, null).getStatus()).isEqualTo(200);
    assertThat(call(get("/api/admin/sweep/settings"), view, null).getStatus()).isEqualTo(200);
    assertThat(call(get("/api/admin/sweep/batches"), view, null).getStatus()).isEqualTo(200);
    assertThat(call(post("/api/admin/sweep/batches"), view, "{\"chain\":\"ERC20\",\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    assertThat(call(post("/api/admin/sweep/batches/1/retry"), view, "{\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    assertThat(call(put("/api/admin/sweep/settings"), view, "{}").getStatus()).isEqualTo(403);
    Cookie none = login(Permission.RECON_VIEW);
    assertThat(call(get("/api/admin/sweep/overview"), none, null).getStatus()).isEqualTo(403);
  }

  @Test
  void 概览_TRON下一阶段() throws Exception {
    JsonNode o = body(call(get("/api/admin/sweep/overview"), login(Permission.SWEEP_VIEW), null));
    assertThat(o).hasSize(3);
    assertThat(o.get(2).path("chain").asString()).isEqualTo("TRC20");
    assertThat(o.get(2).path("mode").asString()).isEqualTo("offline");
    assertThat(o.get(2).path("available").asBoolean()).isFalse();
    assertThat(o.get(2).path("unavailable").asString()).contains("下一阶段");
    assertThat(o.get(0).path("mode").asString()).isEqualTo("contract");
    assertThat(o.get(0).path("destination").asString()).isEqualToIgnoringCase(PARENT);
  }

  @Test
  void 执行归集_原因必填_写审计_批次详情() throws Exception {
    FakeEvm f = evm(Chain.ERC20);
    long uid = newUser();
    credited(f, Chain.ERC20, Create2.forwarderAddress(FACTORY, IMPLEMENTATION, PARENT, BATCHER, Create2.salt(uid)), "321");
    Cookie op = login(Permission.SWEEP_VIEW, Permission.SWEEP_OPERATE);
    JsonNode p = body(call(post("/api/admin/sweep/preview"), op, "{\"chain\":\"ERC20\"}"));
    assertThat(p.path("totals").path("count").asInt()).isEqualTo(1);
    assertThat(p.path("candidates").get(0).path("customerName").isMissingNode()).isFalse();
    assertThat(p.path("candidates").get(0).path("usdt").asString()).isEqualTo("321");

    MockHttpServletResponse r = call(post("/api/admin/sweep/batches"), op, "{\"chain\":\"ERC20\",\"reason\":\" \"}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("reason");
    r = call(post("/api/admin/sweep/batches"), op, "{\"chain\":\"ERC20\",\"reason\":\"每周归集\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    String no = body(r).path("no").asString();
    assertThat(jdbc.queryForMap("SELECT result, high_risk, reason, target FROM admin_audit_log WHERE action = 'sweep.run' ORDER BY id DESC LIMIT 1"))
        .containsEntry("result", "SUCCESS").containsEntry("reason", "每周归集").containsEntry("target", "ERC20");

    r = call(post("/api/admin/sweep/batches"), op, "{\"chain\":\"ERC20\",\"reason\":\"再来一批\"}");
    assertThat(r.getStatus()).as("这一批还没完成，地址在批次里：没有可归集的").isEqualTo(400);
    assertThat(body(r).path("code").asString()).isEqualTo("SWEEP_NOTHING");

    mineAndConfirm(f, Chain.ERC20);
    JsonNode d = body(call(get("/api/admin/sweep/batches/" + no), op, null));
    assertThat(d.path("status").asString()).isEqualTo("confirmed");
    assertThat(d.path("mode").asString()).isEqualTo("contract");
    assertThat(d.path("reason").asString()).isEqualTo("每周归集");
    assertThat(d.path("sweptUsdt").asString()).isEqualTo("321");
    assertThat(d.path("items").get(0).path("status").asString()).isEqualTo("swept");
    assertThat(d.path("items").get(0).path("deploy").asBoolean()).isTrue();
    assertThat(d.path("events").size()).isGreaterThanOrEqualTo(3);
    JsonNode list = body(call(get("/api/admin/sweep/batches?chain=ERC20&page=1&pageSize=5"), op, null));
    assertThat(list.path("items").get(0).path("no").asString()).isEqualTo(no);
    assertThat(call(get("/api/admin/sweep/batches/123"), op, null).getStatus()).isEqualTo(404);
    assertThat(call(post("/api/admin/sweep/preview"), op, "{\"chain\":\"DOGE\"}").getStatus()).isEqualTo(400);
  }

  @Test
  void 设置_默认值_校验_版本号_审计() throws Exception {
    Cookie cfg = login(Permission.SWEEP_VIEW, Permission.SWEEP_CONFIG);
    JsonNode s = body(call(get("/api/admin/sweep/settings"), cfg, null));
    assertThat(s.path("thresholds").path("ERC20").asString()).isEqualTo("100");
    assertThat(s.path("thresholds").path("BSC").asString()).isEqualTo("10");
    assertThat(s.path("maxFeeRatioPct").asString()).isEqualTo("3");
    assertThat(s.path("evmCaps").path("perAddress").asString()).isEqualTo("5000");
    assertThat(s.path("evmCaps").path("perBatch").asString()).isEqualTo("20000");
    assertThat(s.path("defaults").path("threshold.ERC20").asString()).isEqualTo("100");
    assertThat(s.path("evm").path("configured").asBoolean()).isTrue();
    assertThat(s.path("evm").path("parent").asString()).isEqualToIgnoringCase(PARENT);
    assertThat(s.path("evm").path("operatorReady").asBoolean()).isTrue();
    assertThat(s.toString()).doesNotContain(org.web3j.utils.Numeric.toHexStringNoPrefixZeroPadded(OPERATOR.getPrivateKey(), 64));
    long v = s.path("version").asLong();

    String good = json.writeValueAsString(Map.of("thresholds", Map.of("ERC20", "150", "BSC", "10", "TRC20", "100"), "maxFeeRatioPct", "2.5", "evmCaps",
        Map.of("perAddress", "5000", "perBatch", "20000"), "operatorMin", Map.of("ERC20", "0.02", "BSC", "0"), "reason", "调高门槛", "expectedVersion", v));
    String noReason = good.replace("调高门槛", "");
    assertThat(call(put("/api/admin/sweep/settings"), cfg, noReason).getStatus()).isEqualTo(400);
    String stale = good.replace("\"expectedVersion\":" + v, "\"expectedVersion\":" + (v + 5));
    assertThat(call(put("/api/admin/sweep/settings"), cfg, stale).getStatus()).isEqualTo(409);
    String bad = good.replace("\"2.5\"", "\"150\"");
    MockHttpServletResponse r = call(put("/api/admin/sweep/settings"), cfg, bad);
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("maxFeeRatioPct");

    r = call(put("/api/admin/sweep/settings"), cfg, good);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode saved = body(r);
    assertThat(saved.path("version").asLong()).isEqualTo(v + 1);
    assertThat(saved.path("thresholds").path("ERC20").asString()).isEqualTo("150");
    assertThat(saved.path("updatedBy").isNull()).isFalse();
    Map<String, Object> log = jdbc.queryForMap("SELECT result, high_risk, reason, before_json, after_json FROM admin_audit_log WHERE action = 'sweep.settings_update' "
        + "AND result = 'SUCCESS' ORDER BY id DESC LIMIT 1");
    assertThat(log).containsEntry("reason", "调高门槛");
    assertThat(String.valueOf(log.get("before_json"))).contains("\"threshold.ERC20\":\"100\"").contains("\"maxFeeRatioPct\":\"3\"");
    assertThat(String.valueOf(log.get("after_json"))).contains("\"threshold.ERC20\":\"150\"").contains("\"operatorMin.ERC20\":\"0.02\"").doesNotContain("threshold.BSC");
    String same = good.replace("\"expectedVersion\":" + v, "\"expectedVersion\":" + (v + 1));
    r = call(put("/api/admin/sweep/settings"), cfg, same);
    assertThat(body(r).path("code").asString()).isEqualTo("NO_CHANGE");
    // 系统配置页不出现归集的键（只能在归集设置页改）
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM common_config WHERE config_key LIKE 'sweep.%'", Integer.class)).isEqualTo(9);
  }
}
