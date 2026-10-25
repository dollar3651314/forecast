package com.forecast.app.settle.run;

import com.forecast.app.common.job.JobLease;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 派彩任务（技术方案/v1-架构方案.md 第 5.2 节 PayoutWorker；forecast S5）：每 2 秒把派彩中的结算单、退款单一批一批派完再收尾。
 * batch-size = 0 时结算、退款在当时的事务里就派完了，这里只处理配置改动前留下的、或者重试后回到派彩中的单。
 * 先拿数据库租约（多实例只有一个在跑）；每批本身还有结算单行锁、明细条件更新、账本幂等键，租约意外失效时重复执行也只派一次。
 * 开关 {@code forecast.settle.worker-enabled}（后端测试关闭，测试直接调用 {@link SettleService#drainAll()}）。
 */
@Component
@ConditionalOnProperty(name = "forecast.settle.worker-enabled", havingValue = "true", matchIfMissing = true)
class PayoutWorker {

  private static final Logger log = LoggerFactory.getLogger(PayoutWorker.class);
  static final String JOB = "settle.payout";
  private static final Duration TTL = Duration.ofSeconds(30);

  private final SettleService settle;
  private final JobLease lease;

  PayoutWorker(SettleService settle, JobLease lease) {
    this.settle = settle;
    this.lease = lease;
  }

  @Scheduled(fixedDelay = 2_000, initialDelay = 10_000)
  void tick() {
    try {
      lease.runExclusive(JOB, TTL, () -> {
        int n = settle.drainAll();
        if (n > 0) log.info("{} 张结算单 / 退款单派完", n);
      });
    } catch (RuntimeException e) {
      log.error("派彩任务失败", e);
    }
  }
}
