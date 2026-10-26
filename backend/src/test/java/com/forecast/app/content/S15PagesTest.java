package com.forecast.app.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.forecast.app.common.error.BizException;
import com.forecast.app.content.api.ContentAdminApi;
import com.forecast.app.content.core.Settings;
import com.forecast.app.content.page.PageService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * SEO 内容页（forecast S15；MF-11、EQ-7、AC-112、NF-14）：焦点战页、BTC 每小时涨跌统计页、文章页；数据部分自动，文字审核后才显示；
 * 每天新增页面上限；不运行脚本也能读到（SSI 的页面头部和正文）；站点地图。
 */
class S15PagesTest extends ContentTestSupport {

  private MockHttpServletResponse anon(String url) throws Exception {
    return mvc.perform(get(url)).andReturn().getResponse();
  }

  private JsonNode anonJson(String url) throws Exception {
    MockHttpServletResponse r = anon(url);
    assertThat(r.getStatus()).as(url + " " + r.getContentAsString()).isEqualTo(200);
    return json.readTree(r.getContentAsString());
  }

  private String seo(String part, String page) throws Exception {
    return mvc.perform(get("/api/public/seo/" + part).header("X-Fc-Page", page)).andReturn().getResponse().getContentAsString();
  }

  private int exists(String page) throws Exception {
    return mvc.perform(get("/api/public/seo/exists").header("X-Fc-Page", page)).andReturn().getResponse().getStatus();
  }

  @Test
  void AC112_焦点战页_数据和系统一致_说明文字审核前不显示_审核后三语显示_不跑脚本也能读到() throws Exception {
    Market m = sportsMarket("焦点战页 " + System.nanoTime(), Instant.now(clock).plus(Duration.ofHours(6)), true);
    User a = user("500");
    bet(a.id(), m, 0, "30");
    tasks.runOnce();
    String slug = PageService.FOCUS_PREFIX + m.no();
    JsonNode v = anonJson("/api/public/insights/" + slug + "?lang=zh-CN");
    assertThat(v.path("kind").asString()).isEqualTo("FOCUS");
    assertThat(v.path("intro").isNull()).as("没审核的说明不显示，只显示数据").isTrue();
    JsonNode market = v.path("market");
    assertThat(market.path("publicNo").isString()).as("市场编号是字符串（超过 JavaScript 能精确表示的范围）").isTrue();
    assertThat(market.path("publicNo").asString()).isEqualTo(m.no());
    assertThat(market.path("total").asString()).isEqualTo("30.00");
    assertThat(market.path("bettors").asInt()).isEqualTo(1);
    assertThat(market.path("options").get(0).path("amount").asString()).isEqualTo("30.00");
    assertThat(market.path("options").get(1).path("bettors").asInt()).isZero();
    assertThat(v.toString()).doesNotContain("@");
    // 搜索引擎版本（Nginx SSI）
    assertThat(exists("/insights/" + slug)).isEqualTo(204);
    assertThat(exists("/insights/focus-1")).isEqualTo(403);
    String body = seo("body", "/insights/" + slug + "?lang=zh-CN");
    assertThat(body).contains(m.no()).contains("30.00 USDT").contains("大 2.5").contains("小 2.5");
    assertThat(seo("head", "/insights/" + slug + "?lang=en")).contains("<title>").contains("hreflang=\"zh-TW\"").doesNotContain("noindex");
    // 说明文字：起草 → 审核发布 → 中文（含繁体转换）显示，英文还没发布时英文页不显示
    List<ContentAdminApi.Draft> g = group(generate("SEO_INTRO", null, slug, null));
    ContentAdminApi.Draft zh = lang(g, "zh");
    assertThat(zh.status()).as(zh.failReason()).isEqualTo("PENDING");
    content.publish(Long.parseLong(zh.draftNo()), 1);
    assertThat(anonJson("/api/public/insights/" + slug + "?lang=zh-CN").path("intro").asString()).isEqualTo(zh.body());
    assertThat(anonJson("/api/public/insights/" + slug + "?lang=zh-TW").path("intro").asString()).isEqualTo(com.forecast.app.content.check.Hant.convert(zh.body()));
    assertThat(anonJson("/api/public/insights/" + slug + "?lang=en").path("intro").isNull()).isTrue();
    // 赛前数据短文发布后显示在焦点战页
    ContentAdminApi.Draft pv = lang(group(generate("PREVIEW", m.no(), null, null)), "en");
    content.publish(Long.parseLong(pv.draftNo()), 1);
    assertThat(anonJson("/api/public/insights/" + slug + "?lang=en").path("preview").path("body").asString()).isEqualTo(pv.body());
    assertThat(seo("body", "/insights/" + slug + "?lang=en")).contains("Pre-match data note");
    // 列表和站点地图
    assertThat(anonJson("/api/public/insights?lang=zh-CN").path("items").toString()).contains(slug);
    assertThat(anon("/api/public/sitemap.xml").getContentAsString()).contains("/insights/" + slug + "</loc>").contains("/insights</loc>");
    // 隐藏以后不存在
    content.setPageVisible(slug, false, 1);
    assertThat(exists("/insights/" + slug)).isEqualTo(403);
    assertThat(anon("/api/public/insights/" + slug).getStatus()).isEqualTo(404);
  }

  @Test
  void BTC统计页_和同一段K线手算一致_来源和时间写明() throws Exception {
    content.refreshBtc(1);
    JsonNode v = anonJson("/api/public/insights/" + PageService.BTC_SLUG + "?lang=en");
    JsonNode b = v.path("btc");
    // 手算：最近 168 根整点 K 线（替身规律：小时序号 % 3 == 0 跌，其余涨）
    Instant end = Instant.now(clock).truncatedTo(ChronoUnit.HOURS);
    int up = 0;
    int up24 = 0;
    for (int i = 0; i < 168; i++) {
      long h = end.minus(Duration.ofHours(168 - i)).toEpochMilli() / 3_600_000;
      boolean isUp = h % 3 != 0;
      if (isUp) up++;
      if (isUp && i >= 144) up24++;
    }
    assertThat(b.path("bars").asInt()).isEqualTo(168);
    assertThat(b.path("up7d").asInt()).isEqualTo(up);
    assertThat(b.path("up24").asInt()).isEqualTo(up24);
    assertThat(b.path("up7dPct").asString()).isEqualTo(new BigDecimal(up * 100).divide(BigDecimal.valueOf(168), 1, java.math.RoundingMode.HALF_UP).toPlainString());
    assertThat(b.path("source").asString()).isEqualTo("Binance BTC/USDT");
    assertThat(b.path("fetchedAt").asString()).isNotBlank();
    String body = seo("body", "/insights/" + PageService.BTC_SLUG + "?lang=zh-CN");
    assertThat(body).contains("过去 7 天上涨的小时").contains(up + " / 168").contains("Binance BTC/USDT");
    // 原始返回存档（数据源网关），来源名和加密裁决的分开
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resolve_source_fetch WHERE source = 'BINANCE_1H' AND market_id IS NULL", Integer.class)).isPositive();
  }

  @Test
  void BTC数据取不到_页面显示数据不可用_不影响别的() throws Exception {
    MARKET_DATA.handle(u -> com.forecast.app.support.StubHttp.Reply.status(500, "{}"));
    jdbc.update("DELETE FROM content_page WHERE slug = ?", PageService.BTC_SLUG);
    assertThatThrownBy(() -> content.refreshBtc(1)).isInstanceOf(BizException.class);
    tasks.runOnce();
    assertThat(anon("/api/public/insights/" + PageService.BTC_SLUG + "?lang=en").getStatus()).isEqualTo(200);
    assertThat(anonJson("/api/public/insights/" + PageService.BTC_SLUG + "?lang=en").path("btc").isNull()).isTrue();
    assertThat(seo("body", "/insights/" + PageService.BTC_SLUG)).contains("Data temporarily unavailable");
  }

  @Test
  void 每天新增页面上限_到了当天不再建() throws Exception {
    tasks.runOnce(); // 先把别的测试留下的今日场次建完页
    long today = jdbc.queryForObject("SELECT COUNT(*) FROM content_page WHERE created_day = '2026-10-14'", Long.class);
    setting(Settings.PAGES_DAILY, String.valueOf(today + 1));
    Market a = sportsMarket("上限 A " + System.nanoTime(), Instant.now(clock).plus(Duration.ofHours(5)), true);
    Market b = sportsMarket("上限 B " + System.nanoTime(), Instant.now(clock).plus(Duration.ofHours(7)), true);
    int created = tasks.runOnce().focusPages();
    assertThat(created).isEqualTo(1);
    assertThat(pages.page(PageService.FOCUS_PREFIX + a.no()) != null ^ pages.page(PageService.FOCUS_PREFIX + b.no()) != null).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_log WHERE action = 'PAGE' AND outcome = 'SKIPPED'", Integer.class)).isPositive();
    // 文章发布也受上限
    ContentAdminApi.Draft art = lang(group(generate("ARTICLE", null, null, "彩池玩法的抽成怎么算")), "zh");
    assertThatThrownBy(() -> content.publish(Long.parseLong(art.draftNo()), 1)).isInstanceOf(BizException.class)
        .satisfies(e -> assertThat(((BizException) e).code()).isEqualTo("PAGE_LIMIT"));
    assertThat(content.pages().createdToday()).isEqualTo((int) today + 1);
  }

  @Test
  void 文章页_审核发布才有_中文发布后繁体自动转换_英文没发布时显示中文() throws Exception {
    List<ContentAdminApi.Draft> g = group(generate("ARTICLE", null, null, "第一次用 USDT 押注要知道什么"));
    ContentAdminApi.Draft zh = lang(g, "zh");
    ContentAdminApi.Draft en = lang(g, "en");
    assertThat(zh.refs()).extracting(ContentAdminApi.RefView::key).contains("rules.fee", "rules.minWithdraw");
    String slug = PageService.ARTICLE_PREFIX + Long.toString(Long.parseLong(zh.groupNo()), 36);
    assertThat(anon("/api/public/insights/" + slug).getStatus()).as("没发布不存在").isEqualTo(404);
    assertThat(exists("/insights/" + slug)).isEqualTo(403);
    ContentAdminApi.Draft p = content.publish(Long.parseLong(zh.draftNo()), 1);
    assertThat(p.siteUrl()).isEqualTo("/insights/" + slug);
    JsonNode tw = anonJson("/api/public/insights/" + slug + "?lang=zh-TW");
    assertThat(tw.path("body").asString()).isEqualTo(com.forecast.app.content.check.Hant.convert(zh.body()));
    JsonNode enView = anonJson("/api/public/insights/" + slug + "?lang=en");
    assertThat(enView.path("fallbackLang").asString()).isEqualTo("zh-CN");
    content.publish(Long.parseLong(en.draftNo()), 1);
    JsonNode enView2 = anonJson("/api/public/insights/" + slug + "?lang=en");
    assertThat(enView2.path("body").asString()).isEqualTo(en.body());
    assertThat(enView2.path("fallbackLang").isNull()).isTrue();
    assertThat(seo("body", "/insights/" + slug + "?lang=en")).contains("<article>");
  }
}
