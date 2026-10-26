package com.forecast.app.content.core;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 自动化程序自己的线程池（forecast S15，NF-15）。{@code async = false}（后端测试）时在调用线程里直接执行。
 * 任务里的异常只记日志，不会传出去（起草失败由任务自己把草稿标成"生成失败"）。
 */
public final class Workers {

  private static final Logger log = LoggerFactory.getLogger(Workers.class);

  private final ThreadPoolExecutor executor;
  private final boolean async;

  Workers(ThreadPoolExecutor executor, boolean async) {
    this.executor = executor;
    this.async = async;
  }

  /** 交给线程池；队列满了返回 false（调用方把草稿标成失败，原因"排队已满"） */
  public boolean submit(String what, Runnable r) {
    Runnable safe = () -> {
      try {
        r.run();
      } catch (RuntimeException e) {
        log.warn("自动化程序任务 {} 出错", what, e);
      }
    };
    if (!async) {
      safe.run();
      return true;
    }
    try {
      executor.execute(safe);
      return true;
    } catch (RejectedExecutionException e) {
      log.warn("自动化程序线程池已满，任务 {} 没有执行", what);
      return false;
    }
  }

  /** 排队和正在执行的任务数（测试、用量页） */
  public int busy() {
    return executor.getActiveCount() + executor.getQueue().size();
  }

  public void shutdownNow() {
    executor.shutdownNow();
  }
}
