package com.forecast.app.settle.recon;

import com.forecast.app.common.job.JobLease;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天 UTC 00:30 跑一次彩池对账（需求说明书第 3.9 节第 3 条；技术方案/v1-架构方案.md 第 5.2 节 PoolReconJob）。先拿数据库租约（多实例只跑一次）；
 * 对账只读、差异按"对象 + 日期 + 种类"唯一，重复跑也不会重复记。开关 {@code forecast.settle.recon-enabled}（后端测试关闭，测试直接调用）。
 */
@Component
@ConditionalOnProperty(name = "forecast.settle.recon-enabled", havingValue = "true", matchIfMissing = true)
class PoolReconJob {

  private static final Logger log = LoggerFactory.getLogger(PoolReconJob.class);
  static final String JOB = "settle.poolRecon";
  private static final Duration TTL = Duration.ofMinutes(5);

  private final PoolReconService recon;
  private final JobLease lease;

  PoolReconJob(PoolReconService recon, JobLease lease) {
    this.recon = recon;
    this.lease = lease;
  }

  @Scheduled(cron = "0 30 0 * * *", zone = "UTC")
  void daily() {
    try {
      lease.runExclusive(JOB, TTL, () -> {
        PoolReconService.Report r = recon.run();
        log.info("彩池对账：未结算市场 {} 个、结算 / 退款单 {} 张，新差异 {} 条", r.markets(), r.runs(), r.fresh());
      });
    } catch (RuntimeException e) {
      log.error("彩池对账失败", e);
    }
  }
}
