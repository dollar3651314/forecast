package com.forecast.app.notification.service;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.common.health.AlertProperties;
import com.forecast.app.common.task.Task;
import com.forecast.app.common.task.TaskService;
import com.forecast.app.common.task.TaskStatus;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 发送邮件任务（{@code MAIL_SEND}）和推送任务（{@code PUSH_SEND}，S15b）。失败按任务表的规则退避重试，最多 5 次，仍失败把通知记录标为失败。
 * 发给客户的邮件在发送时才查邮箱（客户改了邮箱也发到新邮箱）。日志不打印邮件内容（可能有验证码）。
 * 测试环境（{@code FC_ENV_LABEL} 非空）发出的邮件和推送，标题前加"[测试] / [TEST] "（{@link EnvLabel}）。
 */
@Component
class NotifyWorker {

  private static final Logger log = LoggerFactory.getLogger(NotifyWorker.class);
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {};

  private final TaskService tasks;
  private final NotifyMapper mapper;
  private final MailTransport transport;
  private final AccountApi accounts;
  private final ObjectMapper json;
  private final WebPush webPush;
  private final EnvLabel envLabel;
  private final MailHealth mailHealth;

  /** 测试用：不关心邮件告警时 */
  NotifyWorker(TaskService tasks, NotifyMapper mapper, MailTransport transport, AccountApi accounts, ObjectMapper json, WebPush webPush, String envLabel) {
    this(tasks, mapper, transport, accounts, json, webPush, envLabel, new MailHealth(AlertProperties.defaults(), true));
  }

  @Autowired
  NotifyWorker(TaskService tasks, NotifyMapper mapper, MailTransport transport, AccountApi accounts, ObjectMapper json, WebPush webPush,
      @Value("${forecast.env-label:}") String envLabel, MailHealth mailHealth) {
    this.tasks = tasks;
    this.mapper = mapper;
    this.transport = transport;
    this.accounts = accounts;
    this.json = json;
    this.webPush = webPush;
    this.envLabel = new EnvLabel(envLabel);
    this.mailHealth = mailHealth;
  }

  /** 把到期的邮件和推送任务全部发完，返回处理的数量 */
  int drain() {
    return drain(NotifyService.MAIL_TASK) + drain(NotifyService.PUSH_TASK);
  }

  private int drain(String type) {
    int n = 0;
    while (true) {
      var batch = tasks.claim(type, "notify", 20);
      if (batch.isEmpty()) return n;
      for (Task t : batch) {
        handle(t);
        n++;
      }
    }
  }

  void handle(Task t) {
    if (NotifyService.PUSH_TASK.equals(t.taskType())) handlePush(t);
    else handleMail(t);
  }

  private void handleMail(Task t) {
    Long recordId = null;
    try {
      Map<String, Object> p = json.readValue(t.payload(), MAP);
      recordId = p.get("recordId") instanceof Number num ? num.longValue() : null;
      String to = p.get("to") instanceof String s ? s : null;
      if (to == null && p.get("userId") instanceof Number uid) to = accounts.email(uid.longValue());
      if (to == null) throw new IllegalStateException("没有收件人");
      Render.Text text;
      if (p.get("template") instanceof String code) {
        Rows.TemplateRow tpl = mapper.template(code);
        if (tpl == null) throw new IllegalStateException("模板不存在：" + code);
        Map<String, String> params = json.convertValue(p.getOrDefault("params", Map.of()), PARAMS);
        if (p.get("lang") instanceof String lang) {
          Render.Text r = Render.render(tpl, params, lang);
          text = new Render.Text(envLabel.prefix(lang) + r.title(), r.body());
        } else {
          text = Render.bilingual(tpl, params, envLabel.zh(), envLabel.en());
        }
      } else {
        // 后台手写的邮件：不知道是什么语言
        text = new Render.Text(envLabel.prefix(null) + p.get("subject"), String.valueOf(p.get("body")));
      }
      transport.send(to, text.title(), text.body());
      mailHealth.succeeded();
      tasks.succeed(t.id());
      if (recordId != null) mapper.updateRecord(recordId, "SENT", null);
    } catch (MailTransport.NotConfigured e) {
      log.warn("邮件未配置，未发送 task={}", t.id());
      tasks.succeed(t.id());
      if (recordId != null) mapper.updateRecord(recordId, "FAILED", e.getMessage());
    } catch (Exception e) {
      String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
      log.warn("邮件发送失败 task={} 第 {} 次：{}", t.id(), t.attempts(), msg);
      // 告警（E8）：连续失败算一次；模板不存在、没有收件人这类和邮件服务器无关的错误也算进去（同样需要人处理）
      mailHealth.failed(msg);
      tasks.fail(t.id(), msg);
      if (recordId != null) {
        Task after = tasks.find(t.id());
        boolean dead = after != null && after.status() == TaskStatus.FAILED;
        mapper.updateRecord(recordId, dead ? "FAILED" : "PENDING", msg.length() > 500 ? msg.substring(0, 500) : msg);
      }
    }
  }

  /** 推送内容：标题、正文、类型、站内信编号（客户端点开后跳到这条站内信）；超长时截短正文 */
  private void handlePush(Task t) {
    Long recordId = null;
    try {
      Map<String, Object> p = json.readValue(t.payload(), MAP);
      recordId = p.get("recordId") instanceof Number num ? num.longValue() : null;
      long subId = ((Number) p.get("subId")).longValue();
      long userId = ((Number) p.get("userId")).longValue();
      Rows.PushSubRow sub = mapper.pushSub(subId);
      if (sub == null || sub.userId != userId) {
        tasks.succeed(t.id());
        if (recordId != null) mapper.updateRecord(recordId, "SKIPPED", "订阅已删除");
        return;
      }
      Render.Text text;
      boolean urgent = false;
      if (p.get("template") instanceof String code) {
        Rows.TemplateRow tpl = mapper.template(code);
        if (tpl == null) throw new IllegalStateException("模板不存在：" + code);
        String lang = String.valueOf(p.get("lang"));
        Render.Text r = Render.render(tpl, json.convertValue(p.getOrDefault("params", Map.of()), PARAMS), lang);
        text = new Render.Text(envLabel.prefix(lang) + r.title(), r.body());
        // 复制源的追保、强平推送是高优先级；forecast 第一期没有高优先级的推送（S1 删除），需要时按模板编号在这里加
      } else {
        text = new Render.Text(envLabel.prefix(null) + p.get("subject"), String.valueOf(p.get("body")));
      }
      byte[] plaintext = pushPayload(text, p.get("kind"), p.get("messageId"));
      WebPush.Result r = webPush.send(sub.endpoint, WebPushCrypto.b64(sub.p256dh), WebPushCrypto.b64(sub.auth), plaintext, urgent);
      switch (r) {
        case WebPush.Delivered d -> {
          tasks.succeed(t.id());
          if (recordId != null) mapper.updateRecord(recordId, "SENT", null);
        }
        case WebPush.Gone g -> {
          mapper.deletePushSub(subId);
          tasks.succeed(t.id());
          if (recordId != null) mapper.updateRecord(recordId, "FAILED", "订阅已失效（" + g.status() + "），已删除");
        }
        case WebPush.Rejected rej -> {
          log.warn("推送被拒绝 task={} {}", t.id(), rej.reason());
          tasks.succeed(t.id());
          if (recordId != null) mapper.updateRecord(recordId, "FAILED", cut(rej.reason()));
        }
        case WebPush.Retry retry -> retry(t, recordId, retry.reason());
      }
    } catch (RuntimeException e) {
      retry(t, recordId, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private void retry(Task t, Long recordId, String msg) {
    log.warn("推送失败 task={} 第 {} 次：{}", t.id(), t.attempts(), msg);
    tasks.fail(t.id(), msg);
    if (recordId != null) {
      Task after = tasks.find(t.id());
      boolean dead = after != null && after.status() == TaskStatus.FAILED;
      mapper.updateRecord(recordId, dead ? "FAILED" : "PENDING", cut(msg));
    }
  }

  private static String cut(String s) {
    return s == null || s.length() <= 500 ? s : s.substring(0, 500);
  }

  /** JSON：{"title","body","kind","id"}；加密前不超过 RFC 8291 一条记录能放的长度，超长截短正文 */
  byte[] pushPayload(Render.Text text, Object kind, Object messageId) {
    String body = text.body();
    while (true) {
      Map<String, Object> m = new java.util.LinkedHashMap<>();
      m.put("title", text.title());
      m.put("body", body);
      m.put("kind", kind);
      if (messageId != null) m.put("id", String.valueOf(messageId));
      byte[] b = json.writeValueAsBytes(m);
      if (b.length <= WebPushCrypto.MAX_PLAINTEXT || body.isEmpty()) return b;
      // 中文一个字最多 3 字节：每次至少去掉超出部分对应的字数，最后加省略号
      int drop = Math.max(20, (b.length - WebPushCrypto.MAX_PLAINTEXT) / 3 + 2);
      body = body.length() <= drop ? "" : body.substring(0, body.length() - drop) + "…";
    }
  }
}
