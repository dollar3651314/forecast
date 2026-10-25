package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台的加密自动生成、自动裁决接口（forecast S6，需求说明书第 2.2 节：{@code market:autogen} ★ 新建、修改、关闭模板，启用要 {@code market:publish}；
 * 第 4.2 节"模板启用前由有 market:publish 的人检查一次"）。每一步写审计，高危原因必填；没有权限点 403。
 */
class AdminAutogenTest extends AdminTestSupport {

  static final String DEFAULT_NO = "1000000000000000002";
  final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  @AfterEach
  void reset() {
    jdbc.update("UPDATE market_question SET autogen_id = NULL WHERE autogen_id IS NOT NULL");
    jdbc.update("DELETE FROM market_autogen WHERE public_no <> ?", Long.parseLong(DEFAULT_NO));
    jdbc.update("UPDATE market_autogen SET enabled = 0, mode = 'SCHEDULE', slots = '[\"13:00\"]', rolling_count = 3, generate_ahead_min = 60, user_cap = NULL, "
        + "checked_by = NULL, checked_at = NULL, checked_template_version = NULL WHERE public_no = ?", Long.parseLong(DEFAULT_NO));
  }

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  static final String ETH_15M = "{\"symbol\":\"ETHUSDT\",\"period\":\"15M\",\"mode\":\"rolling\",\"reason\":\"冷启动以后加 ETH 15 分钟\"}";

  @Test
  void 只有autogen权限_能建能改能关_不能启用() throws Exception {
    Admin op = withRoles(role(Permission.MARKET_VIEW, Permission.MARKET_AUTOGEN));
    // 原因必填（高危）
    MockHttpServletResponse r = call(post("/api/admin/market-autogens"), op.cookie(), "{\"symbol\":\"ETHUSDT\",\"period\":\"15M\",\"mode\":\"rolling\"}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("reason");
    r = call(post("/api/admin/market-autogens"), op.cookie(), ETH_15M);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    String no = body(r).path("autogenNo").asString();
    assertThat(body(r).path("enabled").asBoolean()).isFalse();
    assertThat(body(r).path("rollingCount").asInt()).isEqualTo(4);
    // 启用：403
    assertThat(call(post("/api/admin/market-autogens/" + no + "/enable"), op.cookie(), "{\"checked\":true}").getStatus()).isEqualTo(403);
    // 修改（关闭中）
    r = call(put("/api/admin/market-autogens/" + no), op.cookie(), "{\"symbol\":\"ETHUSDT\",\"period\":\"15M\",\"mode\":\"schedule\",\"slots\":[\"12:45\",\"13:00\"],"
        + "\"reason\":\"改成两场\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).path("slots").toString()).isEqualTo("[\"12:45\",\"13:00\"]");
    assertThat(audits("market.autogen.create")).anySatisfy(a -> assertThat(a).isEqualTo("SUCCESS " + op.username() + " 冷启动以后加 ETH 15 分钟"));
    assertThat(audits("market.autogen.update")).anySatisfy(a -> assertThat(a).isEqualTo("SUCCESS " + op.username() + " 改成两场"));
    // 同一币种同一周期：409
    r = call(post("/api/admin/market-autogens"), op.cookie(), ETH_15M);
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(r.getContentAsString()).contains("AUTOGEN_EXISTS");
  }

  @Test
  void 启用要publish权限和勾选_启用中不能改_关闭要原因() throws Exception {
    Admin publisher = withRoles(role(Permission.MARKET_VIEW, Permission.MARKET_PUBLISH));
    Admin op = withRoles(role(Permission.MARKET_VIEW, Permission.MARKET_AUTOGEN));
    assertThat(call(post("/api/admin/market-autogens"), publisher.cookie(), ETH_15M).getStatus()).isEqualTo(403);
    MockHttpServletResponse r = call(post("/api/admin/market-autogens/" + DEFAULT_NO + "/enable"), publisher.cookie(), "{}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("checked");
    r = call(post("/api/admin/market-autogens/" + DEFAULT_NO + "/enable"), publisher.cookie(), "{\"checked\":true}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).path("enabled").asBoolean()).isTrue();
    assertThat(body(r).path("checkedBy").asLong()).isEqualTo(publisher.id());
    assertThat(audits("market.autogen.enable")).anySatisfy(a -> assertThat(a).startsWith("SUCCESS " + publisher.username()));
    // 启用中改：409
    r = call(put("/api/admin/market-autogens/" + DEFAULT_NO), op.cookie(), "{\"symbol\":\"BTCUSDT\",\"period\":\"1H\",\"mode\":\"rolling\",\"reason\":\"改滚动\"}");
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(r.getContentAsString()).contains("AUTOGEN_ENABLED");
    // 关闭：原因必填
    assertThat(call(post("/api/admin/market-autogens/" + DEFAULT_NO + "/disable"), op.cookie(), "{}").getStatus()).isEqualTo(400);
    r = call(post("/api/admin/market-autogens/" + DEFAULT_NO + "/disable"), op.cookie(), "{\"reason\":\"暂停节目\"}");
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(body(r).path("enabled").asBoolean()).isFalse();
    assertThat(audits("market.autogen.disable")).anySatisfy(a -> assertThat(a).isEqualTo("SUCCESS " + op.username() + " 暂停节目"));
  }

  @Test
  void 查看要market_view_转人工列表要resolve_propose() throws Exception {
    Admin none = withRoles(role(Permission.BET_VIEW));
    for (String url : new String[] {"/api/admin/market-autogens", "/api/admin/market-autogens/" + DEFAULT_NO, "/api/admin/market-templates",
        "/api/admin/resolve/auto-failed"}) {
      assertThat(call(get(url), none.cookie(), null).getStatus()).as(url).isEqualTo(403);
    }
    Admin viewer = withRoles(role(Permission.MARKET_VIEW));
    MockHttpServletResponse r = call(get("/api/admin/market-autogens"), viewer.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(body(r).get(0).path("symbol").asString()).isEqualTo("BTCUSDT");
    r = call(get("/api/admin/market-autogens/" + DEFAULT_NO), viewer.cookie(), null);
    assertThat(body(r).path("sample").path("title").path("zhCN").asString()).startsWith("BTC 涨还是跌：");
    assertThat(body(r).path("missing").size()).isZero();
    r = call(get("/api/admin/market-templates"), viewer.cookie(), null);
    assertThat(body(r).get(0).path("kind").asString()).isEqualTo("CRYPTO_UPDOWN");
    assertThat(call(get("/api/admin/resolve/auto-failed"), viewer.cookie(), null).getStatus()).isEqualTo(403);
    Admin resolver = withRoles(role(Permission.RESOLVE_PROPOSE));
    r = call(get("/api/admin/resolve/auto-failed"), resolver.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(body(r).has("items")).isTrue();
    // 市场不存在 404
    assertThat(call(get("/api/admin/markets/123/resolution"), viewer.cookie(), null).getStatus()).isEqualTo(404);
  }

  @Test
  void 改规则文本模板要autogen权限和原因_一个超管能走完() throws Exception {
    Admin su = superAdmin();
    MockHttpServletResponse r = call(get("/api/admin/market-templates"), su.cookie(), null);
    JsonNode t = body(r).get(0);
    String no = t.path("templateNo").asString();
    String payload = "{\"name\":" + t.path("name") + ",\"title\":" + t.path("title") + ",\"rules\":" + t.path("rules") + ",\"sourcePrimary\":" + t.path("sourcePrimary")
        + ",\"sourceBackup\":" + t.path("sourceBackup") + ",\"options\":" + t.path("options");
    assertThat(call(put("/api/admin/market-templates/" + no), su.cookie(), payload + "}").getStatus()).isEqualTo(400);
    Admin publisher = withRoles(role(Permission.MARKET_VIEW, Permission.MARKET_PUBLISH));
    assertThat(call(put("/api/admin/market-templates/" + no), publisher.cookie(), payload + ",\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    r = call(put("/api/admin/market-templates/" + no), su.cookie(), payload + ",\"reason\":\"原样保存\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).path("version").asInt()).isEqualTo(t.path("version").asInt() + 1);
    // 超管自己启用、关闭
    assertThat(call(post("/api/admin/market-autogens/" + DEFAULT_NO + "/enable"), su.cookie(), "{\"checked\":true}").getStatus()).isEqualTo(200);
    r = call(put("/api/admin/market-templates/" + no), su.cookie(), payload + ",\"reason\":\"启用中改\"}");
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(r.getContentAsString()).contains("TEMPLATE_IN_USE");
    assertThat(call(post("/api/admin/market-autogens/" + DEFAULT_NO + "/disable"), su.cookie(), "{\"reason\":\"测试结束\"}").getStatus()).isEqualTo(200);
    assertThat(audits("market.template.update")).anySatisfy(a -> assertThat(a).isEqualTo("SUCCESS " + su.username() + " 原样保存"));
  }
}
