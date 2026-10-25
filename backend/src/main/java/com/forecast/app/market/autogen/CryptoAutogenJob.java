package com.forecast.app.market.autogen;

import com.forecast.app.common.job.JobLease;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 加密市场自动生成任务（技术方案/v1-架构方案.md 第 5.2 节 CryptoAutogenJob；forecast S6）：每分钟一轮，先拿数据库租约（多实例只有一个在跑），
 * 生成本身还有唯一键 (autogen_id, event_start_at)，租约意外失效时重复执行也只生成一个。
 * 开关 {@code forecast.market.autogen-enabled}（后端测试关闭，测试直接调用 {@link AutogenService#generateDue()}）。
 */
@Component
@ConditionalOnProperty(name = "forecast.market.autogen-enabled", havingValue = "true", matchIfMissing = true)
class CryptoAutogenJob {

  private static final Logger log = LoggerFactory.getLogger(CryptoAutogenJob.class);
  static final String JOB = "market.autogen";
  private static final Duration TTL = Duration.ofSeconds(60);

  private final AutogenService autogen;
  private final JobLease lease;

  CryptoAutogenJob(AutogenService autogen, JobLease lease) {
    this.autogen = autogen;
    this.lease = lease;
  }

  @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
  void tick() {
    try {
      lease.runExclusive(JOB, TTL, () -> {
        int n = autogen.generateDue();
        if (n > 0) log.info("自动生成了 {} 个加密市场", n);
      });
    } catch (RuntimeException e) {
      log.error("加密市场自动生成任务失败", e);
    }
  }
}
