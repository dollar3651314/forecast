package com.forecast.app.wallet.recon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每小时对一次账（启动 5 分钟后第一次）。测试配置里关闭，测试直接调用。 */
@Component
@ConditionalOnProperty(name = "forecast.wallet.recon.enabled", havingValue = "true", matchIfMissing = true)
class ReconScheduler {

  private static final Logger log = LoggerFactory.getLogger(ReconScheduler.class);

  private final ReconService service;

  ReconScheduler(ReconService service) {
    this.service = service;
  }

  @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
  void tick() {
    try {
      service.run();
    } catch (RuntimeException e) {
      log.error("对账失败", e);
    }
  }
}
