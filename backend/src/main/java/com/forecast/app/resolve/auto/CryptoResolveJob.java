package com.forecast.app.resolve.auto;

import com.forecast.app.common.job.JobLease;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 加密自动裁决任务（技术方案/v1-架构方案.md 第 5.2 节 CryptoResolver：每 15 秒；forecast S6）。先拿数据库租约（多实例只有一个在跑），
 * 进度行的条件更新和结算的防重复保证租约意外失效时也只结算一次。取数在事务外、有超时，一轮最长约 30 秒（ttl 120 秒，执行中续约）。
 * 开关 {@code forecast.resolve.crypto-enabled}（后端测试关闭，测试直接调用 {@link CryptoResolver#resolveDue()}）。
 */
@Component
@ConditionalOnProperty(name = "forecast.resolve.crypto-enabled", havingValue = "true", matchIfMissing = true)
class CryptoResolveJob {

  private static final Logger log = LoggerFactory.getLogger(CryptoResolveJob.class);
  static final String JOB = "resolve.crypto";
  private static final Duration TTL = Duration.ofSeconds(120);

  private final CryptoResolver resolver;
  private final JobLease lease;

  CryptoResolveJob(CryptoResolver resolver, JobLease lease) {
    this.resolver = resolver;
    this.lease = lease;
  }

  @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
  void tick() {
    try {
      lease.runExclusive(JOB, TTL, () -> {
        Map<Long, String> r = resolver.resolveDue();
        if (!r.isEmpty()) log.info("加密自动裁决这一轮：{}", r);
      });
    } catch (RuntimeException e) {
      log.error("加密自动裁决任务失败", e);
    }
  }
}
