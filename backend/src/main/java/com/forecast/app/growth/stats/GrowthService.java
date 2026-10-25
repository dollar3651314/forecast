package com.forecast.app.growth.stats;

import com.forecast.app.account.api.AttributionApi;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.growth.api.GrowthAdminApi;
import com.forecast.app.growth.visit.VisitMapper;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketPoolView;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.pool.api.PoolApi;
import com.forecast.app.wallet.api.WalletSignalsApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * 来源统计和增长数据（forecast S13，MF-1、MF-10）。口径见 {@link GrowthAdminApi}。只读，不加事务（每个模块的查询各自只读）。
 * 第一期客户少，按注册客户逐批（1,000 个一批）去问 wallet 的首充、pool 的下注；注册人数上限 {@link #MAX_USERS}，超过时只统计前面的并标记 truncated。
 */
@Service
public class GrowthService implements GrowthAdminApi {

  static final int MAX_USERS = 50_000;
  static final int BATCH = 1_000;
  static final int MAX_WEEKS = 53;
  static final int MAX_MARKETS = 10_000;

  private final AttributionApi attribution;
  private final WalletSignalsApi wallet;
  private final PoolApi pool;
  private final MarketApi markets;
  private final MarketPoolView poolView;
  private final VisitMapper visits;
  private final Clock clock;

  GrowthService(AttributionApi attribution, WalletSignalsApi wallet, PoolApi pool, MarketApi markets, MarketPoolView poolView, VisitMapper visits, Clock clock) {
    this.attribution = attribution;
    this.wallet = wallet;
    this.pool = pool;
    this.markets = markets;
    this.poolView = poolView;
    this.visits = visits;
    this.clock = clock;
  }

  /** 统计范围：[第一周的周一, 最后一周的下周一) */
  record Range(LocalDate fromMonday, LocalDate toMondayExclusive, String basis) {}

  Range range(String from, String to, String basis) {
    ValidationException.Collector errs = new ValidationException.Collector();
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    LocalDate f = date(errs, "from", from);
    LocalDate t = date(errs, "to", to);
    String b = basis == null || basis.isBlank() ? "first" : basis.strip();
    if (!b.equals("first") && !b.equals("reg")) errs.add("basis", "invalid");
    errs.throwIfAny();
    if (t == null) t = today;
    if (f == null) f = monday(t).minusWeeks(7);
    LocalDate fm = monday(f);
    LocalDate tm = monday(t).plusWeeks(1);
    if (fm.isAfter(monday(t))) errs.add("from", "afterTo");
    else if (java.time.temporal.ChronoUnit.WEEKS.between(fm, tm) > MAX_WEEKS) errs.add("from", "range");
    errs.throwIfAny();
    return new Range(fm, tm, b);
  }

  private static LocalDate date(ValidationException.Collector errs, String field, String v) {
    if (v == null || v.isBlank()) return null;
    try {
      return LocalDate.parse(v.strip());
    } catch (DateTimeParseException e) {
      errs.add(field, "invalid");
      return null;
    }
  }

  /** 一个注册客户的事实（导出客户 CSV 也用） */
  record Customer(long userId, Instant registeredAt, String week, AttributionApi.Source first, AttributionApi.Source reg, LocalDateTime firstDepositAt,
      LocalDateTime firstBetAt, Boolean week2Active) {}

  record Collected(List<Customer> customers, boolean truncated) {}

  Collected customers(Range r) {
    Instant from = r.fromMonday().atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant to = r.toMondayExclusive().atStartOfDay().toInstant(ZoneOffset.UTC);
    List<AttributionApi.Registration> regs = attribution.registrations(from, to, MAX_USERS + 1);
    boolean truncated = regs.size() > MAX_USERS;
    if (truncated) regs = regs.subList(0, MAX_USERS);
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    List<Customer> out = new ArrayList<>(regs.size());
    for (int i = 0; i < regs.size(); i += BATCH) {
      List<AttributionApi.Registration> part = regs.subList(i, Math.min(regs.size(), i + BATCH));
      List<Long> ids = part.stream().map(AttributionApi.Registration::userId).toList();
      Map<Long, LocalDateTime> deposits = wallet.firstCredited(ids);
      Map<Long, PoolApi.BetActivity> acts = pool.activity(ids);
      for (AttributionApi.Registration g : part) {
        LocalDate regDay = LocalDate.ofInstant(g.registeredAt(), ZoneOffset.UTC);
        PoolApi.BetActivity a = acts.get(g.userId());
        // 第 2 周 = 注册后第 8～14 天（UTC 日期）；第 2 周还没过完的客户不算（为空）
        Boolean w2 = null;
        if (!regDay.plusDays(14).isAfter(today)) {
          w2 = a != null && a.days().stream().anyMatch(d -> !d.isBefore(regDay.plusDays(7)) && !d.isAfter(regDay.plusDays(13)));
        }
        out.add(new Customer(g.userId(), g.registeredAt(), isoWeek(monday(regDay)), g.first(), g.reg(), deposits.get(g.userId()),
            a == null ? null : a.firstBetAt(), w2));
      }
    }
    return new Collected(out, truncated);
  }

  private static AttributionApi.Source source(Customer c, String basis) {
    return "reg".equals(basis) ? c.reg() : c.first();
  }

  private static String nz(String v) {
    return v == null ? "" : v;
  }

  @Override
  public Report report(String from, String to, String basis) {
    Range r = range(from, to, basis);
    Collected col = customers(r);
    // 注册周 × 来源
    Map<List<String>, long[]> rows = new TreeMap<>(Comparator.<List<String>, String>comparing(k -> k.get(0)).reversed()
        .thenComparing(k -> k.get(1)).thenComparing(k -> k.get(2)).thenComparing(k -> k.get(3)));
    for (VisitMapper.DayCount d : visits.counts(r.fromMonday(), r.toMondayExclusive().minusDays(1))) {
      rows.computeIfAbsent(List.of(isoWeek(monday(d.day)), nz(d.src), nz(d.cmp), nz(d.ref)), k -> new long[6])[0] += d.n;
    }
    for (Customer c : col.customers()) {
      AttributionApi.Source s = source(c, r.basis());
      long[] v = rows.computeIfAbsent(List.of(c.week(), s == null ? "" : nz(s.src()), s == null ? "" : nz(s.cmp()), s == null ? "" : nz(s.ref())),
          k -> new long[6]);
      v[1]++;
      if (c.firstDepositAt() != null) v[2]++;
      if (c.firstBetAt() != null) v[3]++;
      if (c.week2Active() != null) {
        v[5]++;
        if (c.week2Active()) v[4]++;
      }
    }
    List<Cohort> cohorts = new ArrayList<>();
    long[] t = new long[6];
    for (Map.Entry<List<String>, long[]> e : rows.entrySet()) {
      long[] v = e.getValue();
      for (int i = 0; i < 6; i++) t[i] += v[i];
      cohorts.add(new Cohort(e.getKey().get(0), e.getKey().get(1), e.getKey().get(2), e.getKey().get(3), v[0], v[1], v[2], v[3], v[4], v[5]));
    }
    MarketPart mp = marketPart(r);
    return new Report(r.fromMonday().toString(), r.toMondayExclusive().minusDays(1).toString(), r.basis(), cohorts,
        new Totals(t[0], t[1], t[2], t[3], t[4], t[5]), mp.weeks(), mp.reasons(), mp.rows(), col.truncated());
  }

  record MarketPart(List<MarketWeek> weeks, List<ReasonCount> reasons, List<MarketRow> rows) {}

  MarketPart marketPart(Range r) {
    Instant from = r.fromMonday().atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant to = r.toMondayExclusive().atStartOfDay().toInstant(ZoneOffset.UTC);
    List<MarketApi.Ended> ended = markets.ended(from, to, MAX_MARKETS);
    Map<Long, MarketPoolView.Totals> totals = new java.util.HashMap<>(ended.isEmpty() ? Map.of() : poolView.totals(ended.stream().map(MarketApi.Ended::marketId).toList()));
    // 取消、作废的市场退款后持仓清零：奖池和人数按收到过的下注算（含已退回的）
    List<Long> refunded = ended.stream().filter(m -> m.status() != MarketStatus.SETTLED).map(MarketApi.Ended::marketId).toList();
    if (!refunded.isEmpty()) totals.putAll(poolView.placed(refunded));
    List<MarketRow> rows = new ArrayList<>();
    Map<String, List<MarketApi.Ended>> byWeek = new TreeMap<>(Comparator.reverseOrder());
    Map<String, Integer> reasons = new LinkedHashMap<>();
    for (MarketApi.Ended m : ended) {
      MarketPoolView.Totals tt = totals.get(m.marketId());
      String reason = m.status() == MarketStatus.VOIDED ? m.voidReason() : m.status() == MarketStatus.CANCELLED ? m.cancelReason() : null;
      rows.add(new MarketRow(String.valueOf(m.publicNo()), m.category().name(), m.title().zhCN(), m.title().en(), m.status().api(), m.endedAt().toString(),
          tt == null ? 0 : tt.bettors(), money(tt == null ? BigDecimal.ZERO : tt.amount()), reason));
      byWeek.computeIfAbsent(isoWeek(monday(LocalDate.ofInstant(m.endedAt(), ZoneOffset.UTC))), k -> new ArrayList<>()).add(m);
      if (m.status() != MarketStatus.SETTLED) reasons.merge(m.status().api() + "\u0000" + nz(reason), 1, Integer::sum);
    }
    List<MarketWeek> weeks = new ArrayList<>();
    for (Map.Entry<String, List<MarketApi.Ended>> e : byWeek.entrySet()) {
      List<MarketApi.Ended> ms = e.getValue();
      int settled = (int) ms.stream().filter(m -> m.status() == MarketStatus.SETTLED).count();
      int voided = (int) ms.stream().filter(m -> m.status() == MarketStatus.VOIDED).count();
      int cancelled = (int) ms.stream().filter(m -> m.status() == MarketStatus.CANCELLED).count();
      List<Integer> bettors = ms.stream().map(m -> totals.containsKey(m.marketId()) ? totals.get(m.marketId()).bettors() : 0).sorted().toList();
      weeks.add(new MarketWeek(e.getKey(), ms.size(), settled, voided, cancelled, pct(settled, ms.size()), median(bettors)));
    }
    List<ReasonCount> rs = reasons.entrySet().stream().map(e -> {
      String[] k = e.getKey().split("\u0000", 2);
      return new ReasonCount(k[0], k.length > 1 && !k[1].isEmpty() ? k[1] : null, e.getValue());
    }).toList();
    return new MarketPart(weeks, rs, rows);
  }

  static String pct(int a, int b) {
    if (b == 0) return null;
    return BigDecimal.valueOf(a).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(b), 1, RoundingMode.HALF_UP).toPlainString();
  }

  static String median(List<Integer> sorted) {
    if (sorted.isEmpty()) return null;
    int n = sorted.size();
    if (n % 2 == 1) return String.valueOf(sorted.get(n / 2));
    return BigDecimal.valueOf(sorted.get(n / 2 - 1) + sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP).stripTrailingZeros()
        .toPlainString();
  }

  static String money(BigDecimal v) {
    return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.DOWN).toPlainString();
  }

  static LocalDate monday(LocalDate d) {
    return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  static String isoWeek(LocalDate monday) {
    return String.format("%d-W%02d", monday.get(IsoFields.WEEK_BASED_YEAR), monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
  }

  // =====================================================================
  // CSV（MF-10，AC-94）
  // =====================================================================

  @Override
  public String csv(String kind, String from, String to, String basis, String lang) {
    if (kind == null || !EXPORT_KINDS.contains(kind)) throw new ValidationException(List.of(new ValidationException.Field("kind", "invalid")));
    boolean en = "en".equals(lang);
    Range r = range(from, to, basis);
    Csv out = new Csv();
    switch (kind) {
      case "sources" -> {
        Report rep = report(from, to, basis);
        out.row(en ? List.of("week", "src", "cmp", "ref", "visits", "registrations", "first_deposits", "activated", "week2_active", "week2_eligible")
            : List.of("注册周", "渠道 src", "活动 cmp", "分享人 ref", "访问", "注册", "首充", "激活（首注）", "第 2 周仍下注", "第 2 周已过完的注册"));
        for (Cohort c : rep.cohorts()) {
          out.row(List.of(c.week(), c.src(), c.cmp(), c.ref(), String.valueOf(c.visits()), String.valueOf(c.registrations()), String.valueOf(c.firstDeposits()),
              String.valueOf(c.activated()), String.valueOf(c.week2Active()), String.valueOf(c.week2Eligible())));
        }
      }
      case "markets" -> {
        MarketPart mp = marketPart(r);
        out.row(en ? List.of("market_no", "category", "title_zh", "title_en", "status", "ended_at", "bettors", "pool", "reason")
            : List.of("市场编号", "品类", "题目（简体）", "题目（英文）", "状态", "结束时间（UTC）", "下注人数", "奖池", "作废 / 取消原因"));
        for (MarketRow m : mp.rows()) {
          out.row(List.of(m.marketNo(), m.category(), nz(m.titleZhCN()), nz(m.titleEn()), m.status(), m.endedAt(), String.valueOf(m.bettors()), m.pool(),
              nz(m.reason())));
        }
      }
      default -> {
        Collected col = customers(r);
        out.row(en ? List.of("customer_no", "registered_at", "week", "first_src", "first_cmp", "first_ref", "first_start", "reg_src", "reg_cmp", "reg_ref",
            "reg_start", "first_deposit_at", "first_bet_at", "week2_active")
            : List.of("客户编号", "注册时间（UTC）", "注册周", "首次来源 src", "首次来源 cmp", "首次来源 ref", "首次来源 start", "注册来源 src", "注册来源 cmp",
                "注册来源 ref", "注册来源 start", "首充时间（UTC）", "首注时间（UTC）", "第 2 周仍下注"));
        for (Customer c : col.customers()) {
          AttributionApi.Source f = c.first();
          AttributionApi.Source g = c.reg();
          out.row(List.of(String.valueOf(c.userId()), c.registeredAt().toString(), c.week(), f == null ? "" : nz(f.src()), f == null ? "" : nz(f.cmp()),
              f == null ? "" : nz(f.ref()), f == null ? "" : nz(f.start()), g == null ? "" : nz(g.src()), g == null ? "" : nz(g.cmp()),
              g == null ? "" : nz(g.ref()), g == null ? "" : nz(g.start()), c.firstDepositAt() == null ? "" : c.firstDepositAt().toInstant(ZoneOffset.UTC).toString(),
              c.firstBetAt() == null ? "" : c.firstBetAt().toInstant(ZoneOffset.UTC).toString(),
              c.week2Active() == null ? "" : c.week2Active() ? "1" : "0"));
        }
      }
    }
    return out.toString();
  }

  /** CSV：UTF-8 带 BOM；含逗号、引号、换行的加引号；以 = + - @ 开头的前面加一个单引号（防止表格软件当公式执行） */
  static final class Csv {
    private final StringBuilder sb = new StringBuilder("﻿");

    void row(List<String> cells) {
      for (int i = 0; i < cells.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append(cell(Objects.toString(cells.get(i), "")));
      }
      sb.append("\r\n");
    }

    static String cell(String v) {
      String s = v;
      if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
      if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) s = "\"" + s.replace("\"", "\"\"") + "\"";
      return s;
    }

    @Override
    public String toString() {
      return sb.toString();
    }
  }
}
