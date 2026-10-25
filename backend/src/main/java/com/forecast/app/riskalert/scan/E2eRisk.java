package com.forecast.app.riskalert.scan;

import com.forecast.app.common.e2e.E2eGuard;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 端到端测试专用（只在 profile e2e 下存在，forecast S10）：{@code POST /api/e2e/riskalert/scan} 立即扫一轮风控（不用等 30 秒的任务）。 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eRisk {

  private final RiskScanner scanner;

  E2eRisk(RiskScanner scanner) {
    this.scanner = scanner;
  }

  @PostMapping("/api/e2e/riskalert/scan")
  RiskScanner.Report scan() {
    return scanner.scanAll();
  }
}
