package com.forecast.app.notification.service;

import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.common.health.AlertProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 邮件连续发送失败告警（E8）：{@link NotifyWorker} 每发一封邮件告诉它成功还是失败；连续 {@code forecast.alert.mail-failures}（默认 5）封失败 →
 * 健康检查的 {@code alerts.mail} 变成 DOWN（不影响 {@code status}：邮件服务商故障不能影响交易，也不能让部署回滚，约定 14）。成功一封就恢复。
 * 没有配置邮件服务器（只记录、不发）时不检查。告警邮件本身也走同一个邮件服务器，邮件坏了的时候可能发不出去，所以健康检查里也能看到。
 */
@Component
class MailHealth implements AlertCheck {

  private final AlertProperties props;
  private final boolean configured;
  private volatile int failures;
  private volatile String lastError;

  @Autowired
  MailHealth(AlertProperties props, NotifyProperties notify) {
    this(props, notify.mail().configured());
  }

  MailHealth(AlertProperties props, boolean configured) {
    this.props = props;
    this.configured = configured;
  }

  void succeeded() {
    failures = 0;
    lastError = null;
  }

  void failed(String error) {
    failures++;
    lastError = error == null ? null : error.length() > 200 ? error.substring(0, 200) : error;
  }

  int failures() {
    return failures;
  }

  @Override
  public List<Item> evaluate() {
    if (!configured) return List.of();
    boolean ok = failures < props.mailFailures();
    return List.of(new Item("mail", "邮件连续发送失败", "Email delivery failing", ok,
        ok ? "正常" : "连续 " + failures + " 封邮件发送失败（最近一次：" + lastError + "）"));
  }
}
