package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台"内容中心"的权限点、高危确认和审计（forecast S15；需求第 2.2 节 content:view / content:edit / content:publish★，AC-109 的 403 部分，AC-118）。
 * Gemini 在后端测试里不可达（地址指向本机不监听的端口），这里只测权限和不调 Gemini 的操作。
 */
class AdminContentTest extends AdminTestSupport {

  final ObjectMapper json = new ObjectMapper();

  @AfterEach
  void resume() {
    jdbc.update("UPDATE content_setting SET value = '0' WHERE setting_key = 'publish.stopped'");
    jdbc.update("UPDATE content_setting SET value = '5' WHERE setting_key = 'pages.dailyNew'");
  }

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  @Test
  void 只有查看权限的_能看不能改不能发布() throws Exception {
    Admin viewer = withRoles(role(Permission.CONTENT_VIEW));
    assertThat(call(get("/api/admin/content/status"), viewer.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(call(get("/api/admin/content/drafts"), viewer.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(call(get("/api/admin/content/candidates"), viewer.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(call(post("/api/admin/content/drafts"), viewer.cookie(), "{\"kind\":\"ARTICLE\",\"topic\":\"x\"}").getStatus()).isEqualTo(403);
    assertThat(call(post("/api/admin/content/drafts/1/publish"), viewer.cookie(), "{\"reason\":\"ok\",\"checked\":true}").getStatus()).isEqualTo(403);
    assertThat(call(post("/api/admin/content/stop"), viewer.cookie(), "{\"stopped\":true,\"reason\":\"x\",\"checked\":true}").getStatus()).isEqualTo(403);
    assertThat(call(put("/api/admin/content/settings"), viewer.cookie(), "{\"values\":{\"pages.dailyNew\":\"6\"},\"reason\":\"x\",\"checked\":true}").getStatus())
        .isEqualTo(403);
    // 有编辑权限、没有发布权限：能起草，不能发布（AC-109）
    Admin editor = withRoles(role(Permission.CONTENT_VIEW, Permission.CONTENT_EDIT));
    assertThat(call(post("/api/admin/content/drafts/1/publish"), editor.cookie(), "{\"reason\":\"ok\",\"checked\":true}").getStatus()).isEqualTo(403);
    assertThat(call(post("/api/admin/content/econ"), editor.cookie(), "{\"kind\":\"FOMC\"}").getStatus()).isEqualTo(400);
  }

  @Test
  void 超管_发布类操作要原因和勾选_写审计_紧急停止和设置() throws Exception {
    Admin su = superAdmin();
    MockHttpServletResponse noReason = call(post("/api/admin/content/stop"), su.cookie(), "{\"stopped\":true}");
    assertThat(noReason.getStatus()).isEqualTo(400);
    assertThat(body(noReason).path("fields").toString()).contains("reason").contains("checked");
    MockHttpServletResponse stop = call(post("/api/admin/content/stop"), su.cookie(), "{\"stopped\":true,\"reason\":\"测试紧急停止\",\"checked\":true}");
    assertThat(stop.getStatus()).isEqualTo(200);
    assertThat(body(stop).path("stopped").asBoolean()).isTrue();
    assertThat(audits("content.stop")).anyMatch(a -> a.startsWith("SUCCESS " + su.username() + " 测试紧急停止"));
    MockHttpServletResponse set = call(put("/api/admin/content/settings"), su.cookie(), "{\"values\":{\"pages.dailyNew\":\"7\"},\"reason\":\"多开两页\",\"checked\":true}");
    assertThat(set.getStatus()).isEqualTo(200);
    assertThat(body(set).path("settings").path("pages.dailyNew").asString()).isEqualTo("7");
    MockHttpServletResponse bad = call(put("/api/admin/content/settings"), su.cookie(), "{\"values\":{\"pages.dailyNew\":\"999\",\"nope\":\"1\"},\"reason\":\"x\",\"checked\":true}");
    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat(body(bad).path("fields").toString()).contains("pages.dailyNew").contains("nope");
    // 状态：key 只显示有没有配置
    JsonNode st = body(call(get("/api/admin/content/status"), su.cookie(), null));
    assertThat(st.path("geminiConfigured").isBoolean()).isTrue();
    assertThat(st.toString()).doesNotContain("ci_only_test_gemini");
    // 内置词不能删
    long builtIn = jdbc.queryForObject("SELECT id FROM content_word WHERE built_in = 1 LIMIT 1", Long.class);
    assertThat(call(post("/api/admin/content/words/" + builtIn + "/delete"), su.cookie(), "{\"reason\":\"x\",\"checked\":true}").getStatus()).isEqualTo(409);
    MockHttpServletResponse add = call(post("/api/admin/content/words"), su.cookie(), "{\"kind\":\"BANNED\",\"word\":\"包赢\",\"reason\":\"补词\",\"checked\":true}");
    assertThat(add.getStatus()).isEqualTo(200);
    String id = body(add).path("id").asString();
    assertThat(call(post("/api/admin/content/words/" + id + "/delete"), su.cookie(), "{\"reason\":\"删掉\",\"checked\":true}").getStatus()).isEqualTo(200);
  }

  @Test
  void 菜单_内容中心在超管的菜单里() throws Exception {
    Admin su = superAdmin();
    JsonNode me = body(call(get("/api/admin/me"), su.cookie(), null));
    assertThat(me.toString()).contains("\"content\"").contains("/content/drafts").contains("/content/settings");
  }
}
