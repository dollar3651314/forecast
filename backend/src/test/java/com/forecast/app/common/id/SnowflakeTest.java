package com.forecast.app.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 雪花 ID（v2 第 11.4 节，E11）：唯一、递增、节点号、时钟回拨、同一毫秒序号用完、超过 JavaScript 安全整数。 */
class SnowflakeTest {

  /** JavaScript 能精确表示的最大整数 2^53 - 1 */
  static final long JS_MAX_SAFE = (1L << 53) - 1;

  @Test
  void 编号唯一递增_含时间和节点号_远大于旧数据的自增号_超过JavaScript安全整数() {
    MutableClock clock = new MutableClock();
    Snowflake s = new Snowflake(1, clock);
    long a = s.next();
    long b = s.next();
    clock.advance(Duration.ofMillis(5));
    long c = s.next();
    assertThat(b).isGreaterThan(a);
    assertThat(c).isGreaterThan(b);
    assertThat(Snowflake.nodeOf(a)).isEqualTo(1);
    assertThat(Snowflake.timeOf(a)).isEqualTo(clock.instant().minusMillis(5));
    assertThat(Snowflake.timeOf(c)).isEqualTo(clock.instant());
    assertThat(a).as("2026-10 的编号远大于旧数据的自增号，也超过 2^53，接口里必须用字符串").isGreaterThan(JS_MAX_SAFE).isGreaterThan(Snowflake.MIN_NEW);
  }

  @Test
  void 时钟回拨时不重复_继续用上一次的时间往后排() {
    MutableClock clock = new MutableClock();
    Snowflake s = new Snowflake(2, clock);
    clock.advance(Duration.ofSeconds(10));
    long before = s.next();
    clock.advance(Duration.ofSeconds(-5)); // 校时把时钟往回调了 5 秒
    long after = s.next();
    long after2 = s.next();
    assertThat(after).isGreaterThan(before);
    assertThat(after2).isGreaterThan(after);
    assertThat(Snowflake.timeOf(after)).as("逻辑时间不后退").isEqualTo(Snowflake.timeOf(before));
    clock.advance(Duration.ofSeconds(6));
    assertThat(Snowflake.timeOf(s.next())).as("时钟追上以后用真实时间").isEqualTo(clock.instant());
  }

  @Test
  void 同一毫秒的序号用完时借用下一毫秒_不重复() {
    MutableClock clock = new MutableClock(); // 时间不动
    Snowflake s = new Snowflake(3, clock);
    Set<Long> seen = new HashSet<>();
    long last = -1;
    for (int i = 0; i < 10_000; i++) {
      long id = s.next();
      assertThat(seen.add(id)).isTrue();
      assertThat(id).isGreaterThan(last);
      last = id;
    }
    assertThat(Snowflake.timeOf(last)).isAfter(clock.instant());
  }

  @Test
  void 多线程同时取号不重复() throws Exception {
    Snowflake s = new Snowflake(4, java.time.Clock.systemUTC());
    Set<Long> seen = ConcurrentHashMap.newKeySet();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    for (int t = 0; t < 8; t++) pool.execute(() -> {
      for (int i = 0; i < 5000; i++) seen.add(s.next());
    });
    pool.shutdown();
    assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    assertThat(seen).hasSize(40_000);
  }

  @Test
  void 节点号范围_同一节点号在进程里共用一个生成器() {
    assertThatThrownBy(() -> new Snowflake(1024, java.time.Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Snowflake.shared(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThat(Snowflake.shared(7)).isSameAs(Snowflake.shared(7));
    assertThat(Snowflake.nodeOf(Snowflake.shared(7).next())).isEqualTo(7);
    assertThat(Snowflake.timeOf(Snowflake.shared(7).next())).isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.SECONDS));
  }
}
