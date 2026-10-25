package com.forecast.app.resolve.platform;

import com.forecast.app.common.job.JobLease;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 平台裁决的定时任务（技术方案/v1-架构方案.md 第 5.2 节；forecast S7）。每次先拿数据库租约（多实例只有一个在跑），任务本身是条件更新 + 结算的三道防重复。
 * <ul>
 *   <li>{@code resolve.publicity}：每 15 秒，公示期满 → 结算（AC-31"1 分钟内"）。</li>
 *   <li>{@code resolve.timeouts}：每分钟，E6 / E7 等满 48 小时 → 作废；待提议、待确认、争议处理超时 → 邮件提醒（AC-36）。</li>
 * </ul>
 * 开关 {@code forecast.resolve.platform-enabled}（后端测试关闭，测试直接调用 {@link PublicityService}）。
 */
@Component
@ConditionalOnProperty(name = "forecast.resolve.platform-enabled", havingValue = "true", matchIfMissing = true)
class PlatformJob {

  private static final Logger log = LoggerFactory.getLogger(PlatformJob.class);
  static final String PUBLICITY = "resolve.publicity";
  static final String TIMEOUTS = "resolve.timeouts";
  private static final Duration TTL = Duration.ofSeconds(60);

  private final PublicityService publicity;
  private final JobLease lease;

  PlatformJob(PublicityService publicity, JobLease lease) {
    this.publicity = publicity;
    this.lease = lease;
  }

  @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
  void publicity() {
    try {
      lease.runExclusive(PUBLICITY, TTL, () -> {
        Map<Long, String> r = publicity.settleDue();
        if (!r.isEmpty()) log.info("公示期满结算这一轮：{}", r);
      });
    } catch (RuntimeException e) {
      log.error("公示期满结算任务失败", e);
    }
  }

  @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
  void timeouts() {
    try {
      lease.runExclusive(TIMEOUTS, TTL, () -> {
        Map<Long, String> h = publicity.holdsDue();
        if (!h.isEmpty()) log.info("延期 / 腰斩到期这一轮：{}", h);
        int n = publicity.remindOverdue();
        if (n > 0) log.info("裁决超时提醒 {} 封", n);
      });
    } catch (RuntimeException e) {
      log.error("裁决超时任务失败", e);
    }
  }
}
