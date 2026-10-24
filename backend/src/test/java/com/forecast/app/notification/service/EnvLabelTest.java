package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.common.task.Task;
import com.forecast.app.common.task.TaskService;
import com.forecast.app.common.task.TaskStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * 测试环境标识（S20）：邮件和推送标题按实际语言加"[测试] / [TEST] "；没设置标识（生产）时标题不变。
 * 不启动 Spring，直接调用 {@link NotifyWorker#handle}；任务表、模板、订阅都用替身。
 */
class EnvLabelTest {

  final ObjectMapper json = new ObjectMapper();
  final TaskService tasks = mock(TaskService.class);
  final NotifyMapper mapper = mock(NotifyMapper.class);
  final AccountApi accounts = mock(AccountApi.class);
  final WebPush webPush = mock(WebPush.class);
  final List<String> subjects = new ArrayList<>();
  final MailTransport transport = (to, subject, body) -> subjects.add(subject);

  EnvLabelTest() {
    Rows.TemplateRow deposit = new Rows.TemplateRow();
    deposit.code = "DEPOSIT_CREDITED";
    deposit.titleZh = "充值已到账";
    deposit.titleEn = "Deposit credited";
    deposit.bodyZh = "您的 {amount} USDT 已到账。";
    deposit.bodyEn = "Your {amount} USDT has been credited.";
    when(mapper.template("DEPOSIT_CREDITED")).thenReturn(deposit);
    Rows.TemplateRow code = new Rows.TemplateRow();
    code.code = "EMAIL_CODE";
    code.titleZh = "Forecast 验证码";
    code.titleEn = "Forecast verification code";
    code.bodyZh = "您的验证码是 {code}";
    code.bodyEn = "Your code is {code}";
    when(mapper.template("EMAIL_CODE")).thenReturn(code);
    when(accounts.email(7L)).thenReturn("a@example.com");
    Rows.PushSubRow sub = new Rows.PushSubRow();
    sub.id = 3L;
    sub.userId = 7L;
    sub.endpoint = "https://fcm.googleapis.com/x";
    sub.p256dh = "AAAA";
    sub.auth = "AAAA";
    when(mapper.pushSub(3L)).thenReturn(sub);
    when(webPush.send(anyString(), any(), any(), any(), anyBoolean())).thenReturn(new WebPush.Delivered());
  }

  NotifyWorker worker(String label) {
    return new NotifyWorker(tasks, mapper, transport, accounts, json, webPush, label);
  }

  Task task(String type, Map<String, Object> payload) {
    return new Task(1, type, json.writeValueAsString(payload), TaskStatus.RUNNING, 1, 5, null, null);
  }

  String mail(String label, Map<String, Object> payload) {
    subjects.clear();
    worker(label).handle(task(NotifyService.MAIL_TASK, payload));
    assertThat(subjects).hasSize(1);
    return subjects.getFirst();
  }

  String pushTitle(String label, Map<String, Object> payload) {
    org.mockito.Mockito.clearInvocations(webPush);
    worker(label).handle(task(NotifyService.PUSH_TASK, payload));
    ArgumentCaptor<byte[]> plain = ArgumentCaptor.forClass(byte[].class);
    verify(webPush).send(anyString(), any(), any(), plain.capture(), anyBoolean());
    return json.readTree(new String(plain.getValue(), StandardCharsets.UTF_8)).get("title").asString();
  }

  static final Map<String, Object> ZH_MAIL = Map.of("userId", 7, "template", "DEPOSIT_CREDITED", "params", Map.of("amount", "1"), "lang", "zh-CN");
  static final Map<String, Object> EN_MAIL = Map.of("userId", 7, "template", "DEPOSIT_CREDITED", "params", Map.of("amount", "1"), "lang", "en");
  static final Map<String, Object> CODE_MAIL = Map.of("to", "b@example.com", "template", "EMAIL_CODE", "params", Map.of("code", "123456"));
  static final Map<String, Object> MANUAL_MAIL = Map.of("userId", 7, "subject", "系统维护通知", "body", "今晚维护");

  @Test
  void 测试环境的邮件标题按邮件语言加前缀() {
    assertThat(mail("test", ZH_MAIL)).isEqualTo("[测试] 充值已到账");
    assertThat(mail("test", EN_MAIL)).isEqualTo("[TEST] Deposit credited");
    assertThat(mail("test", CODE_MAIL)).as("中英文各一份的邮件，两段各加各的").isEqualTo("[测试] Forecast 验证码 | [TEST] Forecast verification code");
    assertThat(mail("test", MANUAL_MAIL)).as("后台手写的不知道语言").isEqualTo("[测试/TEST] 系统维护通知");
  }

  @Test
  void 没有设置标识时邮件标题不变_生产() {
    for (String label : new String[] {"", "  ", null}) {
      assertThat(mail(label, ZH_MAIL)).isEqualTo("充值已到账");
      assertThat(mail(label, EN_MAIL)).isEqualTo("Deposit credited");
      assertThat(mail(label, CODE_MAIL)).isEqualTo("Forecast 验证码 | Forecast verification code");
      assertThat(mail(label, MANUAL_MAIL)).isEqualTo("系统维护通知");
    }
  }

  @Test
  void 推送标题也加前缀_系统通知栏里看不到页面上的标识条() {
    Map<String, Object> zh = Map.of("userId", 7, "subId", 3, "template", "DEPOSIT_CREDITED", "params", Map.of("amount", "1"), "lang", "zh-CN", "kind", "deposit");
    Map<String, Object> en = Map.of("userId", 7, "subId", 3, "template", "DEPOSIT_CREDITED", "params", Map.of("amount", "1"), "lang", "en", "kind", "deposit");
    Map<String, Object> manual = Map.of("userId", 7, "subId", 3, "subject", "维护通知", "body", "今晚维护", "kind", "system");
    assertThat(pushTitle("test", zh)).isEqualTo("[测试] 充值已到账");
    assertThat(pushTitle("test", en)).isEqualTo("[TEST] Deposit credited");
    assertThat(pushTitle("test", manual)).isEqualTo("[测试/TEST] 维护通知");
    assertThat(pushTitle("", zh)).isEqualTo("充值已到账");
    assertThat(pushTitle("", manual)).isEqualTo("维护通知");
    verify(tasks, org.mockito.Mockito.atLeastOnce()).succeed(anyLong());
  }
}
