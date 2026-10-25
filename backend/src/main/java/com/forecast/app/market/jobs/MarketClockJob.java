package com.forecast.app.market.jobs;

import com.forecast.app.common.job.JobLease;
import com.forecast.app.market.core.MarketService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 市场的两个定时任务（技术方案/v1-架构方案.md 第 5.2 节；forecast S3）：
 * <ul>
 *   <li>截止：每 5 秒，{@code LISTED / PAUSED AND close_at ≤ now} → CLOSED（有效选项不足 2 个同一事务 → VOIDED），AC-14、AC-24（1 分钟内）。</li>
 *   <li>事件结束：每 15 秒，{@code CLOSED AND event_end_at ≤ now} → PENDING_RESOLUTION。</li>
 * </ul>
 * 每次执行先拿数据库租约（{@link JobLease}，多实例时只有一个实例在跑）；状态跳转本身也是条件更新，租约意外失效时重复执行也没有效果。
 * 开关 {@code forecast.market.clock-enabled}（默认开；后端测试关闭，测试直接调用 {@link MarketService#closeDue()}、{@link MarketService#endDue()}）。
 */
@Component
@ConditionalOnProperty(name = "forecast.market.clock-enabled", havingValue = "true", matchIfMissing = true)
class MarketClockJob {

  private static final Logger log = LoggerFactory.getLogger(MarketClockJob.class);
  static final String CLOSE = "market.close";
  static final String EVENT_END = "market.eventEnd";
  private static final Duration TTL = Duration.ofSeconds(30);

  private final MarketService markets;
  private final JobLease lease;

  MarketClockJob(MarketService markets, JobLease lease) {
    this.markets = markets;
    this.lease = lease;
  }

  @Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
  void close() {
    try {
      lease.runExclusive(CLOSE, TTL, () -> {
        int n = markets.closeDue();
        if (n > 0) log.info("截止了 {} 个市场", n);
      });
    } catch (RuntimeException e) {
      log.error("市场截止任务失败", e);
    }
  }

  @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
  void eventEnd() {
    try {
      lease.runExclusive(EVENT_END, TTL, () -> {
        int n = markets.endDue();
        if (n > 0) log.info("{} 个市场进入待裁决", n);
      });
    } catch (RuntimeException e) {
      log.error("市场事件结束任务失败", e);
    }
  }
}
