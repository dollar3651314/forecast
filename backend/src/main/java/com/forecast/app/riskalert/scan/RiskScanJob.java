package com.forecast.app.riskalert.scan;

import com.forecast.app.common.job.JobLease;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每 30 秒扫一轮风控（forecast S10）。先拿数据库租约 {@code riskalert.scan}（多实例只跑一个，项目地图约定 25）；处理本身可以重复（唯一键）。
 * 开关 {@code forecast.riskalert.scan-enabled}（后端测试关闭，测试直接调用 {@link RiskScanner#scanAll()}）。
 */
@Component
@ConditionalOnProperty(name = "forecast.riskalert.scan-enabled", havingValue = "true", matchIfMissing = true)
class RiskScanJob {

  private static final Logger log = LoggerFactory.getLogger(RiskScanJob.class);
  static final String JOB = "riskalert.scan";
  private static final Duration TTL = Duration.ofMinutes(2);

  private final RiskScanner scanner;
  private final JobLease lease;

  RiskScanJob(RiskScanner scanner, JobLease lease) {
    this.scanner = scanner;
    this.lease = lease;
  }

  @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT20S")
  void tick() {
    try {
      lease.runExclusive(JOB, TTL, scanner::scanAll);
    } catch (RuntimeException e) {
      log.error("风控扫描失败", e);
    }
  }
}
