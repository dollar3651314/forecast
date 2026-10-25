package com.forecast.app.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每 2 秒发送到期的邮件任务；每分钟把卡在"处理中"超过 5 分钟的任务收回重发。测试配置里关闭，测试直接调用。 */
@Component
@ConditionalOnProperty(name = "forecast.notify.worker-enabled", havingValue = "true", matchIfMissing = true)
class NotifyScheduler {

  private static final Logger log = LoggerFactory.getLogger(NotifyScheduler.class);

  private final NotifyWorker worker;
  private final com.forecast.app.common.task.TaskService tasks;

  NotifyScheduler(NotifyWorker worker, com.forecast.app.common.task.TaskService tasks) {
    this.worker = worker;
    this.tasks = tasks;
  }

  @Scheduled(fixedDelay = 2_000, initialDelay = 10_000)
  void tick() {
    try {
      worker.drain();
    } catch (RuntimeException e) {
      log.error("发送邮件任务失败", e);
    }
  }

  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  void recover() {
    try {
      int n = tasks.recoverStuck(300);
      if (n > 0) log.warn("收回 {} 个卡住的任务", n);
    } catch (RuntimeException e) {
      log.error("收回卡住的任务失败", e);
    }
  }
}
