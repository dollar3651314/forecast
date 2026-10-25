package com.forecast.app.settle.run;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.settle.api.SettleAdminApi;
import com.forecast.app.settle.api.SettleApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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

  /** 人工作废能用的状态（待裁决、公示中、争议中 → 已作废，第 15 行的 3 条边） */
  static final Set<MarketStatus> VOIDABLE = SettleService.SETTLEABLE;

  private final SettleService settle;
  private final SettleMapper mapper;
  private final MarketApi markets;
  private final Clock clock;

  SettleAdmin(SettleService settle, SettleMapper mapper, MarketApi markets, Clock clock) {
    this.settle = settle;
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

  /** 校验过的手工结果 */
  private record Manual(MarketApi.MarketRef market, MarketApi.OptionRef option, Instant actualStartAt, List<ValidationException.Field> errors) {}

  private Manual manual(long marketNo, String optionNo, String actualStartAt) {
    MarketApi.MarketRef m = market(marketNo);
    List<ValidationException.Field> errs = new java.util.ArrayList<>();
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
        if (start.isAfter(Instant.now(clock))) errs.add(new ValidationException.Field("actualStartAt", "future"));
      } catch (DateTimeParseException e) {
        errs.add(new ValidationException.Field("actualStartAt", "invalid"));
      }
    }
    return new Manual(m, option, start, errs);
  }

  @Override
  public List<ValidationException.Field> checkManual(long marketNo, String optionNo, String actualStartAt) {
    return manual(marketNo, optionNo, actualStartAt).errors();
  }

  @Override
  public Outcome settleManually(long marketNo, String optionNo, String actualStartAt, String resolvedBy) {
    Manual x = manual(marketNo, optionNo, actualStartAt);
    if (!x.errors().isEmpty()) throw new ValidationException(x.errors());
    MarketApi.MarketRef m = x.market();
    MarketApi.OptionRef option = x.option();
    Instant start = x.actualStartAt();
    if (!SettleService.SETTLEABLE.contains(m.status()) && m.status() != MarketStatus.SETTLED) {
      throw new BizException("MARKET_NOT_SETTLEABLE", "市场现在不能结算（" + m.status().api() + "）：要等事件结束、进入待裁决");
    }
    if (m.status() != MarketStatus.SETTLED) settle.settle(m.marketId(), option.optionId(), start, resolvedBy);
    return outcome(marketNo).orElseThrow();
  }

  @Override
  public Outcome voidManually(long marketNo, String reason, String actor) {
    MarketApi.MarketRef m = market(marketNo);
    if (!VOIDABLE.contains(m.status())) throw new BizException("MARKET_NOT_VOIDABLE", "只有待裁决、公示中、争议中的市场能作废（截止前请用取消）");
    if (!markets.transition(m.marketId(), m.status(), MarketStatus.VOIDED, actor, reason)) throw new BizException("MARKET_STATE_CHANGED", "市场状态已变化，请刷新");
    return outcome(marketNo).orElseThrow();
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
