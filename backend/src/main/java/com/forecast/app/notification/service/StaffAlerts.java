package com.forecast.app.notification.service;

import com.forecast.app.notification.api.MailApi;
import com.forecast.app.notification.api.StaffAlertApi;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** {@link StaffAlertApi} 的实现：给每个配置的邮箱发一封模板邮件（经邮件任务表，失败有上限地重试）；没有配置时只记日志。 */
@Component
class StaffAlerts implements StaffAlertApi {

  private static final Logger log = LoggerFactory.getLogger(StaffAlerts.class);

  private final MailApi mail;
  private final NotifyProperties props;

  StaffAlerts(MailApi mail, NotifyProperties props) {
    this.mail = mail;
    this.props = props;
  }

  @Override
  public void notifyStaff(String template, Map<String, String> params) {
    log.warn("业务告警 template={} params={}", template, params);
    var to = props.staffAlertEmails().stream().filter(e -> e != null && !e.isBlank()).toList();
    if (to.isEmpty()) {
      log.warn("没有配置业务告警邮箱（forecast.notify.staff-alert-emails，环境变量 FC_RISK_ALERT_EMAILS），只记了日志");
      return;
    }
    for (String email : to) mail.send(email, template, params);
  }
}
