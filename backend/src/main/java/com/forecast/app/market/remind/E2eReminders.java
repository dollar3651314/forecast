package com.forecast.app.market.remind;

import com.forecast.app.common.e2e.E2eGuard;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试专用（只在 profile e2e 下存在，forecast S11）：{@code POST /api/e2e/reminders/tick} 立即跑一轮开赛提醒，
 * 配合可调时钟（{@code /api/e2e/clock/*}），不用等每 30 秒的任务。
 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eReminders {

  private final ReminderService reminders;

  E2eReminders(ReminderService reminders) {
    this.reminders = reminders;
  }

  @PostMapping("/api/e2e/reminders/tick")
  Map<String, Integer> tick() {
    return Map.of("sent", reminders.sendDue());
  }
}
