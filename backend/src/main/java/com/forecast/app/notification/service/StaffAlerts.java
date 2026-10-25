package com.forecast.app.notification.service;

import com.forecast.app.admin.api.StaffDirectoryApi;
import com.forecast.app.notification.api.MailApi;
import com.forecast.app.notification.api.StaffAlertApi;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link StaffAlertApi} 的实现：每个收件人一封模板邮件（经邮件任务表，失败有上限地重试）。
 * 收件人：按角色（模板的 staff_perms 或调用方指定的权限点）→ 有这些权限点的在用管理员；没有时发告警邮箱（兜底）；都没有时只记日志（S9，SQ-14）。
 */
@Component
class StaffAlerts implements StaffAlertApi {

  private static final Logger log = LoggerFactory.getLogger(StaffAlerts.class);

  private final MailApi mail;
  private final NotifyProperties props;
  private final NotifyMapper mapper;
  private final StaffDirectoryApi directory;

  StaffAlerts(MailApi mail, NotifyProperties props, NotifyMapper mapper, StaffDirectoryApi directory) {
    this.mail = mail;
    this.props = props;
    this.mapper = mapper;
    this.directory = directory;
  }

  @Override
  public void notifyStaff(String template, Map<String, String> params) {
    notifyStaff(template, params, null);
  }

  @Override
  public void notifyStaff(String template, Map<String, String> params, Collection<String> permissions) {
    log.warn("员工提醒 template={} params={}", template, params);
    List<String> to = recipients(template, permissions);
    if (to.isEmpty()) {
      log.warn("员工提醒 {} 没有收件人：没有有对应权限点的管理员，也没有配置告警邮箱（FC_RISK_ALERT_EMAILS），只记了日志", template);
      return;
    }
    for (String email : to) mail.send(email, template, params);
  }

  /** 收件人：按角色；没有时告警邮箱 */
  List<String> recipients(String template, Collection<String> permissions) {
    Collection<String> perms = permissions;
    if (perms == null || perms.isEmpty()) {
      Rows.TemplateRow t = mapper.template(template);
      perms = t == null || t.staffPerms == null || t.staffPerms.isBlank() ? List.of() : Arrays.stream(t.staffPerms.split(",")).map(String::strip).toList();
    }
    if (!perms.isEmpty()) {
      List<String> byRole = directory.emailsWithAny(perms);
      if (!byRole.isEmpty()) return byRole;
      log.warn("员工提醒 {}：没有有权限点 {} 的在用管理员，改发告警邮箱", template, perms);
    }
    return props.staffAlertEmails().stream().filter(e -> e != null && !e.isBlank()).toList();
  }
}
