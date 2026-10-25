package com.forecast.app.resolve.auto;

import com.forecast.app.market.api.MarketApi;
import com.forecast.app.resolve.api.ResolveAdminApi;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** {@link ResolveAdminApi} 的实现（forecast S6）：后台看自动裁决的进度、裁决记录、原始返回。只读。 */
@Service
class ResolveAdmin implements ResolveAdminApi {

  static final int FETCH_LIMIT = 100;

  private final ResolveMapper mapper;
  private final MarketApi markets;
  private final ObjectMapper json;

  ResolveAdmin(ResolveMapper mapper, MarketApi markets, ObjectMapper json) {
    this.mapper = mapper;
    this.markets = markets;
    this.json = json;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Evidence> evidence(long marketNo) {
    Optional<MarketApi.MarketRef> m = markets.find(marketNo);
    if (m.isEmpty()) return Optional.empty();
    long id = m.get().marketId();
    ResolveRows.Task t = mapper.task(id);
    List<ResolveRows.Record> records = mapper.records(id);
    Set<Long> optionIds = new HashSet<>();
    if (t != null && t.resultOptionId != null) optionIds.add(t.resultOptionId);
    for (ResolveRows.Record r : records) if (r.optionId != null) optionIds.add(r.optionId);
    Map<Long, MarketApi.OptionRef> opts = markets.optionsById(optionIds);
    List<Record> rv = records.stream().map(r -> new Record(r.id, r.kind.toLowerCase(Locale.ROOT), optionNo(opts, r.optionId), parse(r.detail), r.note, r.actor,
        iso(r.createdAt))).toList();
    List<Fetch> fv = mapper.fetches(id, FETCH_LIMIT).stream().map(f -> new Fetch(f.id, f.source, f.attempt, f.requestUrl, f.httpStatus,
        f.outcome.toLowerCase(Locale.ROOT), parse(f.parsed), f.error, f.body, f.bodySha256, iso(f.fetchedAt), f.elapsedMs)).toList();
    return Optional.of(new Evidence(String.valueOf(marketNo), t == null ? null : task(t, String.valueOf(marketNo), opts), rv, fv));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<Task> manual(int page, int pageSize) {
    int p = Math.max(1, page);
    int size = Math.min(100, Math.max(1, pageSize));
    List<ResolveRows.Task> rows = mapper.manualTasks((p - 1) * size, size);
    Map<Long, MarketApi.MarketRef> refs = markets.refs(rows.stream().map(t -> t.marketId).toList());
    List<Task> items = rows.stream().map(t -> task(t, refs.containsKey(t.marketId) ? String.valueOf(refs.get(t.marketId).publicNo()) : null, Map.of())).toList();
    return new Page<>(items, mapper.countManual(), p, size);
  }

  private Task task(ResolveRows.Task t, String marketNo, Map<Long, MarketApi.OptionRef> opts) {
    return new Task(marketNo, t.symbol, iso(t.windowStart), iso(t.windowEnd), t.status.toLowerCase(Locale.ROOT), t.attempts, iso(t.nextAttemptAt),
        iso(t.deadlineAt), optionNo(opts, t.resultOptionId), t.lastReason, iso(t.lastAttemptAt), iso(t.updatedAt));
  }

  private static String optionNo(Map<Long, MarketApi.OptionRef> opts, Long optionId) {
    return optionId == null || !opts.containsKey(optionId) ? null : String.valueOf(opts.get(optionId).optionNo());
  }

  private Object parse(String s) {
    return s == null ? null : json.readValue(s, new TypeReference<Map<String, Object>>() {});
  }

  private static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }
}
