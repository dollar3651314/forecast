package com.forecast.app.growth.waitlist;

import com.forecast.app.common.e2e.E2eGuard;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试的等候页入口（只在 profile e2e 下存在，forecast S16）：{@code POST /api/e2e/waitlist/launch-tick} 立即跑一轮上线通知的发送（不用等 15 秒的任务）；
 * {@code POST /api/e2e/waitlist/reset-launch} 回到"还没上线"。
 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eWaitlist {

  private final WaitlistService waitlist;
  private final WaitlistMapper mapper;

  E2eWaitlist(WaitlistService waitlist, WaitlistMapper mapper) {
    this.waitlist = waitlist;
    this.mapper = mapper;
  }

  /** 回到"还没上线"（只删上线通知这一行；订阅的状态不改），用例在同一个库里重跑时用 */
  @PostMapping("/api/e2e/waitlist/reset-launch")
  Map<String, Object> resetLaunch() {
    return Map.of("deleted", mapper.deleteLaunchForE2e());
  }

  @PostMapping("/api/e2e/waitlist/launch-tick")
  Map<String, Object> tick() {
    return Map.of("sent", waitlist.sendLaunchBatch(), "launch", waitlist.launch());
  }
}
