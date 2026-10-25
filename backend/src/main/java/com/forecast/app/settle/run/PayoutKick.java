package com.forecast.app.settle.run;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 分批派彩（batch-size &gt; 0）时，结算单、退款单一提交就叫派彩任务马上跑一轮，不用等下一个 2 秒（加密市场窗口结束 3 分钟内结算完，AC-27）。
 * 派彩任务没开（后端测试）时什么都不做。在后台的单个线程里跑，同一时间只排一轮。
 */
@Component
class PayoutKick {

  private static final Logger log = LoggerFactory.getLogger(PayoutKick.class);
  private final ObjectProvider<PayoutWorker> worker;
  private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "fc-settle-kick");
    t.setDaemon(true);
    return t;
  });
  private final AtomicBoolean queued = new AtomicBoolean();

  PayoutKick(ObjectProvider<PayoutWorker> worker) {
    this.worker = worker;
  }

  /** 当前事务提交后跑一轮（没有事务时马上跑） */
  void afterCommit() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          now();
        }
      });
    } else {
      now();
    }
  }

  void now() {
    PayoutWorker w = worker.getIfAvailable();
    if (w == null || !queued.compareAndSet(false, true)) return;
    executor.submit(() -> {
      queued.set(false);
      try {
        w.tick();
      } catch (RuntimeException e) {
        log.error("派彩任务失败", e);
      }
    });
  }
}
