package com.forecast.app.market.pub;

import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.core.ClientViews;
import com.forecast.app.market.core.MarketPublicService;
import com.forecast.app.market.core.MarketService;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 服务器给搜索引擎输出的公开页面（forecast S13，MF-4、NF-14、AC-85）。客户端是浏览器里渲染的单页应用，搜索引擎不运行脚本时什么都读不到；
 * 所以部署时 Nginx 用 SSI 把这里的两段 HTML 嵌进客户端的 index.html（{@code deploy/nginx/site.conf.template}）：
 * <ul>
 *   <li>{@link #head}：{@code <title>}、描述、canonical、三种语言互相标注（hreflang）、Open Graph；</li>
 *   <li>{@link #body}：页面的正文（题目、选项、概率、截止时间或结果、奖池……），放在 {@code #root} 里；浏览器运行脚本后单页应用把它换掉
 *       （有脚本的浏览器一开始就隐藏它，见 {@code web/client/index.html}），所以登录后的体验不变。</li>
 * </ul>
 * 公开的页面：{@code /}、{@code /markets}、{@code /markets/{编号}}、{@code /results}、{@code /results/weekly}、{@code /results/weekly/{周}}、
 * {@code /how-to-play}。三种语言用 {@code ?lang=en|zh-CN|zh-TW} 区分地址，不带 lang 的是 x-default（英文）。别的路径（登录后才能看的页面）只给品牌标题和 noindex。
 * <p>只用公开数据：没有任何客户的个人数据（详情按"没登录"取，列表的奖池本来就不带个人数据）。所有文字都经过 HTML 转义。
 */
@Component
public class SeoPages {

  private static final Logger log = LoggerFactory.getLogger(SeoPages.class);
  private static final Pattern MARKET = Pattern.compile("^/markets/([^/]+)/?$");
  private static final Pattern WEEK = Pattern.compile("^/results/weekly/([^/]+)/?$");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
  /** 列表页正文里最多列几个市场 / 开奖记录 */
  static final int LIST_MAX = 24;

  private final MarketService markets;
  private final MarketPublicService pub;
  private final PublicContent content;
  private final ObjectMapper json;
  private final Clock clock;

  SeoPages(MarketService markets, MarketPublicService pub, PublicContent content, ObjectMapper json, Clock clock) {
    this.markets = markets;
    this.pub = pub;
    this.content = content;
    this.json = json;
    this.clock = clock;
  }

  /** 页面地址拆成路径和语言（只认 lang 参数，其余参数例如 src、ref 不影响内容） */
  record Page(String path, String lang, boolean explicitLang) {

    static Page of(String uri) {
      String u = uri == null || uri.isBlank() ? "/" : uri.strip();
      String path = u;
      String query = "";
      int q = u.indexOf('?');
      if (q >= 0) {
        path = u.substring(0, q);
        query = u.substring(q + 1);
      }
      try {
        path = URI.create("http://x" + (path.startsWith("/") ? path : "/" + path)).getPath();
      } catch (IllegalArgumentException e) {
        path = "/";
      }
      if (path == null || path.isEmpty() || path.equals("/index.html")) path = "/";
      String lang = null;
      for (String kv : query.split("&")) {
        if (kv.startsWith("lang=")) lang = kv.substring(5);
      }
      return new Page(path, PublicContent.lang(lang), lang != null && !lang.isBlank());
    }
  }

  /** 这个页面是否存在（草稿、不存在的市场，不合法的周：否） */
  public boolean exists(String uri) {
    Page p = Page.of(uri);
    Matcher m = MARKET.matcher(p.path());
    if (m.matches()) return marketNo(m.group(1)).map(pub::visible).orElse(false);
    Matcher w = WEEK.matcher(p.path());
    if (w.matches()) {
      try {
        pub.week(w.group(1));
        return true;
      } catch (RuntimeException e) {
        return false;
      }
    }
    return true;
  }

  private static Optional<Long> marketNo(String s) {
    if (!s.matches("\\d{1,19}")) return Optional.empty();
    try {
      return Optional.of(Long.parseLong(s));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /** 一个页面的标题、描述，以及是否允许收录 */
  record Meta(String title, String description, boolean index) {}

  Meta meta(Page p) {
    String l = p.lang();
    String path = p.path();
    if (path.equals("/") || path.equals("/markets")) return new Meta(content.text(l, "site.title"), content.text(l, "site.description"), true);
    Matcher m = MARKET.matcher(path);
    if (m.matches()) {
      Optional<ClientViews.Detail> d = marketNo(m.group(1)).flatMap(no -> markets.clientDetail(no, null));
      if (d.isEmpty()) return new Meta(content.text(l, "market.notFound") + " " + content.text(l, "market.titleSuffix"), content.text(l, "market.notFound"), false);
      return new Meta(pick(d.get().title(), l) + " " + content.text(l, "market.titleSuffix"), marketDescription(d.get(), l), true);
    }
    if (path.equals("/results")) return new Meta(content.text(l, "results.title"), content.text(l, "results.description"), true);
    if (path.equals("/results/weekly")) return new Meta(content.text(l, "weekly.listTitle"), content.text(l, "weekly.listDescription"), true);
    Matcher w = WEEK.matcher(path);
    if (w.matches()) {
      try {
        MarketPublicService.Week wk = pub.week(w.group(1));
        Map<String, String> v = weekVars(wk);
        return new Meta(content.text(l, "weekly.title", v), content.text(l, "weekly.description", v), true);
      } catch (RuntimeException e) {
        return new Meta(content.text(l, "site.title"), content.text(l, "site.description"), false);
      }
    }
    if (path.equals("/how-to-play")) return new Meta(content.text(l, "howto.title"), content.text(l, "howto.description"), true);
    // 登录后才能看的页面（资产、我的下注、我的……）和登录注册页：只给品牌标题，不收录
    return new Meta(content.brand(), content.text(l, "site.description"), false);
  }

  /** 市场的描述：各选项的概率、奖池、截止时间；已结算的写结果 */
  private String marketDescription(ClientViews.Detail d, String l) {
    StringBuilder sb = new StringBuilder();
    JsonNode pool = poolOf(d.pool());
    String result = resultLabel(d, l);
    if (result != null) sb.append(content.text(l, "market.result")).append(": ").append(result).append(" · ");
    else {
      for (ClientViews.OptionDetail o : d.options()) {
        String pct = pct(pool, o.optionNo());
        sb.append(pick(o.label(), l)).append(' ').append(pct).append(" · ");
      }
    }
    sb.append(content.text(l, "market.pool")).append(' ').append(text(pool, "total", "0.00")).append(" USDT · ");
    sb.append(content.text(l, "status." + statusKey(d))).append(" · ");
    sb.append(content.text(l, "market.closes")).append(' ').append(time(d.closeAt()));
    return sb.toString();
  }

  /** 头部（{@code <head>} 里的部分） */
  public String head(String uri, String base) {
    Page p = Page.of(uri);
    Meta meta;
    try {
      meta = meta(p);
    } catch (RuntimeException e) {
      log.warn("公开页面 {} 的标题生成失败，用品牌标题", p.path(), e);
      meta = new Meta(content.brand(), "", false);
    }
    String b = base == null ? "" : base.replaceAll("/+$", "");
    String self = b + p.path() + (p.explicitLang() ? "?lang=" + p.lang() : "");
    StringBuilder h = new StringBuilder();
    h.append("<title>").append(esc(meta.title())).append("</title>\n");
    h.append("<meta name=\"description\" content=\"").append(esc(meta.description())).append("\">\n");
    if (!meta.index()) {
      h.append("<meta name=\"robots\" content=\"noindex\">\n");
      return h.toString();
    }
    h.append("<link rel=\"canonical\" href=\"").append(esc(self)).append("\">\n");
    for (String l : PublicContent.LANGS) {
      h.append("<link rel=\"alternate\" hreflang=\"").append(l).append("\" href=\"").append(esc(b + p.path() + "?lang=" + l)).append("\">\n");
    }
    h.append("<link rel=\"alternate\" hreflang=\"x-default\" href=\"").append(esc(b + p.path())).append("\">\n");
    h.append("<meta property=\"og:type\" content=\"website\">\n");
    h.append("<meta property=\"og:site_name\" content=\"").append(esc(content.brand())).append("\">\n");
    h.append("<meta property=\"og:title\" content=\"").append(esc(meta.title())).append("\">\n");
    h.append("<meta property=\"og:description\" content=\"").append(esc(meta.description())).append("\">\n");
    h.append("<meta property=\"og:url\" content=\"").append(esc(self)).append("\">\n");
    h.append("<meta property=\"og:locale\" content=\"").append(p.lang().replace('-', '_').replace("en", "en_US")).append("\">\n");
    h.append("<meta name=\"twitter:card\" content=\"summary\">\n");
    return h.toString();
  }

  /** 正文（{@code #root} 里的部分）；没有公开内容的页面返回空 */
  public String body(String uri) {
    Page p = Page.of(uri);
    try {
      String main = main(p);
      if (main == null) return "";
      String l = p.lang();
      String q = p.explicitLang() ? "?lang=" + l : "";
      StringBuilder sb = new StringBuilder();
      sb.append("<div class=\"seo-static\" lang=\"").append(l).append("\">\n<nav>");
      sb.append("<a href=\"/markets").append(q).append("\">").append(esc(content.brand())).append("</a> · ");
      sb.append("<a href=\"/markets").append(q).append("\">").append(esc(content.text(l, "markets.h1"))).append("</a> · ");
      sb.append("<a href=\"/results").append(q).append("\">").append(esc(content.text(l, "results.h1"))).append("</a> · ");
      sb.append("<a href=\"/results/weekly").append(q).append("\">").append(esc(content.text(l, "results.weekly"))).append("</a> · ");
      sb.append("<a href=\"/how-to-play").append(q).append("\">").append(esc(content.text(l, "howto.h1"))).append("</a>");
      sb.append("</nav>\n<main>\n").append(main).append("</main>\n</div>\n");
      return sb.toString();
    } catch (RuntimeException e) {
      log.warn("公开页面 {} 的正文生成失败，不输出", p.path(), e);
      return "";
    }
  }

  private String main(Page p) {
    String l = p.lang();
    String path = p.path();
    String q = p.explicitLang() ? "?lang=" + l : "";
    if (path.equals("/") || path.equals("/markets")) return marketsMain(l, q);
    Matcher m = MARKET.matcher(path);
    if (m.matches()) {
      Optional<ClientViews.Detail> d = marketNo(m.group(1)).flatMap(no -> markets.clientDetail(no, null));
      return d.isEmpty() ? "<h1>" + esc(content.text(l, "market.notFound")) + "</h1>\n" : marketMain(d.get(), l);
    }
    if (path.equals("/results")) return resultsMain(l, q);
    if (path.equals("/results/weekly")) return weeksMain(l, q);
    Matcher w = WEEK.matcher(path);
    if (w.matches()) return weekMain(w.group(1), l, q);
    if (path.equals("/how-to-play")) return howToMain(l);
    return null;
  }

  private String marketsMain(String l, String q) {
    StringBuilder sb = new StringBuilder();
    sb.append("<h1>").append(esc(content.text(l, "markets.h1"))).append("</h1>\n");
    MarketPublicService.Today today = pub.today(null, null);
    sb.append("<section><h2>").append(esc(content.text(l, "markets.today"))).append(" (UTC)</h2>\n");
    if (today.items().isEmpty()) {
      sb.append("<p>").append(esc(content.text(l, "markets.noToday")));
      if (today.next() != null) sb.append(' ').append(esc(content.text(l, "markets.next", Map.of("time", time(today.next().eventStartAt())))));
      sb.append("</p>\n");
    } else {
      sb.append("<ul>\n");
      for (ClientViews.Card c : today.items()) sb.append(cardItem(c, l, q));
      sb.append("</ul>\n");
    }
    sb.append("</section>\n<section><h2>").append(esc(content.text(l, "markets.more"))).append("</h2>\n<ul>\n");
    for (ClientViews.Card c : markets.clientList("active", null, "closeAt", null, 1, LIST_MAX).items()) sb.append(cardItem(c, l, q));
    sb.append("</ul>\n</section>\n");
    return sb.toString();
  }

  private String cardItem(ClientViews.Card c, String l, String q) {
    JsonNode pool = poolOf(c.pool());
    StringBuilder sb = new StringBuilder("<li><a href=\"/markets/").append(c.publicNo()).append(q).append("\">").append(esc(pick(c.title(), l))).append("</a> — ");
    for (ClientViews.OptionCard o : c.options()) sb.append(esc(pick(o.label(), l))).append(' ').append(esc(pct(pool, o.optionNo()))).append("; ");
    sb.append(esc(content.text(l, "market.pool"))).append(' ').append(esc(text(pool, "total", "0.00"))).append(" USDT; ");
    sb.append(esc(content.text(l, "market.closes"))).append(' ').append(esc(time(c.closeAt()))).append("</li>\n");
    return sb.toString();
  }

  private String marketMain(ClientViews.Detail d, String l) {
    JsonNode pool = poolOf(d.pool());
    StringBuilder sb = new StringBuilder();
    sb.append("<h1>").append(esc(pick(d.title(), l))).append("</h1>\n");
    sb.append("<p>").append(esc(content.text(l, "status." + statusKey(d)))).append("</p>\n<dl>\n");
    dl(sb, content.text(l, "market.opens"), time(d.openAt()));
    dl(sb, content.text(l, "market.closes"), time(d.closeAt()));
    dl(sb, content.text(l, "market.event"), time(d.eventStartAt()));
    dl(sb, content.text(l, "market.pool"), text(pool, "total", "0.00") + " USDT");
    dl(sb, content.text(l, "market.bettors"), text(pool, "bettors", "0"));
    dl(sb, content.text(l, "market.feeRate"), d.feeRate() + "%");
    String result = resultLabel(d, l);
    if (result != null) dl(sb, content.text(l, "market.result"), result);
    if (d.cancelReason() != null) dl(sb, content.text(l, "market.cancelReason"), reason(d.cancelReason(), l));
    if (d.voidReason() != null) dl(sb, content.text(l, "market.cancelReason"), reason(d.voidReason(), l));
    JsonNode settle = d.settle() == null ? null : json.valueToTree(d.settle());
    if (settle != null) {
      if ("payout".equals(settle.path("kind").asString())) {
        dl(sb, content.text(l, "market.payout"), settle.path("payoutTotal").asString() + " USDT");
        dl(sb, content.text(l, "market.fee"), settle.path("feeTotal").asString() + " USDT");
        dl(sb, content.text(l, "market.dust"), settle.path("dust").asString() + " USDT");
      } else {
        dl(sb, content.text(l, "market.refund"), settle.path("payoutTotal").asString() + " USDT");
      }
    }
    JsonNode res = d.resolution() == null ? null : json.valueToTree(d.resolution());
    if (res != null) {
      if (res.hasNonNull("resolvedBy")) dl(sb, content.text(l, "market.resolvedBy"), res.path("resolvedBy").asString());
      if (res.hasNonNull("source")) dl(sb, content.text(l, "market.source"), res.path("source").asString());
      for (JsonNode u : res.path("evidenceUrls")) dl(sb, content.text(l, "market.evidence"), u.asString());
    }
    sb.append("</dl>\n<table>\n<thead><tr><th></th><th>").append(esc(content.text(l, "market.probability"))).append("</th><th>")
        .append(esc(content.text(l, "market.pool"))).append("</th></tr></thead>\n<tbody>\n");
    for (ClientViews.OptionDetail o : d.options()) {
      JsonNode op = option(pool, o.optionNo());
      sb.append("<tr><td>").append(esc(pick(o.label(), l))).append("</td><td>").append(esc(pct(pool, o.optionNo()))).append("</td><td>")
          .append(esc(op == null ? "0.00" : op.path("amount").asString("0.00"))).append(" USDT</td></tr>\n");
    }
    sb.append("</tbody>\n</table>\n");
    String rules = pick(d.rules(), l);
    if (!rules.isBlank()) {
      sb.append("<h2>").append(esc(content.text(l, "market.rules"))).append("</h2>\n<p>").append(esc(rules).replace("\n", "<br>")).append("</p>\n");
    }
    return sb.toString();
  }

  private String resultsMain(String l, String q) {
    StringBuilder sb = new StringBuilder("<h1>").append(esc(content.text(l, "results.h1"))).append("</h1>\n");
    List<MarketPublicService.Result> rs = pub.results(null, null, null, null, 1, LIST_MAX).items();
    if (rs.isEmpty()) return sb.append("<p>").append(esc(content.text(l, "results.empty"))).append("</p>\n").toString();
    sb.append("<ul>\n");
    for (MarketPublicService.Result r : rs) {
      sb.append("<li><a href=\"/markets/").append(r.publicNo()).append(q).append("\">").append(esc(pick(r.title(), l))).append("</a> — ")
          .append(esc(content.text(l, "status." + r.status())));
      String win = r.winningOptionNo() == null ? null : r.options().stream().filter(o -> o.optionNo().equals(r.winningOptionNo())).findFirst()
          .map(o -> pick(o.label(), l)).orElse(null);
      if (win != null) sb.append("; ").append(esc(content.text(l, "market.result"))).append(": ").append(esc(win));
      JsonNode s = r.settle() == null ? null : json.valueToTree(r.settle());
      if (s != null) {
        sb.append("; ").append(esc(content.text(l, "market.pool"))).append(' ').append(esc(s.path("poolTotal").asString())).append(" USDT");
        if ("payout".equals(s.path("kind").asString())) {
          sb.append("; ").append(esc(content.text(l, "market.payout"))).append(' ').append(esc(s.path("payoutTotal").asString())).append(" USDT; ")
              .append(esc(content.text(l, "market.fee"))).append(' ').append(esc(s.path("feeTotal").asString())).append(" USDT");
        }
      }
      sb.append("; ").append(esc(time(r.endedAt()))).append("</li>\n");
    }
    return sb.append("</ul>\n").toString();
  }

  private String weeksMain(String l, String q) {
    StringBuilder sb = new StringBuilder("<h1>").append(esc(content.text(l, "results.weekly"))).append("</h1>\n<ul>\n");
    for (MarketPublicService.Week w : pub.weeks(1).items()) {
      Map<String, String> v = weekVars(w);
      sb.append("<li><a href=\"/results/weekly/").append(w.week()).append(q).append("\">").append(esc(w.week())).append("</a> — ")
          .append(esc(content.text(l, "weekly.description", v))).append(w.complete() ? "" : " (" + esc(content.text(l, "weekly.inProgress")) + ")").append("</li>\n");
    }
    return sb.append("</ul>\n").toString();
  }

  private String weekMain(String week, String l, String q) {
    MarketPublicService.Week w;
    try {
      w = pub.week(week);
    } catch (RuntimeException e) {
      return null;
    }
    Map<String, String> v = weekVars(w);
    StringBuilder sb = new StringBuilder("<h1>").append(esc(content.text(l, "weekly.h1", v))).append("</h1>\n<dl>\n");
    dl(sb, content.text(l, "weekly.markets"), String.valueOf(w.markets()));
    dl(sb, content.text(l, "weekly.settled"), String.valueOf(w.settled()));
    dl(sb, content.text(l, "weekly.voided"), String.valueOf(w.voided() + w.cancelled()));
    dl(sb, content.text(l, "weekly.pool"), w.poolTotal() + " USDT");
    dl(sb, content.text(l, "weekly.payout"), w.payoutTotal() + " USDT");
    dl(sb, content.text(l, "weekly.fee"), w.feeTotal() + " USDT");
    dl(sb, content.text(l, "weekly.refund"), w.refundTotal() + " USDT");
    sb.append("</dl>\n<ul>\n");
    for (MarketPublicService.Reason r : w.reasons()) {
      sb.append("<li>").append(esc(content.text(l, "status." + r.status()))).append(": ").append(esc(r.reason() == null ? "—" : reason(r.reason(), l)))
          .append(" × ").append(r.count()).append("</li>\n");
    }
    sb.append("</ul>\n<ul>\n");
    for (MarketPublicService.Result r : pub.weekMarkets(week)) {
      sb.append("<li><a href=\"/markets/").append(r.publicNo()).append(q).append("\">").append(esc(pick(r.title(), l))).append("</a> — ")
          .append(esc(content.text(l, "status." + r.status()))).append("</li>\n");
    }
    return sb.append("</ul>\n").toString();
  }

  private String howToMain(String l) {
    PublicContent.HowTo h = content.howTo(l);
    StringBuilder sb = new StringBuilder("<h1>").append(esc(h.h1())).append("</h1>\n<p>").append(esc(h.intro())).append("</p>\n");
    for (PublicContent.Section s : h.sections()) {
      sb.append("<section id=\"").append(esc(s.id())).append("\"><h2>").append(esc(s.heading())).append("</h2>\n<ol>\n");
      for (String i : s.items()) sb.append("<li>").append(esc(i)).append("</li>\n");
      sb.append("</ol>\n</section>\n");
    }
    return sb.toString();
  }

  // ---------- 小工具 ----------

  private Map<String, String> weekVars(MarketPublicService.Week w) {
    return Map.of("week", w.week(), "markets", String.valueOf(w.markets()), "settled", String.valueOf(w.settled()), "voided",
        String.valueOf(w.voided() + w.cancelled()), "pool", w.poolTotal(), "payout", w.payoutTotal(), "fee", w.feeTotal());
  }

  /** 没到开放时间的已上架市场显示"未开放"（和客户端一致） */
  private String statusKey(ClientViews.Detail d) {
    if ("listed".equals(d.status()) && d.openAt() != null && Instant.parse(d.openAt()).isAfter(clock.instant())) return "upcoming";
    return d.status();
  }

  private String resultLabel(ClientViews.Detail d, String l) {
    if (d.winningOptionNo() == null) return null;
    return d.options().stream().filter(o -> o.optionNo().equals(d.winningOptionNo())).findFirst().map(o -> pick(o.label(), l)).orElse(null);
  }

  /** 作废原因：系统代码翻译，管理员写的原样 */
  private String reason(String r, String l) {
    String t = content.text(l, "reasons." + r);
    return t.equals("reasons." + r) ? r : t;
  }

  private JsonNode poolOf(Object pool) {
    return pool == null ? null : json.valueToTree(pool);
  }

  private static JsonNode option(JsonNode pool, String optionNo) {
    if (pool == null) return null;
    for (JsonNode o : pool.path("options")) if (optionNo.equals(o.path("optionNo").asString())) return o;
    return null;
  }

  private static String pct(JsonNode pool, String optionNo) {
    JsonNode o = option(pool, optionNo);
    if (o == null || !o.hasNonNull("pct")) return "—";
    String p = o.path("pct").asString();
    return p.isBlank() || p.equals("—") ? "—" : p.endsWith("%") ? p : p + "%";
  }

  private static String text(JsonNode n, String field, String fallback) {
    if (n == null || !n.hasNonNull(field)) return fallback;
    return n.path(field).asString(fallback);
  }

  static String pick(I18nText t, String lang) {
    if (t == null) return "";
    String v = switch (lang) {
      case "zh-CN" -> t.zhCN();
      case "zh-TW" -> t.zhTW() == null || t.zhTW().isBlank() ? t.zhCN() : t.zhTW();
      default -> t.en();
    };
    return v == null || v.isBlank() ? (t.en() == null ? "" : t.en()) : v;
  }

  static String time(String iso) {
    if (iso == null || iso.isBlank()) return "—";
    try {
      return TIME.format(Instant.parse(iso));
    } catch (RuntimeException e) {
      return iso;
    }
  }

  private static void dl(StringBuilder sb, String k, String v) {
    sb.append("<dt>").append(esc(k)).append("</dt><dd>").append(esc(v)).append("</dd>\n");
  }

  /** HTML 转义（文字和属性值都用它） */
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
