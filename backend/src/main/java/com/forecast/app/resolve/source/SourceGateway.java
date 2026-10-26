package com.forecast.app.resolve.source;

import com.forecast.app.common.http.ExternalHttp;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 数据源网关（技术方案/v1-架构方案.md 第 6.1、6.3 节；agents C81；forecast S6，S8 起体育数据源也走这里）：适配器只管拼地址和解析，这里统一做
 * <ol>
 *   <li><b>超时</b>：连接超时、读取超时（读完响应体的总时长）、响应体上限（{@link ExternalHttp}）；每个来源可以单独覆盖（体育读取 10 秒）；</li>
 *   <li><b>有上限的退避重试</b>：一轮最多 {@code attempts} 次，间隔 backoff、2 × backoff …（不超过 maxBackoff）；只重试超时、连不上、断开、5xx、429、408；
 *       格式错误、其他 4xx 不重试（重试也一样）；"数据还没好"（K 线还不全）不重试（下一轮再来）；</li>
 *   <li><b>熔断</b>（{@link CircuitBreaker}，按来源）：连续失败打开后直接返回 {@code CIRCUIT_OPEN}，不再打这个接口；</li>
 *   <li><b>限频</b>（S8，{@link SourceRateLimiter}，按配置的 key，同一个网站共用）：每个窗口最多 N 次，超过的<b>排队</b>，排队超过 queueWait 的这次不调用
 *       （{@code THROTTLED}，不算故障）；收到 429 按 Retry-After 暂停这个来源；</li>
 *   <li><b>存档</b>：每次 HTTP 调用（成功、失败、超时）和每次被熔断、限频跳过，都写原始返回（含 sha256，{@link SourceArchive}）；
 *       同一次调用给几个市场用时每个市场各写一行（S8：同一场比赛的几个玩法）。成功但存档失败当作取数失败。存档里的地址不带 key。</li>
 * </ol>
 * <b>不能在数据库事务里调用</b>（C81 第 3 条：外部变慢时不能占住连接和锁），在事务里调用直接抛 {@link IllegalStateException}。
 * 最长耗时 ≈ 排队 + attempts × (连接超时 + 读取超时) + Σ退避：加密默认 3 × 8 + 3 = 27 秒，体育 3 × 13 + 6 = 45 秒（不含排队），不会一直卡住调用方。
 */
@Component
public class SourceGateway {

  private static final Logger log = LoggerFactory.getLogger(SourceGateway.class);
  private static final String USER_AGENT = "forecast-resolve/1";
  /** 429 以后最多暂停多久 */
  static final Duration MAX_PENALTY = Duration.ofSeconds(120);

  public enum Outcome {
    /** 拿到了完整数据 */
    OK,
    /** 数据还不全（K 线根数不够、没收盘）：下一轮再取，不算故障 */
    NOT_READY,
    /** 失败（超时、连不上、断开、HTTP 错误、格式不对、存档失败），重试过了 */
    FAILED,
    /** 熔断中，没有调用 */
    CIRCUIT_OPEN,
    /** 限频排队超过上限，这一轮没有调用（forecast S8），不算故障 */
    THROTTLED,
    /** 这个数据源没打开（配置）或未开通（没有 key） */
    DISABLED
  }

  /**
   * 一轮 K 线取数的结果（S6 的加密裁决用）。
   *
   * @param candles OK 时的价格
   * @param archiveId 最后一次调用的存档行
   * @param attempts 这一轮实际调用了几次
   */
  public record Result(String source, String label, Outcome outcome, Candles candles, String error, Long archiveId, String bodySha256, Instant fetchedAt,
      int attempts) {

    public boolean ok() {
      return outcome == Outcome.OK;
    }
  }

  private final ResolveProperties props;
  private final SourceArchive archive;
  private final Clock clock;
  private final ExternalHttp http;
  private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
  private final Map<String, SourceRateLimiter> limiters = new ConcurrentHashMap<>();

  @Autowired
  public SourceGateway(ResolveProperties props, SourceArchive archive, Clock clock) {
    this(props, archive, clock, new ExternalHttp());
  }

  SourceGateway(ResolveProperties props, SourceArchive archive, Clock clock, ExternalHttp http) {
    this.props = props;
    this.archive = archive;
    this.clock = clock;
    this.http = http;
  }

  /** 这个数据源的熔断（按配置建，测试改了配置以后调 {@link #resetBreakers()}） */
  public CircuitBreaker breaker(String source) {
    ResolveProperties.Gateway g = props.getGateway();
    return breakers.computeIfAbsent(source, k -> new CircuitBreaker(g.getFailureThreshold(), g.getOpenDuration(), clock));
  }

  /** 测试用：清掉熔断状态（和限频状态） */
  public void resetBreakers() {
    breakers.clear();
    limiters.clear();
  }

  public boolean enabled(CandleSource src) {
    return usable(src.key());
  }

  /** 配置 key 对应的来源能不能用（打开、有地址、要 key 的有 key） */
  public boolean usable(String key) {
    return props.source(key).usable();
  }

  /** 来源的配置（地址、key）：适配器拼请求用 */
  public ResolveProperties.Source config(String key) {
    return props.source(key);
  }

  /** 限频（没配限额的为 null） */
  SourceRateLimiter limiter(String key) {
    ResolveProperties.Source s = props.source(key);
    if (s.getRateLimit() <= 0) return null;
    return limiters.computeIfAbsent(key, k -> new SourceRateLimiter(s.getRateLimit(), s.getRateWindow()));
  }

  /**
   * 取一个窗口的价格（一轮：最多 attempts 次调用）。
   *
   * @param marketId 存档里记为哪个市场取的（可以为空）
   */
  public Result fetch(CandleSource src, CandleQuery q, Long marketId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("不能在数据库事务里调用外部接口（C81）：" + src.name());
    }
    if (!enabled(src)) return new Result(src.name(), src.label(q), Outcome.DISABLED, null, "数据源没有打开", null, null, Instant.now(clock), 0);
    URI uri = src.uri(props.source(src.key()).getBaseUrl(), q);
    SourceRequest<Candles> req = new SourceRequest<>(src.name(), src.key(), uri, uri.toString(), Map.of(), body -> src.parse(body, q), Candles::toMap);
    SourceReply<Candles> r = call(req, marketId == null ? List.of() : List.of(marketId), null);
    return new Result(src.name(), src.label(q), r.outcome(), r.value(), r.error(), r.archiveId(), r.bodySha256(), r.fetchedAt(), r.attempts());
  }

  /**
   * 一轮取数（forecast S8 起的通用入口）：最多 attempts 次调用，每次都存档（给 marketIds 里的每个市场各写一行；为空时写一行、市场为空）。
   *
   * @param queueWait 限频排队最多等多久；为空用来源的配置（后台的交互式调用传短一些）
   */
  public <T> SourceReply<T> call(SourceRequest<T> req, Collection<Long> marketIds, Duration queueWait) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("不能在数据库事务里调用外部接口（C81）：" + req.source());
    }
    ResolveProperties.Source cfg = props.source(req.key());
    if (!cfg.usable()) return new SourceReply<>(req.source(), Outcome.DISABLED, null, cfg.unusableReason(), null, null, Instant.now(clock), 0, null);
    Settings st = settings(cfg);
    CircuitBreaker breaker = breaker(req.source());
    SourceRateLimiter limiter = limiter(req.key());
    Duration wait = queueWait != null ? queueWait : cfg.getQueueWait();
    SourceReply<T> last = null;
    for (int attempt = 1; attempt <= st.attempts; attempt++) {
      if (attempt > 1) sleep(backoff(st.backoff, st.maxBackoff, attempt - 1));
      if (limiter != null) {
        long ns = limiter.reserve(wait);
        if (ns < 0) {
          Instant at = Instant.now(clock);
          String err = "限频排队超过 " + wait.toSeconds() + " 秒，这一轮没有调用（每 " + limiter.window().toSeconds() + " 秒最多 " + limiter.limit() + " 次）"
              + (last == null ? "" : "；上一次：" + last.error());
          int a = attempt;
          Long id = saveAll(marketIds, m -> new SourceArchive.Entry(m, req.source(), a, req.displayUrl(), null, null, null, Outcome.THROTTLED.name(), null,
              cut(err), at, 0));
          return new SourceReply<>(req.source(), Outcome.THROTTLED, null, err, id, null, at, attempt - 1, null);
        }
        if (ns > 0) sleepNanos(ns);
      }
      if (!breaker.allow()) {
        Instant at = Instant.now(clock);
        int a = attempt;
        Long id = saveAll(marketIds, m -> new SourceArchive.Entry(m, req.source(), a, req.displayUrl(), null, null, null, Outcome.CIRCUIT_OPEN.name(), null,
            "熔断中：连续失败后暂停调用", at, 0));
        ResolveProperties.Gateway g = props.getGateway();
        return new SourceReply<>(req.source(), Outcome.CIRCUIT_OPEN, null, "熔断中（连续失败 " + g.getFailureThreshold() + " 次后暂停 "
            + g.getOpenDuration().toSeconds() + " 秒）", id, null, at, attempt - 1, null);
      }
      Call<T> c = once(req, attempt, marketIds, st, limiter, cfg);
      if (c.reply.outcome() == Outcome.OK || c.reply.outcome() == Outcome.NOT_READY) breaker.success();
      else breaker.failure();
      last = c.reply;
      if (!c.retryable) return last;
    }
    log.warn("数据源 {} 取 {} 失败（{} 次）：{}", req.source(), req.displayUrl(), st.attempts, last.error());
    return last;
  }

  /** 这个来源实际用的超时和重试（来源的覆盖，没有就用网关默认） */
  private record Settings(Duration connect, Duration read, int attempts, Duration backoff, Duration maxBackoff, int maxBytes) {}

  private Settings settings(ResolveProperties.Source s) {
    ResolveProperties.Gateway g = props.getGateway();
    return new Settings(s.getConnectTimeout() != null ? s.getConnectTimeout() : g.getConnectTimeout(),
        s.getReadTimeout() != null ? s.getReadTimeout() : g.getReadTimeout(), Math.max(1, s.getAttempts() != null ? s.getAttempts() : g.getAttempts()),
        s.getBackoff() != null ? s.getBackoff() : g.getBackoff(), s.getMaxBackoff() != null ? s.getMaxBackoff() : g.getMaxBackoff(), g.getMaxBodyBytes());
  }

  private record Call<T>(SourceReply<T> reply, boolean retryable) {}

  private <T> Call<T> once(SourceRequest<T> req, int attempt, Collection<Long> marketIds, Settings st, SourceRateLimiter limiter, ResolveProperties.Source cfg) {
    Instant at = Instant.now(clock);
    long t0 = System.nanoTime();
    ExternalHttp.Response r;
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("User-Agent", USER_AGENT);
    if (req.headers() != null) headers.putAll(req.headers());
    try {
      r = http.get(req.uri(), st.connect, st.read, st.maxBytes, headers);
    } catch (ExternalHttp.Failure f) {
      int ms = elapsed(t0);
      String err = f.kind().name() + "：" + f.getMessage();
      Long id = saveAll(marketIds, m -> new SourceArchive.Entry(m, req.source(), attempt, req.displayUrl(), null, null, null, Outcome.FAILED.name(), null,
          cut(err), at, ms));
      // 响应太大：重试也一样
      return new Call<>(new SourceReply<>(req.source(), Outcome.FAILED, null, err, id, null, at, attempt, null), f.kind() != ExternalHttp.Kind.TOO_LARGE);
    }
    int ms = elapsed(t0);
    String body = new String(r.body(), StandardCharsets.UTF_8);
    String sha = sha256(r.body());
    if (r.status() < 200 || r.status() >= 300) {
      String err = "HTTP " + r.status();
      boolean retry = r.status() >= 500 || r.status() == 429 || r.status() == 408;
      if (r.status() == 429) {
        Duration pause = penalty(r, cfg);
        if (limiter != null) limiter.block(pause);
        err += "（限频，暂停 " + pause.toSeconds() + " 秒）";
      }
      String e = err;
      Long id = saveAll(marketIds, m -> new SourceArchive.Entry(m, req.source(), attempt, req.displayUrl(), r.status(), body, sha, Outcome.FAILED.name(), null,
          e, at, ms));
      return new Call<>(new SourceReply<>(req.source(), Outcome.FAILED, null, err, id, sha, at, attempt, r.status()), retry);
    }
    try {
      T v = req.parser().parse(body);
      Map<String, Object> parsed = req.summary() == null || v == null ? null : req.summary().apply(v);
      Long id = saveAll(marketIds, m -> new SourceArchive.Entry(m, req.source(), attempt, req.displayUrl(), r.status(), body, sha, Outcome.OK.name(), parsed,
          null, at, ms));
      if (id == null) return new Call<>(new SourceReply<>(req.source(), Outcome.FAILED, null, "存档失败", null, sha, at, attempt, r.status()), false);
      return new Call<>(new SourceReply<>(req.source(), Outcome.OK, v, null, id, sha, at, attempt, r.status()), false);
    } catch (SourceException e) {
      Outcome o = e.kind() == SourceException.Kind.NOT_READY ? Outcome.NOT_READY : Outcome.FAILED;
      String err = (o == Outcome.FAILED ? "格式不对：" : "") + e.getMessage();
      Long id = saveAll(marketIds, m -> new SourceArchive.Entry(m, req.source(), attempt, req.displayUrl(), r.status(), body, sha, o.name(), null, cut(err),
          at, ms));
      return new Call<>(new SourceReply<>(req.source(), o, null, err, id, sha, at, attempt, r.status()), false);
    }
  }

  /** 429 以后暂停多久：Retry-After（秒）或 football-data 的 X-RequestCounter-Reset（秒），没有就按限频窗口；最多 2 分钟 */
  static Duration penalty(ExternalHttp.Response r, ResolveProperties.Source cfg) {
    for (String h : List.of("retry-after", "x-requestcounter-reset")) {
      String v = r.header(h);
      if (v == null) continue;
      try {
        long sec = Long.parseLong(v.strip());
        if (sec >= 0) return Duration.ofSeconds(Math.min(sec, MAX_PENALTY.toSeconds()));
      } catch (NumberFormatException e) {
        // 不是秒数（HTTP 日期）：按窗口
      }
    }
    Duration w = cfg.getRateWindow() == null ? Duration.ofMinutes(1) : cfg.getRateWindow();
    return w.compareTo(MAX_PENALTY) > 0 ? MAX_PENALTY : w;
  }

  /** 给每个市场各存一行（没有市场时存一行、市场为空）；返回第一行的 id，任何一行失败返回 null（记日志） */
  private Long saveAll(Collection<Long> marketIds, java.util.function.Function<Long, SourceArchive.Entry> entry) {
    List<Long> ids = marketIds == null || marketIds.isEmpty() ? java.util.Collections.singletonList(null) : new ArrayList<>(marketIds);
    Long first = null;
    boolean failed = false;
    for (Long m : ids) {
      SourceArchive.Entry e = entry.apply(m);
      try {
        long id = archive.save(e);
        if (first == null) first = id;
      } catch (RuntimeException ex) {
        log.error("数据源 {} 的原始返回存档失败", e.source(), ex);
        failed = true;
      }
    }
    return failed ? null : first;
  }

  static Duration backoff(ResolveProperties.Gateway g, int retry) {
    return backoff(g.getBackoff(), g.getMaxBackoff(), retry);
  }

  static Duration backoff(Duration base, Duration max, int retry) {
    long ms = base.toMillis() * (1L << Math.min(20, retry - 1));
    return Duration.ofMillis(Math.min(ms, max.toMillis()));
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void sleepNanos(long ns) {
    try {
      Thread.sleep(Duration.ofNanos(ns));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static int elapsed(long t0) {
    return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - t0) / 1_000_000);
  }

  public static String sha256(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String cut(String s) {
    return s == null || s.length() <= 500 ? s : s.substring(0, 500);
  }
}
