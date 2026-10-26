package com.forecast.app.content.job;

import com.forecast.app.content.ai.AiAlerts;
import com.forecast.app.content.ai.GeminiClient;
import com.forecast.app.content.api.ContentAdminApi;
import com.forecast.app.content.core.ContentLog;
import com.forecast.app.content.core.ContentMapper;
import com.forecast.app.content.core.ContentProperties;
import com.forecast.app.content.core.ContentRows;
import com.forecast.app.content.core.Settings;
import com.forecast.app.content.draft.DraftService;
import com.forecast.app.content.event.CandidateService;
import com.forecast.app.content.event.EconService;
import com.forecast.app.content.page.PageService;
import com.forecast.app.market.api.MarketCategory;
import com.forecast.app.market.api.MarketPublicApi;
import com.forecast.app.notification.api.StaffAlertApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 自动化程序的定时工作（forecast S15）：每分钟一轮（{@link ContentJob}，拿租约 {@code content.job}；e2e 下 {@code /api/e2e/content/tick}）。
 * 每一步单独 try，一步出错不影响下一步，<b>也不影响下注、结算等任何主业务</b>（NF-15：这里只写 content_* 表、经 {@code MarketDraftApi} 建草稿）。
 * <ol>
 *   <li>草稿过期（赛前分析到开赛、帖子 24 小时）；起草中断的标成失败；</li>
 *   <li>经济日历：发布前 7 天生成市场草稿；</li>
 *   <li>焦点战页、BTC 统计页（每天更新一次数据），受每天新增页面上限；</li>
 *   <li>每日草稿包（00:00 UTC 以后的第一轮，一天一次）：今天的场次逐场起草赛前分析 / 行情短文和 X、Telegram 帖子（Gemini 能用时），然后给 content:publish
 *       的人发一封汇总邮件（待审核、失败、经济日历草稿、焦点战建议、用量）；</li>
 *   <li>本月按付费档估算的费用到了提醒线，发一次告警。</li>
 * </ol>
 */
@Service
public class ContentTasks {

  private static final Logger log = LoggerFactory.getLogger(ContentTasks.class);
  static final Set<MarketCategory> SPORTS = Set.of(MarketCategory.FOOTBALL, MarketCategory.NBA);
  static final Set<MarketCategory> CRYPTO = Set.of(MarketCategory.CRYPTO_15M, MarketCategory.CRYPTO_1H);

  private final ContentProperties props;
  private final DraftService drafts;
  private final EconService econ;
  private final PageService pages;
  private final CandidateService candidates;
  private final MarketPublicApi markets;
  private final ContentMapper mapper;
  private final Settings settings;
  private final GeminiClient gemini;
  private final AiAlerts alerts;
  private final StaffAlertApi staff;
  private final ContentLog contentLog;
  private final ContentAdminApi admin;
  private final Clock clock;
  private final TransactionTemplate tx;

  ContentTasks(ContentProperties props, DraftService drafts, EconService econ, PageService pages, CandidateService candidates, MarketPublicApi markets,
      ContentMapper mapper, Settings settings, GeminiClient gemini, AiAlerts alerts, StaffAlertApi staff, ContentLog contentLog, ContentAdminApi admin, Clock clock,
      PlatformTransactionManager tm) {
    this.props = props;
    this.drafts = drafts;
    this.econ = econ;
    this.pages = pages;
    this.candidates = candidates;
    this.markets = markets;
    this.mapper = mapper;
    this.settings = settings;
    this.gemini = gemini;
    this.alerts = alerts;
    this.staff = staff;
    this.contentLog = contentLog;
    this.admin = admin;
    this.clock = clock;
    this.tx = new TransactionTemplate(tm);
  }

  /** 一轮的结果（e2e、测试看） */
  public record Round(int expired, int econDrafted, int focusPages, boolean btcRefreshed, boolean packed) {}

  public Round runOnce() {
    if (!props.isEnabled()) return new Round(0, 0, 0, false, false);
    int expired = step("过期", drafts::expire, 0);
    int econDrafted = step("经济日历", econ::draftDue, 0);
    int focus = step("焦点战页", pages::ensureFocusPages, 0);
    boolean btc = step("BTC 统计页", this::btcDaily, false);
    boolean packed = step("每日草稿包", this::packIfDue, false);
    step("费用提醒", this::costAlert, false);
    return new Round(expired, econDrafted, focus, btc, packed);
  }

  private <T> T step(String what, java.util.function.Supplier<T> s, T fallback) {
    try {
      return s.get();
    } catch (RuntimeException e) {
      log.warn("自动化程序：{} 这一步出错（不影响别的步骤）", what, e);
      contentLog.write("JOB", null, what, null, "FAILED", String.valueOf(e.getMessage()), 0, null);
      return fallback;
    }
  }

  /** BTC 统计页：今天（UTC）还没更新过就更新 */
  boolean btcDaily() {
    Instant now = clock.instant();
    Instant todayStart = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    ContentRows.Page p = pages.page(PageService.BTC_SLUG);
    boolean stale = p == null || p.dataAt == null || p.dataAt.toInstant(ZoneOffset.UTC).isBefore(todayStart);
    if (!stale) return false;
    return pages.ensureBtc(Duration.between(todayStart, now)).isPresent();
  }

  /** 每日草稿包：今天（UTC）还没做过就做 */
  boolean packIfDue() {
    LocalDateTime todayStart = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).atStartOfDay();
    if (mapper.countLogsSince("DIGEST", todayStart) > 0) return false;
    pack();
    return true;
  }

  /** 做一次草稿包（e2e 可以直接调） */
  public Map<String, String> pack() {
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    Instant from = today.atStartOfDay().toInstant(ZoneOffset.UTC);
    int groups = 0;
    String skipped = null;
    if (settings.flag(Settings.PACK)) {
      var no = drafts.aiUnavailable();
      if (no.isPresent()) skipped = no.get();
      else {
        for (MarketPublicApi.Snapshot s : markets.slate(from, from.plus(Duration.ofDays(1)))) {
          List<String> kinds = new ArrayList<>();
          if (SPORTS.contains(s.category()) && s.eventStartAt() != null && s.eventStartAt().isAfter(now)) kinds.add("PREVIEW");
          if (CRYPTO.contains(s.category())) kinds.add("BRIEF");
          kinds.add("POST_X");
          kinds.add("POST_TG");
          for (String k : kinds) if (drafts.generateSystem(k, s.publicNo())) groups++;
        }
      }
    } else {
      skipped = "每日草稿包已关闭";
    }
    // 汇总邮件
    LocalDateTime since = LocalDateTime.ofInstant(now.minus(Duration.ofHours(24)), ZoneOffset.UTC);
    ContentRows.AiDay d = mapper.aiDay(today);
    String focus = "—";
    try {
      List<ContentAdminApi.Suggestion> sg = candidates.candidates().focus();
      if (!sg.isEmpty()) focus = String.join("；", sg.stream().map(x -> x.home() + " vs " + x.away() + "（" + x.kickoffAt() + "）").toList());
    } catch (RuntimeException e) {
      focus = "赛程暂时取不到";
    }
    long econDrafts = mapper.countLogsSince("ECON_DRAFT", since);
    Map<String, String> p = new LinkedHashMap<>();
    p.put("day", today.toString());
    p.put("pending", String.valueOf(mapper.countDrafts("PENDING", null, null)));
    p.put("failed", String.valueOf(mapper.countByStatusSince("FAILED", since)));
    p.put("econ", String.valueOf(econDrafts));
    p.put("focus", focus);
    p.put("used", String.valueOf(d == null ? 0 : d.used));
    p.put("limit", String.valueOf(settings.integer(Settings.AI_DAILY_LIMIT, 300)));
    try {
      tx.executeWithoutResult(st -> staff.notifyStaff("CONTENT_DIGEST", p));
    } catch (RuntimeException e) {
      log.warn("每日草稿包邮件没发出去", e);
    }
    contentLog.write("DIGEST", null, today.toString(), null, "OK", "草稿包：新起草 " + groups + " 组" + (skipped == null ? "" : "（AI 起草没有做：" + skipped + "）")
        + "；待审核 " + p.get("pending") + "；邮件已发", 0, null);
    return p;
  }

  /** 本月估算费用到了提醒线 */
  boolean costAlert() {
    ContentAdminApi.Status s = admin.status();
    if (!s.costAlert()) return false;
    alerts.raise("cost", "本月估算费用到了提醒线", "Monthly cost alert", "本月按付费档估算约 " + s.monthCostUsd() + " 美元，提醒线 " + s.costAlertUsd() + " 美元",
        "Estimated at paid-tier prices: about USD " + s.monthCostUsd() + "; alert line USD " + s.costAlertUsd());
    return true;
  }

  static String usd(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
