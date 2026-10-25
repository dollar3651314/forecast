package com.forecast.app.resolve.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 限频（forecast S8；技术方案第 6.3 节"遵守每分钟 N 次（令牌桶，留余量）"）：滑动窗口里不超过 N 次，超过的排队，排队超过上限不调用；429 以后暂停。用可调的假时钟，不真的等。
 */
class SourceRateLimiterTest {

  static final long S = 1_000_000_000L;
  final AtomicLong now = new AtomicLong(1_000 * S);

  @Test
  void 每分钟8次_第9次排到第一次之后一分钟() {
    SourceRateLimiter l = new SourceRateLimiter(8, Duration.ofMinutes(1), now::get);
    for (int i = 0; i < 8; i++) {
      assertThat(l.reserve(Duration.ofSeconds(30))).as("第 %d 次", i + 1).isZero();
      now.addAndGet(S); // 每秒一次
    }
    // 现在 = 第一次 + 8 秒：第 9 次要等到第一次 + 60 秒，排队 52 秒，超过 30 秒上限 → 不调用
    assertThat(l.reserve(Duration.ofSeconds(30))).isEqualTo(-1);
    assertThat(l.reserve(Duration.ofSeconds(60))).isEqualTo(52 * S);
    // 第 10 次排到第二次 + 60 秒（任意 60 秒里不超过 8 次）
    assertThat(l.reserve(Duration.ofSeconds(60))).isEqualTo(53 * S);
    // 一分钟以后全部出窗口
    now.addAndGet(120 * S);
    assertThat(l.reserve(Duration.ZERO)).isZero();
  }

  @Test
  void 任意60秒里不超过上限() {
    SourceRateLimiter l = new SourceRateLimiter(4, Duration.ofMinutes(1), now::get);
    long[] at = new long[20];
    for (int i = 0; i < 20; i++) {
      long w = l.reserve(Duration.ofMinutes(10));
      assertThat(w).isGreaterThanOrEqualTo(0);
      at[i] = now.get() + w;
      now.addAndGet(S / 10); // 调用方很密集
    }
    for (int i = 4; i < 20; i++) assertThat(at[i] - at[i - 4]).as("第 %d 次和往前第 4 次相差至少 60 秒", i).isGreaterThanOrEqualTo(60 * S);
  }

  @Test
  void 收到429以后暂停() {
    SourceRateLimiter l = new SourceRateLimiter(30, Duration.ofMinutes(1), now::get);
    assertThat(l.reserve(Duration.ofSeconds(1))).isZero();
    l.block(Duration.ofSeconds(20));
    assertThat(l.reserve(Duration.ofSeconds(10))).as("暂停期间排队超过上限").isEqualTo(-1);
    assertThat(l.reserve(Duration.ofSeconds(30))).isEqualTo(20 * S);
    now.addAndGet(25 * S);
    assertThat(l.reserve(Duration.ZERO)).isZero();
  }
}
