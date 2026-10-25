package com.forecast.app.resolve.sports;

import com.forecast.app.common.job.JobLease;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 体育的两个定时任务（forecast S8；技术方案第 5.2 节 SportsResolver）。都先拿数据源租约（多实例只有一个在跑），任务本身靠条件更新和结算的防重复保证只生效一次。
 * <ul>
 *   <li>自动裁决：每 30 秒看一次到时间的进度（每个市场默认 5 分钟一次，{@code forecast.sports.poll-interval}），租约 {@code resolve.sports}；
 *       开关 {@code forecast.sports.resolver-enabled}。</li>
 *   <li>同步赛程：每 {@code forecast.sports.sync-interval}（默认 4 小时）一次，租约 {@code resolve.sports.sync}；开关 {@code forecast.sports.sync-enabled}。
 *       来源未开通（没配 key）的品类跳过。</li>
 * </ul>
 * 一轮最长：取数在事务外、有超时和限频排队上限；租约 10 分钟、执行中续约。
 */
@Component
class SportsJob {

  private static final Logger log = LoggerFactory.getLogger(SportsJob.class);
  static final String RESOLVE = "resolve.sports";
  static final String SYNC = "resolve.sports.sync";
  private static final Duration TTL = Duration.ofMinutes(10);

  private final SportsResolver resolver;
  private final FixtureService fixtures;
  private final SportsProperties props;
  private final JobLease lease;
  private final Clock clock;
  private volatile Instant nextSync = Instant.MIN;

  SportsJob(SportsResolver resolver, FixtureService fixtures, SportsProperties props, JobLease lease, Clock clock) {
    this.resolver = resolver;
    this.fixtures = fixtures;
    this.props = props;
    this.lease = lease;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
  void tick() {
    if (props.isResolverEnabled()) {
      try {
        lease.runExclusive(RESOLVE, TTL, () -> {
          Map<Long, String> r = resolver.resolveDue();
          if (!r.isEmpty()) log.info("体育自动裁决这一轮：{}", r);
        });
      } catch (RuntimeException e) {
        log.error("体育自动裁决任务失败", e);
      }
    }
    if (props.isSyncEnabled() && !Instant.now(clock).isBefore(nextSync)) {
      try {
        lease.runExclusive(SYNC, TTL, () -> {
          fixtures.sync(null, null);
          nextSync = Instant.now(clock).plus(props.getSyncInterval());
        });
      } catch (RuntimeException e) {
        log.error("体育赛程同步失败", e);
        nextSync = Instant.now(clock).plus(Duration.ofMinutes(10));
      }
    }
  }
}
