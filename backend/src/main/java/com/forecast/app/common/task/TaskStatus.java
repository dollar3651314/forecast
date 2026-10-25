package com.forecast.app.common.task;

/** 任务状态：待处理 → 处理中 → 成功；失败会按退避时间回到待处理，重试次数用完后为失败。 */
public enum TaskStatus {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED
}
