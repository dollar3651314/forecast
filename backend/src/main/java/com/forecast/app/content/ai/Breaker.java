package com.forecast.app.content.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Gemini 的熔断（forecast S15，C81）：连续 threshold 次失败打开 openFor，之后半开放放 1 次探测，成功关闭、失败再打开；
 * 收到 429 时直接暂停 {@link #pause}（自动化需求第 6.4 节：被限流时这一类功能暂停 10 分钟）。只在本实例内存里（熔断只是"少打一个已经坏了的接口"）。
 * 和 resolve 模块数据源的熔断是同一个思路，多了"按指定时长暂停"，所以单独一份（模块之间不能共用内部类）。
 */
public final class Breaker {

  private final int threshold;
  private final Duration openFor;
  private final Clock clock;
  private int failures;
  private Instant openUntil;
  private boolean probing;

  Breaker(int threshold, Duration openFor, Clock clock) {
    this.threshold = Math.max(1, threshold);
    this.openFor = openFor;
    this.clock = clock;
  }

  public synchronized boolean allow() {
    if (openUntil == null) return true;
    if (Instant.now(clock).isBefore(openUntil)) return false;
    if (probing) return false;
    probing = true;
    return true;
  }

  /** allow() 之后没有真正调用（例如额度满了）：放掉探测名额 */
  public synchronized void release() {
    probing = false;
  }

  public synchronized void success() {
    failures = 0;
    openUntil = null;
    probing = false;
  }

  public synchronized void failure() {
    failures++;
    boolean wasProbe = probing;
    probing = false;
    if (wasProbe || failures >= threshold) openUntil = Instant.now(clock).plus(openFor);
  }

  public synchronized void pause(Duration d) {
    failures++;
    probing = false;
    Instant until = Instant.now(clock).plus(d);
    if (openUntil == null || until.isAfter(openUntil)) openUntil = until;
  }

  /** 现在是不是打开（不调用） */
  public synchronized boolean isOpen() {
    return openUntil != null && Instant.now(clock).isBefore(openUntil);
  }

  /** 暂停到什么时候（没有为空） */
  public synchronized Instant openUntil() {
    return isOpen() ? openUntil : null;
  }

  /** 测试用 */
  public synchronized void reset() {
    failures = 0;
    openUntil = null;
    probing = false;
  }
}
