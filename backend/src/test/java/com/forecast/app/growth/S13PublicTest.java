package com.forecast.app.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.growth.api.GrowthAdminApi;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.settle.SettleTestSupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * forecast S13 推广功能第一批（开发记录/S13-推广功能1.md）：真实 MySQL 8.4、Redis 7（C37），测试数据各自新建（C80）；库是几个测试类共用的，
 * 所以断言只看本测试建的数据（contains / 唯一的来源值），合计用 SQL 当场算的值比较。
 * <ul>
 *   <li>MF-3 今日场次（AC-82）：按客户时区的"今天"列出带标记的市场，没标记的不出现；没有场次时给下一场；不登录能看。</li>
 *   <li>MF-4 不登录可看（AC-84）、隐私：公开接口里没有任何客户的个人数据；搜索引擎（AC-85）：页面头部、正文、草稿 404、robots、站点地图；说明页（P14）。</li>
 *   <li>MF-6 开奖记录和周报（AC-88）：结果、依据、奖池、派彩、抽成、尾差、作废原因和结算单一致；周报合计等于那一周已结束市场的合计。</li>
 *   <li>MF-1 访问记录、MF-10 来源统计的数字（AC-83 的后台部分、AC-94）。</li>
 * </ul>
 */
class S13PublicTest extends SettleTestSupport {

  @Autowired GrowthAdminApi growth;

  private static I18nText tx(String s) {
    return new I18nText(s + " en", s + " 简", s + " 繁");
  }

  /** 一个上架的市场：开放 = 现在 − 1 分钟，场次时间（事件开始）= start，截止 = start − 5 分钟；slate = 列入今日场次 */
  private Market slateMarket(String title, Instant start, boolean slate) {
    Instant now = Instant.now(clock);
    List<MarketAdminApi.OptionInput> opts = List.of(new MarketAdminApi.OptionInput(tx("涨"), tx("收盘高于开盘")),
        new MarketAdminApi.OptionInput(tx("跌"), tx("收盘不高于开盘")));
    MarketAdminApi.DraftInput in = new MarketAdminApi.DraftInput("FOOTBALL", tx(title), tx("规则"), "AUTO", "premierleague.com", "API-Football", null,
        now.minus(Duration.ofMinutes(1)).toString(), start.minus(Duration.ofMinutes(5)).toString(), start.toString(), start.plus(Duration.ofHours(2)).toString(),
        null, null, null, opts, slate);
    MarketAdminApi.Detail d = admin.createDraft(in, 1);
    admin.publish(Long.parseLong(d.publicNo()), true, 1);
    long id = jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, Long.parseLong(d.publicNo()));
    return new Market(id, d.publicNo(), d.options().stream().map(MarketAdminApi.OptionView::optionNo).toList());
  }

  private MockHttpServletResponse anon(MockHttpServletRequestBuilder b) throws Exception {
    return call(b, null, null);
  }

  private JsonNode anonJson(String url) throws Exception {
    MockHttpServletResponse r = anon(get(url));
    assertThat(r.getStatus()).as(url + " " + r.getContentAsString()).isEqualTo(200);
    return body(r);
  }

  private static List<String> nos(JsonNode items) {
    List<String> out = new ArrayList<>();
    items.forEach(i -> out.add(i.get("publicNo").asString()));
    return out;
  }

  // =====================================================================
  // MF-3 今日场次
  // =====================================================================

  @Test
  void AC82_今日场次_按客户时区的今天_没标记的不出现_没有时给下一场_不登录能看() throws Exception {
    // 测试时钟 2026-10-14 10:00 UTC。13:00 UTC 的场次对 UTC+8 的客户是当天 21:00
    Instant today13 = Instant.parse("2026-10-14T13:00:00Z");
    Market on = slateMarket("S13 今日 " + System.nanoTime(), today13, true);
    Market off = slateMarket("S13 不在今日 " + System.nanoTime(), today13, false);
    Market tomorrow = slateMarket("S13 明天 " + System.nanoTime(), Instant.parse("2026-10-14T17:00:00Z"), true); // UTC+8 的第二天 01:00
    // UTC+8 的 10-14：[10-13 16:00Z, 10-14 16:00Z)
    JsonNode t = anonJson("/api/markets/today?from=2026-10-13T16:00:00Z&to=2026-10-14T16:00:00Z");
    assertThat(nos(t.get("items"))).contains(on.no()).doesNotContain(off.no(), tomorrow.no());
    for (JsonNode i : t.get("items")) {
      assertThat(i.get("todaySlate").asBoolean()).isTrue();
      if (i.get("publicNo").asString().equals(on.no())) {
        assertThat(i.get("eventStartAt").asString()).isEqualTo("2026-10-14T13:00:00Z");
        assertThat(i.get("pool")).as("带两边的人数和概率").isNotNull();
        assertThat(i.get("pool").get("options").size()).isEqualTo(2);
      }
    }
    // 按时间排
    List<String> starts = new ArrayList<>();
    t.get("items").forEach(i -> starts.add(i.get("eventStartAt").asString()));
    assertThat(starts).isSorted();
    // 一个很远、没有场次的日子：给下一场（远处另建一个，天数随机，避免和别的测试撞）
    long days = 1000 + (System.nanoTime() % 500);
    Instant far = Instant.parse("2026-10-14T13:00:00Z").plus(Duration.ofDays(days));
    Market next = slateMarket("S13 下一场 " + System.nanoTime(), far, true);
    Instant dayBefore = far.minus(Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
    JsonNode empty = anonJson("/api/markets/today?from=" + dayBefore + "&to=" + dayBefore.plus(Duration.ofDays(1)));
    assertThat(empty.get("items").size()).isZero();
    assertThat(empty.get("next").get("publicNo").asString()).isEqualTo(next.no());
    // 范围不对：一次指出
    MockHttpServletResponse bad = anon(get("/api/markets/today?from=x&to=2026-10-14T00:00:00Z"));
    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat(anon(get("/api/markets/today?from=2026-10-13T00:00:00Z&to=2026-10-20T00:00:00Z")).getStatus()).as("超过 48 小时").isEqualTo(400);
  }

  // =====================================================================
  // MF-6 开奖记录、周报；MF-4 隐私
  // =====================================================================

  @Test
  void AC88_开奖记录和周报_和结算单一致_不登录能看_没有个人数据() throws Exception {
    User a = user("1000");
    User b = user("1000");
    String emailA = jdbc.queryForObject("SELECT email FROM account_user WHERE id = ?", String.class, a.id());
    String tag = "S13R" + System.nanoTime();
    Market settled = slateMarket(tag + " 结算", Instant.now(clock).plus(Duration.ofMinutes(10)), false);
    Market voided = slateMarket(tag + " 作废", Instant.now(clock).plus(Duration.ofMinutes(10)), false);
    Market cancelled = slateMarket(tag + " 取消", Instant.now(clock).plus(Duration.ofMinutes(10)), false);
    bet(a.id(), settled, 0, "60");
    bet(b.id(), settled, 1, "40");
    bet(a.id(), voided, 0, "25"); // 只有一边有人押：截止时作废（NOT_ENOUGH_OPTIONS）
    admin.cancel(Long.parseLong(cancelled.no()), 1, "赛事取消");
    closeAndEnd(settled);
    closeAndEnd(voided);
    settle(settled, 0);

    JsonNode page = anonJson("/api/public/results?pageSize=50");
    Map<String, JsonNode> byNo = new java.util.HashMap<>();
    page.get("items").forEach(i -> byNo.put(i.get("publicNo").asString(), i));
    assertThat(byNo).containsKeys(settled.no(), voided.no(), cancelled.no());
    JsonNode s = byNo.get(settled.no());
    Map<String, Object> run = run(settled, "PAYOUT");
    assertThat(s.get("status").asString()).isEqualTo("settled");
    assertThat(s.get("winningOptionNo").asString()).isEqualTo(settled.options().get(0));
    assertThat(s.get("bettors").asInt()).isEqualTo(2);
    assertThat(new BigDecimal(s.get("settle").get("poolTotal").asString())).isEqualByComparingTo((BigDecimal) run.get("pool_total"));
    assertThat(new BigDecimal(s.get("settle").get("payoutTotal").asString())).isEqualByComparingTo(((BigDecimal) run.get("sum_payout")).setScale(2, java.math.RoundingMode.DOWN));
    assertThat(new BigDecimal(s.get("settle").get("feeTotal").asString())).isEqualByComparingTo(((BigDecimal) run.get("sum_fee")).setScale(2, java.math.RoundingMode.DOWN));
    assertThat(s.get("settle").get("dust").asString()).isNotEmpty();
    assertThat(s.get("settle").get("mine").isNull()).as("不登录没有本人的结果").isTrue();
    assertThat(s.get("resolution").isNull()).as("手工结算的测试市场没有裁决记录").isTrue();
    assertThat(byNo.get(voided.no()).get("voidReason").asString()).isEqualTo("NOT_ENOUGH_OPTIONS");
    assertThat(byNo.get(voided.no()).get("settle").get("kind").asString()).isEqualTo("refund");
    assertThat(byNo.get(voided.no()).get("bettors").asInt()).as("作废的市场退款后持仓清零，下注人数仍按收到过的下注算").isEqualTo(1);
    assertThat(byNo.get(cancelled.no()).get("bettors").asInt()).as("取消前没人下注").isZero();
    assertThat(byNo.get(cancelled.no()).get("cancelReason").asString()).isEqualTo("赛事取消");
    // 筛选：只看已作废
    assertThat(nos(anonJson("/api/public/results?status=voided&pageSize=50").get("items"))).contains(voided.no()).doesNotContain(settled.no(), cancelled.no());
    assertThat(anon(get("/api/public/results?status=listed")).getStatus()).isEqualTo(400);

    // 周报：结算的那一周，场数、有效场数、作废、取消、奖池、派彩、抽成 = 那一周已结束市场的合计（SQL 当场算）
    LocalDateTime endedAt = jdbc.queryForObject("SELECT ended_at FROM market_question WHERE id = ?", Timestamp.class, settled.id()).toLocalDateTime();
    LocalDate monday = endedAt.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    String week = String.format("%d-W%02d", monday.get(IsoFields.WEEK_BASED_YEAR), monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    JsonNode wk = anonJson("/api/public/weekly/" + week);
    Timestamp f = Timestamp.valueOf(monday.atStartOfDay());
    Timestamp to = Timestamp.valueOf(monday.plusWeeks(1).atStartOfDay());
    long ended = jdbc.queryForObject("SELECT COUNT(*) FROM market_question WHERE ended_at >= ? AND ended_at < ? AND deleted_at IS NULL "
        + "AND status IN ('SETTLED','VOIDED','CANCELLED')", Long.class, f, to);
    long nSettled = jdbc.queryForObject("SELECT COUNT(*) FROM market_question WHERE ended_at >= ? AND ended_at < ? AND status = 'SETTLED'", Long.class, f, to);
    BigDecimal pool = jdbc.queryForObject("SELECT COALESCE(SUM(r.pool_total), 0) FROM settle_run r JOIN market_question q ON q.id = r.market_id "
        + "WHERE q.ended_at >= ? AND q.ended_at < ? AND q.status = 'SETTLED' AND r.kind = 'PAYOUT' AND r.status = 'DONE'", BigDecimal.class, f, to);
    BigDecimal fee = jdbc.queryForObject("SELECT COALESCE(SUM(r.sum_fee), 0) FROM settle_run r JOIN market_question q ON q.id = r.market_id "
        + "WHERE q.ended_at >= ? AND q.ended_at < ? AND q.status = 'SETTLED' AND r.kind = 'PAYOUT' AND r.status = 'DONE'", BigDecimal.class, f, to);
    BigDecimal payout = jdbc.queryForObject("SELECT COALESCE(SUM(r.sum_payout), 0) FROM settle_run r JOIN market_question q ON q.id = r.market_id "
        + "WHERE q.ended_at >= ? AND q.ended_at < ? AND q.status = 'SETTLED' AND r.kind = 'PAYOUT' AND r.status = 'DONE'", BigDecimal.class, f, to);
    JsonNode w = wk.get("week");
    assertThat(w.get("week").asString()).isEqualTo(week);
    assertThat(w.get("markets").asLong()).isEqualTo(ended);
    assertThat(w.get("settled").asLong()).isEqualTo(nSettled);
    assertThat(w.get("voided").asLong() + w.get("cancelled").asLong()).isEqualTo(ended - nSettled);
    assertThat(new BigDecimal(w.get("poolTotal").asString())).isEqualByComparingTo(pool.setScale(2, java.math.RoundingMode.DOWN));
    assertThat(new BigDecimal(w.get("payoutTotal").asString())).isEqualByComparingTo(payout.setScale(2, java.math.RoundingMode.DOWN));
    assertThat(new BigDecimal(w.get("feeTotal").asString())).isEqualByComparingTo(fee.setScale(2, java.math.RoundingMode.DOWN));
    List<String> reasons = new ArrayList<>();
    w.get("reasons").forEach(r -> reasons.add(r.get("status").asString() + ":" + (r.get("reason").isNull() ? "" : r.get("reason").asString())));
    assertThat(reasons).contains("voided:NOT_ENOUGH_OPTIONS", "cancelled:赛事取消");
    assertThat(nos(wk.get("markets"))).contains(settled.no(), voided.no(), cancelled.no());
    // 来源统计的"结束的市场"：作废的按收到过的下注算奖池和人数
    GrowthAdminApi.MarketRow vr = growth.report(null, null, null).markets().stream().filter(r -> r.marketNo().equals(voided.no())).findFirst().orElseThrow();
    assertThat(vr.bettors()).isEqualTo(1);
    assertThat(new BigDecimal(vr.pool())).isEqualByComparingTo("25");
    // 周报列表里有这一周
    List<String> weeks = new ArrayList<>();
    anonJson("/api/public/weekly").get("items").forEach(x -> weeks.add(x.get("week").asString()));
    assertThat(weeks).contains(week);
    assertThat(anon(get("/api/public/weekly/2026-W99")).getStatus()).isEqualTo(400);

    // 隐私：公开接口（不登录）里没有客户的邮箱、本人的持仓；登录的人看详情才有自己的
    String all = anonJson("/api/public/results?pageSize=50").toString() + anonJson("/api/markets/" + settled.no()) + anonJson("/api/markets?status=ended&pageSize=50")
        + wk + anonJson("/api/markets/today");
    assertThat(all).doesNotContain(emailA).doesNotContain("\"canDispute\":true");
    JsonNode anonDetail = anonJson("/api/markets/" + settled.no());
    assertThat(anonDetail.get("settle").get("mine").isNull()).isTrue();
    assertThat(anonDetail.get("pool").get("mine") == null || anonDetail.get("pool").get("mine").isNull() || anonDetail.get("pool").get("mine").isEmpty()).isTrue();
    JsonNode mineDetail = getJson("/api/markets/" + settled.no(), a.cookie());
    assertThat(mineDetail.get("settle").get("mine").isNull()).as("登录了有本人的结果").isFalse();
  }

  // =====================================================================
  // MF-4 搜索引擎、说明页
  // =====================================================================

  @Test
  void AC85_页面头部和正文_三种语言互相标注_草稿404_robots和站点地图() throws Exception {
    String tag = "S13SEO" + System.nanoTime();
    Market m = slateMarket(tag, Instant.now(clock).plus(Duration.ofHours(3)), true);
    User u = user("1000");
    bet(u.id(), m, 0, "30");
    bet(user("1000").id(), m, 1, "10");
    String page = "/markets/" + m.no() + "?lang=zh-CN&src=tg";
    MockHttpServletResponse head = anon(get("/api/public/seo/head").header("X-Fc-Page", page));
    assertThat(head.getStatus()).isEqualTo(200);
    assertThat(head.getContentType()).startsWith("text/html");
    String h = head.getContentAsString();
    assertThat(h).contains("<title>" + tag + " 简 | Quick Come</title>").contains("<meta name=\"description\" content=\"")
        .contains("hreflang=\"en\" href=\"https://app.forecast.test/markets/" + m.no() + "?lang=en\"").contains("hreflang=\"zh-CN\"").contains("hreflang=\"zh-TW\"")
        .contains("hreflang=\"x-default\" href=\"https://app.forecast.test/markets/" + m.no() + "\"")
        .contains("<link rel=\"canonical\" href=\"https://app.forecast.test/markets/" + m.no() + "?lang=zh-CN\">").contains("og:title").doesNotContain("noindex");
    // 描述有各选项的概率（75% / 25%）
    assertThat(h).contains("涨 简 75").contains("跌 简 25");
    String en = anon(get("/api/public/seo/head").header("X-Fc-Page", "/markets/" + m.no() + "?lang=en")).getContentAsString();
    assertThat(en).contains("<title>" + tag + " en | Quick Come</title>");
    // 正文：题目、选项、概率、截止时间（没有脚本也能读到）
    String b = anon(get("/api/public/seo/body").header("X-Fc-Page", page)).getContentAsString();
    assertThat(b).contains("class=\"seo-static\"").contains("<h1>" + tag + " 简</h1>").contains("涨 简").contains("75").contains("截止下注")
        .contains("UTC").contains("40.00 USDT");
    // 草稿、不存在、格式不对：不存在（403 给 Nginx 换成 404）、不收录
    MarketAdminApi.Detail draft = admin.createDraft(new MarketAdminApi.DraftInput("FOOTBALL", tx("草稿"), tx("r"), "AUTO", "a", "b", null,
        Instant.now(clock).toString(), Instant.now(clock).plus(Duration.ofDays(1)).toString(), Instant.now(clock).plus(Duration.ofDays(1)).toString(),
        Instant.now(clock).plus(Duration.ofDays(2)).toString(), null, null, null, List.of(new MarketAdminApi.OptionInput(tx("a"), tx("a")),
            new MarketAdminApi.OptionInput(tx("b"), tx("b")))), 1);
    assertThat(anon(get("/api/public/seo/exists").header("X-Fc-Page", "/markets/" + draft.publicNo())).getStatus()).isEqualTo(403);
    assertThat(anon(get("/api/public/seo/exists").header("X-Fc-Page", "/markets/abc")).getStatus()).isEqualTo(403);
    assertThat(anon(get("/api/public/seo/exists").header("X-Fc-Page", "/markets/" + m.no() + "?lang=en")).getStatus()).isEqualTo(204);
    assertThat(anon(get("/api/public/seo/exists").header("X-Fc-Page", "/how-to-play")).getStatus()).isEqualTo(204);
    assertThat(anon(get("/api/public/seo/head").header("X-Fc-Page", "/markets/" + draft.publicNo())).getContentAsString()).contains("noindex")
        .doesNotContain("草稿");
    // 登录后才能看的页面：不收录
    assertThat(anon(get("/api/public/seo/head").header("X-Fc-Page", "/assets")).getContentAsString()).contains("noindex");
    assertThat(anon(get("/api/public/seo/body").header("X-Fc-Page", "/assets")).getContentAsString()).isEmpty();
    // 每页的标题按内容生成、互不相同
    String list = anon(get("/api/public/seo/head").header("X-Fc-Page", "/markets?lang=zh-CN")).getContentAsString();
    String results = anon(get("/api/public/seo/head").header("X-Fc-Page", "/results?lang=zh-CN")).getContentAsString();
    String how = anon(get("/api/public/seo/head").header("X-Fc-Page", "/how-to-play?lang=zh-CN")).getContentAsString();
    assertThat(List.of(title(h), title(list), title(results), title(how))).doesNotHaveDuplicates();
    // 列表页、说明页的正文
    assertThat(anon(get("/api/public/seo/body").header("X-Fc-Page", "/markets?lang=zh-CN")).getContentAsString()).contains("今日场次").contains(tag + " 简");
    assertThat(anon(get("/api/public/seo/body").header("X-Fc-Page", "/how-to-play?lang=zh-CN")).getContentAsString()).contains("怎么提").contains("100 USDT")
        .doesNotContain("{");
    // 转义：题目里的 HTML 不会原样输出
    Market xss = slateMarket("<script>alert(1)</script>" + tag, Instant.now(clock).plus(Duration.ofHours(3)), false);
    String xb = anon(get("/api/public/seo/body").header("X-Fc-Page", "/markets/" + xss.no())).getContentAsString();
    assertThat(xb).doesNotContain("<script>").contains("&lt;script&gt;");
    // robots、站点地图
    String robots = anon(get("/api/public/robots.txt")).getContentAsString();
    assertThat(robots).contains("Disallow: /bets").contains("Disallow: /assets$").contains("Sitemap: https://app.forecast.test/sitemap.xml").doesNotContain("Disallow: /\n");
    String sitemap = anon(get("/api/public/sitemap.xml")).getContentAsString();
    assertThat(sitemap).contains("<loc>https://app.forecast.test/markets/" + m.no() + "</loc>").contains("<loc>https://app.forecast.test/how-to-play?lang=zh-TW</loc>")
        .doesNotContain(draft.publicNo());
    // 说明页接口：三段、参数已替换
    JsonNode ht = anonJson("/api/public/pages/how-to-play?lang=zh-CN");
    assertThat(ht.get("sections").size()).isEqualTo(3);
    assertThat(ht.toString()).contains("单笔最少提现 100 USDT").doesNotContain("{minWithdraw}");
  }

  private static String title(String head) {
    int a = head.indexOf("<title>");
    return head.substring(a + 7, head.indexOf("</title>"));
  }

  // =====================================================================
  // MF-1 访问、MF-10 来源统计
  // =====================================================================

  @Test
  void 访问记录_同一个人同一天同一来源只记一次_不合法的不记() throws Exception {
    String src = "v" + Long.toString(System.nanoTime(), 36);
    String dev = "dev-" + System.nanoTime() + "-aaaaaaaa";
    for (int i = 0; i < 3; i++) {
      assertThat(call(post("/api/attribution/visit").header("X-Device-Id", dev), null, "{\"src\":\"" + src + "\",\"cmp\":\"w1\"}").getStatus()).isEqualTo(204);
    }
    assertThat(call(post("/api/attribution/visit").header("X-Device-Id", dev + "x"), null, "{\"src\":\"" + src + "\",\"cmp\":\"w1\"}").getStatus()).isEqualTo(204);
    assertThat(call(post("/api/attribution/visit").header("X-Device-Id", dev), null, "{\"src\":\"<bad>\"}").getStatus()).isEqualTo(204);
    // Telegram 机器人深链的 start 参数：s-渠道__c-活动__r-分享人
    assertThat(call(post("/api/attribution/visit").header("X-Device-Id", dev), null, "{\"start\":\"s-" + src + "__c-bot__r-42\"}").getStatus()).isEqualTo(204);
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT src, cmp, ref, start_param, visitor FROM growth_visit WHERE src = ? ORDER BY id", src);
    assertThat(rows).hasSize(3);
    assertThat(rows.get(0).get("visitor").toString()).hasSize(64).doesNotContain(dev);
    assertThat(rows.get(2).get("cmp")).isEqualTo("bot");
    assertThat(rows.get(2).get("ref")).isEqualTo("42");
    assertThat(rows.get(2).get("start_param")).isEqualTo("s-" + src + "__c-bot__r-42");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM growth_visit WHERE src = '<bad>'", Long.class)).isZero();
  }

  @Test
  void AC94_来源统计_访问注册首充激活和第2周仍下注_和数据库计数一致_导出没有邮箱() throws Exception {
    String src = "g" + Long.toString(System.nanoTime(), 36);
    Instant t0 = Instant.now(clock);
    // 访问 2 人
    call(post("/api/attribution/visit").header("X-Device-Id", "device-aaaaaaaaaaaaaaa-" + src), null, "{\"src\":\"" + src + "\",\"cmp\":\"w1\",\"ref\":\"r1\"}");
    call(post("/api/attribution/visit").header("X-Device-Id", "device-bbbbbbbbbbbbbbb-" + src), null, "{\"src\":\"" + src + "\",\"cmp\":\"w1\",\"ref\":\"r1\"}");
    // 注册 3 人（注册时间 = 测试时钟的现在）：A 首充 + 第 2 周下注；B 只首充；C 什么都没做；D 首次来源是别的、注册来源是它（basis=reg 才算进来）
    long a = registered(src, "w1", "r1", src, "w1", "r1", t0);
    long b = registered(src, "w1", "r1", src, "w1", "r1", t0);
    long c = registered(src, "w1", "r1", src, "w1", "r1", t0);
    long d = registered("other" + src, null, null, src, "w1", "r1", t0);
    credited(a, t0);
    credited(b, t0);
    ledger.openAccount(a);
    deposit(a, "100");
    // 第 8 天下注（注册后第 2 周）
    advance(Duration.ofDays(8));
    Market m = market(2);
    bet(a, m, 0, "10");
    // 第 15 天看：A、B、C 的第 2 周都过完了
    advance(Duration.ofDays(7));
    String week = isoWeek(t0);
    GrowthAdminApi.Report rep = growth.report(LocalDate.ofInstant(t0, ZoneOffset.UTC).toString(), null, "first");
    GrowthAdminApi.Cohort row = rep.cohorts().stream().filter(x -> x.week().equals(week) && x.src().equals(src)).findFirst().orElseThrow();
    assertThat(row.cmp()).isEqualTo("w1");
    assertThat(row.ref()).isEqualTo("r1");
    assertThat(row.visits()).isEqualTo(2);
    assertThat(row.registrations()).isEqualTo(3);
    assertThat(row.firstDeposits()).isEqualTo(2);
    assertThat(row.activated()).isEqualTo(1);
    assertThat(row.week2Active()).isEqualTo(1);
    assertThat(row.week2Eligible()).isEqualTo(3);
    // 注册来源口径：D 也算进来
    GrowthAdminApi.Report byReg = growth.report(LocalDate.ofInstant(t0, ZoneOffset.UTC).toString(), null, "reg");
    assertThat(byReg.cohorts().stream().filter(x -> x.week().equals(week) && x.src().equals(src)).findFirst().orElseThrow().registrations()).isEqualTo(4);
    // 和数据库计数一致：这一周这个首次来源的注册数
    long dbRegs = jdbc.queryForObject("SELECT COUNT(*) FROM account_attribution a JOIN account_user u ON u.id = a.user_id WHERE a.first_src = ?", Long.class, src);
    assertThat(row.registrations()).isEqualTo(dbRegs);
    // 导出：客户编号有、邮箱没有
    String csv = growth.csv("customers", LocalDate.ofInstant(t0, ZoneOffset.UTC).toString(), null, "first", "en");
    assertThat(csv).contains(String.valueOf(a)).contains(src).doesNotContain("@");
    String email = jdbc.queryForObject("SELECT email FROM account_user WHERE id = ?", String.class, a);
    assertThat(csv).doesNotContain(email);
    String sources = growth.csv("sources", LocalDate.ofInstant(t0, ZoneOffset.UTC).toString(), null, "first", "zh");
    assertThat(sources).contains(week + "," + src + ",w1,r1,2,3,2,1,1,3");
    // 没有任何账户因为 ref 收到钱（MQ-5）：ref 不是账户；这里确认没有和来源有关的流水类型
    assertThat(java.util.Arrays.stream(com.forecast.app.ledger.api.LedgerEntryType.values()).map(Enum::name))
        .noneMatch(n -> n.contains("REFERRAL") || n.contains("COMMISSION") || n.contains("REBATE") || n.contains("AFFILIATE"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry e JOIN ledger_account a ON a.id = e.account_id WHERE a.user_id = 42 AND e.biz_type LIKE '%REF%'",
        Long.class)).as("ref 只是统计的标签，没有任何流水").isZero();
    assertThat(List.of(b, c, d)).hasSize(3);
  }

  private long registered(String fsrc, String fcmp, String fref, String rsrc, String rcmp, String rref, Instant at) {
    String email = "g" + System.nanoTime() + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status, created_at) VALUES (?, 'ACTIVE', ?)", email, Timestamp.valueOf(LocalDateTime.ofInstant(at, ZoneOffset.UTC)));
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    jdbc.update("INSERT INTO account_attribution (user_id, first_src, first_cmp, first_ref, reg_src, reg_cmp, reg_ref, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3))",
        id, fsrc, fcmp, fref, rsrc, rcmp, rref);
    return id;
  }

  private void credited(long uid, Instant at) {
    jdbc.update("INSERT INTO wallet_deposit (public_no, chain, tx_hash, log_index, block_number, block_hash, from_address, to_address, user_id, amount, status, confirmations, credited_at) "
        + "VALUES (?, 'TRC20', ?, 0, 1, 'h', 'Tfrom', 'Tto', ?, 100, 'CREDITED', 20, ?)", System.nanoTime(), "tx" + System.nanoTime(), uid,
        Timestamp.valueOf(LocalDateTime.ofInstant(at, ZoneOffset.UTC)));
  }

  private static String isoWeek(Instant t) {
    LocalDate monday = LocalDate.ofInstant(t, ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    return String.format("%d-W%02d", monday.get(IsoFields.WEEK_BASED_YEAR), monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
  }
}
