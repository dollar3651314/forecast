package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.forecast.app.resolve.source.ResolveProperties;
import com.forecast.app.resolve.source.SourceGateway;
import com.forecast.app.support.ScoreBook;
import com.forecast.app.support.StubHttp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台"从赛程生成"接口的权限点和审计（forecast S8；需求说明书 v1.3 第 2.2 节）：查看 {@code market:view}；同步、找候选、确认对应、生成草稿 {@code market:edit}（运营）；
 * 改规则文本模板 {@code market:autogen}（★ 原因必填）；生成的草稿上架仍要 {@code market:publish}。替身数据源，不连外网。
 */
class AdminSportsTest extends AdminTestSupport {

  static final StubHttp STUB = new StubHttp();
  static final ScoreBook BOOK = new ScoreBook();

  static {
    STUB.handle(BOOK.handler());
  }

  @Autowired ResolveProperties props;
  @Autowired SourceGateway gateway;
  @Autowired Clock clock;
  final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void stub() {
    BOOK.clear();
    props.getSources().get("football-data").setBaseUrl(STUB.url() + "/fd");
    props.getSources().get("football-data").setKey("test-token");
    props.getSources().get("thesportsdb").setBaseUrl(STUB.url() + "/tsdb");
    props.getSources().get("thesportsdb").setKey("test-key");
    gateway.resetBreakers();
  }

  @AfterEach
  void restore() {
    props.getSources().get("football-data").setBaseUrl("http://127.0.0.1:9");
    props.getSources().get("thesportsdb").setBaseUrl("http://127.0.0.1:9");
  }

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  @Test
  void 查看要market_view_同步对应生成要market_edit_都写审计_上架仍要market_publish() throws Exception {
    Instant k = Instant.now(clock).plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MINUTES);
    String pr = String.valueOf(System.nanoTime() % 1_000_000_000L);
    String br = String.valueOf(System.nanoTime() % 1_000_000_000L + 7);
    BOOK.add("FOOTBALL_DATA", pr, "FOOTBALL", "Arsenal FC", "Everton FC", k);
    BOOK.add("THESPORTSDB", br, "FOOTBALL", "Arsenal", "Everton", k);
    Admin viewer = withRoles(role(Permission.MARKET_VIEW));
    Admin editor = withRoles(role(Permission.MARKET_VIEW, Permission.MARKET_EDIT));
    assertThat(call(post("/api/admin/sports/fixtures/sync"), viewer.cookie(), "{\"sport\":\"FOOTBALL\"}").getStatus()).isEqualTo(403);
    assertThat(call(get("/api/admin/sports/fixtures"), null, null).getStatus()).isEqualTo(401);
    // 运营：立即同步
    MockHttpServletResponse r = call(post("/api/admin/sports/fixtures/sync"), editor.cookie(), "{\"sport\":\"FOOTBALL\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).get(0).path("outcome").asString()).isEqualTo("ok");
    assertThat(audits("sports.sync")).anySatisfy(a -> assertThat(a).startsWith("SUCCESS " + editor.username()));
    // 查看：列表、详情
    r = call(get("/api/admin/sports/fixtures?sport=FOOTBALL&mapping=unconfirmed&pageSize=100"), viewer.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    String fixtureNo = null;
    for (JsonNode f : body(r).path("items")) if (f.path("primaryRef").asString().equals(pr)) fixtureNo = f.path("fixtureNo").asString();
    assertThat(fixtureNo).isNotNull();
    assertThat(call(get("/api/admin/sports/fixtures?sport=TENNIS"), viewer.cookie(), null).getStatus()).isEqualTo(400);
    assertThat(call(get("/api/admin/sports/fixtures/1"), viewer.cookie(), null).getStatus()).isEqualTo(404);
    assertThat(call(post("/api/admin/sports/fixtures/" + fixtureNo + "/candidates"), viewer.cookie(), "{}").getStatus()).isEqualTo(403);
    // 找候选 → 确认对应 → 生成草稿
    r = call(post("/api/admin/sports/fixtures/" + fixtureNo + "/candidates"), editor.cookie(), "{}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).path("candidates").get(0).path("ref").asString()).isEqualTo(br);
    assertThat(body(r).path("candidates").get(0).path("suggested").asBoolean()).isTrue();
    assertThat(call(post("/api/admin/sports/fixtures/" + fixtureNo + "/mapping"), viewer.cookie(), "{\"ref\":\"" + br + "\"}").getStatus()).isEqualTo(403);
    r = call(post("/api/admin/sports/fixtures/" + fixtureNo + "/mapping"), editor.cookie(), "{\"ref\":\"" + br + "\",\"reason\":\"队名和时间都对\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).path("mapping").path("ref").asString()).isEqualTo(br);
    assertThat(audits("sports.mapping.confirm")).anySatisfy(a -> assertThat(a).isEqualTo("SUCCESS " + editor.username() + " 队名和时间都对"));
    r = call(post("/api/admin/sports/fixtures/" + fixtureNo + "/drafts"), editor.cookie(), "{\"plays\":[{\"play\":\"FOOTBALL_OU\",\"line\":\"3\"}]}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("plays[0].line").contains("notHalf");
    assertThat(call(post("/api/admin/sports/fixtures/" + fixtureNo + "/drafts"), viewer.cookie(), "{\"plays\":[{\"play\":\"FOOTBALL_OU\"}]}").getStatus())
        .isEqualTo(403);
    r = call(post("/api/admin/sports/fixtures/" + fixtureNo + "/drafts"), editor.cookie(), "{\"plays\":[{\"play\":\"FOOTBALL_OU\"},{\"play\":\"FOOTBALL_1X2\"}]}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode g = body(r);
    assertThat(g.path("created")).hasSize(2);
    assertThat(g.path("warnings")).isEmpty();
    String marketNo = g.path("created").get(0).path("marketNo").asString();
    assertThat(audits("sports.drafts.generate")).anySatisfy(a -> assertThat(a).startsWith("SUCCESS " + editor.username()));
    // 草稿：运营能提交上架，不能上架
    assertThat(call(post("/api/admin/markets/" + marketNo + "/submit"), editor.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(call(post("/api/admin/markets/" + marketNo + "/publish"), editor.cookie(), "{\"checked\":true}").getStatus()).isEqualTo(403);
    Admin publisher = withRoles(role(Permission.MARKET_VIEW, Permission.MARKET_PUBLISH));
    assertThat(call(post("/api/admin/markets/" + marketNo + "/publish"), publisher.cookie(), "{\"checked\":true}").getStatus()).isEqualTo(200);
    // 取消对应（开赛以前）
    r = call(delete("/api/admin/sports/fixtures/" + fixtureNo + "/mapping"), editor.cookie(), "{\"reason\":\"对错了\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).path("mapping").isNull()).isTrue();
    assertThat(audits("sports.mapping.clear")).anySatisfy(a -> assertThat(a).isEqualTo("SUCCESS " + editor.username() + " 对错了"));
  }

  @Test
  void 改规则文本模板要market_autogen_原因必填_写审计() throws Exception {
    Admin editor = withRoles(role(Permission.MARKET_VIEW, Permission.MARKET_EDIT));
    Admin autogen = withRoles(role(Permission.MARKET_VIEW, Permission.MARKET_AUTOGEN));
    MockHttpServletResponse r = call(get("/api/admin/sports/templates"), editor.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    JsonNode ou = null;
    for (JsonNode t : body(r)) if (t.path("kind").asString().equals("FOOTBALL_OU")) ou = t;
    assertThat(ou).isNotNull();
    ObjectMapper m = json;
    tools.jackson.databind.node.ObjectNode in = m.createObjectNode();
    in.put("name", ou.path("name").asString());
    in.set("title", ou.path("title"));
    in.set("rules", ou.path("rules"));
    in.put("sourcePrimary", ou.path("sourcePrimary").asString());
    in.put("sourceBackup", ou.path("sourceBackup").asString());
    in.set("options", ou.path("options"));
    assertThat(call(put("/api/admin/sports/templates/FOOTBALL_OU"), editor.cookie(), in.toString()).getStatus()).isEqualTo(403);
    r = call(put("/api/admin/sports/templates/FOOTBALL_OU"), autogen.cookie(), in.toString());
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("reason");
    in.put("reason", "统一措辞");
    r = call(put("/api/admin/sports/templates/FOOTBALL_OU"), autogen.cookie(), in.toString());
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).path("version").asInt()).isEqualTo(ou.path("version").asInt() + 1);
    assertThat(audits("sports.template.update")).anySatisfy(a -> assertThat(a).isEqualTo("SUCCESS " + autogen.username() + " 统一措辞"));
    assertThat(call(put("/api/admin/sports/templates/TENNIS"), autogen.cookie(), in.toString()).getStatus()).isEqualTo(404);
    assertThat(List.of(ou.path("variables").toString())).anySatisfy(v -> assertThat(v).contains("line"));
  }
}
