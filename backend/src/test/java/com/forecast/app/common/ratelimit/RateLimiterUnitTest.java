package com.forecast.app.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.forecast.app.common.error.RetryLaterException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 限次计数的边界（不连 Redis）：Redis 不可用时放行、不拖住请求；上限 0 不限；超限返回剩余秒数。 */
class RateLimiterUnitTest {

  static final Duration MINUTE = Duration.ofMinutes(1);

  final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked")
  final ValueOperations<String, String> ops = mock(ValueOperations.class);
  final AtomicLong now = new AtomicLong(1_000_000_000L);

  RateLimiter limiter(boolean enabled) {
    when(redis.opsForValue()).thenReturn(ops);
    return new RateLimiter(redis, new RateLimitProperties(enabled, "p:", 600, 120, 60, 20, Duration.ofMinutes(15), Duration.ofSeconds(30)), now::get);
  }

  @Test
  void Redis不可用时放行_30秒内不再访问Redis_之后恢复计数() {
    RateLimiter l = limiter(true);
    when(ops.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));
    assertThat(l.hit("ip", "1.2.3.4", 1, MINUTE)).isZero();
    // 30 秒内：直接放行，不再访问 Redis（Redis 卡住时不让每个请求都等超时）
    now.addAndGet(Duration.ofSeconds(29).toNanos());
    for (int i = 0; i < 100; i++) assertThat(l.hit("ip", "1.2.3.4", 1, MINUTE)).isZero();
    verify(ops, times(1)).increment(anyString());
    // 30 秒后再试；Redis 恢复后照常计数和限流
    now.addAndGet(Duration.ofSeconds(2).toNanos());
    org.mockito.Mockito.reset(ops);
    when(ops.increment("p:ip:1.2.3.4")).thenReturn(1L, 2L);
    when(redis.getExpire("p:ip:1.2.3.4", TimeUnit.SECONDS)).thenReturn(42L);
    assertThat(l.hit("ip", "1.2.3.4", 1, MINUTE)).isZero();
    assertThat(l.hit("ip", "1.2.3.4", 1, MINUTE)).isEqualTo(42);
    assertThatThrownBy(() -> {
      when(ops.increment("p:user:7")).thenReturn(61L);
      when(redis.getExpire("p:user:7", TimeUnit.SECONDS)).thenReturn(0L);
      l.check("user", "7", 60, MINUTE);
    }).isInstanceOfSatisfying(RetryLaterException.class, e -> {
      assertThat(e.code()).isEqualTo("RATE_LIMITED");
      assertThat(e.cooldownSeconds()).isEqualTo(1);
    });
  }

  @Test
  void 上限为0或总开关关闭时不限_也不访问Redis() {
    RateLimiter off = limiter(false);
    assertThat(off.hit("ip", "x", 1, MINUTE)).isZero();
    RateLimiter on = limiter(true);
    assertThat(on.hit("ip", "x", 0, MINUTE)).isZero();
    verify(ops, never()).increment(anyString());
  }

  @Test
  void 第一次计数设过期时间_没有过期时间的key超限时补上() {
    RateLimiter l = limiter(true);
    when(ops.increment("p:ws:x")).thenReturn(1L, 5L);
    when(redis.getExpire("p:ws:x", TimeUnit.SECONDS)).thenReturn(-1L);
    assertThat(l.hit("ws", "x", 3, MINUTE)).isZero();
    verify(redis, times(1)).expire("p:ws:x", MINUTE);
    assertThat(l.hit("ws", "x", 3, MINUTE)).isEqualTo(60);
    verify(redis, times(2)).expire(any(String.class), any(Duration.class));
  }
}
