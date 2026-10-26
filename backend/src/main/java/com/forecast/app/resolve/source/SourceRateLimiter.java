package com.forecast.app.resolve.source;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 一个数据源的限频（forecast S8；技术方案第 6.3 节"遵守每分钟 N 次"）：<b>滑动窗口</b>，任意 window 长的时间里最多 limit 次调用，超过的<b>排队</b>
 * （{@link #reserve} 预约一个时间点，调用方睡到那个时间再调用），排队超过 maxWait 的这次不调用（返回 -1）。
 * 收到 429 时 {@link #block} 一段时间（按 Retry-After），之后的预约都排到它后面。
 * <p>任意滑动窗口里不超过 limit，也就保证了按固定分钟计数的限额（football-data 的 X-RequestCounter-Reset）不会超。
 * 状态只在本实例的内存里（第一期单实例，AQ-11）；用真实的单调时钟（{@code System.nanoTime}），不受业务的可调时钟影响。
 */
final class SourceRateLimiter {

  private final int limit;
  private final long windowNs;
  private final LongSupplier nanoTime;
  /** 已经预约的调用时间（升序；含将来的） */
  private final List<Long> slots = new ArrayList<>();
  private long blockedUntil = Long.MIN_VALUE;

  SourceRateLimiter(int limit, Duration window) {
    this(limit, window, System::nanoTime);
  }

  SourceRateLimiter(int limit, Duration window, LongSupplier nanoTime) {
    this.limit = Math.max(1, limit);
    this.windowNs = window.toNanos();
    this.nanoTime = nanoTime;
  }

  /**
   * 预约一次调用。
   *
   * @return 要等多少纳秒（0 = 现在就可以）；排队超过 maxWait 返回 -1（没有预约）
   */
  synchronized long reserve(Duration maxWait) {
    long now = nanoTime.getAsLong();
    while (!slots.isEmpty() && slots.get(0) <= now - windowNs) slots.remove(0);
    long t = now;
    if (blockedUntil > t) t = blockedUntil;
    if (!slots.isEmpty() && slots.get(slots.size() - 1) > t) t = slots.get(slots.size() - 1);
    if (slots.size() >= limit) {
      // 窗口 (t − window, t] 里最多 limit − 1 个已预约的：倒数第 limit 个必须已经出了窗口
      long k = slots.get(slots.size() - limit) + windowNs;
      if (k > t) t = k;
    }
    if (t - now > maxWait.toNanos()) return -1;
    slots.add(t);
    return t - now;
  }

  /** 收到 429：从现在起 d 之内不再调用 */
  synchronized void block(Duration d) {
    long until = nanoTime.getAsLong() + d.toNanos();
    if (until > blockedUntil) blockedUntil = until;
  }

  int limit() {
    return limit;
  }

  Duration window() {
    return Duration.ofNanos(windowNs);
  }
}
