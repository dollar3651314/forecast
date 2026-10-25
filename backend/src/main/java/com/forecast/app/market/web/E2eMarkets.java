package com.forecast.app.market.web;

import com.forecast.app.common.e2e.E2eGuard;
import com.forecast.app.market.core.MarketService;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试专用（只在 profile e2e 下存在，forecast S3）：{@code POST /api/e2e/markets/tick} 立即跑一次截止任务和事件结束任务，
 * 配合可调时钟（{@code /api/e2e/clock/*}）验证"到点截止"，不用等定时任务的 5 秒、15 秒。
 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eMarkets {

  private final MarketService markets;

  E2eMarkets(MarketService markets) {
    this.markets = markets;
  }

  @PostMapping("/api/e2e/markets/tick")
  Map<String, Integer> tick() {
    int closed = markets.closeDue();
    int ended = markets.endDue();
    return Map.of("closed", closed, "ended", ended);
  }
}
