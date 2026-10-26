package com.forecast.app.content.page;

import com.forecast.app.content.check.Hant;
import com.forecast.app.content.core.ContentMapper;
import com.forecast.app.content.core.ContentRows;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketPublicApi;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 公开的 SEO 页面的内容（forecast S15，MF-11、AC-112、NF-14）：给客户端（JSON，{@code InsightsController}）和搜索引擎（HTML，{@code InsightsProvider}）
 * 同一份。只用公开数据：市场快照（不登录的版本）、BTC 统计、审核发布过的文字；<b>没有审核的说明文字不显示</b>（页面只显示数据），没有任何客户的个人数据。
 */
@Component
public class Insights {

  static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,79}");
  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final ContentMapper mapper;
  private final MarketPublicApi markets;

  Insights(ContentMapper mapper, MarketPublicApi markets) {
    this.mapper = mapper;
    this.markets = markets;
  }

  /** 列表里的一项 */
  public record Item(String slug, String kind, String title, String summary, String marketNo, String updatedAt) {}

  /** 赛前数据短文（审核发布过的） */
  public record Preview(String title, String body, String publishedAt) {}

  /**
   * 一页。
   *
   * @param intro 审核发布过的说明文字（这种语言没有时为空：页面只显示数据）
   * @param body 文章正文（文章页）
   * @param market 焦点战页的市场快照（实时）
   * @param btc BTC 统计页的数据
   * @param fallbackLang 这种语言还没有发布、显示的是另一种语言时为那种语言
   */
  public record View(String slug, String kind, String lang, String title, String intro, String body, String marketNo, MarketPublicApi.Snapshot market,
      BtcStats btc, Preview preview, String updatedAt, String fallbackLang) {}

  public List<Item> list(String lang) {
    String l = lang(lang);
    List<Item> out = new ArrayList<>();
    for (ContentRows.Page p : mapper.pages(true, 200)) {
      if (!visible(p)) continue;
      String title = title(p, l);
      String summary = intro(p, l);
      if (summary == null && "ARTICLE".equals(p.kind)) summary = firstParagraph(body(p, l));
      out.add(new Item(p.slug, p.kind, title, summary == null ? null : cut(summary, 160), p.marketNo == null ? null : String.valueOf(p.marketNo),
          p.updatedAt.toInstant(ZoneOffset.UTC).toString()));
    }
    return out;
  }

  public Optional<View> view(String slug, String lang) {
    if (slug == null || !SLUG.matcher(slug).matches()) return Optional.empty();
    ContentRows.Page p = mapper.page(slug);
    if (p == null || !visible(p)) return Optional.empty();
    String l = lang(lang);
    MarketPublicApi.Snapshot m = p.marketNo == null ? null : markets.snapshot(p.marketNo).orElse(null);
    if ("FOCUS".equals(p.kind) && m == null) return Optional.empty();
    BtcStats btc = "BTC_STATS".equals(p.kind) && p.data != null ? PageService.read(p.data) : null;
    String body = null;
    String fallback = null;
    if ("ARTICLE".equals(p.kind)) {
      body = body(p, l);
      if (body == null) {
        fallback = "en".equals(l) ? "zh-CN" : "en";
        body = body(p, fallback);
      }
    }
    String title = title(p, fallback != null ? fallback : l);
    Preview preview = null;
    if (p.marketNo != null) {
      String want = "en".equals(l) ? "en" : "zh";
      for (ContentRows.Draft d : mapper.publishedFor(p.marketNo, "PREVIEW")) {
        if (!want.equals(d.lang)) continue;
        boolean tw = "zh-TW".equals(l);
        preview = new Preview(tw ? Hant.convert(d.title) : d.title, tw ? Hant.convert(d.body) : d.body, d.publishedAt.toInstant(ZoneOffset.UTC).toString());
        break;
      }
    }
    return Optional.of(new View(p.slug, p.kind, l, title, intro(p, l), body, p.marketNo == null ? null : String.valueOf(p.marketNo), m, btc, preview,
        p.updatedAt.toInstant(ZoneOffset.UTC).toString(), fallback));
  }

  boolean visible(ContentRows.Page p) {
    if (!"VISIBLE".equals(p.status)) return false;
    if ("ARTICLE".equals(p.kind)) return p.bodyEn != null || p.bodyZhCn != null;
    return true;
  }

  static String lang(String l) {
    return "zh-CN".equals(l) || "zh-TW".equals(l) ? l : "en";
  }

  static String title(ContentRows.Page p, String l) {
    String t = switch (l) {
      case "zh-CN" -> p.titleZhCn;
      case "zh-TW" -> p.titleZhTw;
      default -> p.titleEn;
    };
    if (t == null || t.isBlank()) t = p.titleEn != null && !p.titleEn.isBlank() ? p.titleEn : p.titleZhCn;
    return t;
  }

  static String intro(ContentRows.Page p, String l) {
    return switch (l) {
      case "zh-CN" -> p.introZhCn;
      case "zh-TW" -> p.introZhTw;
      default -> p.introEn;
    };
  }

  static String body(ContentRows.Page p, String l) {
    return switch (l) {
      case "zh-CN" -> p.bodyZhCn;
      case "zh-TW" -> p.bodyZhTw;
      default -> p.bodyEn;
    };
  }

  static String pick(I18nText t, String l) {
    String v = switch (l) {
      case "zh-CN" -> t.zhCN();
      case "zh-TW" -> t.zhTW() == null || t.zhTW().isBlank() ? t.zhCN() : t.zhTW();
      default -> t.en();
    };
    return v == null || v.isBlank() ? t.en() : v;
  }

  static String firstParagraph(String body) {
    if (body == null) return null;
    for (String para : body.split("\\n\\s*\\n")) {
      String s = para.strip();
      if (!s.isEmpty() && !s.startsWith("## ")) return s;
    }
    return null;
  }

  static String cut(String s, int n) {
    return s.length() <= n ? s : s.substring(0, n - 1) + "…";
  }

  static String time(Instant t) {
    return t == null ? "—" : TIME.format(t);
  }
}
