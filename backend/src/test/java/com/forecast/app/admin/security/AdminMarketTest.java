package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.support.MarketTestDoubles;
import com.forecast.app.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台的市场管理和参数设置（forecast S3），真实 MySQL：AC-11～AC-14、AC-37 前半、需求第 2.2 节的权限点、第 3.8 节的费率、第 6.2 节的参数设置。
 * 截止任务、状态机穷举、截止屏障在 market 模块自己的测试里（MarketClockTest、MarketTransitionDbTest）。
 */
@Import({FixedClockConfig.class, MarketTestDoubles.class})
class AdminMarketTest extends AdminTestSupport {

  static final Instant NOW = FixedClockConfig.NOW;

  @Autowired Clock clock;
  @Autowired MarketTestDoubles.Pool pool;
  @Autowired MarketTestDoubles.Events events;
  @Autowired com.forecast.app.market.core.MarketService markets;
  final ObjectMapper json = new ObjectMapper();

  @AfterEach
  void resetClock() {
    ((MutableClock) clock).reset();
  }

  /** 把时钟往后拨；拨过后台的 30 分钟无操作退出时，重新登录拿新的 Cookie */
  Admin later(Admin a, Duration d) throws Exception {
    ((MutableClock) clock).advance(d);
    return login(a.id());
  }

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  static I18nText t(String s) {
    return new I18nText(s + " en", s + " 简", s + " 繁");
  }

  static MarketAdminApi.DraftInput draft(String category, int options, Instant open, Instant close) {
    List<MarketAdminApi.OptionInput> opts = new ArrayList<>();
    for (int i = 1; i <= options; i++) opts.add(new MarketAdminApi.OptionInput(t("选项" + i), t("条件" + i)));
    return new MarketAdminApi.DraftInput(category, t("题目"), t("规则：90 分钟；延期 48 小时作废；以完赛 1 小时后的比分为准"), "AUTO", "premierleague.com",
        "API-Football", Map.of("fixtureRefs", Map.of("apiFootball", "123")), open.toString(), close.toString(), close.plus(Duration.ofMinutes(5)).toString(),
        close.plus(Duration.ofHours(2)).toString(), null, null, null, opts);
  }

  static MarketAdminApi.DraftInput draft() {
    return draft("FOOTBALL", 3, NOW, NOW.plus(Duration.ofDays(1)));
  }

  String toJson(Object o) {
    return json.writeValueAsString(o);
  }

  /** 建草稿，返回对外编号 */
  String create(Admin a, MarketAdminApi.DraftInput d) throws Exception {
    MockHttpServletResponse r = call(post("/api/admin/markets"), a.cookie(), toJson(d));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return body(r).get("publicNo").asString();
  }

  /** 建草稿并上架 */
  String listed(Admin a) throws Exception {
    String no = create(a, draft());
    MockHttpServletResponse r = call(post("/api/admin/markets/" + no + "/publish"), a.cookie(), "{\"checked\":true}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return no;
  }

  long marketId(String no) {
    return jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, Long.parseLong(no));
  }

  List<String> fields(MockHttpServletResponse r) throws Exception {
    List<String> out = new ArrayList<>();
    for (JsonNode f : body(r).get("fields")) out.add(f.get("field").asString() + ":" + f.get("code").asString());
    return out;
  }

  // ---------------------------------------------------------------- AC-11

  @Test
  void AC11_保存草稿_选项少于2或多于10_时间先后不对_缺时间_一次列出全部错误() throws Exception {
    Admin s = superAdmin();
    MarketAdminApi.DraftInput one = draft("FOOTBALL", 1, NOW, NOW.plus(Duration.ofDays(1)));
    MockHttpServletResponse r = call(post("/api/admin/markets"), s.cookie(), toJson(one));
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(fields(r)).containsExactly("options:min2");
    assertThat(fields(call(post("/api/admin/markets"), s.cookie(), toJson(draft("FOOTBALL", 11, NOW, NOW.plus(Duration.ofDays(1))))))).containsExactly("options:max10");
    // 开放晚于截止、截止晚于事件开始、事件开始不早于结束、品类不认识：一次列出
    MarketAdminApi.DraftInput bad = new MarketAdminApi.DraftInput("CHESS", t("x"), t("x"), "AUTO", "a", "b", null, NOW.plus(Duration.ofDays(2)).toString(),
        NOW.plus(Duration.ofDays(1)).toString(), NOW.toString(), NOW.toString(), null, null, null, draft().options());
    assertThat(fields(call(post("/api/admin/markets"), s.cookie(), toJson(bad))))
        .containsExactlyInAnyOrder("category:invalid", "closeAt:notAfterOpen", "eventStartAt:beforeClose", "eventEndAt:notAfterStart");
    MarketAdminApi.DraftInput noTime = new MarketAdminApi.DraftInput("NBA", null, null, null, null, null, null, null, "not-a-time", null, null, "0", null, null,
        draft().options());
    assertThat(fields(call(post("/api/admin/markets"), s.cookie(), toJson(noTime))))
        .containsExactlyInAnyOrder("openAt:required", "closeAt:invalid", "eventStartAt:required", "eventEndAt:required", "userCap:invalid");
    assertThat(fields(call(post("/api/admin/markets"), s.cookie(), "{}"))).contains("category:required", "options:min2");
  }

  @Test
  void AC11_必填项没填齐_能存草稿_不能提交上架也不能上架_一次列出全部缺的项() throws Exception {
    Admin s = superAdmin();
    MarketAdminApi.DraftInput d = draft();
    MarketAdminApi.DraftInput partial = new MarketAdminApi.DraftInput("MACRO", new I18nText("Fed hike?", "", ""), null, "PLATFORM", "federalreserve.gov", "",
        null, d.openAt(), d.closeAt(), d.eventStartAt(), d.eventEndAt(), null, null, null,
        List.of(new MarketAdminApi.OptionInput(t("是"), t("加息")), new MarketAdminApi.OptionInput(new I18nText("No", "否", ""), null)));
    String no = create(s, partial);
    JsonNode detail = body(call(get("/api/admin/markets/" + no), s.cookie(), null));
    assertThat(detail.get("status").asString()).isEqualTo("draft");
    assertThat(detail.get("disputeWindowMinutes").asInt()).as("平台裁决带出时事的默认争议窗口 24 小时（RQ-9）").isEqualTo(1440);
    assertThat(detail.get("userCap").asString()).as("单客户上限按品类默认").isEqualTo("10000");
    List<String> expected = List.of("title.zhCN:required", "title.zhTW:required", "rules.en:required", "rules.zhCN:required", "rules.zhTW:required",
        "sourceBackup:required", "options[1].label.zhTW:required", "options[1].winCondition.en:required", "options[1].winCondition.zhCN:required",
        "options[1].winCondition.zhTW:required");
    List<String> missing = new ArrayList<>();
    for (JsonNode f : detail.get("missing")) missing.add(f.get("field").asString() + ":" + f.get("code").asString());
    assertThat(missing).containsExactlyElementsOf(expected);
    MockHttpServletResponse r = call(post("/api/admin/markets/" + no + "/submit"), s.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(fields(r)).containsExactlyElementsOf(expected);
    r = call(post("/api/admin/markets/" + no + "/publish"), s.cookie(), "{\"checked\":false}");
    assertThat(fields(r)).containsAll(expected).contains("checked:required");
    assertThat(jdbc.queryForObject("SELECT status FROM market_question WHERE public_no = ?", String.class, Long.parseLong(no))).isEqualTo("DRAFT");
  }

  @Test
  void 上架_要勾选检查清单_截止时间已过不能上架_提交上架只加标记_退回要写理由() throws Exception {
    Admin s = superAdmin();
    String no = create(s, draft());
    JsonNode sub = body(call(post("/api/admin/markets/" + no + "/submit"), s.cookie(), null));
    assertThat(sub.get("status").asString()).as("提交上架不改状态（RQ-17）").isEqualTo("draft");
    assertThat(sub.get("submitted").asBoolean()).isTrue();
    assertThat(body(call(get("/api/admin/markets?submitted=true&keyword=" + no), s.cookie(), null)).get("total").asLong()).isEqualTo(1);
    assertThat(fields(call(post("/api/admin/markets/" + no + "/return"), s.cookie(), "{}"))).containsExactly("reason:required");
    assertThat(body(call(post("/api/admin/markets/" + no + "/return"), s.cookie(), "{\"reason\":\"来源写得不清楚\"}")).get("submitted").asBoolean()).isFalse();
    assertThat(fields(call(post("/api/admin/markets/" + no + "/publish"), s.cookie(), "{}"))).containsExactly("checked:required");
    Admin s2 = later(s, Duration.ofDays(1));
    assertThat(fields(call(post("/api/admin/markets/" + no + "/publish"), s2.cookie(), "{\"checked\":true}"))).containsExactly("closeAt:passed");
    ((MutableClock) clock).reset();
    JsonNode pub = body(call(post("/api/admin/markets/" + no + "/publish"), s.cookie(), "{\"checked\":true}"));
    assertThat(pub.get("status").asString()).isEqualTo("listed");
    assertThat(pub.get("listedBy").asLong()).isEqualTo(s.id());
    assertThat(pub.get("statusLog").get(0).get("from").asString() + ">" + pub.get("statusLog").get(0).get("to").asString()).isEqualTo("draft>listed");
    assertThat(call(post("/api/admin/markets/" + no + "/publish"), s.cookie(), "{\"checked\":true}").getContentAsString()).as("已上架不能再上架")
        .contains("ILLEGAL_TRANSITION");
  }

  // ---------------------------------------------------------------- AC-12

  @Test
  void AC12_上架后题目选项时间来源都不能改_不能删_补充说明可以发并按时间显示() throws Exception {
    Admin s = superAdmin();
    String no = listed(s);
    MarketAdminApi.DraftInput changed = draft("FOOTBALL", 2, NOW, NOW.plus(Duration.ofHours(1)));
    MockHttpServletResponse r = call(put("/api/admin/markets/" + no), s.cookie(), toJson(changed));
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(r.getContentAsString()).contains("MARKET_NOT_EDITABLE");
    assertThat(call(delete("/api/admin/markets/" + no), s.cookie(), null).getStatus()).isEqualTo(409);
    JsonNode d = body(call(get("/api/admin/markets/" + no), s.cookie(), null));
    assertThat(d.get("options").size()).isEqualTo(3);
    assertThat(d.get("closeAt").asString()).isEqualTo(NOW.plus(Duration.ofDays(1)).toString());
    // 补充说明：三种语言都要有、原因必填；按时间顺序
    assertThat(fields(call(post("/api/admin/markets/" + no + "/clarifications"), s.cookie(), "{\"text\":{\"en\":\"x\"},\"reason\":\"澄清\"}")))
        .containsExactly("text.zhCN:required", "text.zhTW:required");
    assertThat(fields(call(post("/api/admin/markets/" + no + "/clarifications"), s.cookie(), "{\"text\":" + toJson(t("一")) + "}"))).containsExactly("reason:required");
    call(post("/api/admin/markets/" + no + "/clarifications"), s.cookie(), "{\"text\":" + toJson(t("第一条")) + ",\"reason\":\"澄清\"}");
    ((MutableClock) clock).advance(Duration.ofMinutes(5));
    JsonNode after = body(call(post("/api/admin/markets/" + no + "/clarifications"), s.cookie(), "{\"text\":" + toJson(t("第二条")) + ",\"reason\":\"澄清\"}"));
    assertThat(after.get("clarifications").get(0).get("text").get("zhCN").asString()).isEqualTo("第一条 简");
    assertThat(after.get("clarifications").get(1).get("text").get("zhCN").asString()).isEqualTo("第二条 简");
    assertThat(after.get("clarifications").get(1).get("time").asString()).isEqualTo(NOW.plus(Duration.ofMinutes(5)).toString());
    // 截止以后不能再发
    Admin s2 = later(s, Duration.ofDays(1));
    assertThat(call(post("/api/admin/markets/" + no + "/clarifications"), s2.cookie(), "{\"text\":" + toJson(t("三")) + ",\"reason\":\"x\"}").getContentAsString())
        .contains("MARKET_CLOSED");
    assertThat(jdbc.queryForList("SELECT result FROM admin_audit_log WHERE action = 'market.clarify' AND target = ? ORDER BY id", String.class, no))
        .as("缺原因的在记审计之前就拒绝了；字段不全、截止后被拒都记失败").containsExactly("FAILURE", "SUCCESS", "SUCCESS", "FAILURE");
  }

  @Test
  void 草稿可以改可以删_删了以后列表和详情都没有() throws Exception {
    Admin s = superAdmin();
    String no = create(s, draft());
    JsonNode d = body(call(put("/api/admin/markets/" + no), s.cookie(), toJson(draft("NBA", 2, NOW, NOW.plus(Duration.ofHours(3))))));
    assertThat(d.get("category").asString()).isEqualTo("NBA");
    assertThat(d.get("options").size()).isEqualTo(2);
    assertThat(d.get("closeAt").asString()).isEqualTo(NOW.plus(Duration.ofHours(3)).toString());
    assertThat(call(delete("/api/admin/markets/" + no), s.cookie(), null).getStatus()).isEqualTo(204);
    assertThat(call(get("/api/admin/markets/" + no), s.cookie(), null).getStatus()).isEqualTo(404);
    assertThat(body(call(get("/api/admin/markets?keyword=" + no), s.cookie(), null)).get("total").asLong()).isZero();
    assertThat(audits("market.delete")).anyMatch(l -> l.startsWith("SUCCESS " + s.username()));
  }

  // ---------------------------------------------------------------- AC-13

  @Test
  void AC13_只有一个超管_自己建草稿自己上架_只有编辑权限的人上架返回403_每一步都写审计() throws Exception {
    Admin s = superAdmin();
    String no = create(s, draft());
    Admin editor = withRoles(role(Permission.MARKET_EDIT, Permission.MARKET_VIEW));
    String mine = create(editor, draft());
    assertThat(call(post("/api/admin/markets/" + mine + "/submit"), editor.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(call(post("/api/admin/markets/" + mine + "/publish"), editor.cookie(), "{\"checked\":true}").getStatus()).isEqualTo(403);
    assertThat(call(put("/api/admin/markets/" + mine + "/fee"), editor.cookie(), "{\"feeRate\":\"8\",\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    assertThat(call(post("/api/admin/markets/" + mine + "/pause"), editor.cookie(), "{\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    assertThat(call(post("/api/admin/markets/" + mine + "/publish"), s.cookie(), "{\"checked\":true}").getStatus()).isEqualTo(200);
    assertThat(call(post("/api/admin/markets/" + no + "/publish"), s.cookie(), "{\"checked\":true}").getStatus()).as("自己建的自己上架").isEqualTo(200);
    Admin viewer = withRoles(role(Permission.MARKET_VIEW));
    assertThat(call(post("/api/admin/markets"), viewer.cookie(), toJson(draft())).getStatus()).isEqualTo(403);
    assertThat(call(get("/api/admin/markets/" + no), viewer.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(audits("market.create")).anyMatch(l -> l.startsWith("SUCCESS " + s.username())).anyMatch(l -> l.startsWith("SUCCESS " + editor.username()));
    assertThat(audits("market.submit")).anyMatch(l -> l.startsWith("SUCCESS " + editor.username()));
    assertThat(audits("market.publish")).anyMatch(l -> l.startsWith("SUCCESS " + s.username()));
  }

  // ---------------------------------------------------------------- AC-14、取消、提前截止

  @Test
  void AC14_暂停恢复要原因_暂停中到了截止时间进入截止下注_截止后不能恢复() throws Exception {
    Admin s = superAdmin();
    String no = listed(s);
    assertThat(fields(call(post("/api/admin/markets/" + no + "/pause"), s.cookie(), "{}"))).containsExactly("reason:required");
    assertThat(body(call(post("/api/admin/markets/" + no + "/pause"), s.cookie(), "{\"reason\":\"突发伤停\"}")).get("status").asString()).isEqualTo("paused");
    assertThat(call(post("/api/admin/markets/" + no + "/pause"), s.cookie(), "{\"reason\":\"x\"}").getContentAsString()).contains("ILLEGAL_TRANSITION");
    assertThat(body(call(post("/api/admin/markets/" + no + "/resume"), s.cookie(), "{\"reason\":\"恢复\"}")).get("status").asString()).isEqualTo("listed");
    call(post("/api/admin/markets/" + no + "/pause"), s.cookie(), "{\"reason\":\"再停\"}");
    Admin s2 = later(s, Duration.ofDays(1));
    assertThat(call(post("/api/admin/markets/" + no + "/resume"), s2.cookie(), "{\"reason\":\"x\"}").getContentAsString()).contains("MARKET_CLOSED");
    markets.closeDue();
    JsonNode d = body(call(get("/api/admin/markets/" + no), s2.cookie(), null));
    assertThat(d.get("status").asString()).isEqualTo("closed");
    JsonNode log = d.get("statusLog");
    assertThat(log.get(log.size() - 1).get("from").asString() + ">" + log.get(log.size() - 1).get("to").asString() + ":" + log.get(log.size() - 1).get("actor").asString())
        .isEqualTo("paused>closed:SYSTEM");
    assertThat(log.get(1).get("reason").asString()).isEqualTo("突发伤停");
    assertThat(audits("market.pause")).anyMatch(l -> l.equals("SUCCESS " + s.username() + " 突发伤停"));
  }

  @Test
  void 截止前取消_原因必填_通知退款_截止后不能取消() throws Exception {
    Admin s = superAdmin();
    String no = listed(s);
    assertThat(fields(call(post("/api/admin/markets/" + no + "/cancel"), s.cookie(), "{}"))).containsExactly("reason:required");
    JsonNode d = body(call(post("/api/admin/markets/" + no + "/cancel"), s.cookie(), "{\"reason\":\"比赛取消\"}"));
    assertThat(d.get("status").asString()).isEqualTo("cancelled");
    assertThat(d.get("cancelReason").asString()).isEqualTo("比赛取消");
    assertThat(events.refundsOf(marketId(no))).singleElement().extracting(MarketTestDoubles.Refund::to).hasToString("CANCELLED");
    String late = listed(s);
    Admin s2 = later(s, Duration.ofDays(1));
    assertThat(call(post("/api/admin/markets/" + late + "/cancel"), s2.cookie(), "{\"reason\":\"x\"}").getContentAsString()).contains("MARKET_CLOSED");
    Admin noCancel = withRoles(role(Permission.MARKET_PAUSE, Permission.MARKET_VIEW));
    assertThat(call(post("/api/admin/markets/" + late + "/cancel"), noCancel.cookie(), "{\"reason\":\"x\"}").getStatus()).isEqualTo(403);
  }

  @Test
  void 提前截止_只能提前不能推后_要晚于开放时间_通知S5退回之后的下注() throws Exception {
    Admin s = superAdmin();
    String no = listed(s);
    Instant close = NOW.plus(Duration.ofDays(1));
    assertThat(fields(call(post("/api/admin/markets/" + no + "/advance-close"), s.cookie(), "{\"closeAt\":\"" + close.plusSeconds(60) + "\",\"reason\":\"x\"}")))
        .containsExactly("closeAt:notEarlier");
    assertThat(fields(call(post("/api/admin/markets/" + no + "/advance-close"), s.cookie(), "{\"closeAt\":\"" + NOW + "\",\"reason\":\"x\"}")))
        .containsExactly("closeAt:notAfterOpen");
    assertThat(fields(call(post("/api/admin/markets/" + no + "/advance-close"), s.cookie(), "{\"closeAt\":\"" + close.minusSeconds(3600) + "\"}")))
        .containsExactly("reason:required");
    JsonNode d = body(call(post("/api/admin/markets/" + no + "/advance-close"), s.cookie(), "{\"closeAt\":\"" + close.minusSeconds(3600) + "\",\"reason\":\"改到提前开赛\"}"));
    assertThat(d.get("closeAt").asString()).isEqualTo(close.minusSeconds(3600).toString());
    assertThat(events.advanced).anyMatch(a -> a.marketId() == marketId(no) && a.oldCloseAt().equals(close) && a.newCloseAt().equals(close.minusSeconds(3600)));
    assertThat(jdbc.queryForObject("SELECT CONCAT(before_json, after_json) FROM admin_audit_log WHERE action = 'market.advance_close' AND target = ? ORDER BY id DESC LIMIT 1",
        String.class, no)).isEqualTo("{\"closeAt\":\"" + close + "\"}{\"closeAt\":\"" + close.minusSeconds(3600) + "\"}");
  }

  // ---------------------------------------------------------------- AC-37 前半（费率）

  @Test
  void AC37_新建按品类默认5_改品类默认值只影响之后新建的市场() throws Exception {
    Admin s = superAdmin();
    String before = create(s, draft());
    assertThat(body(call(get("/api/admin/markets/" + before), s.cookie(), null)).get("feeRate").asString()).isEqualTo("5");
    try {
      MockHttpServletResponse r = call(put("/api/admin/params/fee/market.fee.FOOTBALL"), s.cookie(), "{\"value\":\"7.5\",\"reason\":\"调整足球费率\"}");
      assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
      String after = create(s, draft());
      assertThat(body(call(get("/api/admin/markets/" + after), s.cookie(), null)).get("feeRate").asString()).isEqualTo("7.5");
      assertThat(body(call(get("/api/admin/markets/" + before), s.cookie(), null)).get("feeRate").asString()).as("已建的不变").isEqualTo("5");
      String nba = create(s, draft("NBA", 2, NOW, NOW.plus(Duration.ofDays(1))));
      assertThat(body(call(get("/api/admin/markets/" + nba), s.cookie(), null)).get("feeRate").asString()).isEqualTo("5");
      // 草稿换品类、没人工改过费率：费率跟着换
      JsonNode d = body(call(put("/api/admin/markets/" + nba), s.cookie(), toJson(draft())));
      assertThat(d.get("feeRate").asString()).isEqualTo("7.5");
    } finally {
      jdbc.update("UPDATE common_config SET value = '5' WHERE config_key = ?", ConfigKey.FEE_FOOTBALL.key);
      config.refresh();
    }
  }

  @Test
  void AC37_上架有下注后改费率5到8_要原因_写审计前后_记录公开_有下注的客户收到22_没有权限403() throws Exception {
    Admin s = superAdmin();
    String no = listed(s);
    long id = marketId(no);
    long alice = customer();
    long bob = customer();
    pool.bettors.put(id, List.of(alice, bob));
    assertThat(fields(call(put("/api/admin/markets/" + no + "/fee"), s.cookie(), "{\"feeRate\":\"8\"}"))).containsExactly("reason:required");
    for (String bad : List.of("20.01", "-1", "5.255", "abc", "")) {
      assertThat(fields(call(put("/api/admin/markets/" + no + "/fee"), s.cookie(), "{\"feeRate\":\"" + bad + "\",\"reason\":\"x\"}"))).as(bad).hasSize(1);
    }
    assertThat(fields(call(put("/api/admin/markets/" + no + "/fee"), s.cookie(), "{\"feeRate\":\"5.00\",\"reason\":\"x\"}"))).containsExactly("feeRate:unchanged");
    Admin noFee = withRoles(role(Permission.MARKET_VIEW, Permission.MARKET_EDIT, Permission.MARKET_PUBLISH, Permission.BETTING_PARAMS_EDIT));
    assertThat(call(put("/api/admin/markets/" + no + "/fee"), noFee.cookie(), "{\"feeRate\":\"8\",\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    ((MutableClock) clock).advance(Duration.ofMinutes(29));
    JsonNode r = body(call(put("/api/admin/markets/" + no + "/fee"), s.cookie(), "{\"feeRate\":\"8\",\"reason\":\"推广期结束\"}"));
    assertThat(r.get("oldRate").asString()).isEqualTo("5");
    assertThat(r.get("newRate").asString()).isEqualTo("8");
    assertThat(r.get("notified").asInt()).isEqualTo(2);
    assertThat(r.get("market").get("feeRate").asString()).isEqualTo("8");
    JsonNode change = r.get("market").get("feeChanges").get(0);
    assertThat(change.get("oldRate").asString() + ">" + change.get("newRate").asString() + "@" + change.get("time").asString())
        .isEqualTo("5>8@" + NOW.plus(Duration.ofMinutes(29)));
    assertThat(change.get("reason").asString()).isEqualTo("推广期结束");
    assertThat(change.get("notified").asBoolean()).isTrue();
    assertThat(jdbc.queryForObject("SELECT fee_rate FROM market_question WHERE id = ?", java.math.BigDecimal.class, id)).isEqualByComparingTo("0.08");
    assertThat(jdbc.queryForObject("SELECT CONCAT(high_risk, ' ', reason, ' ', before_json, after_json) FROM admin_audit_log WHERE action = 'market.fee' AND target = ? AND reason = '推广期结束'",
        String.class, no)).isEqualTo("1 推广期结束 {\"feeRate\":\"5\"}{\"feeRate\":\"8\",\"notified\":2}");
    for (long u : List.of(alice, bob)) {
      String params = jdbc.queryForObject("SELECT params FROM notify_message WHERE user_id = ? AND template_code = 'FEE_CHANGED'", String.class, u);
      assertThat(params).contains("\"old\":\"5%\"").contains("\"new\":\"8%\"").contains("\"at\":\"2026-10-14 10:29 UTC\"").contains("\"market\":\"题目 简\"")
          .contains("\"marketEn\":\"题目 en\"");
    }
    // 5.25% 这种两位小数可以
    assertThat(body(call(put("/api/admin/markets/" + no + "/fee"), s.cookie(), "{\"feeRate\":\"5.25\",\"reason\":\"再调\"}")).get("newRate").asString()).isEqualTo("5.25");
  }

  @Test
  void AC37_草稿阶段改费率不通知_截止后待裁决仍能改_结算开始后和终点状态不能改() throws Exception {
    Admin s = superAdmin();
    String draftNo = create(s, draft());
    pool.bettors.put(marketId(draftNo), List.of(customer()));
    JsonNode r = body(call(put("/api/admin/markets/" + draftNo + "/fee"), s.cookie(), "{\"feeRate\":\"0\",\"reason\":\"上线推广\"}"));
    assertThat(r.get("notified").asInt()).as("草稿阶段不发 #22").isZero();
    assertThat(r.get("market").get("feeChanges").get(0).get("notified").asBoolean()).isFalse();
    assertThat(r.get("newRate").asString()).isEqualTo("0");
    // 草稿换品类：人工改过费率的不跟着换
    assertThat(body(call(put("/api/admin/markets/" + draftNo), s.cookie(), toJson(draft("NBA", 2, NOW, NOW.plus(Duration.ofDays(1)))))).get("feeRate").asString())
        .isEqualTo("0");
    String no = listed(s);
    long id = marketId(no);
    Admin s2 = later(s, Duration.ofDays(1).plusHours(3));
    markets.closeDue();
    markets.endDue();
    assertThat(jdbc.queryForObject("SELECT status FROM market_question WHERE id = ?", String.class, id)).isEqualTo("PENDING_RESOLUTION");
    assertThat(call(put("/api/admin/markets/" + no + "/fee"), s2.cookie(), "{\"feeRate\":\"6\",\"reason\":\"待裁决也能改\"}").getStatus()).isEqualTo(200);
    jdbc.update("UPDATE market_question SET settle_phase = 'CALCULATED' WHERE id = ?", id);
    MockHttpServletResponse locked = call(put("/api/admin/markets/" + no + "/fee"), s2.cookie(), "{\"feeRate\":\"7\",\"reason\":\"x\"}");
    assertThat(locked.getStatus()).isEqualTo(409);
    assertThat(locked.getContentAsString()).contains("FEE_LOCKED");
    jdbc.update("UPDATE market_question SET settle_phase = 'NONE', status = 'SETTLED' WHERE id = ?", id);
    assertThat(call(put("/api/admin/markets/" + no + "/fee"), s2.cookie(), "{\"feeRate\":\"7\",\"reason\":\"x\"}").getContentAsString()).contains("FEE_LOCKED");
    assertThat(audits("market.fee")).anyMatch(l -> l.startsWith("FAILURE " + s.username() + " x（失败：FEE_LOCKED）"));
  }

  // ---------------------------------------------------------------- 参数设置（需求第 6.2 节）、单笔最小提现的权限（S2 遗留）

  @Test
  void 参数设置_两组分别要fee_edit和betting_params_edit_单笔最小提现搬到这里_系统配置页不再有() throws Exception {
    Admin s = superAdmin();
    JsonNode fee = body(call(get("/api/admin/params/fee"), s.cookie(), null));
    assertThat(fee.size()).isEqualTo(5);
    assertThat(fee.get(0).toString()).contains("\"key\":\"market.fee.FOOTBALL\"").contains("\"value\":\"5\"").contains("\"unit\":\"percent\"").contains("\"section\":\"fee\"");
    JsonNode betting = body(call(get("/api/admin/params/betting"), s.cookie(), null));
    List<String> keys = new ArrayList<>();
    for (JsonNode p : betting) keys.add(p.get("key").asString() + "=" + p.get("value").asString());
    assertThat(keys).contains("withdraw.minAmount=100", "bet.minAmount=1", "bet.maxAmount=5000", "bet.userCap.FOOTBALL=10000", "bet.userCap.CRYPTO_15M=2000",
        "crypto.closeLeadSeconds=30", "crypto.openLeadMinutes.CRYPTO_15M=60", "crypto.openLeadMinutes.CRYPTO_1H=180", "dispute.windowMinutes.FOOTBALL=120",
        "dispute.windowMinutes.MACRO=1440", "kyc.depositThreshold=1000");
    assertThat(body(call(get("/api/admin/configs"), s.cookie(), null)).toString()).doesNotContain("withdraw.minAmount").doesNotContain("market.fee");
    assertThat(call(put("/api/admin/configs/withdraw.minAmount"), s.cookie(), "{\"value\":\"120\",\"reason\":\"x\"}").getStatus()).as("系统配置页改不到了").isEqualTo(404);
    Admin configOnly = withRoles(role(Permission.CONFIG_VIEW, Permission.CONFIG_EDIT));
    assertThat(call(put("/api/admin/params/betting/withdraw.minAmount"), configOnly.cookie(), "{\"value\":\"120\",\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    assertThat(call(get("/api/admin/params/betting"), configOnly.cookie(), null).getStatus()).isEqualTo(403);
    Admin feeOnly = withRoles(role(Permission.FEE_EDIT));
    assertThat(call(put("/api/admin/params/betting/withdraw.minAmount"), feeOnly.cookie(), "{\"value\":\"120\",\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    assertThat(call(put("/api/admin/params/fee/withdraw.minAmount"), feeOnly.cookie(), "{\"value\":\"120\",\"reason\":\"x\"}").getStatus()).as("键不属于费率组").isEqualTo(404);
    Admin params = withRoles(role(Permission.BETTING_PARAMS_EDIT));
    try {
      MockHttpServletResponse r = call(put("/api/admin/params/betting/withdraw.minAmount"), params.cookie(), "{\"value\":\"120\",\"reason\":\"提高门槛\"}");
      assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
      assertThat(body(r).get("value").asString()).isEqualTo("120");
      assertThat(config.decimal(ConfigKey.WITHDRAW_MIN_AMOUNT, null)).isEqualByComparingTo("120");
      assertThat(audits("params.update")).anyMatch(l -> l.equals("SUCCESS " + params.username() + " 提高门槛"));
      // 最多 2 位小数；仍要大于每条链的手续费（AC-61）
      assertThat(fields(call(put("/api/admin/params/betting/withdraw.minAmount"), params.cookie(), "{\"value\":\"100.001\",\"reason\":\"x\"}"))).containsExactly("value:invalid");
      assertThat(fields(call(put("/api/admin/params/betting/withdraw.minAmount"), params.cookie(), "{\"value\":\"1\",\"reason\":\"x\"}")))
          .containsExactly("ERC20:minNotAboveFee", "TRC20:minNotAboveFee");
      // 别人刚改过：带上打开页面时看到的值，不同就 409，不覆盖
      MockHttpServletResponse conflict = call(put("/api/admin/params/betting/withdraw.minAmount"), params.cookie(), "{\"value\":\"150\",\"reason\":\"x\",\"expected\":\"100\"}");
      assertThat(conflict.getStatus()).isEqualTo(409);
      assertThat(conflict.getContentAsString()).contains("CONFIG_CHANGED");
      assertThat(call(put("/api/admin/params/betting/withdraw.minAmount"), params.cookie(), "{\"value\":\"150\",\"reason\":\"x\",\"expected\":\"120.00\"}").getStatus())
          .isEqualTo(200);
      // 范围：费率 0～20、最多 2 位小数
      assertThat(call(put("/api/admin/params/fee/market.fee.NBA"), s.cookie(), "{\"value\":\"20.5\",\"reason\":\"x\"}").getStatus()).isEqualTo(400);
      assertThat(call(put("/api/admin/params/fee/market.fee.NBA"), s.cookie(), "{\"value\":\"5.125\",\"reason\":\"x\"}").getStatus()).isEqualTo(400);
      assertThat(call(put("/api/admin/params/betting/crypto.closeLeadSeconds"), s.cookie(), "{\"value\":\"1.5\",\"reason\":\"x\"}").getStatus()).isEqualTo(400);
    } finally {
      jdbc.update("UPDATE common_config SET value = '100' WHERE config_key = ?", ConfigKey.WITHDRAW_MIN_AMOUNT.key);
      config.refresh();
    }
  }

  @Test
  void 单客户上限只能调低_平台裁决的争议窗口可以改_加密没有默认争议窗口() throws Exception {
    Admin s = superAdmin();
    MarketAdminApi.DraftInput d = draft();
    MarketAdminApi.DraftInput higher = new MarketAdminApi.DraftInput(d.category(), d.title(), d.rules(), "PLATFORM", d.sourcePrimary(), d.sourceBackup(), null,
        d.openAt(), d.closeAt(), d.eventStartAt(), d.eventEndAt(), "10000.01", null, null, d.options());
    assertThat(fields(call(post("/api/admin/markets"), s.cookie(), toJson(higher)))).containsExactly("userCap:aboveDefault");
    MarketAdminApi.DraftInput lower = new MarketAdminApi.DraftInput(d.category(), d.title(), d.rules(), "PLATFORM", d.sourcePrimary(), d.sourceBackup(), null,
        d.openAt(), d.closeAt(), d.eventStartAt(), d.eventEndAt(), "500.5", 30, null, d.options());
    JsonNode m = body(call(get("/api/admin/markets/" + create(s, lower)), s.cookie(), null));
    assertThat(m.get("userCap").asString()).isEqualTo("500.5");
    assertThat(m.get("disputeWindowMinutes").asInt()).isEqualTo(30);
    MarketAdminApi.DraftInput crypto = new MarketAdminApi.DraftInput("CRYPTO_15M", d.title(), d.rules(), "PLATFORM", d.sourcePrimary(), d.sourceBackup(), null,
        d.openAt(), d.closeAt(), d.eventStartAt(), d.eventEndAt(), null, null, null, d.options());
    JsonNode c = body(call(get("/api/admin/markets/" + create(s, crypto)), s.cookie(), null));
    assertThat(c.get("userCap").asString()).as("加密默认 2,000").isEqualTo("2000");
    assertThat(c.get("missing").toString()).contains("disputeWindowMinutes");
  }

  /** 一个客户（直接写库） */
  long customer() {
    String email = "mk" + System.nanoTime() + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    return jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
  }
}
