package com.forecast.app.resolve.auto;

import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.resolve.api.ResultHandler;
import com.forecast.app.resolve.platform.CaseBook;
import com.forecast.app.resolve.source.BinanceSpot;
import com.forecast.app.resolve.source.CandleQuery;
import com.forecast.app.resolve.source.CandleSource;
import com.forecast.app.resolve.source.CoinbaseSpot;
import com.forecast.app.resolve.source.OkxSpot;
import com.forecast.app.resolve.source.ResolveProperties;
import com.forecast.app.resolve.source.SourceGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 加密市场的自动裁决（forecast S6；需求说明书第 4.3 节、E9、E10、AC-27、AC-29；技术方案/v1-架构方案.md 第 5.2 节 CryptoResolver、第 6 节）。
 * <ol>
 *   <li>市场进入待裁决时建进度行（{@link ResolveMarketListener}）：第一次取数时间 = 窗口结束 + 1 分钟，转人工的时间 = 再加 30 分钟。</li>
 *   <li>{@link #resolveDue()}（任务每 15 秒）：到时间的逐个处理。<b>先在事务外</b>并行取主源（币安）和备源（OKX）——网关负责超时、重试、熔断、存档；
 *       主备一方拿不到且打开了第三源（Coinbase）时用它补位。</li>
 *   <li>判定（{@link CryptoDecision}）：两个来源方向一致 → 记下结果（PENDING → RESOLVING，写裁决记录 AUTO_RESULT，一个事务）→ 调 settle 结算
 *       （{@link ResultHandler}，S5 的结算三段，自己开事务）→ RESOLVED。结算失败或进程中途退出：RESOLVING 的下一轮接着调（结算本身幂等）。</li>
 *   <li>不一致或拿不到：每分钟重试；过了转人工的时间仍不行 → MANUAL，写 AUTO_FAILED（带两个来源的数据），建平台裁决的进度行（S7 的裁决台"自动裁决失败"），
 *       邮件告警。市场留在待裁决，由人按首选来源判定（提议 → 确认 → 公示）或作废。</li>
 * </ol>
 * 进度行的每次改动都是带旧状态和版本号的条件更新：多实例、重复执行只生效一次。
 */
@Service
public class CryptoResolver {

  private static final Logger log = LoggerFactory.getLogger(CryptoResolver.class);
  static final String AUTO = "AUTO";
  static final String KIND_RESULT = "AUTO_RESULT";
  static final String KIND_FAILED = "AUTO_FAILED";
  /** 每轮最多处理几个市场（处理不完的下一轮接着处理） */
  static final int BATCH = 50;

  private final ResolveMapper mapper;
  private final MarketApi markets;
  private final SourceGateway gateway;
  private final CandleSource primary;
  private final CandleSource backup;
  private final CandleSource third;
  private final ResolveProperties props;
  private final ObjectProvider<ResultHandler> handler;
  private final StaffAlertApi staff;
  private final CaseBook cases;
  private final Clock clock;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  CryptoResolver(ResolveMapper mapper, MarketApi markets, SourceGateway gateway, BinanceSpot binance, OkxSpot okx, CoinbaseSpot coinbase,
      ResolveProperties props, ObjectProvider<ResultHandler> handler, StaffAlertApi staff, CaseBook cases, Clock clock, ObjectMapper json,
      PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.markets = markets;
    this.gateway = gateway;
    this.primary = binance;
    this.backup = okx;
    this.third = coinbase;
    this.props = props;
    this.handler = handler;
    this.staff = staff;
    this.cases = cases;
    this.clock = clock;
    this.json = json;
    this.tx = new TransactionTemplate(tm);
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  /**
   * 跑一轮：到时间的市场逐个处理（不在事务里）。
   *
   * @return 这一轮各市场的处理结果（市场 id → settled / retry / manual / closed …），给测试和日志看
   */
  public Map<Long, String> resolveDue() {
    Map<Long, String> out = new LinkedHashMap<>();
    for (long id : mapper.dueTasks(now(), BATCH)) {
      try {
        out.put(id, resolveOne(id));
      } catch (RuntimeException e) {
        log.error("市场 {} 自动裁决这一轮失败，下一轮重试", id, e);
        out.put(id, "error");
      }
    }
    return out;
  }

  /** 处理一个市场（到时间才处理；测试也直接调用） */
  public String resolveOne(long marketId) {
    ResolveRows.Task t = mapper.task(marketId);
    if (t == null) return "none";
    LocalDateTime now = now();
    if (!"PENDING".equals(t.status) && !"RESOLVING".equals(t.status)) return t.status.toLowerCase();
    if (t.nextAttemptAt.isAfter(now)) return "notDue";
    if ("RESOLVING".equals(t.status)) return finish(t);
    MarketApi.AutoRef m = markets.autoRef(marketId).orElse(null);
    if (m == null || m.status() != MarketStatus.PENDING_RESOLUTION) {
      mapper.close(marketId, "市场已不在待裁决（" + (m == null ? "不存在" : m.status().api()) + "）", now);
      return "closed";
    }
    CandleQuery q = new CandleQuery(t.symbol, instant(t.windowStart), instant(t.windowEnd));
    SourceGateway.Result[] pb = fetchBoth(q, marketId);
    SourceGateway.Result p = pb[0];
    SourceGateway.Result b = pb[1];
    SourceGateway.Result c = null;
    // 第三源只在主备一方拿不到时补位（X-7，默认关闭）
    if (p.ok() != b.ok() && gateway.enabled(third)) c = gateway.fetch(third, q, marketId);
    CryptoDecision.Verdict v = CryptoDecision.decide(p, b, c);
    LocalDateTime after = now();
    Map<String, Object> detail = detail(t, v, p, b, c);
    if (v.decided()) {
      Long optionId = optionOf(m, v.direction());
      if (optionId == null) return manual(t, m, "市场的自动裁决参数里没有 " + v.direction() + " 对应的选项", detail, after);
      Boolean ok = tx.execute(st -> {
        if (mapper.toResolving(marketId, t.version, optionId, after.plus(props.getRetryInterval()), cut(v.reason()), after) != 1) return false;
        mapper.insertRecord(record(marketId, KIND_RESULT, optionId, detail, v.reason(), after));
        return true;
      });
      if (!Boolean.TRUE.equals(ok)) return "raced";
      log.info("市场 {} 自动裁决：{}（{}）", m.publicNo(), v.direction(), v.reason());
      return finish(mapper.task(marketId));
    }
    if (!after.isBefore(t.deadlineAt)) return manual(t, m, v.reason(), detail, after);
    mapper.retryLater(marketId, t.version, after.plus(props.getRetryInterval()), cut(v.reason()), after);
    log.info("市场 {} 自动裁决这一轮没定下来，{} 后重试：{}", m.publicNo(), props.getRetryInterval(), v.reason());
    return "retry";
  }

  /** 并行取主源和备源（各自一轮，网关里有超时和重试；最长 ≈ 一个来源的一轮） */
  private SourceGateway.Result[] fetchBoth(CandleQuery q, long marketId) {
    try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<SourceGateway.Result> fp = ex.submit(() -> gateway.fetch(primary, q, marketId));
      Future<SourceGateway.Result> fb = ex.submit(() -> gateway.fetch(backup, q, marketId));
      return new SourceGateway.Result[] {get(fp, primary, q), get(fb, backup, q)};
    }
  }

  private SourceGateway.Result get(Future<SourceGateway.Result> f, CandleSource src, CandleQuery q) {
    try {
      return f.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return failed(src, q, "被中断");
    } catch (ExecutionException e) {
      log.error("数据源 {} 取数出错", src.name(), e.getCause());
      return failed(src, q, String.valueOf(e.getCause()));
    }
  }

  private SourceGateway.Result failed(CandleSource src, CandleQuery q, String err) {
    return new SourceGateway.Result(src.name(), src.label(q), SourceGateway.Outcome.FAILED, null, err, null, null, Instant.now(clock), 0);
  }

  /** 结果已定：调 settle 结算；结算失败下一轮（retryInterval 之后）再调 */
  private String finish(ResolveRows.Task t) {
    LocalDateTime now = now();
    MarketApi.AutoRef m = markets.autoRef(t.marketId).orElse(null);
    if (m == null || m.status() != MarketStatus.PENDING_RESOLUTION) {
      // 已经结算完（一个事务模式）、E1 作废，或者被人工处理了：结算已经触发过或不用再触发
      mapper.toResolved(t.marketId, "市场已是 " + (m == null ? "不存在" : m.status().api()), now);
      return m != null && m.status() == MarketStatus.SETTLED ? "settled" : "resolved";
    }
    ResultHandler h = handler.getIfAvailable();
    if (h == null) {
      mapper.resolvingLater(t.marketId, now.plus(props.getRetryInterval()), "没有结算模块", now);
      log.error("市场 {} 结果已定，但没有结算模块（ResultHandler）", m.publicNo());
      return "settleFailed";
    }
    try {
      h.settle(t.marketId, t.resultOptionId, null, AUTO);
    } catch (RuntimeException e) {
      mapper.resolvingLater(t.marketId, now.plus(props.getRetryInterval()), cut("结算失败：" + e.getMessage()), now);
      log.error("市场 {} 自动裁决后结算失败，{} 后重试", m.publicNo(), props.getRetryInterval(), e);
      return "settleFailed";
    }
    mapper.toResolved(t.marketId, "已触发结算", now());
    MarketStatus after = markets.autoRef(t.marketId).map(MarketApi.AutoRef::status).orElse(null);
    return after == MarketStatus.SETTLED ? "settled" : after == MarketStatus.VOIDED ? "voided" : "settling";
  }

  /** 转人工：PENDING → MANUAL，写 AUTO_FAILED（带各来源的数据），提交后邮件告警（AC-29） */
  private String manual(ResolveRows.Task t, MarketApi.AutoRef m, String reason, Map<String, Object> detail, LocalDateTime now) {
    Boolean ok = tx.execute(st -> {
      if (mapper.toManual(t.marketId, t.version, cut(reason), now) != 1) return false;
      mapper.insertRecord(record(t.marketId, KIND_FAILED, null, detail, reason, now));
      // S7：进裁决台（"自动裁决失败"），提议时限从现在算
      cases.open(t.marketId, CaseBook.AUTO_FAILED, m.category());
      return true;
    });
    if (!Boolean.TRUE.equals(ok)) return "raced";
    log.warn("市场 {} 自动裁决失败，转人工：{}", m.publicNo(), reason);
    alert(m, reason);
    return "manual";
  }

  void alert(MarketApi.AutoRef m, String reason) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("marketNo", String.valueOf(m.publicNo()));
    p.put("market", m.title().zhCN());
    p.put("marketEn", m.title().en());
    p.put("minutes", String.valueOf(props.getGiveUpAfter().toMinutes()));
    p.put("reason", reason == null || reason.isBlank() ? "-" : reason);
    try {
      staff.notifyStaff("RESOLVE_AUTO_FAILED", p);
    } catch (RuntimeException e) {
      log.error("市场 {} 转人工的告警邮件发送失败", m.publicNo(), e);
    }
  }

  /** 结果 → 选项（市场的自动裁决参数 sourceRef.options，例如 {"UP": 1, "DOWN": 2} 是选项序号） */
  static Long optionOf(MarketApi.AutoRef m, String direction) {
    Object map = m.sourceRef() == null ? null : m.sourceRef().get("options");
    if (!(map instanceof Map<?, ?> opts) || !(opts.get(direction) instanceof Number seq)) return null;
    return m.options().stream().filter(o -> o.seq() == seq.intValue()).findFirst().map(MarketApi.OptionRef::optionId).orElse(null);
  }

  /** 裁决记录里的依据：各来源的开始价、结束价、方向、存档行 */
  private Map<String, Object> detail(ResolveRows.Task t, CryptoDecision.Verdict v, SourceGateway.Result... results) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("result", v.direction());
    d.put("basis", v.basis());
    d.put("reason", v.reason());
    d.put("symbol", t.symbol);
    d.put("windowStart", instant(t.windowStart).toString());
    d.put("windowEnd", instant(t.windowEnd).toString());
    List<Map<String, Object>> sources = new ArrayList<>();
    for (SourceGateway.Result r : results) {
      if (r == null || r.outcome() == SourceGateway.Outcome.DISABLED) continue;
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("source", r.source());
      s.put("label", r.label());
      s.put("outcome", r.outcome().name());
      s.put("used", v.used().contains(r.source()));
      if (r.candles() != null) {
        s.put("startPrice", r.candles().open().toPlainString());
        s.put("endPrice", r.candles().close().toPlainString());
        s.put("direction", r.candles().direction());
      }
      s.put("error", r.error());
      s.put("fetchId", r.archiveId());
      s.put("sha256", r.bodySha256());
      s.put("fetchedAt", r.fetchedAt() == null ? null : r.fetchedAt().toString());
      s.put("attempts", r.attempts());
      sources.add(s);
    }
    d.put("sources", sources);
    return d;
  }

  private ResolveRows.Record record(long marketId, String kind, Long optionId, Map<String, Object> detail, String note, LocalDateTime now) {
    ResolveRows.Record r = new ResolveRows.Record();
    r.marketId = marketId;
    r.kind = kind;
    r.optionId = optionId;
    r.detail = json.writeValueAsString(detail);
    r.note = cut(note);
    r.actor = AUTO;
    r.createdAt = now;
    return r;
  }

  static Instant instant(LocalDateTime t) {
    return t.toInstant(ZoneOffset.UTC);
  }

  static String cut(String s) {
    return s == null || s.length() <= 500 ? s : s.substring(0, 500);
  }
}
