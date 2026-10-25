package com.forecast.app.settle.web;

import com.forecast.app.common.e2e.E2eGuard;
import com.forecast.app.settle.recon.PoolReconService;
import com.forecast.app.settle.run.SettleService;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试专用（只在 profile e2e 下存在，forecast S5）：
 * {@code POST /api/e2e/settle/tick} 立即把派彩中的结算单、退款单派完（batch-size &gt; 0 时不用等派彩任务的 2 秒）；
 * {@code POST /api/e2e/settle/recon} 立即跑一次彩池对账（不用等每天 UTC 00:30）。
 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eSettle {

  private final SettleService settle;
  private final PoolReconService recon;

  E2eSettle(SettleService settle, PoolReconService recon) {
    this.settle = settle;
    this.recon = recon;
  }

  @PostMapping("/api/e2e/settle/tick")
  Map<String, Integer> tick() {
    return Map.of("finished", settle.drainAll());
  }

  @PostMapping("/api/e2e/settle/recon")
  Map<String, Object> recon() {
    PoolReconService.Report r = recon.run();
    return Map.of("markets", r.markets(), "runs", r.runs(), "fresh", r.fresh());
  }
}
