package com.forecast.app.growth.waitlist;

import com.forecast.app.common.job.JobLease;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 上线通知的发送任务（forecast S16，MF-8、SQ-22）：每 15 秒一轮，先拿数据库租约 {@code growth.launch}；没按过"发送上线通知"时什么都不做。
 * 发送本身是 CONFIRMED → NOTIFIED 的条件更新，租约意外失效时重复执行也只发一次（约定 25）。
 * 开关 {@code forecast.growth.waitlist.launch-enabled}（后端测试关闭，测试直接调用 {@link WaitlistService#sendLaunchBatch()}）。
 */
@Component
@ConditionalOnProperty(name = "forecast.growth.waitlist.launch-enabled", havingValue = "true", matchIfMissing = true)
class LaunchNoticeJob {

  private static final Logger log = LoggerFactory.getLogger(LaunchNoticeJob.class);
  static final String JOB = "growth.launch";
  private static final Duration TTL = Duration.ofSeconds(120);

  private final WaitlistService waitlist;
  private final JobLease lease;

  LaunchNoticeJob(WaitlistService waitlist, JobLease lease) {
    this.waitlist = waitlist;
    this.lease = lease;
  }

  @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
  void tick() {
    try {
      lease.runExclusive(JOB, TTL, () -> {
        int n = waitlist.sendLaunchBatch();
        if (n > 0) log.info("等候页：写出了 {} 封上线通知", n);
      });
    } catch (RuntimeException e) {
      log.error("上线通知任务失败", e);
    }
  }
}
