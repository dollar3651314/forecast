package com.forecast.app.wallet.withdraw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每 30 秒推进提现状态：冷静期满、大额延迟满、待上链的去广播（AC-19：审核通过后 5 分钟内广播）、查上链中的结果。测试配置里关闭，测试直接调用。 */
@Component
@ConditionalOnProperty(name = "forecast.wallet.withdraw.scheduler-enabled", havingValue = "true", matchIfMissing = true)
class WithdrawScheduler {

  private static final Logger log = LoggerFactory.getLogger(WithdrawScheduler.class);

  private final WithdrawService service;

  WithdrawScheduler(WithdrawService service) {
    this.service = service;
  }

  @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
  void tick() {
    try {
      service.advance();
      service.track();
    } catch (RuntimeException e) {
      log.error("提现状态推进失败", e);
    }
  }
}
