package com.forecast.app.market.core;

import static com.forecast.app.market.core.MarketFormats.iso;

import com.forecast.app.common.error.ValidationException;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketCategory;
import com.forecast.app.market.api.MarketPoolView;
import com.forecast.app.market.api.MarketResolutionView;
import com.forecast.app.market.api.MarketSettleView;
import com.forecast.app.market.api.MarketStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 不登录也能看的市场信息（forecast S13，推广功能）：
 * <ul>
 *   <li>今日场次（MF-3，需求第 4.2 节、P11、AC-82）：带"列入今日场次"标记的市场，按场次时间（事件开始时间）落在客户的"今天"里；没有时给下一场。</li>
 *   <li>开奖记录（MF-6，P12、AC-88）：已结算、已作废、已取消的市场，结果、依据、奖池、派彩合计、抽成、尾差、裁决人编号、作废 / 取消原因。</li>
 *   <li>周报（MF-6）：按 UTC 的 ISO 周（周一 00:00 起）合计已结束的市场：场数、有效场数（已结算）、作废场数（已作废 + 已取消）和原因、
 *       奖池合计、派彩合计、抽成合计、尾差、退款合计。数字全部来自结算单（{@link MarketSettleView#totals}），和后台结算记录一致。</li>
 * </ul>
 * 全部只读；不含任何客户的个人数据（详情里"我的"部分只在登录后由 {@link MarketService#clientDetail} 给）。
 */
@Service
public class MarketPublicService {

  /** 今日场次最多列几个（第一期每天 1～3 场） */
  static final int SLATE_MAX = 50;
  /** 开奖记录每页最多几条 */
  static final int RESULTS_PAGE_MAX = 50;
  /** 周报列表一页几周 */
  static final int WEEKS_PAGE = 12;
  static final List<String> ENDED = List.of(MarketStatus.SETTLED.name(), MarketStatus.VOIDED.name(), MarketStatus.CANCELLED.name());

  private final MarketMapper mapper;
  private final MarketService markets;
  private final ObjectProvider<MarketSettleView> settle;
  private final ObjectProvider<MarketResolutionView> resolution;
  private final ObjectProvider<MarketPoolView> pool;
  private final Clock clock;

  MarketPublicService(MarketMapper mapper, MarketService markets, ObjectProvider<MarketSettleView> settle, ObjectProvider<MarketResolutionView> resolution,
      ObjectProvider<MarketPoolView> pool, Clock clock) {
    this.mapper = mapper;
    this.markets = markets;
    this.settle = settle;
    this.resolution = resolution;
    this.pool = pool;
    this.clock = clock;
  }

  // =====================================================================
  // 今日场次（MF-3）
  // =====================================================================

  /** 今日场次：items 按时间排；没有时 next 是下一场（也可能没有） */
  public record Today(String from, String to, List<ClientViews.Card> items, ClientViews.Card next) {}

  /**
   * 客户的"今天"由客户端按自己的时区算好传 [from, to)（UTC 时间，AC-82：UTC+8 的客户 13:00 UTC 的场次显示在当天）；不传时按 UTC 的今天。
   * 范围必须在 1～48 小时之间（夏令时那天是 23 / 25 小时）。
   */
  @Transactional(readOnly = true)
  public Today today(String from, String to) {
    Instant f;
    Instant t;
    if ((from == null || from.isBlank()) && (to == null || to.isBlank())) {
      LocalDate d = LocalDate.now(clock.withZone(ZoneOffset.UTC));
      f = d.atStartOfDay().toInstant(ZoneOffset.UTC);
      t = f.plus(Duration.ofDays(1));
    } else {
      f = MarketFormats.parseInstant(from);
      t = MarketFormats.parseInstant(to);
      ValidationException.Collector errs = new ValidationException.Collector();
      if (f == null) errs.add("from", "invalid");
      if (t == null) errs.add("to", "invalid");
      if (f != null && t != null) {
        Duration span = Duration.between(f, t);
        if (span.compareTo(Duration.ofHours(1)) < 0 || span.compareTo(Duration.ofHours(48)) > 0) errs.add("to", "range");
      }
      errs.throwIfAny();
    }
    List<MarketRows.Market> rows = mapper.slate(MarketFormats.ldt(f), MarketFormats.ldt(t), SLATE_MAX);
    List<ClientViews.Card> items = markets.cards(rows);
    ClientViews.Card next = null;
    if (items.isEmpty()) {
      MarketRows.Market n = mapper.nextSlate(MarketFormats.ldt(t));
      if (n != null) next = markets.cards(List.of(n)).get(0);
    }
    return new Today(f.toString(), t.toString(), items, next);
  }

  // =====================================================================
  // 开奖记录（MF-6）
  // =====================================================================

  /**
   * 开奖记录的一条。settle、resolution 和客户端市场详情里的同名字段是同一个结构（不登录时的版本，没有"我的"）；
   * bettors：下注人数（已结算的：结算时的有效下注客户数；取消、作废的：收到过下注的客户数，含已退回的；pool 给）。
   */
  public record Result(String publicNo, String category, I18nText title, String status, String endedAt, String eventStartAt,
      List<ClientViews.OptionCard> options, String winningOptionNo, String cancelReason, String voidReason, int bettors, Object settle, Object resolution) {}

  /**
   * 开奖记录：status 不传为三种都要（settled / voided / cancelled 可以逗号分隔）；category；from、to 是结束时间的范围（ISO 时间，可以只传一个）。
   */
  @Transactional(readOnly = true)
  public ClientViews.Page<Result> results(String status, String category, String from, String to, int page, int pageSize) {
    ValidationException.Collector errs = new ValidationException.Collector();
    List<String> sts = new ArrayList<>();
    if (status == null || status.isBlank()) sts.addAll(ENDED);
    else {
      for (String one : status.split(",")) {
        MarketStatus s = MarketStatus.ofApi(one.strip());
        if (s == null || !ENDED.contains(s.name())) {
          errs.add("status", "invalid");
          break;
        }
        sts.add(s.name());
      }
    }
    MarketCategory cat = category == null || category.isBlank() ? null : MarketCategory.of(category);
    if (category != null && !category.isBlank() && cat == null) errs.add("category", "invalid");
    LocalDateTime f = optionalTime(errs, "from", from);
    LocalDateTime t = optionalTime(errs, "to", to);
    errs.throwIfAny();
    int p = Math.max(1, page);
    int size = Math.min(RESULTS_PAGE_MAX, Math.max(1, pageSize));
    String c = cat == null ? null : cat.name();
    List<MarketRows.Market> rows = mapper.endedList(sts, c, f, t, (p - 1) * size, size);
    return new ClientViews.Page<>(resultsOf(rows), mapper.endedCount(sts, c, f, t), p, size);
  }

  private List<Result> resultsOf(List<MarketRows.Market> rows) {
    if (rows.isEmpty()) return List.of();
    List<Long> ids = rows.stream().map(r -> r.id).toList();
    Map<Long, List<MarketRows.Option>> opts = mapper.optionsOf(ids).stream().collect(Collectors.groupingBy(o -> o.marketId));
    MarketPoolView pv = pool.getIfAvailable();
    Map<Long, MarketPoolView.Totals> totals = pv == null ? Map.of() : pv.totals(ids);
    // 取消、作废的市场退款后持仓清零：下注人数按收到过的下注算（含已退回的）
    List<Long> refunded = rows.stream().filter(r -> MarketService.status(r) != MarketStatus.SETTLED).map(r -> r.id).toList();
    Map<Long, MarketPoolView.Totals> placed = pv == null || refunded.isEmpty() ? Map.of() : pv.placed(refunded);
    MarketSettleView sv = settle.getIfAvailable();
    MarketResolutionView rv = resolution.getIfAvailable();
    List<Result> out = new ArrayList<>();
    for (MarketRows.Market m : rows) {
      MarketStatus s = MarketService.status(m);
      List<MarketRows.Option> os = opts.getOrDefault(m.id, List.of());
      out.add(new Result(String.valueOf(m.publicNo), m.category, MarketService.title(m), s.api(), iso(m.endedAt), iso(m.eventStartAt),
          os.stream().map(o -> new ClientViews.OptionCard(String.valueOf(o.publicNo), o.seq, MarketService.label(o))).toList(),
          MarketService.winningOptionNo(m, os), m.cancelReason, m.voidReason,
          placed.containsKey(m.id) ? placed.get(m.id).bettors() : totals.containsKey(m.id) ? totals.get(m.id).bettors() : 0,
          sv == null ? null : sv.clientSettle(m.id, s, null), rv == null || s == MarketStatus.CANCELLED ? null : rv.clientResolution(m.id, s, null)));
    }
    return out;
  }

  // =====================================================================
  // 周报（MF-6）
  // =====================================================================

  /** 作废 / 取消的一个原因和场数（原因是系统代码，例如 NOT_ENOUGH_OPTIONS，或管理员写的文字） */
  public record Reason(String status, String reason, int count) {}

  /**
   * 一周的周报。week：ISO 周（"2026-W44"）；from、to：这一周的 [周一 00:00, 下周一 00:00) UTC；complete：这一周已经过完。
   * markets = settled + voided + cancelled；金额是 2 位小数的字符串（向下取整，和结算明细一致）。
   */
  public record Week(String week, String from, String to, boolean complete, int markets, int settled, int voided, int cancelled, List<Reason> reasons,
      String poolTotal, String payoutTotal, String feeTotal, String dust, String refundTotal) {}

  /** 周报列表：从本周往前，一页 12 周，最早到第一个已结束市场所在的那一周；没有任何已结束的市场时只有本周 */
  public record Weeks(List<Week> items, boolean hasMore, int page) {}

  @Transactional(readOnly = true)
  public Weeks weeks(int page) {
    int p = Math.max(1, page);
    LocalDate thisMonday = monday(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
    LocalDateTime first = mapper.firstEndedAt();
    LocalDate firstMonday = first == null ? thisMonday : monday(first.toLocalDate());
    List<Week> out = new ArrayList<>();
    LocalDate start = thisMonday.minusWeeks((long) (p - 1) * WEEKS_PAGE);
    for (int i = 0; i < WEEKS_PAGE; i++) {
      LocalDate w = start.minusWeeks(i);
      if (w.isBefore(firstMonday)) break;
      out.add(week(w));
    }
    boolean more = !start.minusWeeks(WEEKS_PAGE).isBefore(firstMonday);
    return new Weeks(out, more, p);
  }

  /** 一周的周报（week：ISO 周 "2026-W44"）；格式不对 400 */
  @Transactional(readOnly = true)
  public Week week(String isoWeek) {
    LocalDate monday = parseWeek(isoWeek);
    if (monday == null) throw new ValidationException(List.of(new ValidationException.Field("week", "invalid")));
    return week(monday);
  }

  /** 这一周已结束的市场（周报页下面的列表） */
  @Transactional(readOnly = true)
  public List<Result> weekMarkets(String isoWeek) {
    LocalDate monday = parseWeek(isoWeek);
    if (monday == null) throw new ValidationException(List.of(new ValidationException.Field("week", "invalid")));
    return resultsOf(mapper.endedList(ENDED, null, monday.atStartOfDay(), monday.plusWeeks(1).atStartOfDay(), 0, 1000));
  }

  Week week(LocalDate monday) {
    LocalDateTime from = monday.atStartOfDay();
    LocalDateTime to = monday.plusWeeks(1).atStartOfDay();
    // 一周的市场数第一期是几十个；上限 10,000 只是防止意外
    List<MarketRows.Market> rows = mapper.endedList(ENDED, null, from, to, 0, 10_000);
    MarketSettleView sv = settle.getIfAvailable();
    Map<Long, MarketSettleView.SettleTotals> totals = sv == null || rows.isEmpty() ? Map.of() : sv.totals(rows.stream().map(r -> r.id).toList());
    int settled = 0;
    int voided = 0;
    int cancelled = 0;
    BigDecimal pool = BigDecimal.ZERO;
    BigDecimal payout = BigDecimal.ZERO;
    BigDecimal fee = BigDecimal.ZERO;
    BigDecimal dust = BigDecimal.ZERO;
    BigDecimal refund = BigDecimal.ZERO;
    Map<String, Integer> reasons = new LinkedHashMap<>();
    for (MarketRows.Market m : rows) {
      MarketStatus s = MarketService.status(m);
      MarketSettleView.SettleTotals tt = totals.get(m.id);
      switch (s) {
        case SETTLED -> {
          settled++;
          if (tt != null && "payout".equals(tt.kind())) {
            pool = pool.add(tt.poolTotal());
            payout = payout.add(tt.payoutTotal());
            fee = fee.add(tt.feeTotal());
            dust = dust.add(tt.dust());
          }
        }
        case VOIDED, CANCELLED -> {
          if (s == MarketStatus.VOIDED) voided++;
          else cancelled++;
          String r = s == MarketStatus.VOIDED ? m.voidReason : m.cancelReason;
          reasons.merge(s.api() + "\u0000" + (r == null ? "" : r), 1, Integer::sum);
          if (tt != null && "refund".equals(tt.kind())) refund = refund.add(tt.payoutTotal());
        }
        default -> {}
      }
    }
    List<Reason> rs = reasons.entrySet().stream().map(e -> {
      String[] k = e.getKey().split("\u0000", 2);
      return new Reason(k[0], k.length > 1 && !k[1].isEmpty() ? k[1] : null, e.getValue());
    }).toList();
    LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    return new Week(isoWeek(monday), iso(from), iso(to), !to.isAfter(now), rows.size(), settled, voided, cancelled, rs, money(pool), money(payout), money(fee),
        money(dust), money(refund));
  }

  static LocalDate monday(LocalDate d) {
    return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  static String isoWeek(LocalDate monday) {
    return String.format("%d-W%02d", monday.get(IsoFields.WEEK_BASED_YEAR), monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
  }

  /** "2026-W44" → 那一周的周一；不合法为空 */
  static LocalDate parseWeek(String w) {
    if (w == null || !w.strip().matches("\\d{4}-W\\d{2}")) return null;
    String v = w.strip();
    int year = Integer.parseInt(v.substring(0, 4));
    int n = Integer.parseInt(v.substring(6));
    if (year < 2020 || year > 2100 || n < 1) return null;
    LocalDate jan4 = LocalDate.of(year, 1, 4);
    if (n > jan4.range(IsoFields.WEEK_OF_WEEK_BASED_YEAR).getMaximum()) return null;
    return monday(jan4).plusWeeks(n - 1L);
  }

  static String money(BigDecimal v) {
    return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.DOWN).toPlainString();
  }

  private static LocalDateTime optionalTime(ValidationException.Collector errs, String field, String v) {
    if (v == null || v.isBlank()) return null;
    try {
      Instant i = MarketFormats.parseInstant(v);
      if (i == null) {
        errs.add(field, "invalid");
        return null;
      }
      return MarketFormats.ldt(i);
    } catch (DateTimeParseException e) {
      errs.add(field, "invalid");
      return null;
    }
  }

  // =====================================================================
  // 站点地图（MF-4）
  // =====================================================================

  /** 站点地图里的一个市场：对外编号和最后修改时间 */
  public record SitemapEntry(String publicNo, String lastModified) {}

  @Transactional(readOnly = true)
  public List<SitemapEntry> sitemap(int limit) {
    return mapper.sitemap(limit).stream().map(m -> new SitemapEntry(String.valueOf(m.publicNo), iso(m.updatedAt))).toList();
  }

  /** 公开页面要的市场状态（SEO：草稿和不存在的 404） */
  @Transactional(readOnly = true)
  public boolean visible(long publicNo) {
    MarketRows.Market m = mapper.findByPublicNo(publicNo);
    return m != null && MarketService.VISIBLE.contains(MarketService.status(m));
  }

}
