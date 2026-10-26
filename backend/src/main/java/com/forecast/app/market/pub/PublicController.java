package com.forecast.app.market.pub;

import com.forecast.app.market.core.ClientViews;
import com.forecast.app.market.core.MarketPublicService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 不登录也能看的接口（forecast S13，MF-4、MF-6；需求第 5.2 节 P11、P12、P14，NF-14）。登录检查在 {@code SessionFilter}：
 * {@code forecast.public-browse.enabled}（默认开）打开时这些接口不要求登录；关掉时 {@code /api/public/results}、{@code /weekly}、{@code /pages}
 * 和市场列表、详情一样要登录。{@code /api/public/seo/**}、{@code robots.txt}、{@code sitemap.xml} 总是公开（关掉时 robots 禁止收录）。
 * 返回的内容都不含客户的个人数据。
 */
@RestController
class PublicController {

  /** 站点地图里最多列几个市场（每个市场 4 个地址：三种语言 + x-default） */
  static final int SITEMAP_MARKETS = 10_000;

  private final MarketPublicService pub;
  private final PublicContent content;
  private final SeoPages seo;
  private final boolean publicBrowse;
  private final String clientUrl;

  PublicController(MarketPublicService pub, PublicContent content, SeoPages seo, @Value("${forecast.public-browse.enabled:true}") boolean publicBrowse,
      @Value("${forecast.client-url:}") String clientUrl) {
    this.pub = pub;
    this.content = content;
    this.seo = seo;
    this.publicBrowse = publicBrowse;
    this.clientUrl = clientUrl == null ? "" : clientUrl.strip().replaceAll("/+$", "");
  }

  /** 开奖记录（P12）：status（settled / voided / cancelled，可以逗号分隔）、category、from / to（结束时间）、分页 */
  @GetMapping("/api/public/results")
  ClientViews.Page<MarketPublicService.Result> results(@RequestParam(required = false) String status, @RequestParam(required = false) String category,
      @RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return pub.results(status, category, from, to, page, pageSize);
  }

  /** 周报列表：从本周往前一页 12 周 */
  @GetMapping("/api/public/weekly")
  MarketPublicService.Weeks weeks(@RequestParam(defaultValue = "1") int page) {
    return pub.weeks(page);
  }

  record WeekDetail(MarketPublicService.Week week, List<MarketPublicService.Result> markets) {}

  /** 一周的周报和这一周结束的市场；week 是 ISO 周（2026-W44） */
  @GetMapping("/api/public/weekly/{week}")
  WeekDetail week(@PathVariable String week) {
    return new WeekDetail(pub.week(week), pub.weekMarkets(week));
  }

  /** 说明页（P14）：怎么玩、怎么充、怎么提；lang 不传为英文 */
  @GetMapping("/api/public/pages/how-to-play")
  PublicContent.HowTo howTo(@RequestParam(required = false) String lang) {
    return content.howTo(lang);
  }

  // =====================================================================
  // 搜索引擎（NF-14、AC-85）：Nginx 的 SSI 子请求调用，原来的页面地址在请求头 X-Fc-Page（没有时用 page 参数）
  // =====================================================================

  @GetMapping(value = "/api/public/seo/head", produces = "text/html;charset=UTF-8")
  ResponseEntity<String> head(@RequestHeader(value = "X-Fc-Page", required = false) String pageHeader, @RequestParam(required = false) String page,
      HttpServletRequest req) {
    return html(seo.head(pageOf(pageHeader, page), base(req)));
  }

  @GetMapping(value = "/api/public/seo/body", produces = "text/html;charset=UTF-8")
  ResponseEntity<String> body(@RequestHeader(value = "X-Fc-Page", required = false) String pageHeader, @RequestParam(required = false) String page) {
    return html(seo.body(pageOf(pageHeader, page)));
  }

  /**
   * 页面是否存在（草稿、不存在的市场、不合法的周不存在）。给 Nginx 的 {@code auth_request} 用：存在 204；<b>不存在返回 403</b>
   * （auth_request 只认 2xx / 401 / 403，Nginx 再把 403 换成 404 页面，见 site.conf.template）。
   */
  @GetMapping("/api/public/seo/exists")
  ResponseEntity<Void> exists(@RequestHeader(value = "X-Fc-Page", required = false) String pageHeader, @RequestParam(required = false) String page) {
    return ResponseEntity.status(seo.exists(pageOf(pageHeader, page)) ? HttpStatus.NO_CONTENT : HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore()).build();
  }

  @GetMapping(value = "/api/public/robots.txt", produces = "text/plain;charset=UTF-8")
  ResponseEntity<String> robots(HttpServletRequest req) {
    StringBuilder sb = new StringBuilder("User-agent: *\n");
    if (!publicBrowse) {
      sb.append("Disallow: /\n");
    } else {
      // 登录后才能看的页面不收录；/assets 下的构建文件（脚本、样式）要留给搜索引擎渲染页面，只挡资产页本身。
      // S14：分享卡片的图片要让 X（Twitterbot 遵守 robots.txt）、Telegram 等取得到，比 Disallow: /api/ 更具体，放在前面
      sb.append("Allow: /api/public/cards/\n");
      for (String p : List.of("/assets$", "/assets/ledger", "/assets/withdraw", "/assets/whitelist", "/bets", "/me", "/login", "/register",
          "/forgot-password", "/unsubscribe", "/api/", "/admin/")) {
        sb.append("Disallow: ").append(p).append('\n');
      }
      sb.append("Allow: /\n\nSitemap: ").append(base(req)).append("/sitemap.xml\n");
    }
    return ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(MediaType.TEXT_PLAIN).body(sb.toString());
  }

  /** 站点地图：公开页面 + 每个不是草稿的市场 + 周报，每个地址带三种语言和 x-default 的互相标注 */
  @GetMapping(value = "/api/public/sitemap.xml", produces = "application/xml;charset=UTF-8")
  ResponseEntity<String> sitemap(HttpServletRequest req) {
    String b = base(req);
    StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\" xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">\n");
    if (publicBrowse) {
      for (String p : List.of("/markets", "/results", "/results/weekly", "/how-to-play")) url(sb, b, p, null);
      for (MarketPublicService.Week w : pub.weeks(1).items()) if (w.complete()) url(sb, b, "/results/weekly/" + w.week(), null);
      for (MarketPublicService.SitemapEntry e : pub.sitemap(SITEMAP_MARKETS)) url(sb, b, "/markets/" + e.publicNo(), e.lastModified());
      // S15：自动化程序的 SEO 页面（/insights/**，MF-11）
      for (com.forecast.app.market.api.PublicPageProvider.Entry e : seo.providedSitemap()) url(sb, b, e.path(), e.lastModified());
    }
    sb.append("</urlset>\n");
    return ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(MediaType.APPLICATION_XML).body(sb.toString());
  }

  private static void url(StringBuilder sb, String base, String path, String lastModified) {
    List<String> variants = new java.util.ArrayList<>();
    variants.add(base + path);
    for (String l : PublicContent.LANGS) variants.add(base + path + "?lang=" + l);
    for (String loc : variants) {
      sb.append("  <url><loc>").append(SeoPages.esc(loc)).append("</loc>");
      if (lastModified != null) sb.append("<lastmod>").append(lastModified, 0, Math.min(10, lastModified.length())).append("</lastmod>");
      for (String l : PublicContent.LANGS) {
        sb.append("<xhtml:link rel=\"alternate\" hreflang=\"").append(l).append("\" href=\"").append(SeoPages.esc(base + path + "?lang=" + l)).append("\"/>");
      }
      sb.append("<xhtml:link rel=\"alternate\" hreflang=\"x-default\" href=\"").append(SeoPages.esc(base + path)).append("\"/></url>\n");
    }
  }

  private static String pageOf(String header, String param) {
    String p = header != null && !header.isBlank() ? header : param;
    return p == null || p.length() > 2000 ? "/" : p;
  }

  /** 客户端地址：配置了 forecast.client-url 用它，否则按请求（Nginx 传来的 Host 和协议）拼 */
  private String base(HttpServletRequest req) {
    if (!clientUrl.isEmpty()) return clientUrl;
    String proto = req.getHeader("X-Forwarded-Proto");
    String scheme = proto == null || proto.isBlank() ? req.getScheme() : proto;
    String host = req.getHeader("Host");
    return scheme + "://" + (host == null || host.isBlank() ? req.getServerName() : host);
  }

  private static ResponseEntity<String> html(String s) {
    return ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8)).body(s);
  }
}
