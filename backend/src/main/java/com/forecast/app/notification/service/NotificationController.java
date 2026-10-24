package com.forecast.app.notification.service;

import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.web.CurrentUser;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 客户端的站内信和通知偏好接口（字段和 web/client/src/api 的 NotificationItem、NotifyPrefs 一致）。 */
@RestController
class NotificationController {

  record PrefsBody(Boolean push, Boolean email) {}

  record LangBody(String lang) {}

  /** 浏览器 PushSubscription.toJSON() 的格式 */
  record SubscriptionBody(String endpoint, Keys keys) {
    record Keys(String p256dh, String auth) {}
  }

  record EndpointBody(String endpoint) {}

  private final NotifyService service;

  NotificationController(NotifyService service) {
    this.service = service;
  }

  /** 站内信，最新的在前；lang 是客户端当前界面语言（zh-CN / en） */
  @GetMapping("/api/notifications")
  NotifyService.Page<NotifyService.Item> list(@RequestParam(defaultValue = "zh-CN") String lang, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return service.list(CurrentUser.id(), lang, page, pageSize);
  }

  @GetMapping("/api/notifications/unread-count")
  Map<String, Long> unread() {
    return Map.of("count", service.unread(CurrentUser.id()));
  }

  @PostMapping("/api/notifications/{id}/read")
  void read(@PathVariable long id) {
    service.markRead(CurrentUser.id(), id);
  }

  @PostMapping("/api/notifications/read-all")
  void readAll() {
    service.markAllRead(CurrentUser.id());
  }

  @GetMapping("/api/me/notify-prefs")
  NotifyService.Prefs prefs() {
    return service.prefs(CurrentUser.id());
  }

  @PutMapping("/api/me/notify-prefs")
  NotifyService.Prefs setPrefs(@RequestBody PrefsBody body) {
    if (body == null || body.push() == null || body.email() == null) {
      throw new ValidationException(List.of(new ValidationException.Field(body == null || body.push() == null ? "push" : "email", "required")));
    }
    return service.setPrefs(CurrentUser.id(), body.push(), body.email());
  }

  /** 客户端切换语言时调用，推送和邮件按这个语言生成 */
  @PutMapping("/api/me/notify-lang")
  void setLang(@RequestBody LangBody body) {
    service.setLang(CurrentUser.id(), body == null ? null : body.lang());
  }

  /** Web Push 公钥（客户端订阅时的 applicationServerKey）；服务器没有配置推送时为 null */
  @GetMapping("/api/notifications/push/key")
  Map<String, String> pushKey() {
    Map<String, String> m = new java.util.HashMap<>();
    m.put("publicKey", service.pushPublicKey());
    return m;
  }

  /** 保存这台设备的推送订阅（同一个地址重复提交只保留一条） */
  @PostMapping("/api/notifications/push/subscriptions")
  void subscribe(@RequestBody SubscriptionBody body) {
    SubscriptionBody b = body == null ? new SubscriptionBody(null, null) : body;
    SubscriptionBody.Keys k = b.keys() == null ? new SubscriptionBody.Keys(null, null) : b.keys();
    service.subscribe(CurrentUser.id(), b.endpoint(), k.p256dh(), k.auth());
  }

  /** 取消这台设备的推送订阅（退出登录、关闭授权时调用） */
  @PostMapping("/api/notifications/push/unsubscribe")
  void unsubscribe(@RequestBody EndpointBody body) {
    service.unsubscribe(CurrentUser.id(), body == null ? null : body.endpoint());
  }
}
