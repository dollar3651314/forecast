package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.admin.api.StaffDirectoryApi;
import com.forecast.app.notification.api.MailApi;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 员工提醒按角色发（forecast S9，SQ-14）：模板的收件人权限点 → 有权限的管理员；没有这样的人时发告警邮箱；模板没配权限点的照旧发告警邮箱。 */
class StaffAlertsTest {

  static NotifyProperties props(List<String> alertEmails) {
    return new NotifyProperties(false, new NotifyProperties.Mail(null, 587, null, null, null, "STARTTLS"),
        new NotifyProperties.Push(null, null, null, java.time.Duration.ofHours(24), List.of(), false, 10), alertEmails);
  }

  /** 假的模板表：只回答 staff_perms */
  static NotifyMapper templates(Map<String, String> perms) {
    return (NotifyMapper) java.lang.reflect.Proxy.newProxyInstance(NotifyMapper.class.getClassLoader(), new Class<?>[] {NotifyMapper.class}, (p, m, a) -> {
      if (m.getName().equals("template")) {
        Rows.TemplateRow t = new Rows.TemplateRow();
        t.code = (String) a[0];
        t.staffPerms = perms.getOrDefault(t.code, "");
        return t;
      }
      throw new UnsupportedOperationException(m.getName());
    });
  }

  record Sent(String to, String template) {}

  static class Outbox implements MailApi {
    final List<Sent> sent = new ArrayList<>();

    @Override
    public void send(String to, String template, Map<String, String> params) {
      sent.add(new Sent(to, template));
    }

    @Override
    public void send(String to, String template, Map<String, String> params, String lang) {
      send(to, template, params);
    }
  }

  @Test
  void 按角色_没有人时发告警邮箱_没配权限点的发告警邮箱_指定权限点优先() {
    Outbox out = new Outbox();
    List<Collection<String>> asked = new ArrayList<>();
    StaffDirectoryApi dir = codes -> {
      asked.add(codes);
      return codes.contains("resolve:confirm") ? List.of("amos@example.com", "judge@example.com") : List.of();
    };
    StaffAlerts alerts = new StaffAlerts(out, props(List.of("alerts@example.com")), templates(Map.of("RESOLVE_PROPOSED", "resolve:confirm",
        "DISPUTE_NEW", "dispute:handle,dispute:view")), dir);
    alerts.notifyStaff("RESOLVE_PROPOSED", Map.of());
    assertThat(out.sent).extracting(Sent::to).containsExactly("amos@example.com", "judge@example.com");
    out.sent.clear();
    // 有权限点但没有这样的管理员 → 告警邮箱兜底
    alerts.notifyStaff("DISPUTE_NEW", Map.of());
    assertThat(asked.getLast()).containsExactly("dispute:handle", "dispute:view");
    assertThat(out.sent).extracting(Sent::to).containsExactly("alerts@example.com");
    out.sent.clear();
    // 复用的模板（没配权限点）→ 告警邮箱，不查管理员
    int before = asked.size();
    alerts.notifyStaff("HOT_WALLET_LOW", Map.of());
    assertThat(asked).hasSize(before);
    assertThat(out.sent).extracting(Sent::to).containsExactly("alerts@example.com");
    out.sent.clear();
    // 调用方指定的权限点优先于模板的
    alerts.notifyStaff("DISPUTE_NEW", Map.of(), List.of("resolve:confirm"));
    assertThat(out.sent).extracting(Sent::to).containsExactly("amos@example.com", "judge@example.com");
    out.sent.clear();
    // 都没有：只记日志
    new StaffAlerts(out, props(List.of()), templates(Map.of()), dir).notifyStaff("HOT_WALLET_LOW", Map.of());
    assertThat(out.sent).isEmpty();
  }
}
