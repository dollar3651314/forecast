package com.forecast.app.settle.run;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.settle.api.SettleAdminApi;
import com.forecast.app.settle.api.SettleApi;
import com.forecast.app.settle.comp.CompensationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * {@link SettleAdminApi}（后台）和 {@link SettleApi}（给提现自动审批）的实现（forecast S5）。业务在 {@link SettleService}；这里做参数校验和字段转换。
 */
@Service
class SettleAdmin implements SettleAdminApi, SettleApi {

  private final SettleService settle;
  private final CompensationService comp;
  private final SettleMapper mapper;
  private final MarketApi markets;
  private final Clock clock;

  SettleAdmin(SettleService settle, CompensationService comp, SettleMapper mapper, MarketApi markets, Clock clock) {
    this.settle = settle;
    this.comp = comp;
    this.mapper = mapper;
    this.markets = markets;
    this.clock = clock;
  }

  @Override
  public long openPoolReconIssues() {
    return mapper.openIssues();
  }

  private MarketApi.MarketRef market(long marketNo) {
    MarketApi.MarketRef m = markets.find(marketNo).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
    if (m.status() == MarketStatus.DRAFT) throw new BizException("NOT_FOUND", "市场不存在");
    return m;
  }

  @Override
  public Preview preview(long marketNo, String optionNo, String actualStartAt) {
    MarketApi.MarketRef m = market(marketNo);
    List<ValidationException.Field> errs = new ArrayList<>();
    MarketApi.OptionRef option = null;
    if (optionNo == null || optionNo.isBlank()) errs.add(new ValidationException.Field("optionNo", "required"));
    else {
      option = markets.options(m.marketId()).stream().filter(o -> String.valueOf(o.optionNo()).equals(optionNo.strip())).findFirst().orElse(null);
      if (option == null) errs.add(new ValidationException.Field("optionNo", "invalid"));
    }
    Instant start = null;
    if (actualStartAt != null && !actualStartAt.isBlank()) {
      try {
        start = Instant.parse(actualStartAt.strip()).truncatedTo(ChronoUnit.MILLIS);
      } catch (DateTimeParseException e) {
        errs.add(new ValidationException.Field("actualStartAt", "invalid"));
      }
    }
    if (!errs.isEmpty()) throw new ValidationException(errs);
    SettleService.Preview p = settle.preview(m.marketId(), option.optionId(), start);
    return new Preview(String.valueOf(m.publicNo()), String.valueOf(option.optionNo()), start == null ? null : start.toString(), p.outcome(),
        SettleService.percent(p.feeRate()), money(p.total()), money(p.winnerPool()), money(p.loserPool()), money(p.sumGross()), money(p.sumFee()), money(p.dust()),
        money(p.sumPayout()), money(p.platformIncome()), p.winners(), p.losers(), p.conserved(), p.lateBets(), money(p.lateAmount()), p.staffBets(),
        money(p.staffAmount()));
  }

  // ---------------------------------------------------------------------
  // 补偿（E13）
  // ---------------------------------------------------------------------

  /** 正确结果：选项 id，或 null（void = 本应作废）；字段错误放进 errs */
  private Long correctOption(MarketApi.MarketRef m, String correctOptionNo, List<ValidationException.Field> errs) {
    if (correctOptionNo == null || correctOptionNo.isBlank()) {
      errs.add(new ValidationException.Field("correctOptionNo", "required"));
      return null;
    }
    if (correctOptionNo.strip().equalsIgnoreCase("void")) return null;
    MarketApi.OptionRef o = markets.options(m.marketId()).stream().filter(x -> String.valueOf(x.optionNo()).equals(correctOptionNo.strip())).findFirst()
        .orElse(null);
    if (o == null) {
      errs.add(new ValidationException.Field("correctOptionNo", "invalid"));
      return null;
    }
    SettleRows.Run run = mapper.runByMarket(m.marketId(), SettleService.PAYOUT);
    if (run != null && Long.valueOf(o.optionId()).equals(run.winningOptionId)) errs.add(new ValidationException.Field("correctOptionNo", "sameAsResult"));
    return o.optionId();
  }

  @Override
  public List<ValidationException.Field> checkCompensation(long marketNo, String correctOptionNo) {
    List<ValidationException.Field> errs = new ArrayList<>();
    correctOption(market(marketNo), correctOptionNo, errs);
    return errs;
  }

  @Override
  public CompPlan compensationPreview(long marketNo, String correctOptionNo) {
    MarketApi.MarketRef m = market(marketNo);
    List<ValidationException.Field> errs = new ArrayList<>();
    Long correct = correctOption(m, correctOptionNo, errs);
    if (!errs.isEmpty()) throw new ValidationException(errs);
    CompensationService.Plan p = comp.plan(m.marketId(), correct);
    return new CompPlan(String.valueOf(m.publicNo()), optionNo(p.originalOptionId()), correct == null ? "void" : optionNo(correct),
        SettleService.percent(p.feeRate()), money(p.total()), p.lines().size(), lines(p.lines()));
  }

  @Override
  public Compensation compensate(long marketNo, String correctOptionNo, String reason, long adminId) {
    MarketApi.MarketRef m = market(marketNo);
    List<ValidationException.Field> errs = new ArrayList<>();
    Long correct = correctOption(m, correctOptionNo, errs);
    if (!errs.isEmpty()) throw new ValidationException(errs);
    return view(comp.compensate(m.marketId(), correct, reason, adminId));
  }

  @Override
  public Page<Compensation> compensations(int page, int pageSize) {
    int p = Math.max(1, page);
    int size = Math.min(100, Math.max(1, pageSize));
    return new Page<>(comp.list((p - 1) * size, size).stream().map(this::view).toList(), comp.count(), p, size);
  }

  @Override
  public Optional<Compensation> compensation(long compensationNo) {
    return comp.byPublicNo(compensationNo).map(this::view);
  }

  private Compensation view(CompensationService.Saved c) {
    String marketNo = markets.findById(c.marketId()).map(r -> String.valueOf(r.publicNo())).orElse(null);
    return new Compensation(String.valueOf(c.compensationNo()), marketNo, optionNo(c.originalOptionId()), c.correctOptionId() == null ? "void"
        : optionNo(c.correctOptionId()), SettleService.percent(c.feeRate()), money(c.total()), c.userCount(), c.reason(), c.createdBy(), iso(c.createdAt()),
        lines(c.lines()));
  }

  private static List<CompLine> lines(List<CompensationService.Line> lines) {
    return lines.stream().map(l -> new CompLine(l.userId(), money(l.should()), money(l.actual()), money(l.amount()))).toList();
  }

  private String optionNo(long optionId) {
    MarketApi.OptionRef o = markets.optionsById(List.of(optionId)).get(optionId);
    return o == null ? null : String.valueOf(o.optionNo());
  }

  @Override
  public Report report(String from, String to) {
    ValidationException.Collector errs = new ValidationException.Collector();
    LocalDate f = date(from, "from", errs);
    LocalDate t = date(to, "to", errs);
    if (f != null && t != null && (t.isBefore(f) || ChronoUnit.DAYS.between(f, t) > 366)) errs.add("to", "invalidRange");
    errs.throwIfAny();
    BigDecimal fee = BigDecimal.ZERO;
    BigDecimal dust = BigDecimal.ZERO;
    BigDecimal compTotal = BigDecimal.ZERO;
    int comps = 0;
    List<ReportDay> days = new ArrayList<>();
    for (CompensationService.Day d : comp.report(f, t)) {
      fee = fee.add(d.fee());
      dust = dust.add(d.dust());
      compTotal = compTotal.add(d.compensation());
      comps += d.compensations();
      days.add(new ReportDay(d.day().toString(), money(d.fee()), money(d.dust()), money(d.fee().add(d.dust())), money(d.compensation()), d.compensations()));
    }
    return new Report(f.toString(), t.toString(), money(fee), money(dust), money(fee.add(dust)), money(compTotal), comps, days);
  }

  private static LocalDate date(String v, String field, ValidationException.Collector errs) {
    if (v == null || v.isBlank()) {
      errs.add(field, "required");
      return null;
    }
    try {
      return LocalDate.parse(v.strip());
    } catch (DateTimeParseException e) {
      errs.add(field, "invalid");
      return null;
    }
  }

  @Override
  public Optional<Outcome> outcome(long marketNo) {
    Optional<MarketApi.MarketRef> found = markets.find(marketNo);
    if (found.isEmpty() || found.get().status() == MarketStatus.DRAFT) return Optional.empty();
    MarketApi.MarketRef m = found.get();
    SettleRows.Run payout = mapper.runByMarket(m.marketId(), SettleService.PAYOUT);
    SettleRows.Run refund = mapper.runByMarket(m.marketId(), SettleService.REFUND);
    String phase = payout == null ? "none" : switch (payout.status) {
      case SettleService.PAYING -> "paying";
      case SettleService.DONE -> "done";
      default -> "abnormal";
    };
    return Optional.of(new Outcome(String.valueOf(m.publicNo()), m.status().api(), phase, view(payout), view(refund)));
  }

  @Override
  public Page<Run> runs(String status, String kind, int page, int pageSize) {
    int p = Math.max(1, page);
    int size = Math.min(100, Math.max(1, pageSize));
    ValidationException.Collector errs = new ValidationException.Collector();
    String st = enumOf(status, Set.of("PAYING", "DONE", "ABNORMAL"), "status", errs);
    String k = enumOf(kind, Set.of("PAYOUT", "REFUND"), "kind", errs);
    errs.throwIfAny();
    List<Run> items = mapper.listRuns(st, k, (p - 1) * size, size).stream().map(this::view).toList();
    return new Page<>(items, mapper.countRuns(st, k), p, size);
  }

  @Override
  public Optional<RunDetail> run(long runNo, int page, int pageSize) {
    SettleRows.Run r = mapper.runByPublicNo(runNo);
    if (r == null) return Optional.empty();
    int p = Math.max(1, page);
    int size = Math.min(500, Math.max(1, pageSize));
    if (SettleService.PAYOUT.equals(r.kind)) {
      List<SettleRows.Item> rows = mapper.items(r.id, (p - 1) * size, size);
      Map<Long, MarketApi.OptionRef> opts = markets.optionsById(rows.stream().map(i -> i.optionId).distinct().toList());
      List<PayoutItem> items = rows.stream().map(i -> new PayoutItem(i.userId, opts.containsKey(i.optionId) ? String.valueOf(opts.get(i.optionId).optionNo()) : null,
          i.win, money(i.stake), money(i.gross), money(i.fee), money(i.payout), i.status.toLowerCase(Locale.ROOT))).toList();
      return Optional.of(new RunDetail(view(r), new Page<>(items, r.itemCount, p, size), null));
    }
    List<RefundItem> items = mapper.refundItems(r.id, (p - 1) * size, size).stream()
        .map(i -> new RefundItem(i.userId, String.valueOf(i.betId), money(i.amount), i.reason, i.status.toLowerCase(Locale.ROOT))).toList();
    return Optional.of(new RunDetail(view(r), null, new Page<>(items, r.itemCount, p, size)));
  }

  @Override
  public Run retry(long runNo, String actor) {
    settle.retry(runNo);
    return view(mapper.runByPublicNo(runNo));
  }

  @Override
  public Page<Issue> issues(String status, int page, int pageSize) {
    int p = Math.max(1, page);
    int size = Math.min(100, Math.max(1, pageSize));
    ValidationException.Collector errs = new ValidationException.Collector();
    String st = enumOf(status, Set.of("OPEN", "RESOLVED"), "status", errs);
    errs.throwIfAny();
    List<SettleRows.Issue> rows = mapper.listIssues(st, (p - 1) * size, size);
    Map<Long, String> nos = marketNos(rows.stream().map(i -> i.marketId).filter(x -> x != null).distinct().toList());
    return new Page<>(rows.stream().map(i -> view(i, nos)).toList(), mapper.countIssues(st), p, size);
  }

  @Override
  public Issue resolveIssue(long id, long adminId, String note) {
    if (note == null || note.isBlank()) throw new ValidationException(List.of(new ValidationException.Field("note", "required")));
    SettleRows.Issue i = mapper.issue(id);
    if (i == null) throw new BizException("NOT_FOUND", "差异不存在");
    if (mapper.resolveIssue(id, adminId, SettleService.cut(note.strip(), 500), LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS)) != 1) {
      throw new BizException("ISSUE_ALREADY_RESOLVED", "这条差异已经处理过了");
    }
    SettleRows.Issue after = mapper.issue(id);
    return view(after, marketNos(after.marketId == null ? List.of() : List.of(after.marketId)));
  }

  private Map<Long, String> marketNos(List<Long> marketIds) {
    Map<Long, String> out = new HashMap<>();
    markets.refs(marketIds).forEach((id, r) -> out.put(id, String.valueOf(r.publicNo())));
    return out;
  }

  private Issue view(SettleRows.Issue i, Map<Long, String> nos) {
    return new Issue(i.id, i.marketId == null ? null : nos.get(i.marketId), i.refKey, i.checkDate.toString(), i.kind, i.source, money(i.expected), money(i.actual),
        money(i.diff), i.detail, i.status.toLowerCase(Locale.ROOT), i.resolvedBy, iso(i.resolvedAt), i.note, iso(i.createdAt));
  }

  private Run view(SettleRows.Run r) {
    if (r == null) return null;
    String marketNo = markets.findById(r.marketId).map(m -> String.valueOf(m.publicNo())).orElse(null);
    String winNo = r.winningOptionId == null ? null
        : Optional.ofNullable(markets.optionsById(List.of(r.winningOptionId)).get(r.winningOptionId)).map(o -> String.valueOf(o.optionNo())).orElse(null);
    long pending = SettleService.PAYOUT.equals(r.kind) ? mapper.countPendingItems(r.id) : mapper.countPendingRefunds(r.id);
    BigDecimal income = r.sumFee == null ? null : r.sumFee.add(r.dust == null ? BigDecimal.ZERO : r.dust);
    return new Run(String.valueOf(r.publicNo), marketNo, r.kind.toLowerCase(Locale.ROOT), r.status.toLowerCase(Locale.ROOT), r.reason, winNo,
        r.feeRate == null ? null : SettleService.percent(r.feeRate), money(r.poolTotal), money(r.winnerPool), money(r.loserPool), money(r.sumGross),
        money(r.sumFee), money(r.dust), money(r.sumPayout), money(income), r.winnerPositions, r.loserPositions, r.itemCount, pending, r.conservationOk,
        r.abnormalReason, r.batchSize, r.resolvedBy, iso(r.calculatedAt), iso(r.doneAt));
  }

  private static String enumOf(String v, Set<String> allowed, String field, ValidationException.Collector errs) {
    if (v == null || v.isBlank()) return null;
    String u = v.strip().toUpperCase(Locale.ROOT);
    if (!allowed.contains(u)) {
      errs.add(field, "invalid");
      return null;
    }
    return u;
  }

  static String money(BigDecimal v) {
    return v == null ? null : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }
}
