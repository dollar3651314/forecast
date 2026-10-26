package com.forecast.app.content.job;

import com.forecast.app.common.job.JobLease;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自动化程序的定时任务（forecast S15）：每分钟一轮，先拿数据库租约 {@code content.job}（多实例只有一个在跑）。任务里的每一步都是幂等的（条件更新、唯一键）。
 * 开关 {@code forecast.content.jobs-enabled}（后端测试、端到端关闭，直接调用 {@link ContentTasks#runOnce()}）。任务出错只记日志，不影响别的任务。
 */
@Component
@ConditionalOnProperty(name = "forecast.content.jobs-enabled", havingValue = "true", matchIfMissing = true)
class ContentJob {

  private static final Logger log = LoggerFactory.getLogger(ContentJob.class);
  static final String JOB = "content.job";
  /** 一轮可能要等 Gemini（起草在线程池里，任务本身不等），租约给 3 分钟 */
  private static final Duration TTL = Duration.ofMinutes(3);

  private final ContentTasks tasks;
  private final JobLease lease;

  ContentJob(ContentTasks tasks, JobLease lease) {
    this.tasks = tasks;
    this.lease = lease;
  }

  @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
  void tick() {
    try {
      lease.runExclusive(JOB, TTL, tasks::runOnce);
    } catch (RuntimeException e) {
      log.error("自动化程序任务失败", e);
    }
  }
}
