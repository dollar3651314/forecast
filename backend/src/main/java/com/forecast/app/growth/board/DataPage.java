package com.forecast.app.growth.board;

import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.PublicPageProvider;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 数据页 {@code /data} 的搜索引擎版本（forecast S16，MF-17"可以被收录"、NF-14）：market 的 {@code SeoPages} 通过 {@link PublicPageProvider} 调用
 * （S16 分支上自己定义了一个同样作用的 {@code PublicPageView}，整合 4 时改用 S15 的这个接口，只留一个扩展点），
 * Nginx SSI 把标题、描述和正文嵌进客户端页面（和 S13 的公开页同一个做法）；站点地图列出 {@code /data}。正文的数字和页面上一样（{@link BoardService}），全部转义。
 */
@Component
class DataPage implements PublicPageProvider {

  static final String PATH = "/data";
  private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final BoardService board;
  private final GrowthTexts texts;

  DataPage(BoardService board, GrowthTexts texts) {
    this.board = board;
    this.texts = texts;
  }

  @Override
  public boolean handles(String path) {
    return PATH.equals(path) || (PATH + "/").equals(path);
  }

  /** 数据页一直存在（上线前是等候页，上线后是看板） */
  @Override
  public boolean exists(String path) {
    return true;
  }

  @Override
  public Meta meta(String path, String lang) {
    return new Meta(texts.t(lang, "board.title"), texts.t(lang, "board.description"), true);
  }

  @Override
  public String mainHtml(String path, String lang, String q) {
    BoardService.Board b = board.board();
    StringBuilder sb = new StringBuilder();
    sb.append("<h1>").append(esc(texts.t(lang, "board.h1"))).append("</h1>\n");
    sb.append("<p>").append(esc(texts.t(lang, "board.intro"))).append("</p>\n");
    sb.append("<section><h2>").append(esc(texts.t(lang, "board.btcTitle"))).append("</h2>\n");
    BoardService.Btc btc = b.btc();
    if (!btc.available()) {
      sb.append("<p>").append(esc(texts.t(lang, "board.btcNone"))).append("</p>\n");
    } else {
      sb.append("<p>").append(esc(texts.t(lang, "board.btcPrice", Map.of("price", btc.price())))).append("</p>\n");
      sb.append("<p>").append(esc(texts.t(lang, "board.btcHours", Map.of("hours", String.valueOf(btc.hours()), "up", String.valueOf(btc.up()), "down",
          String.valueOf(btc.down()), "upPct", btc.upPct())))).append("</p>\n");
      sb.append("<p>").append(esc(texts.t(lang, "board.btcRule"))).append("</p>\n");
      sb.append("<p>").append(esc(texts.t(lang, "board.btcSource", Map.of("time", HM.format(Instant.parse(btc.updatedAt())))))).append("</p>\n");
    }
    sb.append("</section>\n<section><h2>");
    if (!b.launched()) {
      sb.append(esc(texts.t(lang, "board.launchTitle"))).append("</h2>\n<p>").append(esc(texts.t(lang, "board.launchText"))).append("</p>\n</section>\n");
      return sb.toString();
    }
    sb.append(esc(texts.t(lang, "board.slateTitle"))).append("</h2>\n");
    BoardService.Slate s = b.slate();
    if (s.items().isEmpty()) {
      sb.append("<p>").append(esc(texts.t(lang, "board.slateNone")));
      if (s.next() != null) sb.append(' ').append(esc(texts.t(lang, "board.slateNext", Map.of("time", time(s.next())))));
      sb.append("</p>\n");
    } else {
      sb.append("<ul>\n");
      for (BoardService.Item it : s.items()) {
        sb.append("<li><a href=\"/markets/").append(esc(it.publicNo())).append(q).append("\">")
            .append(esc(texts.t(lang, "board.slateItem", Map.of("time", time(it), "title", pick(it.title(), lang))))).append("</a>");
        List<String> parts = it.options().stream()
            .map(o -> texts.t(lang, "board.slateOption", Map.of("label", pick(o.label(), lang), "pct", o.pct(), "bettors", String.valueOf(o.bettors())))).toList();
        if (!parts.isEmpty()) sb.append(" — ").append(esc(String.join("; ", parts)));
        sb.append("</li>\n");
      }
      sb.append("</ul>\n");
    }
    sb.append("</section>\n");
    return sb.toString();
  }

  @Override
  public List<Entry> sitemap() {
    return List.of(new Entry(PATH, null));
  }

  private static String time(BoardService.Item it) {
    String t = it.eventStartAt() != null ? it.eventStartAt() : it.closeAt();
    return t == null ? "—" : HM.format(Instant.parse(t));
  }

  private static String pick(I18nText t, String lang) {
    if (t == null) return "";
    String v = switch (lang) {
      case "zh-CN" -> t.zhCN();
      case "zh-TW" -> t.zhTW() == null || t.zhTW().isBlank() ? t.zhCN() : t.zhTW();
      default -> t.en();
    };
    return v == null || v.isBlank() ? (t.en() == null ? "" : t.en()) : v;
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
