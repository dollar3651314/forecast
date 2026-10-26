package com.forecast.app.content;

import com.forecast.app.content.ai.GeminiClient;
import com.forecast.app.content.api.ContentAdminApi;
import com.forecast.app.content.core.ContentProperties;
import com.forecast.app.content.core.Settings;
import com.forecast.app.content.draft.DraftService;
import com.forecast.app.content.job.ContentTasks;
import com.forecast.app.content.page.PageService;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.resolve.source.ResolveProperties;
import com.forecast.app.resolve.source.SourceGateway;
import com.forecast.app.settle.SettleTestSupport;
import com.forecast.app.support.StubHttp;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 自动化程序测试的公共部分（forecast S15）：真实 MySQL、Redis（C37），测试数据各自新建（C80）。
 * <ul>
 *   <li>Gemini 一律用本机的 HTTP 替身（{@link #GEMINI}，<b>不连真实的 Gemini</b>）：默认按提示里的"数据"清单拼文字（只用清单里的数字）；
 *       {@link #mode} 切换成 wrongNumber / banned / badJson / 500 / 429 / 401 / slow。</li>
 *   <li>币安 1 小时 K 线也是替身（{@link #MARKET_DATA}）：开盘 60000 + 小时序号 × 10，每 3 小时里第 0 个跌，其余涨（和 e2e 替身同一个规律）。</li>
 *   <li>设置（每天新增页面、每周文章、调用上限……）每个测试前放宽、测试后改回默认；当天的 Gemini 用量清零。</li>
 * </ul>
 */
public abstract class ContentTestSupport extends SettleTestSupport {

  protected static final StubHttp GEMINI = new StubHttp();
  protected static final StubHttp MARKET_DATA = new StubHttp();
  protected static final ObjectMapper JSON = new ObjectMapper();
  protected static volatile String mode = "ok";

  @Autowired protected ContentProperties contentProps;
  @Autowired protected ContentTasks tasks;
  @Autowired protected DraftService drafts;
  @Autowired protected ContentAdminApi content;
  @Autowired protected GeminiClient gemini;
  @Autowired protected PageService pages;
  @Autowired protected ResolveProperties resolveProps;
  @Autowired protected SourceGateway gateway;

  @BeforeEach
  void contentSetup() {
    mode = "ok";
    GEMINI.clear();
    GEMINI.handleWithBody(ContentTestSupport::reply);
    MARKET_DATA.clear();
    MARKET_DATA.handle(ContentTestSupport::hourly);
    contentProps.getGemini().setBaseUrl(GEMINI.url());
    contentProps.getGemini().setApiKey("ci_only_test_gemini");
    gemini.breaker().reset();
    resolveProps.getSources().get("binance").setBaseUrl(MARKET_DATA.url() + "/binance");
    resolveProps.getSources().get("okx").setBaseUrl(MARKET_DATA.url() + "/okx");
    gateway.resetBreakers();
    setting(Settings.PAGES_DAILY, "50");
    setting(Settings.ARTICLES_WEEKLY, "20");
    setting(Settings.STOPPED, "0");
    setting(Settings.AI_DAILY_LIMIT, "10000");
    setting(Settings.AI_CONTENT_SHARE, "80");
    setting(Settings.AI_DRAFTS, "1");
    setting(Settings.PACK, "1");
    jdbc.update("DELETE FROM content_ai_day");
  }

  @AfterEach
  void contentRestore() {
    contentProps.getGemini().setBaseUrl("http://127.0.0.1:9");
    contentProps.getGemini().setApiKey("ci_only_test_gemini");
    gemini.breaker().reset();
    resolveProps.getSources().get("binance").setBaseUrl("http://127.0.0.1:9");
    resolveProps.getSources().get("okx").setBaseUrl("http://127.0.0.1:9");
    gateway.resetBreakers();
    setting(Settings.PAGES_DAILY, "5");
    setting(Settings.ARTICLES_WEEKLY, "2");
    setting(Settings.STOPPED, "0");
    setting(Settings.AI_DAILY_LIMIT, "300");
    setting(Settings.AI_CONTENT_SHARE, "80");
    setting(Settings.AI_DRAFTS, "1");
    setting(Settings.PACK, "1");
  }

  protected void setting(String key, String value) {
    jdbc.update("UPDATE content_setting SET value = ? WHERE setting_key = ?", value, key);
  }

  // ---------------------------------------------------------------------
  // 替身
  // ---------------------------------------------------------------------

  /** Gemini 替身的回复（按 {@link #mode}） */
  static StubHttp.Reply reply(URI uri, String body) {
    switch (mode) {
      case "500" -> {
        return StubHttp.Reply.status(500, "{\"error\":{\"code\":500,\"message\":\"stub 500\"}}");
      }
      case "429" -> {
        return StubHttp.Reply.status(429, "{\"error\":{\"code\":429,\"message\":\"stub 429\"}}");
      }
      case "401" -> {
        return StubHttp.Reply.status(401, "{\"error\":{\"code\":401,\"message\":\"API key not valid\"}}");
      }
      case "drop" -> {
        return StubHttp.Reply.drop();
      }
      default -> {
      }
    }
    String prompt = JSON.readTree(body).path("contents").path(0).path("parts").path(0).path("text").asString("");
    String text = "badJson".equals(mode) ? "not json" : generated(prompt);
    ObjectNode resp = JSON.createObjectNode();
    ObjectNode cand = resp.putArray("candidates").addObject();
    cand.putObject("content").putArray("parts").addObject().put("text", text);
    cand.put("finishReason", "STOP");
    resp.putObject("usageMetadata").put("promptTokenCount", 3000).put("candidatesTokenCount", 800);
    String json = JSON.writeValueAsString(resp);
    return "slow".equals(mode) ? StubHttp.Reply.slow(1_500, json) : StubHttp.Reply.ok(json);
  }

  /** 按提示里的数据清单拼一段（只用清单里的数字） */
  static String generated(String prompt) {
    boolean en = prompt.startsWith("You write");
    List<String> lines = new ArrayList<>();
    boolean data = false;
    for (String line : prompt.split("\n")) {
      if (line.startsWith("DATA") || line.startsWith("数据（")) {
        data = true;
        continue;
      }
      if (data && line.startsWith("- ")) {
        String s = line.substring(2);
        int cut = en ? s.lastIndexOf(" (") : s.lastIndexOf('（');
        lines.add(cut > 0 ? s.substring(0, cut) : s);
      }
      if (lines.size() >= 4) break;
    }
    String b = String.join(en ? ". " : "；", lines) + (en ? "." : "。");
    if ("wrongNumber".equals(mode)) b += en ? " Another 987654 people are watching." : "另有 987654 人在看。";
    if ("banned".equals(mode)) b += en ? " It is risk-free." : "稳赚。";
    ObjectNode out = JSON.createObjectNode();
    out.put("title", en ? "Data snapshot" : "数据速览");
    out.put("body", b);
    return JSON.writeValueAsString(out);
  }

  /** 收到的全部提示 */
  protected static List<String> prompts() {
    List<String> out = new ArrayList<>();
    for (String b : GEMINI.bodies()) {
      JsonNode n = JSON.readTree(b);
      out.add(n.path("contents").path(0).path("parts").path(0).path("text").asString(""));
    }
    return out;
  }

  /** 币安、OKX 1 小时 K 线替身：开盘 60000 + 小时序号 × 10；小时序号 % 3 == 0 跌 25，否则涨 35 */
  static StubHttp.Reply hourly(URI uri) {
    Map<String, String> q = query(uri);
    if (uri.getPath().startsWith("/binance")) {
      long start = Long.parseLong(q.get("startTime"));
      long end = Long.parseLong(q.get("endTime"));
      StringBuilder sb = new StringBuilder("[");
      for (long t = start; t <= end; t += 3_600_000) {
        BigDecimal[] b = bar(t / 3_600_000);
        if (sb.length() > 1) sb.append(',');
        sb.append("[").append(t).append(",\"").append(b[0]).append("\",\"").append(b[0].max(b[1])).append("\",\"").append(b[0].min(b[1])).append("\",\"")
            .append(b[1]).append("\",\"1.0\",").append(t + 3_599_999).append(",\"1.0\",1,\"0.5\",\"0.5\",\"0\"]");
      }
      return StubHttp.Reply.ok(sb.append("]").toString());
    }
    return StubHttp.Reply.status(500, "{}");
  }

  static BigDecimal[] bar(long hourIndex) {
    BigDecimal open = BigDecimal.valueOf(60_000 + (hourIndex % 1000) * 10);
    BigDecimal close = hourIndex % 3 == 0 ? open.subtract(BigDecimal.valueOf(25)) : open.add(BigDecimal.valueOf(35));
    return new BigDecimal[] {open, close};
  }

  static Map<String, String> query(URI u) {
    Map<String, String> m = new LinkedHashMap<>();
    if (u.getRawQuery() == null) return m;
    for (String kv : u.getRawQuery().split("&")) {
      int i = kv.indexOf('=');
      if (i > 0) m.put(kv.substring(0, i), kv.substring(i + 1));
    }
    return m;
  }

  // ---------------------------------------------------------------------
  // 市场
  // ---------------------------------------------------------------------

  static I18nText tx(String s) {
    return new I18nText(s + " en", s + " 简", s + " 繁");
  }

  /** 一个上架的足球市场（列入今日场次）：开赛 = start，截止 = start − 5 分钟 */
  protected Market sportsMarket(String title, Instant start, boolean slate) {
    Instant now = Instant.now(clock);
    List<MarketAdminApi.OptionInput> opts = List.of(new MarketAdminApi.OptionInput(new I18nText("Over 2.5", "大 2.5", "大 2.5"), tx("总进球大于 2.5")),
        new MarketAdminApi.OptionInput(new I18nText("Under 2.5", "小 2.5", "小 2.5"), tx("总进球小于 2.5")));
    MarketAdminApi.DraftInput in = new MarketAdminApi.DraftInput("FOOTBALL", tx(title), tx("规则"), "AUTO", "football-data.org", "TheSportsDB", null,
        now.minus(Duration.ofMinutes(1)).toString(), start.minus(Duration.ofMinutes(5)).toString(), start.toString(), start.plus(Duration.ofHours(2)).toString(),
        null, null, null, opts, slate);
    MarketAdminApi.Detail d = admin.createDraft(in, 1);
    admin.publish(Long.parseLong(d.publicNo()), true, 1);
    long id = jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, Long.parseLong(d.publicNo()));
    return new Market(id, d.publicNo(), d.options().stream().map(MarketAdminApi.OptionView::optionNo).toList());
  }

  /** 一个上架的 BTC 1 小时涨跌市场（列入今日场次） */
  protected Market btcMarket(Instant windowStart) {
    Instant now = Instant.now(clock);
    List<MarketAdminApi.OptionInput> opts = List.of(new MarketAdminApi.OptionInput(new I18nText("Up", "涨", "漲"), tx("收盘 ≥ 开盘")),
        new MarketAdminApi.OptionInput(new I18nText("Down", "跌", "跌"), tx("收盘 < 开盘")));
    MarketAdminApi.DraftInput in = new MarketAdminApi.DraftInput("CRYPTO_1H", tx("BTC 涨还是跌"), tx("规则"), "AUTO", "Binance", "OKX", null,
        now.minus(Duration.ofMinutes(1)).toString(), windowStart.minusSeconds(30).toString(), windowStart.toString(), windowStart.plus(Duration.ofHours(1)).toString(),
        null, null, null, opts, true);
    MarketAdminApi.Detail d = admin.createDraft(in, 1);
    admin.publish(Long.parseLong(d.publicNo()), true, 1);
    long id = jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, Long.parseLong(d.publicNo()));
    return new Market(id, d.publicNo(), d.options().stream().map(MarketAdminApi.OptionView::optionNo).toList());
  }

  protected List<ContentAdminApi.Draft> generate(String kind, String marketNo, String pageSlug, String topic) {
    return content.generate(new ContentAdminApi.GenerateInput(kind, marketNo, pageSlug, topic, null), 1);
  }

  /** 生成以后（后端测试里起草是同步的）重新读一遍两条 */
  protected List<ContentAdminApi.Draft> group(List<ContentAdminApi.Draft> started) {
    return content.group(Long.parseLong(started.get(0).draftNo()));
  }

  protected ContentAdminApi.Draft lang(List<ContentAdminApi.Draft> g, String lang) {
    return g.stream().filter(d -> lang.equals(d.lang())).findFirst().orElseThrow();
  }

  protected int logs(String action, String ref) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM content_log WHERE action = ? AND ref = ?", Integer.class, action, ref);
  }
}
