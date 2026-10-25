package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * forecast S9 新加的后台接口：仪表盘的彩池部分、市场和裁决台的分组数量、市场列表的奖池 / 人数 / 来源 / 多个状态、通知模板的繁体（权限点和字段）。
 */
class AdminS9Test extends AdminTestSupport {

  final ObjectMapper json = new ObjectMapper();

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  @Test
  void 仪表盘彩池部分_要dashboard_view_今日和近7天_待处理() throws Exception {
    Admin nobody = withRoles(role(Permission.CUSTOMER_VIEW));
    assertThat(call(get("/api/admin/dashboard/markets"), nobody.cookie(), null).getStatus()).isEqualTo(403);
    Admin viewer = withRoles(role(Permission.DASHBOARD_VIEW));
    MockHttpServletResponse r = call(get("/api/admin/dashboard/markets"), viewer.cookie(), null);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode b = body(r);
    assertThat(b.get("today").get("from").asString()).isEqualTo(b.get("today").get("to").asString());
    for (String k : new String[] {"betAmount", "bets", "bettors", "revenue", "compensation", "registrations", "deposit", "withdraw"}) {
      assertThat(b.get("week").has(k)).as(k).isTrue();
    }
    for (String k : new String[] {"review", "awaitingProposal", "awaitingConfirm", "autoFailed", "overdue", "disputed", "settleAbnormal", "poolReconOpen",
        "withdrawalsPending"}) {
      assertThat(b.get("pending").get(k).isNumber()).as(k).isTrue();
    }
  }

  @Test
  void 参数设置_没人改过的项不给修改时间() throws Exception {
    Admin a = withRoles(role(Permission.BETTING_PARAMS_EDIT));
    JsonNode items = body(call(get("/api/admin/params/betting"), a.cookie(), null));
    assertThat(items.size()).isGreaterThan(0);
    for (JsonNode it : items) {
      String key = it.get("key").asString();
      List<Long> by = jdbc.queryForList("SELECT updated_by FROM common_config WHERE config_key = ?", Long.class, key);
      boolean edited = !by.isEmpty() && by.getFirst() != null;
      assertThat(it.get("updatedAt").isNull()).as(key).isEqualTo(!edited);
    }
  }

  @Test
  void 分组数量和市场列表的新字段() throws Exception {
    Admin viewer = withRoles(role(Permission.MARKET_VIEW));
    MockHttpServletResponse r = call(get("/api/admin/markets/counts"), viewer.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(body(r).get("byStatus").has("listed")).isTrue();
    assertThat(body(r).get("review").isNumber()).isTrue();
    assertThat(call(get("/api/admin/resolve/counts"), viewer.cookie(), null).getStatus()).isEqualTo(403);
    Admin judge = withRoles(role(Permission.RESOLVE_PROPOSE));
    JsonNode rc = body(call(get("/api/admin/resolve/counts"), judge.cookie(), null));
    assertThat(rc.has("awaiting_proposal")).isTrue();
    assertThat(rc.has("disputed")).isTrue();
    // 列表：多个状态、新字段
    r = call(get("/api/admin/markets?status=listed,paused&pageSize=5"), viewer.cookie(), null);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    for (JsonNode m : body(r).get("items")) {
      assertThat(m.get("status").asString()).isIn("listed", "paused");
      assertThat(m.get("origin").asString()).isIn("manual", "autogen", "sports");
      assertThat(m.get("pool").asString()).matches("\\d+\\.\\d{2}");
      assertThat(m.get("bettors").isNumber()).isTrue();
    }
    assertThat(call(get("/api/admin/markets?status=listed,nope"), viewer.cookie(), null).getStatus()).isEqualTo(400);
  }

  @Test
  void 通知模板_繁体可以改_可以清空_标题正文要么都有要么都空() throws Exception {
    Admin editor = withRoles(role(Permission.NOTIFY_TEMPLATE_VIEW, Permission.NOTIFY_TEMPLATE_EDIT));
    JsonNode list = body(call(get("/api/admin/notify/templates"), editor.cookie(), null));
    JsonNode fee = null;
    for (JsonNode t : list) if (t.get("code").asString().equals("FEE_CHANGED")) fee = t;
    assertThat(fee).isNotNull();
    assertThat(fee.get("reqNo").asString()).isEqualTo("#22");
    assertThat(fee.get("audience").asString()).isEqualTo("user");
    String oldTitle = fee.get("titleTw").asString();
    String oldBody = fee.get("bodyTw").asString();
    try {
      MockHttpServletResponse r = call(put("/api/admin/notify/templates/FEE_CHANGED"), editor.cookie(), "{\"titleTw\":\"費率調整\",\"bodyTw\":\"\"}");
      assertThat(r.getStatus()).isEqualTo(400);
      assertThat(r.getContentAsString()).contains("bodyTw");
      r = call(put("/api/admin/notify/templates/FEE_CHANGED"), editor.cookie(), "{\"titleTw\":\"費率調整：{market}\",\"bodyTw\":\"從 {old} 改為 {new}\"}");
      assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
      assertThat(body(r).get("titleTw").asString()).isEqualTo("費率調整：{market}");
      r = call(put("/api/admin/notify/templates/FEE_CHANGED"), editor.cookie(), "{\"titleTw\":\"{coin}\",\"bodyTw\":\"x\"}");
      assertThat(r.getStatus()).isEqualTo(400);
      r = call(put("/api/admin/notify/templates/FEE_CHANGED"), editor.cookie(), "{\"titleTw\":\"\",\"bodyTw\":\"\"}");
      assertThat(body(r).get("titleTw").asString()).isEmpty();
      assertThat(audits("notify.template_update")).isNotEmpty();
      // 员工模板带收件人权限点
      for (JsonNode t : list) if (t.get("code").asString().equals("RESOLVE_PROPOSED")) assertThat(t.get("staffPerms").get(0).asString()).isEqualTo("resolve:confirm");
    } finally {
      jdbc.update("UPDATE notify_template SET title_tw = ?, body_tw = ? WHERE code = 'FEE_CHANGED'", oldTitle, oldBody);
    }
  }
}
