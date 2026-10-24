package com.forecast.app.common.trace;

import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * 定时任务（@Scheduled）和异步任务的追踪号。Spring Boot 把容器里唯一的 {@link TaskDecorator} 同时用于定时任务的调度器和异步执行器。
 * <ul>
 *   <li>提交时线程上已经有追踪号（例如请求里提交的异步任务）：沿用，日志能和那个请求串起来。</li>
 *   <li>没有（定时任务在启动时注册）：每执行一次生成一个新的 {@code job-…}。</li>
 * </ul>
 */
@Component
public class TraceTaskDecorator implements TaskDecorator {

  @Override
  public Runnable decorate(Runnable task) {
    String parent = TraceId.current();
    return () -> TraceId.run(parent != null ? parent : jobId(), task);
  }

  static String jobId() {
    return "job-" + TraceId.generate().substring(0, 16);
  }
}
