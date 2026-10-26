package com.forecast.app.market.remind;

import com.forecast.app.common.job.JobLease;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * #23 开赛提醒的定时发送（forecast S11）：每 30 秒一轮，先拿数据库租约 {@code market.reminder}（多实例只有一个在跑）；
 * 发送本身是"ACTIVE → SENT"的条件更新，租约意外失效时重复执行也只发一次（约定 25）。
 * 开关 {@code forecast.market.reminder-enabled}（后端测试关闭，测试直接调用 {@link ReminderService#sendDue()}）。
 */
@Component
@ConditionalOnProperty(name = "forecast.market.reminder-enabled", havingValue = "true", matchIfMissing = true)
class ReminderJob {

  private static final Logger log = LoggerFactory.getLogger(ReminderJob.class);
  static final String JOB = "market.reminder";
  private static final Duration TTL = Duration.ofSeconds(60);

  private final ReminderService reminders;
  private final JobLease lease;

  ReminderJob(ReminderService reminders, JobLease lease) {
    this.reminders = reminders;
    this.lease = lease;
  }

  @Scheduled(fixedDelay = 30_000, initialDelay = 25_000)
  void tick() {
    try {
      lease.runExclusive(JOB, TTL, () -> {
        int n = reminders.sendDue();
        if (n > 0) log.info("发出了 {} 封开赛提醒", n);
      });
    } catch (RuntimeException e) {
      log.error("开赛提醒任务失败", e);
    }
  }
}
