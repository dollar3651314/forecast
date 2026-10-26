package com.forecast.app.content.page;

import com.forecast.app.market.api.MarketPublicApi;
import com.forecast.app.market.api.PublicPageProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 搜索引擎版本的 SEO 页面（forecast S15，MF-11、NF-14、AC-112：不运行脚本也能读到）：market 的 {@code SeoPages}（Nginx SSI）遇到 {@code /insights}、
 * {@code /insights/{slug}} 时问这里（{@link PublicPageProvider}）。不存在、隐藏、文章还没发布的返回 404（Nginx 的 auth_request）。
 * 所有文字都经过 HTML 转义。
 */
@Component
class InsightsProvider implements PublicPageProvider {

  static final Pattern ONE = Pattern.compile("^/insights/([^/]+)/?$");

  /** 页面上的固定文字（三种语言） */
  static final Map<String, Map<String, String>> L = Map.of(
      "en", Map.ofEntries(Map.entry("list", "Insights: data pages and articles"), Map.entry("listDesc", "Featured match data, BTC hourly statistics and articles."),
          Map.entry("pool", "Pool"), Map.entry("bettors", "Bettors"), Map.entry("closes", "Betting closes"), Map.entry("starts", "Starts"),
          Map.entry("probability", "Share of pool"), Map.entry("market", "Open the market"), Map.entry("preview", "Pre-match data note"),
          Map.entry("source", "Source"), Map.entry("up24", "Up hours in the last 24 hours"), Map.entry("up7d", "Up hours in the last 7 days"),
          Map.entry("hour", "Hour (UTC)"), Map.entry("upDays", "Up days"), Map.entry("lastClose", "Last hourly close"), Map.entry("change24", "24h change"),
          Map.entry("rule", "An hour counts as up when close ≥ open."), Map.entry("noData", "Data temporarily unavailable."),
          Map.entry("titleSuffix", "| Insights")),
      "zh-CN", Map.ofEntries(Map.entry("list", "数据和文章"), Map.entry("listDesc", "焦点战数据、BTC 每小时涨跌统计和文章。"), Map.entry("pool", "奖池"),
          Map.entry("bettors", "下注人数"), Map.entry("closes", "截止下注"), Map.entry("starts", "开始"), Map.entry("probability", "奖池占比"),
          Map.entry("market", "打开这个市场"), Map.entry("preview", "赛前数据"), Map.entry("source", "来源"), Map.entry("up24", "过去 24 小时上涨的小时"),
          Map.entry("up7d", "过去 7 天上涨的小时"), Map.entry("hour", "小时（UTC）"), Map.entry("upDays", "上涨的天数"), Map.entry("lastClose", "最近一小时收盘价"),
          Map.entry("change24", "24 小时涨跌幅"), Map.entry("rule", "收盘价 ≥ 开盘价算上涨。"), Map.entry("noData", "数据暂时取不到。"),
          Map.entry("titleSuffix", "| 数据和文章")),
      "zh-TW", Map.ofEntries(Map.entry("list", "數據和文章"), Map.entry("listDesc", "焦點戰數據、BTC 每小時漲跌統計和文章。"), Map.entry("pool", "獎池"),
          Map.entry("bettors", "下注人數"), Map.entry("closes", "截止下注"), Map.entry("starts", "開始"), Map.entry("probability", "獎池占比"),
          Map.entry("market", "打開這個市場"), Map.entry("preview", "賽前數據"), Map.entry("source", "來源"), Map.entry("up24", "過去 24 小時上漲的小時"),
          Map.entry("up7d", "過去 7 天上漲的小時"), Map.entry("hour", "小時（UTC）"), Map.entry("upDays", "上漲的天數"), Map.entry("lastClose", "最近一小時收盤價"),
          Map.entry("change24", "24 小時漲跌幅"), Map.entry("rule", "收盤價 ≥ 開盤價算上漲。"), Map.entry("noData", "數據暫時取不到。"),
          Map.entry("titleSuffix", "| 數據和文章")));

  private final Insights insights;

  InsightsProvider(Insights insights) {
    this.insights = insights;
  }

  static String t(String lang, String key) {
    return L.getOrDefault(lang, L.get("en")).getOrDefault(key, key);
  }

  @Override
  public boolean handles(String path) {
    return path.equals("/insights") || path.equals("/insights/") || ONE.matcher(path).matches();
  }

  @Override
  public boolean exists(String path) {
    Matcher m = ONE.matcher(path);
    if (!m.matches()) return true;
    return insights.view(m.group(1), "en").isPresent();
  }

  @Override
  public Meta meta(String path, String lang) {
    Matcher m = ONE.matcher(path);
    if (!m.matches()) return new Meta(t(lang, "list"), t(lang, "listDesc"), true);
    Optional<Insights.View> v = insights.view(m.group(1), lang);
    if (v.isEmpty()) return new Meta(t(lang, "list"), t(lang, "listDesc"), false);
    Insights.View x = v.get();
    String desc = x.intro() != null ? x.intro() : x.body() != null ? Insights.firstParagraph(x.body()) : describe(x, lang);
    return new Meta(x.title() + " " + t(lang, "titleSuffix"), desc == null ? t(lang, "listDesc") : Insights.cut(desc, 160), true);
  }

  /** 没有说明文字时用数据拼描述（每页都有本页独有的数据） */
  private static String describe(Insights.View x, String lang) {
    if (x.market() != null) {
      MarketPublicApi.Snapshot s = x.market();
      StringBuilder sb = new StringBuilder();
      for (MarketPublicApi.Option o : s.options()) sb.append(Insights.pick(o.label(), lang)).append(' ').append(pct(o.pct())).append(" · ");
      return sb.append(t(lang, "pool")).append(' ').append(s.total()).append(" USDT · ").append(t(lang, "bettors")).append(' ').append(s.bettors()).toString();
    }
    if (x.btc() != null) return t(lang, "up7d") + ": " + x.btc().up7d() + " / " + x.btc().bars() + " (" + x.btc().up7dPct() + "%)";
    return null;
  }

  @Override
  public String mainHtml(String path, String lang, String q) {
    Matcher m = ONE.matcher(path);
    if (!m.matches()) {
      StringBuilder sb = new StringBuilder("<h1>").append(esc(t(lang, "list"))).append("</h1>\n<ul>\n");
      for (Insights.Item i : insights.list(lang)) {
        sb.append("<li><a href=\"/insights/").append(esc(i.slug())).append(q).append("\">").append(esc(i.title())).append("</a>");
        if (i.summary() != null) sb.append(" — ").append(esc(i.summary()));
        sb.append("</li>\n");
      }
      return sb.append("</ul>\n").toString();
    }
    Optional<Insights.View> v = insights.view(m.group(1), lang);
    if (v.isEmpty()) return "<h1>404</h1>\n";
    Insights.View x = v.get();
    StringBuilder sb = new StringBuilder("<article>\n<h1>").append(esc(x.title())).append("</h1>\n");
    if (x.intro() != null) sb.append("<p>").append(esc(x.intro())).append("</p>\n");
    if (x.market() != null) market(sb, x.market(), lang, q);
    if (x.preview() != null) {
      sb.append("<section><h2>").append(esc(t(lang, "preview"))).append(": ").append(esc(x.preview().title())).append("</h2>\n");
      paragraphs(sb, x.preview().body());
      sb.append("</section>\n");
    }
    if ("BTC_STATS".equals(x.kind())) btc(sb, x.btc(), lang);
    if (x.body() != null) paragraphs(sb, x.body());
    return sb.append("</article>\n").toString();
  }

  private static void market(StringBuilder sb, MarketPublicApi.Snapshot s, String lang, String q) {
    sb.append("<dl>\n");
    dl(sb, t(lang, "starts"), Insights.time(s.eventStartAt()));
    dl(sb, t(lang, "closes"), Insights.time(s.closeAt()));
    dl(sb, t(lang, "pool"), s.total() + " USDT");
    dl(sb, t(lang, "bettors"), String.valueOf(s.bettors()));
    sb.append("</dl>\n<table>\n<thead><tr><th></th><th>").append(esc(t(lang, "probability"))).append("</th><th>").append(esc(t(lang, "pool")))
        .append("</th><th>").append(esc(t(lang, "bettors"))).append("</th></tr></thead>\n<tbody>\n");
    for (MarketPublicApi.Option o : s.options()) {
      sb.append("<tr><td>").append(esc(Insights.pick(o.label(), lang))).append("</td><td>").append(esc(pct(o.pct()))).append("</td><td>").append(esc(o.amount()))
          .append(" USDT</td><td>").append(o.bettors()).append("</td></tr>\n");
    }
    sb.append("</tbody>\n</table>\n<p><a href=\"/markets/").append(s.publicNo()).append(q).append("\">").append(esc(t(lang, "market"))).append("</a></p>\n");
    sb.append("<p>").append(esc(t(lang, "source"))).append(": ").append(esc(Insights.time(s.at()))).append("</p>\n");
  }

  private static void btc(StringBuilder sb, BtcStats b, String lang) {
    if (b == null) {
      sb.append("<p>").append(esc(t(lang, "noData"))).append("</p>\n");
      return;
    }
    sb.append("<dl>\n");
    dl(sb, t(lang, "up24"), b.up24() + " / " + (b.up24() + b.down24()) + " (" + b.up24Pct() + "%)");
    dl(sb, t(lang, "up7d"), b.up7d() + " / " + b.bars() + " (" + b.up7dPct() + "%)");
    dl(sb, t(lang, "lastClose"), b.lastClose() + " USDT");
    dl(sb, t(lang, "change24"), b.change24Pct() + "%");
    sb.append("</dl>\n<table>\n<thead><tr><th>").append(esc(t(lang, "hour"))).append("</th><th>").append(esc(t(lang, "upDays"))).append("</th><th>%</th></tr></thead>\n<tbody>\n");
    for (BtcStats.HourRow r : b.byHour()) {
      sb.append("<tr><td>").append(String.format("%02d:00", r.hour())).append("</td><td>").append(r.up()).append(" / ").append(r.total()).append("</td><td>")
          .append(esc(r.pct())).append("</td></tr>\n");
    }
    sb.append("</tbody>\n</table>\n<p>").append(esc(t(lang, "rule"))).append(' ').append(esc(t(lang, "source"))).append(": ").append(esc(b.source()))
        .append(", ").append(esc(Insights.time(Instant.parse(b.fetchedAt())))).append("</p>\n");
  }

  /** 正文：空行分段，"## " 开头的是小标题 */
  static void paragraphs(StringBuilder sb, String body) {
    for (String para : body.split("\\n\\s*\\n")) {
      String s = para.strip();
      if (s.isEmpty()) continue;
      if (s.startsWith("## ")) sb.append("<h2>").append(esc(s.substring(3).strip())).append("</h2>\n");
      else sb.append("<p>").append(esc(s).replace("\n", "<br>")).append("</p>\n");
    }
  }

  @Override
  public List<Entry> sitemap() {
    List<Entry> out = new ArrayList<>();
    out.add(new Entry("/insights", null));
    for (Insights.Item i : insights.list("en")) out.add(new Entry("/insights/" + i.slug(), i.updatedAt()));
    return out;
  }

  private static String pct(String p) {
    return p == null || p.equals("—") ? "—" : p.endsWith("%") ? p : p + "%";
  }

  private static void dl(StringBuilder sb, String k, String v) {
    sb.append("<dt>").append(esc(k)).append("</dt><dd>").append(esc(v)).append("</dd>\n");
  }

  static String esc(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '"' -> sb.append("&quot;");
        case '\'' -> sb.append("&#39;");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }
}
