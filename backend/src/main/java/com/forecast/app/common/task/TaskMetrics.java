package com.forecast.app.common.task;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 任务队列积压指标（E10）：{@code fc_tasks_backlog} = 已经到期、还没处理的任务数（邮件、推送等）。
 * 查数据库，结果缓存 10 秒（指标只在内网管理端口上被读取，次数很少，缓存只是防止被频繁读取时反复查库）；查询失败时是 NaN。
 */
@Component
class TaskMetrics {

  private static final Logger log = LoggerFactory.getLogger(TaskMetrics.class);
  private static final long CACHE_MILLIS = 10_000;

  private final TaskMapper mapper;
  private volatile double cached = Double.NaN;
  private volatile long cachedAt;

  TaskMetrics(TaskMapper mapper, MeterRegistry metrics) {
    this.mapper = mapper;
    Gauge.builder("fc.tasks.backlog", this::backlog).description("已经到期、还没处理的任务数（邮件、推送等）").register(metrics);
  }

  double backlog() {
    long now = System.currentTimeMillis();
    if (now - cachedAt < CACHE_MILLIS) return cached;
    try {
      cached = mapper.countDue();
    } catch (RuntimeException e) {
      log.debug("查询任务积压失败", e);
      cached = Double.NaN;
    }
    cachedAt = now;
    return cached;
  }
}
