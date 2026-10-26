package com.forecast.app.market.remind;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.ratelimit.UserWriteLimited;
import com.forecast.app.common.web.CurrentUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * #23 开赛提醒的客户端接口（forecast S11，{@link ReminderService}）。订阅、退订按客户限流（{@code @UserWriteLimited}，约定 16）；
 * 邮件里的退订链接 {@code POST /api/reminders/unsubscribe} 不用登录（{@code SessionFilter.PUBLIC}），凭随机串只能退订那一条。
 */
@RestController
class ReminderController {

  record TokenRequest(String token) {}

  private final ReminderService reminders;

  ReminderController(ReminderService reminders) {
    this.reminders = reminders;
  }

  /** "通知偏好"里的订阅列表和本人能不能订阅 */
  @GetMapping("/api/me/reminders")
  ReminderService.Listing list() {
    return reminders.list(CurrentUser.id());
  }

  /** 订阅（市场详情"开放时提醒我"）：REMINDER_NOT_AVAILABLE 已开放或已结束、REMINDER_LOGIN_REQUIRED 没登录过（403；S16 起注册后的自动登录也算，SQ-27）、REMINDER_TOO_MANY */
  @UserWriteLimited
  @PostMapping("/api/markets/{publicNo}/reminder")
  ReminderService.State subscribe(@PathVariable String publicNo) {
    return reminders.subscribe(CurrentUser.id(), parse(publicNo));
  }

  @UserWriteLimited
  @DeleteMapping("/api/markets/{publicNo}/reminder")
  ReminderService.State unsubscribe(@PathVariable String publicNo) {
    return reminders.unsubscribe(CurrentUser.id(), parse(publicNo));
  }

  /** 邮件里的退订链接（客户端 /unsubscribe?t=… 页面调用，不用登录） */
  @PostMapping("/api/reminders/unsubscribe")
  ReminderService.Unsubscribed unsubscribeByToken(@RequestBody TokenRequest r) {
    return reminders.unsubscribeByToken(r == null ? null : r.token());
  }

  private static long parse(String publicNo) {
    try {
      return Long.parseLong(publicNo);
    } catch (NumberFormatException e) {
      throw new BizException("NOT_FOUND", "市场不存在");
    }
  }
}
