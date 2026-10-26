package com.forecast.app.market.card;

import com.forecast.app.account.api.ShareCodeApi;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.ratelimit.UserWriteLimited;
import com.forecast.app.common.web.CurrentUser;
import com.forecast.app.market.pub.PublicContent;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分享卡片的接口（forecast S14，MF-5；需求第 5.2 节"分享卡片和挑战卡"、AC-86、AC-87）。
 * <ul>
 *   <li>{@code GET /api/public/cards/{market|result|countdown}/{市场编号}.png?lang=}、{@code GET /api/public/cards/weekly/{周}.png?lang=}：
 *       公开的卡片图片（1200 × 630 PNG，Open Graph 和下载用）；{@code GET /api/public/cards/s/{凭证}.png?lang=}：客户生成的挑战卡、开奖卡。
 *       不登录也能取（和市场详情一样跟 {@code forecast.public-browse.enabled}）；带 ETag，内容没变时 304。</li>
 *   <li>{@code GET /api/public/share?kind=market|countdown|weekly&no=|week=&lang=}：分享用的链接、图片地址、标题和文字。登录了链接带分享人
 *       {@code ref}（S15 起是分享人的随机分享码 {@link ShareCodeApi}，不再是递增的客户编号），没登录不带；都带 {@code src=share}、{@code cmp=card-<种类>}（来源统计 MF-1，<b>不给分享人任何奖励</b>，MQ-5）。</li>
 *   <li>{@code POST /api/me/share} {@code {kind: challenge|win, marketNo, optionNo, showAmount, lang}}：生成挑战卡 / 押中的开奖卡（要登录，按客户限流）。</li>
 * </ul>
 * 分享链接用短链接 {@code /m/{编号}}（Nginx 301 到市场详情，参数保留）；{@code cl} 是卡片的语言（给 Telegram、X 的链接预览选语言，客户端不用它，
 * 不会改变打开链接的人的界面语言）；{@code card} 是凭证（链接预览显示这张卡）。
 */
@RestController
class CardController {

  /** 已结束的市场、过完的周：图片内容不会再变 */
  private static final CacheControl SETTLED = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();
  /** 进行中的市场：数字一直在变，缓存 1 分钟 */
  private static final CacheControl LIVE = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

  private final CardService cards;
  private final ShareCodeApi shareCodes;
  private final String clientUrl;

  CardController(CardService cards, ShareCodeApi shareCodes, @Value("${forecast.client-url:}") String clientUrl) {
    this.cards = cards;
    this.shareCodes = shareCodes;
    this.clientUrl = clientUrl == null ? "" : clientUrl.strip().replaceAll("/+$", "");
  }

  // =====================================================================
  // 图片
  // =====================================================================

  @GetMapping("/api/public/cards/{kind}/{key}.png")
  ResponseEntity<byte[]> image(@PathVariable String kind, @PathVariable String key, @RequestParam(required = false) String lang,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    Optional<CardModel> m;
    if ("s".equals(kind)) m = cards.tokenCard(key, lang);
    else if ("weekly".equals(kind)) m = cards.weeklyCard(key, lang);
    else m = marketNo(key).flatMap(no -> cards.publicCard(kind, no, lang));
    return m.map(x -> png(x, ifNoneMatch)).orElseGet(() -> ResponseEntity.notFound().build());
  }

  private ResponseEntity<byte[]> png(CardModel m, String ifNoneMatch) {
    CardService.Image img;
    try {
      img = cards.image(m);
    } catch (CardService.BusyException e) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "2").build();
    }
    CacheControl cc = img.settled() ? SETTLED : LIVE;
    if (ifNoneMatch != null && ifNoneMatch.contains(img.etag())) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(img.etag()).cacheControl(cc).build();
    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(cc).eTag(img.etag()).body(img.png());
  }

  // =====================================================================
  // 分享信息
  // =====================================================================

  /**
   * 分享用的信息。
   *
   * @param kind 实际的卡片种类（市场已结束时 market 会变成 result）
   * @param url 分享链接（带 src、cmp、ref、cl、card）
   * @param imageUrl 卡片图片
   * @param title 标题（市场题目 / 周报标题）
   * @param text 卡片上的文字（和图片同一份，系统分享面板、复制时附带）
   */
  record ShareInfo(String kind, String url, String imageUrl, String title, String text) {}

  @GetMapping("/api/public/share")
  ShareInfo share(@RequestParam String kind, @RequestParam(required = false) String no, @RequestParam(required = false) String week,
      @RequestParam(required = false) String lang, HttpServletRequest req) {
    String l = PublicContent.lang(lang);
    String ref = CurrentUser.optional().map(shareCodes::codeOf).orElse(null);
    String b = base(req);
    if ("weekly".equals(kind)) {
      CardModel m = cards.weeklyCard(week, l).orElseThrow(CardController::notFound);
      String w = enc(week);
      return new ShareInfo("weekly", b + "/results/weekly/" + w + query("weekly", ref, l, null), b + "/api/public/cards/weekly/" + w + ".png?lang=" + l, m.title(),
          m.text());
    }
    if (!"market".equals(kind) && !"countdown".equals(kind)) throw new BizException("VALIDATION_FAILED", "kind 只能是 market、countdown、weekly");
    long n = marketNo(no).orElseThrow(CardController::notFound);
    CardModel m = cards.publicCard(kind, n, l).orElseThrow(CardController::notFound);
    String path = "result".equals(m.kind()) ? "result" : kind;
    return new ShareInfo(m.kind(), b + "/m/" + n + query(m.kind(), ref, l, null), b + "/api/public/cards/" + path + "/" + n + ".png?lang=" + l, m.title(), m.text());
  }

  /** 客户生成卡片的请求 */
  record ShareRequest(String kind, String marketNo, String optionNo, Boolean showAmount, String lang) {}

  @PostMapping("/api/me/share")
  @UserWriteLimited
  ShareInfo create(@RequestBody ShareRequest r, HttpServletRequest req) {
    long userId = CurrentUser.id();
    if (r == null || r.kind() == null) throw new BizException("VALIDATION_FAILED", "缺少 kind");
    long no = marketNo(r.marketNo()).orElseThrow(CardController::notFound);
    String l = PublicContent.lang(r.lang());
    CardService.Share s = switch (r.kind()) {
      case "challenge" -> {
        if (r.optionNo() == null || r.optionNo().isBlank()) throw new BizException("VALIDATION_FAILED", "缺少 optionNo");
        yield cards.challenge(userId, no, r.optionNo().strip());
      }
      case "win" -> cards.win(userId, no, Boolean.TRUE.equals(r.showAmount()));
      default -> throw new BizException("VALIDATION_FAILED", "kind 只能是 challenge、win");
    };
    CardModel m = cards.tokenCard(s.token(), l).orElseThrow(CardController::notFound);
    String b = base(req);
    return new ShareInfo(m.kind(), b + "/m/" + no + query(m.kind(), shareCodes.codeOf(userId), l, s.token()), b + "/api/public/cards/s/" + s.token() + ".png?lang=" + l, m.title(),
        m.text());
  }

  // =====================================================================

  /** 链接参数：src=share、cmp=card-<种类>、ref=分享人的分享码（登录了才有，S15）、cl=卡片语言、card=凭证 */
  static String query(String kind, String ref, String lang, String token) {
    Map<String, String> q = new LinkedHashMap<>();
    q.put("src", "share");
    q.put("cmp", "card-" + kind);
    if (ref != null) q.put("ref", ref);
    q.put("cl", lang);
    if (token != null) q.put("card", token);
    StringBuilder sb = new StringBuilder();
    q.forEach((k, v) -> sb.append(sb.isEmpty() ? '?' : '&').append(k).append('=').append(enc(v)));
    return sb.toString();
  }

  private static String enc(String s) {
    return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
  }

  private static Optional<Long> marketNo(String s) {
    if (s == null || !s.matches("\\d{1,19}")) return Optional.empty();
    try {
      return Optional.of(Long.parseLong(s));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static BizException notFound() {
    return new BizException("NOT_FOUND", "卡片不存在");
  }

  /** 客户端地址：配置了 forecast.client-url 用它，否则按请求（Nginx 传来的 Host 和协议）拼（和 market.pub.PublicController 一样） */
  private String base(HttpServletRequest req) {
    if (!clientUrl.isEmpty()) return clientUrl;
    String proto = req.getHeader("X-Forwarded-Proto");
    String scheme = proto == null || proto.isBlank() ? req.getScheme() : proto;
    String host = req.getHeader("Host");
    return scheme + "://" + (host == null || host.isBlank() ? req.getServerName() : host);
  }
}
