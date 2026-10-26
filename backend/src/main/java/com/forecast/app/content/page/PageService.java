package com.forecast.app.content.page;

import com.forecast.app.common.error.BizException;
import com.forecast.app.content.check.Hant;
import com.forecast.app.content.core.ContentLog;
import com.forecast.app.content.core.ContentMapper;
import com.forecast.app.content.core.ContentRows;
import com.forecast.app.content.core.Settings;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketCategory;
import com.forecast.app.market.api.MarketPublicApi;
import com.forecast.app.resolve.api.MarketDataApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 网站上的 SEO 页面（forecast S15，MF-11；EQ-7：焦点战每场一页、BTC 统计一页、文章每周最多 2 篇、每天新增页面最多 5 个）。
 * <ul>
 *   <li><b>焦点战页</b> {@code /insights/focus-{市场编号}}：列入今日场次的足球、NBA 市场（焦点战由人上架时勾选"今日场次"，MQ-1），定时任务建页；
 *       数据部分（奖池、概率、人数、开赛时间）每次打开时实时取市场（客户端还随奖池推送更新），说明文字和赛前数据短文审核后才显示。</li>
 *   <li><b>BTC 每小时涨跌统计页</b> {@code /insights/btc-hourly}：过去 7 天的 1 小时 K 线统计（{@link BtcStats}），每天更新一次（也可以在后台手动刷新）。</li>
 *   <li><b>文章页</b> {@code /insights/article-…}：草稿箱里的文章审核发布时才建页（中文、英文分别发布，繁体按固定规则转换）。</li>
 * </ul>
 * 每天新增页面上限（{@code pages.dailyNew}，默认 5）：到了上限的焦点战页当天不建（第二天再建）、文章发布返回 409 {@code PAGE_LIMIT}。
 * 页面内容不含任何客户的个人数据。
 */
@Service
public class PageService {

  public static final String BTC_SLUG = "btc-hourly";
  public static final String FOCUS_PREFIX = "focus-";
  public static final String ARTICLE_PREFIX = "article-";
  static final Set<MarketCategory> SPORTS = Set.of(MarketCategory.FOOTBALL, MarketCategory.NBA);
  static final ObjectMapper JSON = new ObjectMapper();

  private final ContentMapper mapper;
  private final Settings settings;
  private final ContentLog contentLog;
  private final MarketPublicApi markets;
  private final MarketDataApi data;
  private final Clock clock;

  PageService(ContentMapper mapper, Settings settings, ContentLog contentLog, MarketPublicApi markets, MarketDataApi data, Clock clock) {
    this.mapper = mapper;
    this.settings = settings;
    this.contentLog = contentLog;
    this.markets = markets;
    this.data = data;
    this.clock = clock;
  }

  // =====================================================================
  // 建页
  // =====================================================================

  /** 今天还能不能新建页面 */
  public boolean canCreateToday() {
    return mapper.countPagesOn(today()) < settings.integer(Settings.PAGES_DAILY, 5);
  }

  /** 新建一页（受每天上限限制）；已经有同一个地址或同一个市场的页面时什么都不做。返回是否新建了 */
  boolean create(ContentRows.Page p) {
    if (!canCreateToday()) {
      contentLog.write("PAGE", p.kind, p.slug, null, "SKIPPED", "今天新增页面已到上限 " + settings.integer(Settings.PAGES_DAILY, 5) + " 个，明天再建", 0, null);
      return false;
    }
    LocalDateTime now = now();
    p.createdDay = today();
    p.createdAt = now;
    p.updatedAt = now;
    boolean ok = mapper.insertPageIgnore(p) == 1;
    if (ok) contentLog.write("PAGE", p.kind, p.slug, null, "OK", "新建页面", 0, null);
    return ok;
  }

  /** 焦点战页：未来 48 小时（和过去 6 小时）列入今日场次的足球、NBA 市场，没有页面的建一页。返回新建了几页 */
  public int ensureFocusPages() {
    Instant now = clock.instant();
    int created = 0;
    for (MarketPublicApi.Snapshot s : markets.slate(now.minus(Duration.ofHours(6)), now.plus(Duration.ofHours(42)))) {
      if (!SPORTS.contains(s.category()) || mapper.pageByMarket(s.publicNo()) != null) continue;
      ContentRows.Page p = new ContentRows.Page();
      p.slug = FOCUS_PREFIX + s.publicNo();
      p.kind = "FOCUS";
      p.marketNo = s.publicNo();
      I18nText t = s.title();
      p.titleEn = t.en();
      p.titleZhCn = t.zhCN();
      p.titleZhTw = t.zhTW() == null || t.zhTW().isBlank() ? Hant.convert(t.zhCN()) : t.zhTW();
      if (create(p)) created++;
      else if (!canCreateToday()) break;
    }
    return created;
  }

  /** BTC 统计页：没有就建（受上限），数据超过 maxAge 就刷新。返回现在的统计（取不到为空） */
  public Optional<BtcStats> ensureBtc(Duration maxAge) {
    ContentRows.Page p = mapper.page(BTC_SLUG);
    if (p == null) {
      ContentRows.Page n = new ContentRows.Page();
      n.slug = BTC_SLUG;
      n.kind = "BTC_STATS";
      n.titleEn = "BTC hourly up/down statistics";
      n.titleZhCn = "BTC 每小时涨跌统计";
      n.titleZhTw = "BTC 每小時漲跌統計";
      if (!create(n)) return Optional.empty();
      p = mapper.page(BTC_SLUG);
    }
    if (p.data != null && p.dataAt != null && p.dataAt.toInstant(ZoneOffset.UTC).plus(maxAge).isAfter(clock.instant())) return Optional.of(read(p.data));
    return refreshBtc(p);
  }

  /** 立即刷新 BTC 统计（外部调用，不在事务里） */
  public Optional<BtcStats> refreshBtc() {
    ContentRows.Page p = mapper.page(BTC_SLUG);
    if (p == null) return ensureBtc(Duration.ZERO);
    return refreshBtc(p);
  }

  private Optional<BtcStats> refreshBtc(ContentRows.Page p) {
    MarketDataApi.Hourly h = data.hourly("BTCUSDT", 168);
    if (!h.ok()) {
      contentLog.write("PAGE", "BTC_STATS", BTC_SLUG, null, "FAILED", "BTC K 线取不到（数据不可用）：" + h.error(), 0, null);
      return p.data == null ? Optional.empty() : Optional.of(read(p.data));
    }
    BtcStats s = BtcStats.of(h);
    mapper.updatePageData(p.id, JSON.writeValueAsString(s), LocalDateTime.ofInstant(h.fetchedAt().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC), now());
    contentLog.write("PAGE", "BTC_STATS", BTC_SLUG, null, "OK", "更新数据：" + s.source() + "，" + s.bars() + " 根", 0, null);
    return Optional.of(s);
  }

  /** 现有的 BTC 统计（不刷新） */
  public Optional<BtcStats> btcStats() {
    ContentRows.Page p = mapper.page(BTC_SLUG);
    return p == null || p.data == null ? Optional.empty() : Optional.of(read(p.data));
  }

  static BtcStats read(String json) {
    return JSON.readValue(json, BtcStats.class);
  }

  // =====================================================================
  // 审核发布写进页面
  // =====================================================================

  /** 页面说明（SEO_INTRO）发布：zh → 简体 + 转换的繁体，en → 英文 */
  public void publishIntro(String slug, String lang, String text) {
    ContentRows.Page p = mapper.page(slug);
    if (p == null) throw new BizException("NOT_FOUND", "页面不存在");
    mapper.updatePageIntro(p.id, lang, text, "en".equals(lang) ? null : Hant.convert(text), now());
  }

  /** 文章发布：第一次发布时建页（受每天新增页面上限）。返回页面地址 */
  public String publishArticle(long groupNo, String lang, String title, String body) {
    String slug = ARTICLE_PREFIX + Long.toString(groupNo, 36);
    ContentRows.Page p = mapper.page(slug);
    if (p == null) {
      ContentRows.Page n = new ContentRows.Page();
      n.slug = slug;
      n.kind = "ARTICLE";
      n.titleEn = "en".equals(lang) ? title : "";
      n.titleZhCn = "en".equals(lang) ? "" : title;
      n.titleZhTw = "en".equals(lang) ? "" : Hant.convert(title);
      n.bodyEn = "en".equals(lang) ? body : null;
      n.bodyZhCn = "en".equals(lang) ? null : body;
      n.bodyZhTw = "en".equals(lang) ? null : Hant.convert(body);
      if (!create(n)) throw new BizException("PAGE_LIMIT", "今天新增页面已到上限（" + settings.integer(Settings.PAGES_DAILY, 5) + " 个），明天再发布");
      return slug;
    }
    mapper.updateArticle(p.id, lang, title, "en".equals(lang) ? null : Hant.convert(title), body, "en".equals(lang) ? null : Hant.convert(body), now());
    return slug;
  }

  /** 后台：显示 / 隐藏页面 */
  public void setVisible(String slug, boolean visible) {
    ContentRows.Page p = mapper.page(slug);
    if (p == null) throw new BizException("NOT_FOUND", "页面不存在");
    mapper.setPageStatus(p.id, visible ? "VISIBLE" : "HIDDEN", now());
  }

  public ContentRows.Page page(String slug) {
    return mapper.page(slug);
  }

  public List<ContentRows.Page> pages(boolean visibleOnly, int limit) {
    return mapper.pages(visibleOnly, limit);
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
