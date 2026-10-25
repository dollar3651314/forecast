package com.forecast.app.common.ratelimit;

import com.forecast.app.common.error.RetryLaterException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 限次计数（功能取舍对照表 P-05）：Redis 固定窗口，写法和 {@code LoginLimiter}、{@code SendCodeLimiter} 一致——
 * 第一次请求开始计时，窗口到期 key 自动过期清零；超过上限的请求也计数（窗口不延长）。
 *
 * <p><b>Redis 不可用时放行</b>：限流只是保护，不能因为 Redis 出问题把整个系统拖死。出错时记一条警告，之后
 * {@code redisDownSkip}（默认 30 秒）内不再访问 Redis、直接放行，到期再试，避免 Redis 卡住时每个请求都等超时。
 */
@Component
public class RateLimiter {

  /** 超限时的错误码：429，带 Retry-After；两个前端都有翻译 */
  public static final String CODE = "RATE_LIMITED";

  private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

  private final StringRedisTemplate redis;
  private final RateLimitProperties props;
  private final LongSupplier nanoTime;
  /** Redis 出错后到这个时间（System.nanoTime）之前直接放行 */
  private volatile long skipUntil;
  private volatile boolean skipping;

  @Autowired
  public RateLimiter(StringRedisTemplate redis, RateLimitProperties props) {
    this(redis, props, System::nanoTime);
  }

  RateLimiter(StringRedisTemplate redis, RateLimitProperties props, LongSupplier nanoTime) {
    this.redis = redis;
    this.props = props;
    this.nanoTime = nanoTime;
  }

  public RateLimitProperties props() {
    return props;
  }

  /**
   * 记一次。返回 0 表示放行；大于 0 表示超过上限，值是还要等多少秒（窗口剩余时间，至少 1 秒）。
   * 上限 ≤ 0、总开关关闭、Redis 不可用时都放行。
   *
   * @param bucket 哪一种计数（ip、ws、user、admin-login）
   * @param id 计数对象（IP 或客户 ID）
   */
  public long hit(String bucket, String id, int limit, Duration window) {
    if (!props.enabled() || limit <= 0) return 0;
    if (skipping && nanoTime.getAsLong() - skipUntil < 0) return 0;
    String key = props.keyPrefix() + bucket + ":" + (id == null ? "" : id);
    try {
      Long n = redis.opsForValue().increment(key);
      if (n != null && n == 1) redis.expire(key, window);
      if (skipping) {
        skipping = false;
        log.info("限流计数恢复（Redis 可用）");
      }
      if (n == null || n <= limit) return 0;
      Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
      if (ttl == null || ttl < 0) {
        // 没有过期时间（第一次 expire 失败等）：补上，否则会一直超限
        redis.expire(key, window);
        ttl = window.toSeconds();
      }
      return Math.max(1, ttl);
    } catch (RuntimeException e) {
      skipUntil = nanoTime.getAsLong() + props.redisDownSkip().toNanos();
      skipping = true;
      log.warn("限流计数失败（Redis 不可用？），{} 内不限流、直接放行：{}", props.redisDownSkip(), e.toString());
      return 0;
    }
  }

  /** 记一次，超过上限抛 429 {@code RATE_LIMITED}（带 Retry-After），由统一错误处理返回 */
  public void check(String bucket, String id, int limit, Duration window) {
    long wait = hit(bucket, id, limit, window);
    if (wait > 0) throw new RetryLaterException(CODE, "请求太频繁，请稍后再试", wait);
  }
}
