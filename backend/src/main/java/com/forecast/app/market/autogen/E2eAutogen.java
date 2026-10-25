package com.forecast.app.market.autogen;

import com.forecast.app.common.e2e.E2eGuard;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试专用（只在 profile e2e 下存在，forecast S6）：{@code POST /api/e2e/autogen/tick} 立即跑一轮加密市场自动生成，
 * 配合可调时钟（{@code /api/e2e/clock/*}），不用等每分钟的任务。
 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eAutogen {

  private final AutogenService autogen;

  E2eAutogen(AutogenService autogen) {
    this.autogen = autogen;
  }

  @PostMapping("/api/e2e/autogen/tick")
  Map<String, Integer> tick() {
    return Map.of("generated", autogen.generateDue());
  }
}
