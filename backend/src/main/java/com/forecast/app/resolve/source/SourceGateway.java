package com.forecast.app.resolve.source;

import com.forecast.app.common.http.ExternalHttp;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 数据源网关（技术方案/v1-架构方案.md 第 6.1、6.3 节；agents C81；forecast S6）：适配器只管拼地址和解析，这里统一做
 * <ol>
 *   <li><b>超时</b>：连接超时、读取超时（读完响应体的总时长）、响应体上限（{@link ExternalHttp}）；</li>
 *   <li><b>有上限的退避重试</b>：一轮最多 {@code attempts} 次，间隔 backoff、2 × backoff …（不超过 maxBackoff）；只重试超时、连不上、断开、5xx、429、408；
 *       格式错误、其他 4xx 不重试（重试也一样）；"K 线还不全"不重试（下一轮再来）；</li>
 *   <li><b>熔断</b>（{@link CircuitBreaker}）：连续失败打开后直接返回 {@code CIRCUIT_OPEN}，不再打这个接口；</li>
 *   <li><b>存档</b>：每次 HTTP 调用（成功、失败、超时）和每次被熔断跳过，都写一行原始返回（含 sha256，{@link SourceArchive}）；存档失败当作取数失败。</li>
 * </ol>
 * <b>不能在数据库事务里调用</b>（C81 第 3 条：外部变慢时不能占住连接和锁），在事务里调用直接抛 {@link IllegalStateException}。
 * 最长耗时 ≈ attempts × (连接超时 + 读取超时) + Σ退避，默认 3 × 8 + 3 = 27 秒，不会一直卡住调用方。
 */
@Component
public class SourceGateway {

  private static final Logger log = LoggerFactory.getLogger(SourceGateway.class);

  public enum Outcome {
    /** 拿到了窗口的完整数据 */
    OK,
    /** 数据还不全（K 线根数不够、没收盘）：下一轮再取，不算故障 */
    NOT_READY,
    /** 失败（超时、连不上、断开、HTTP 错误、格式不对、存档失败），重试过了 */
    FAILED,
    /** 熔断中，没有调用 */
    CIRCUIT_OPEN,
    /** 这个数据源没打开（配置） */
    DISABLED
  }

  /**
   * 一轮取数的结果。
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

  /** 测试用：清掉熔断状态 */
  public void resetBreakers() {
    breakers.clear();
  }

  public boolean enabled(CandleSource src) {
    ResolveProperties.Source s = props.source(src.key());
    return s.isEnabled() && s.getBaseUrl() != null && !s.getBaseUrl().isBlank();
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
    ResolveProperties.Gateway g = props.getGateway();
    URI uri = src.uri(props.source(src.key()).getBaseUrl(), q);
    CircuitBreaker breaker = breaker(src.name());
    int max = Math.max(1, g.getAttempts());
    Result last = null;
    for (int attempt = 1; attempt <= max; attempt++) {
      if (attempt > 1) sleep(backoff(g, attempt - 1));
      if (!breaker.allow()) {
        Instant at = Instant.now(clock);
        Long id = save(new SourceArchive.Entry(marketId, src.name(), attempt, uri.toString(), null, null, null, Outcome.CIRCUIT_OPEN.name(), null,
            "熔断中：连续失败后暂停调用", at, 0));
        return new Result(src.name(), src.label(q), Outcome.CIRCUIT_OPEN, null, "熔断中（连续失败 " + g.getFailureThreshold() + " 次后暂停 "
            + g.getOpenDuration().toSeconds() + " 秒）", id, null, at, attempt - 1);
      }
      Call c = call(src, q, uri, attempt, marketId, g);
      if (c.result.outcome() == Outcome.OK || c.result.outcome() == Outcome.NOT_READY) breaker.success();
      else breaker.failure();
      last = c.result;
      if (!c.retryable) return last;
    }
    log.warn("数据源 {} 取 {} {}～{} 失败（{} 次）：{}", src.name(), q.symbol(), q.windowStart(), q.windowEnd(), max, last.error());
    return last;
  }

  private record Call(Result result, boolean retryable) {}

  private Call call(CandleSource src, CandleQuery q, URI uri, int attempt, Long marketId, ResolveProperties.Gateway g) {
    Instant at = Instant.now(clock);
    long t0 = System.nanoTime();
    ExternalHttp.Response r;
    try {
      r = http.get(uri, g.getConnectTimeout(), g.getReadTimeout(), g.getMaxBodyBytes(), Map.of("User-Agent", "forecast-resolve/1"));
    } catch (ExternalHttp.Failure f) {
      int ms = elapsed(t0);
      String err = f.kind().name() + "：" + f.getMessage();
      Long id = save(new SourceArchive.Entry(marketId, src.name(), attempt, uri.toString(), null, null, null, Outcome.FAILED.name(), null, cut(err), at, ms));
      // 响应太大：重试也一样
      return new Call(new Result(src.name(), src.label(q), Outcome.FAILED, null, err, id, null, at, attempt), f.kind() != ExternalHttp.Kind.TOO_LARGE);
    }
    int ms = elapsed(t0);
    String body = new String(r.body(), StandardCharsets.UTF_8);
    String sha = sha256(r.body());
    if (r.status() < 200 || r.status() >= 300) {
      String err = "HTTP " + r.status();
      boolean retry = r.status() >= 500 || r.status() == 429 || r.status() == 408;
      Long id = save(new SourceArchive.Entry(marketId, src.name(), attempt, uri.toString(), r.status(), body, sha, Outcome.FAILED.name(), null, err, at, ms));
      return new Call(new Result(src.name(), src.label(q), Outcome.FAILED, null, err, id, sha, at, attempt), retry);
    }
    try {
      Candles c = src.parse(body, q);
      Long id = save(new SourceArchive.Entry(marketId, src.name(), attempt, uri.toString(), r.status(), body, sha, Outcome.OK.name(), c.toMap(), null, at, ms));
      if (id == null) return new Call(new Result(src.name(), src.label(q), Outcome.FAILED, null, "存档失败", null, sha, at, attempt), false);
      return new Call(new Result(src.name(), src.label(q), Outcome.OK, c, null, id, sha, at, attempt), false);
    } catch (SourceException e) {
      Outcome o = e.kind() == SourceException.Kind.NOT_READY ? Outcome.NOT_READY : Outcome.FAILED;
      String err = (o == Outcome.FAILED ? "格式不对：" : "") + e.getMessage();
      Long id = save(new SourceArchive.Entry(marketId, src.name(), attempt, uri.toString(), r.status(), body, sha, o.name(), null, cut(err), at, ms));
      return new Call(new Result(src.name(), src.label(q), o, null, err, id, sha, at, attempt), false);
    }
  }

  /** 存档；失败返回 null（记日志），调用方当作这次取数失败 */
  private Long save(SourceArchive.Entry e) {
    try {
      return archive.save(e);
    } catch (RuntimeException ex) {
      log.error("数据源 {} 的原始返回存档失败", e.source(), ex);
      return null;
    }
  }

  static Duration backoff(ResolveProperties.Gateway g, int retry) {
    long ms = g.getBackoff().toMillis() * (1L << Math.min(20, retry - 1));
    return Duration.ofMillis(Math.min(ms, g.getMaxBackoff().toMillis()));
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d.toMillis());
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
