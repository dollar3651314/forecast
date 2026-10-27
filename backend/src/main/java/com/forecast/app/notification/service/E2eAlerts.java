package com.forecast.app.notification.service;

import com.forecast.app.common.e2e.E2eGuard;
import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.common.health.AlertMailSwitch;
import com.forecast.app.common.health.AlertProperties;
import com.forecast.app.notification.api.MailApi;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试的告警邮件触发（forecast S12b，只在 profile e2e 下存在）：{@code POST /api/e2e/alerts/check {"email": ...}} 用真实的告警检查、
 * 真实的后台开关（参数设置"告警邮件"）和真实的邮件链路（模板 → 任务表 → e2e 邮件替身）查一遍，收件人换成请求里的邮箱（端到端环境没有配 FC_ALERT_EMAILS）。
 * 每个邮箱一个独立的告警状态（第一次调用就像应用刚启动），同一个邮箱再调用接着上一次的状态。不等每 15 秒的定时任务。
 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eAlerts {

  record CheckBody(String email) {}

  private final List<AlertCheck> checks;
  private final AlertProperties props;
  private final MailApi mail;
  private final AlertMailSwitch mailSwitch;
  private final Clock clock;
  private final Map<String, AlertMonitor> monitors = new ConcurrentHashMap<>();

  E2eAlerts(List<AlertCheck> checks, AlertProperties props, MailApi mail, AlertMailSwitch mailSwitch, Clock clock) {
    this.checks = checks;
    this.props = props;
    this.mail = mail;
    this.mailSwitch = mailSwitch;
    this.clock = clock;
  }

  @PostMapping("/api/e2e/alerts/check")
  Map<String, Object> check(@RequestBody CheckBody body) {
    String email = body == null || body.email() == null ? "" : body.email().strip();
    if (email.isEmpty() || email.length() > 200) throw new IllegalArgumentException("email");
    AlertMonitor m = monitors.computeIfAbsent(email, e -> new AlertMonitor(checks, props.with(true, List.of(e)), mail, mailSwitch, clock));
    m.check();
    return Map.of("ok", true, "mailEnabled", mailSwitch.mailEnabled(), "muted", List.copyOf(mailSwitch.muted()));
  }
}
