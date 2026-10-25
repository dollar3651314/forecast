package com.forecast.app.resolve.source;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 一个数据源的熔断（技术方案第 6.3 节：连续 5 次失败打开 60 秒，之后半开放 1 次探测；成功关闭，失败再打开 60 秒）。
 * 状态只在本实例的内存里（多实例时各自熔断，不需要共享：熔断只是"少打一个已经坏了的接口"，不影响正确性）。时间用注入的 {@link Clock}（测试可以拨）。
 * <p>用法：{@link #allow()} 返回 true 才调用；调用完一定要报 {@link #success()} 或 {@link #failure()}（半开放时只放一个探测）。
 */
public final class CircuitBreaker {

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final int threshold;
  private final Duration openFor;
  private final Clock clock;
  private State state = State.CLOSED;
  private int failures;
  private Instant openedAt;
  private boolean probing;

  public CircuitBreaker(int threshold, Duration openFor, Clock clock) {
    this.threshold = Math.max(1, threshold);
    this.openFor = openFor;
    this.clock = clock;
  }

  public synchronized boolean allow() {
    switch (state) {
      case CLOSED:
        return true;
      case OPEN:
        if (Instant.now(clock).isBefore(openedAt.plus(openFor))) return false;
        state = State.HALF_OPEN;
        probing = true;
        return true;
      default: // HALF_OPEN：已经有一个探测在跑
        if (probing) return false;
        probing = true;
        return true;
    }
  }

  public synchronized void success() {
    state = State.CLOSED;
    failures = 0;
    probing = false;
  }

  public synchronized void failure() {
    probing = false;
    if (state == State.HALF_OPEN) {
      open();
      return;
    }
    failures++;
    if (state == State.CLOSED && failures >= threshold) open();
  }

  private void open() {
    state = State.OPEN;
    openedAt = Instant.now(clock);
  }

  public synchronized State state() {
    // 打开时间到了还没有调用：对外显示"半开放"（下一次调用就是探测）
    if (state == State.OPEN && !Instant.now(clock).isBefore(openedAt.plus(openFor))) return State.HALF_OPEN;
    return state;
  }

  public synchronized int consecutiveFailures() {
    return failures;
  }
}
