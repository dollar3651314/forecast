package com.forecast.app.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 测试时钟：默认停在 {@link FixedClockConfig#NOW}，需要"过一段时间"的测试可以往前拨，测完调用 {@link #reset()}。 */
public class MutableClock extends Clock {

  private volatile Instant now = FixedClockConfig.NOW;

  public void advance(Duration d) {
    now = now.plus(d);
  }

  public void reset() {
    now = FixedClockConfig.NOW;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return Clock.fixed(now, zone);
  }

  @Override
  public Instant instant() {
    return now;
  }
}
