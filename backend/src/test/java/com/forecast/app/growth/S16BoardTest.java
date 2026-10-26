package com.forecast.app.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.forecast.app.growth.board.BoardProperties;
import com.forecast.app.growth.board.BtcBoard;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.settle.SettleTestSupport;
import com.forecast.app.support.StubHttp;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * forecast S16 数据页（MF-17，和等候页 MF-8 同一页 /data；需求第 5.2 节 P13、AC-96）。
 * <ul>
 *   <li>"过去 N 小时 BTC 每小时涨跌的比例"和用同一段币安 K 线手算的结果一致；没收盘的那一根不算；现价是最新一根的收盘价</li>
 *   <li>币安接口（替身）：5xx 重试后成功；连续失败熔断、不再请求；取不到时保留上一次的数据和它的更新时间</li>
 *   <li>不登录能看；上线前没有"今天的场次"（显示即将上线）、上线后有，两边人数和概率和市场详情一致；Polymarket 这一块不接（默认关）</li>
 *   <li>搜索引擎版本（SSI 的页面头部、正文）、站点地图、robots</li>
 * </ul>
 */
class S16BoardTest extends SettleTestSupport {

  @Autowired BtcBoard board;
  @Autowired BoardProperties props;

  @AfterEach
  void restore() {
    jdbc.update("DELETE FROM growth_launch");
    props.setBinanceUrl("http://127.0.0.1:9");
    props.setBreakerThreshold(5);
    props.setBreakerOpen(Duration.ofMinutes(5));
    props.setRetries(2);
  }

  /** n 根已经收盘的 1 小时 K 线（开盘、收盘）+ 一根还没收盘的（现价）；时间按测试时钟 */
  private String klines(List<String[]> closed, String price) {
    Instant hour = Instant.now(clock).truncatedTo(ChronoUnit.HOURS);
    StringBuilder sb = new StringBuilder("[");
    int n = closed.size();
    for (int i = 0; i < n; i++) {
      long open = hour.minus(n - i, ChronoUnit.HOURS).toEpochMilli();
      String[] c = closed.get(i);
      sb.append("[").append(open).append(",\"").append(c[0]).append("\",\"0\",\"0\",\"").append(c[1]).append("\",\"1\",").append(open + 3_599_999)
          .append(",\"1\",1,\"0\",\"0\",\"0\"],");
    }
    long open = hour.toEpochMilli();
    sb.append("[").append(open).append(",\"").append(price).append("\",\"0\",\"0\",\"").append(price).append("\",\"1\",").append(open + 3_599_999)
        .append(",\"1\",1,\"0\",\"0\",\"0\"]]");
    return sb.toString();
  }

  /** 24 根：开盘 100，收盘按一个固定的序列（含正好相等的） */
  private List<String[]> day() {
    List<String[]> out = new ArrayList<>();
    String[] closes = {"101", "99", "100", "102.5", "98", "100.01", "99.99", "100", "105", "95", "100", "100", "101", "102", "97", "96", "100.5", "99.5", "103",
        "100", "98.7", "101.2", "100", "90"};
    for (String c : closes) out.add(new String[] {"100", c});
    return out;
  }

  @Test
  void AC96_每小时涨跌的比例_和同一段K线手算的一致_没收盘的不算_现价是最新一根() throws Exception {
    List<String[]> candles = day();
    // 手算：收盘 ≥ 开盘算涨
    int up = 0;
    for (String[] c : candles) if (new BigDecimal(c[1]).compareTo(new BigDecimal(c[0])) >= 0) up++;
    String upPct = BigDecimal.valueOf(up * 100L).divide(BigDecimal.valueOf(24), 1, RoundingMode.HALF_UP).toPlainString();
    assertThat(up).isEqualTo(15);
    try (StubHttp stub = new StubHttp()) {
      stub.handle(u -> u.getPath().equals("/api/v3/klines") ? StubHttp.Reply.ok(klines(candles, "65432.1")) : StubHttp.Reply.status(404, "{}"));
      props.setBinanceUrl(stub.url());
      assertThat(board.refresh()).isTrue();
      assertThat(stub.requests().get(0).getQuery()).isEqualTo("symbol=BTCUSDT&interval=1h&limit=25");
    }
    BtcBoard.Snapshot s = board.snapshot();
    assertThat(s.hours()).isEqualTo(24);
    assertThat(s.up()).isEqualTo(up);
    assertThat(s.down()).isEqualTo(24 - up);
    assertThat(s.upPct()).isEqualTo(upPct).isEqualTo("62.5");
    assertThat(s.downPct()).isEqualTo("37.5");
    assertThat(s.price()).isEqualTo("65432.10");
    assertThat(s.to()).isEqualTo(Instant.now(clock).truncatedTo(ChronoUnit.HOURS));
    assertThat(s.from()).isEqualTo(Instant.now(clock).truncatedTo(ChronoUnit.HOURS).minus(24, ChronoUnit.HOURS));
  }

  @Test
  void 币安替身_5xx重试后成功_连续失败熔断_取不到时保留上一次的数据() throws Exception {
    try (StubHttp stub = new StubHttp()) {
      props.setBinanceUrl(stub.url());
      int[] n = {0};
      stub.handle(u -> ++n[0] == 1 ? StubHttp.Reply.status(502, "{}") : StubHttp.Reply.ok(klines(day(), "60000")));
      assertThat(board.refresh()).isTrue();
      assertThat(stub.hits()).as("502 重试一次").isEqualTo(2);
      BtcBoard.Snapshot good = board.snapshot();
      // 一直 500：每次刷新重试 2 次（共 3 次请求）；连续 2 次刷新失败后熔断（阈值改成 2），之后不再请求
      props.setBreakerThreshold(2);
      props.setRetries(2);
      stub.clear();
      stub.handle(u -> StubHttp.Reply.status(500, "{}"));
      assertThat(board.refresh()).isFalse();
      assertThat(board.refresh()).isFalse();
      assertThat(stub.hits()).isEqualTo(6);
      assertThat(board.refresh()).as("熔断中").isFalse();
      assertThat(stub.hits()).as("熔断中不请求").isEqualTo(6);
      assertThat(board.snapshot()).as("保留上一次的数据").isEqualTo(good);
      // 熔断期过了再试
      advance(Duration.ofMinutes(6));
      stub.handle(u -> StubHttp.Reply.ok(klines(day(), "61000")));
      assertThat(board.refresh()).isTrue();
      assertThat(board.snapshot().price()).isEqualTo("61000.00");
    }
  }

  private static I18nText tx(String s) {
    return new I18nText(s + " en", s + " 简", s + " 繁");
  }

  /** 一个上架、带"今日场次"标记的市场：开放 = 现在 − 1 分钟，事件开始 = start */
  private Market slateMarket(String title, Instant start) {
    Instant now = Instant.now(clock);
    List<MarketAdminApi.OptionInput> opts = List.of(new MarketAdminApi.OptionInput(tx("涨"), tx("收盘高于开盘")),
        new MarketAdminApi.OptionInput(tx("跌"), tx("收盘不高于开盘")));
    MarketAdminApi.DraftInput in = new MarketAdminApi.DraftInput("FOOTBALL", tx(title), tx("规则"), "AUTO", "premierleague.com", "API-Football", null,
        now.minus(Duration.ofMinutes(1)).toString(), start.minus(Duration.ofMinutes(5)).toString(), start.toString(), start.plus(Duration.ofHours(2)).toString(),
        null, null, null, opts, true);
    MarketAdminApi.Detail d = admin.createDraft(in, 1);
    admin.publish(Long.parseLong(d.publicNo()), true, 1);
    long id = jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, Long.parseLong(d.publicNo()));
    return new Market(id, d.publicNo(), d.options().stream().map(MarketAdminApi.OptionView::optionNo).toList());
  }

  private JsonNode anon(String url) throws Exception {
    MockHttpServletResponse r = call(get(url), null, null);
    assertThat(r.getStatus()).as(url + " " + r.getContentAsString()).isEqualTo(200);
    return body(r);
  }

  @Test
  void P13_不登录能看_上线前没有今天的场次_上线后和市场详情一致_Polymarket不接() throws Exception {
    Market m = slateMarket("S16 数据页 " + System.nanoTime(), Instant.parse("2026-10-14T13:00:00Z"));
    User a = user("1000");
    User b = user("1000");
    User c = user("1000");
    bet(a.id(), m, 0, "60");
    bet(b.id(), m, 0, "40");
    bet(c.id(), m, 1, "100");
    JsonNode before = anon("/api/public/board");
    assertThat(before.get("launched").asBoolean()).isFalse();
    assertThat(before.get("waitlistOpen").asBoolean()).isTrue();
    assertThat(before.get("slate").get("available").asBoolean()).isFalse();
    assertThat(before.get("slate").get("items")).isEmpty();
    assertThat(before.get("polymarket").get("enabled").asBoolean()).isFalse();
    assertThat(before.toString()).doesNotContain("@example.com");

    jdbc.update("INSERT INTO growth_launch (id, status, admin_id, reason, recipients, sent, created_at) VALUES (1, 'DONE', 1, 't', 0, 0, UTC_TIMESTAMP(3))");
    JsonNode after = anon("/api/public/board");
    assertThat(after.get("launched").asBoolean()).isTrue();
    assertThat(after.get("waitlistOpen").asBoolean()).isFalse();
    JsonNode item = null;
    for (JsonNode i : after.get("slate").get("items")) if (m.no().equals(i.get("publicNo").asString())) item = i;
    assertThat(item).as("今天带标记的市场").isNotNull();
    JsonNode pool = getJson("/api/markets/" + m.no(), null).get("pool");
    assertThat(item.get("poolTotal").asString()).isEqualTo(pool.get("total").asString()).isEqualTo("200.00");
    assertThat(item.get("bettors").asInt()).isEqualTo(3);
    for (int i = 0; i < 2; i++) {
      JsonNode o = item.get("options").get(i);
      JsonNode p = pool.get("options").get(i);
      assertThat(o.get("optionNo").asString()).isEqualTo(p.get("optionNo").asString());
      assertThat(o.get("bettors").asInt()).isEqualTo(p.get("bettors").asInt());
      assertThat(o.get("pct").asString()).isEqualTo(p.get("pct").asString().endsWith("%") ? p.get("pct").asString() : p.get("pct").asString() + "%");
    }
    assertThat(item.get("options").get(0).get("pct").asString()).isEqualTo("50.0%");
  }

  @Test
  void 搜索引擎版本_标题描述正文_站点地图_robots() throws Exception {
    try (StubHttp stub = new StubHttp()) {
      stub.handle(u -> StubHttp.Reply.ok(klines(day(), "64000")));
      props.setBinanceUrl(stub.url());
      board.refresh();
    }
    MockHttpServletResponse h = call(get("/api/public/seo/head").header("X-Fc-Page", "/data?lang=zh-CN"), null, null);
    assertThat(h.getContentAsString()).contains("<title>BTC 每小时涨跌和今日场次 | Quick Come</title>").contains("rel=\"canonical\" href=\"https://app.forecast.test/data?lang=zh-CN\"")
        .doesNotContain("noindex");
    String bodyZh = call(get("/api/public/seo/body").header("X-Fc-Page", "/data?lang=zh-CN"), null, null).getContentAsString();
    assertThat(bodyZh).contains("<h1>数据</h1>").contains("BTC 现价：64000.00 USDT").contains("过去 24 小时：15 小时收涨、9 小时收跌（涨 62.5%）").contains("即将上线");
    String en = call(get("/api/public/seo/body").header("X-Fc-Page", "/data"), null, null).getContentAsString();
    assertThat(en).contains("Last 24 hours: 15 closed up, 9 closed down (62.5% up)").doesNotContain("即将");
    assertThat(call(get("/api/public/sitemap.xml"), null, null).getContentAsString()).contains("<loc>https://app.forecast.test/data</loc>")
        .contains("https://app.forecast.test/data?lang=zh-TW");
    assertThat(call(get("/api/public/robots.txt"), null, null).getContentAsString()).contains("Disallow: /data/confirm").contains("Disallow: /data/unsubscribe");
    // 确认、退订页不收录
    assertThat(call(get("/api/public/seo/head").header("X-Fc-Page", "/data/confirm?t=x"), null, null).getContentAsString()).contains("noindex");
  }
}
