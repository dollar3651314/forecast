package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.task.TaskService;
import com.forecast.app.common.task.TaskStatus;
import com.forecast.app.notification.api.NotifyAdminApi;
import com.forecast.app.notification.api.NotifyApi;
import com.forecast.app.notification.api.NotifyChannel;
import com.forecast.app.support.FixedClockConfig;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * 通知：站内信、通知偏好、邮件、通知记录。邮件发到测试进程里的 SMTP 服务器（GreenMail，端口 3025），不连外网。
 * 品牌名上线前沿用 Quick Come（迁移 V8，Amos 2026-10-25）；正式改名时用新迁移改模板，并改这里的期望值。
 */
class NotifyFlowTest extends NotifyTestSupport {

  @Test
  void 验证码邮件按邮箱发_中英文各一份_记录已发送() throws Exception {
    User u = register();
    MimeMessage m = mailsTo(u.email()).getFirst();
    assertThat(m.getSubject()).isEqualTo("Quick Come 验证码 | Quick Come verification code");
    assertThat(body(m)).contains("你的验证码是").contains("Your code is").contains("10 分钟内有效");
    assertThat(m.getFrom()[0].toString()).isEqualTo("noreply@forecast.test");
    assertThat(jdbc.queryForObject("SELECT status FROM notify_record WHERE recipient = ? AND template_code = 'EMAIL_CODE'", String.class, u.email())).isEqualTo("SENT");
  }

  @Test
  void 站内信按客户当前界面语言显示_未读数_标记已读_只能标记自己的() throws Exception {
    User u = registerClean();
    User other = registerClean();
    // 通过、驳回是两个模板（复制源 V30 起）；通过的文案（S9 PM 定稿）说可以提现，没有"交易"
    notify.toUser(u.id(), "KYC_REJECTED", Map.of("reason", "照片模糊"));
    notify.toUser(u.id(), "KYC_APPROVED", Map.of());

    String zh = call(get("/api/notifications?lang=zh-CN"), u.cookie(), null).getContentAsString();
    assertThat(zh).contains("\"total\":2").contains("\"kind\":\"kyc\"").contains("\"title\":\"身份认证已通过\"").contains("\"title\":\"身份认证未通过\"")
        .contains("你的身份认证已通过，现在可以提现，下注也不再受累计充值的认证门槛限制。").contains("驳回原因：照片模糊").contains("\"read\":false")
        .doesNotContain("交易");
    assertThat(zh.indexOf("已通过")).as("最新的在前").isLessThan(zh.indexOf("未通过"));
    String en = call(get("/api/notifications?lang=en&page=1&pageSize=1"), u.cookie(), null).getContentAsString();
    assertThat(en).contains("\"total\":2").contains("\"pageSize\":1").contains("You can now withdraw")
        .doesNotContain("not approved");
    assertThat(call(get("/api/notifications/unread-count"), u.cookie(), null).getContentAsString()).isEqualTo("{\"count\":2}");

    long first = jdbc.queryForObject("SELECT MIN(id) FROM notify_message WHERE user_id = ?", Long.class, u.id());
    assertThat(call(post("/api/notifications/" + first + "/read"), other.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(call(get("/api/notifications/unread-count"), u.cookie(), null).getContentAsString()).as("别人标记不了").isEqualTo("{\"count\":2}");
    call(post("/api/notifications/" + first + "/read"), u.cookie(), null);
    assertThat(call(get("/api/notifications/unread-count"), u.cookie(), null).getContentAsString()).isEqualTo("{\"count\":1}");
    call(post("/api/notifications/read-all"), u.cookie(), null);
    assertThat(call(get("/api/notifications/unread-count"), u.cookie(), null).getContentAsString()).isEqualTo("{\"count\":0}");
    assertThat(call(get("/api/notifications"), null, null).getStatus()).isEqualTo(401);
  }

  @Test
  void 基线模板_认证通过说可以提现_没有差价合约的模板和交易的说法() {
    Map<String, Object> approved = jdbc.queryForMap("SELECT audience, kind, variables, channels, body_zh, body_en FROM notify_template WHERE code = 'KYC_APPROVED'");
    assertThat(approved).containsEntry("audience", "USER").containsEntry("kind", "kyc").containsEntry("variables", "").containsEntry("channels", "INBOX,PUSH,EMAIL");
    assertThat((String) approved.get("body_zh")).isEqualTo("你的身份认证已通过，现在可以提现，下注也不再受累计充值的认证门槛限制。");
    assertThat((String) approved.get("body_en")).isEqualTo("Your identity has been verified. You can now withdraw, and the verification threshold on total deposits no longer limits your bets.");
    assertThat(jdbc.queryForObject("SELECT variables FROM notify_template WHERE code = 'KYC_REJECTED'", String.class)).isEqualTo("reason");
    // 复制源的差价合约模板和旧的 KYC_RESULT 不建（forecast S1 基线 V3）
    assertThat(jdbc.queryForList("SELECT code FROM notify_template", String.class)).doesNotContain("KYC_RESULT", "MARGIN_CALL", "LIQUIDATED", "ORDER_FILLED",
        "POSITION_OPENED", "POSITION_CLOSED", "POSITION_PARTIAL_CLOSED", "TP_SL_TRIGGERED", "RISK_AUTO_PAUSE");
    for (Map<String, Object> t : jdbc.queryForList("SELECT code, title_zh, title_en, body_zh, body_en FROM notify_template WHERE audience <> 'STAFF'")) {
      String all = t.get("title_zh") + "\n" + t.get("title_en") + "\n" + t.get("body_zh") + "\n" + t.get("body_en");
      assertThat(all).as("模板 %s 里没有交易的说法（\"交易所\"除外）", t.get("code")).doesNotContainPattern("交易(?!所)").doesNotContainIgnoringCase("trading").doesNotContainIgnoringCase(" trade");
    }
    // 正文里用到的变量都在 variables 列里（后台模板页按它提示运营、保存时按它检查）
    for (Map<String, Object> t : jdbc.queryForList("SELECT code, variables, title_zh, title_en, body_zh, body_en FROM notify_template WHERE audience = 'USER'")) {
      java.util.Set<String> declared = new java.util.HashSet<>(java.util.Arrays.asList(((String) t.get("variables")).split(",")));
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([A-Za-z0-9_]+)}").matcher(t.get("title_zh") + "\n" + t.get("title_en") + "\n" + t.get("body_zh") + "\n" + t.get("body_en"));
      while (m.find()) assertThat(declared).as("模板 %s 的变量 %s", t.get("code"), m.group(1)).contains(m.group(1));
    }
  }

  @Test
  void 三个渠道各一条记录_邮件按客户语言发出_关闭推送或邮件后记已跳过_站内信照常_AC25() throws Exception {
    User u = registerClean();
    assertThat(call(get("/api/me/notify-prefs"), u.cookie(), null).getContentAsString()).isEqualTo("{\"push\":true,\"email\":true}");
    notify.toUser(u.id(), "DEPOSIT_CREDITED", Map.of("amount", "100", "chain", "TRC20"));
    assertThat(records(u.id())).containsExactly("INBOX SENT -", "PUSH SKIPPED 没有订阅推送的设备", "EMAIL PENDING -");
    worker.drain();
    assertThat(records(u.id())).last().isEqualTo("EMAIL SENT -");
    MimeMessage zh = mailsTo(u.email()).getLast();
    assertThat(zh.getSubject()).isEqualTo("充值已到账");
    assertThat(body(zh)).isEqualTo("你通过 TRC20 充值的 100 USDT 已到账，已计入可用余额。");

    assertThat(call(put("/api/me/notify-prefs"), u.cookie(), "{\"push\":false,\"email\":false}").getContentAsString()).isEqualTo("{\"push\":false,\"email\":false}");
    notify.toUser(u.id(), "DEPOSIT_CREDITED", Map.of("amount", "5", "chain", "BSC"));
    assertThat(records(u.id()).subList(3, 6)).containsExactly("INBOX SENT -", "PUSH SKIPPED 客户关闭了推送", "EMAIL SKIPPED 客户关闭了邮件");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE user_id = ?", Long.class, u.id())).isEqualTo(2);

    assertThat(call(put("/api/me/notify-lang"), u.cookie(), "{\"lang\":\"en\"}").getStatus()).isEqualTo(200);
    call(put("/api/me/notify-prefs"), u.cookie(), "{\"push\":false,\"email\":true}");
    notify.toUser(u.id(), "WITHDRAW_STATUS", Map.of("orderId", "88", "amount", "50", "status", "REJECTED", "reason", "address risk"));
    worker.drain();
    MimeMessage en = mailsTo(u.email()).getLast();
    assertThat(en.getSubject()).isEqualTo("Withdrawal update");
    assertThat(body(en)).isEqualTo("Your withdrawal 88 (50 USDT) was rejected and the funds have been returned.\nReason: address risk");
    assertThat(mailsTo(u.email())).as("验证码 1 封 + 中文 1 封 + 英文 1 封").hasSize(3);
  }

  @Test
  void 偏好和语言接口校验() throws Exception {
    User u = registerClean();
    MockHttpServletResponse r = call(put("/api/me/notify-prefs"), u.cookie(), "{\"push\":true}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("\"field\":\"email\"");
    r = call(put("/api/me/notify-lang"), u.cookie(), "{\"lang\":\"fr\"}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("\"field\":\"lang\"");
  }

  @Test
  void 后台手动发送_原样发送_记录操作人_遵守客户偏好() throws Exception {
    User u = registerClean();
    admin.manual(u.id(), Set.of(NotifyChannel.INBOX, NotifyChannel.EMAIL, NotifyChannel.PUSH), " 系统维护通知 ", "今晚 22:00 维护 10 分钟。", 9);
    String list = call(get("/api/notifications?lang=en"), u.cookie(), null).getContentAsString();
    assertThat(list).contains("\"kind\":\"system\"").contains("\"title\":\"系统维护通知\"").contains("今晚 22:00 维护 10 分钟。");
    assertThat(jdbc.queryForList("SELECT CONCAT(source, ' ', operator_id) FROM notify_record WHERE user_id = ?", String.class, u.id())).containsOnly("MANUAL 9");
    worker.drain();
    MimeMessage m = mailsTo(u.email()).getLast();
    assertThat(m.getSubject()).isEqualTo("系统维护通知");
    assertThat(body(m)).isEqualTo("今晚 22:00 维护 10 分钟。");

    assertThatThrownBy(() -> admin.manual(u.id(), Set.of(), "", "x".repeat(2001), 9)).isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(((ValidationException) e).fields()).extracting(ValidationException.Field::field).containsExactly("channels", "title", "body"));
  }

  @Test
  void 邮件发送失败按任务表重试_5次用完记失败_未配置时不重试记失败() throws Exception {
    User u = registerClean();
    MailTransport broken = (to, subject, body) -> {
      throw new jakarta.mail.MessagingException("连接被拒绝");
    };
    NotifyWorker failing = new NotifyWorker(tasks, mapper, broken, accounts, json, webPush, "");
    notify.toUser(u.id(), "DEPOSIT_CREDITED", Map.of("amount", "1", "chain", "BSC"));
    long rid = jdbc.queryForObject("SELECT id FROM notify_record WHERE user_id = ? AND channel = 'EMAIL'", Long.class, u.id());
    long task = taskOf(rid);
    for (int i = 1; i <= 5; i++) {
      jdbc.update("UPDATE common_task SET status = 'RUNNING', attempts = attempts + 1 WHERE id = ?", task);
      failing.handle(tasks.find(task));
      String status = mapper.record(rid).status;
      assertThat(status).as("第 " + i + " 次").isEqualTo(i < 5 ? "PENDING" : "FAILED");
      assertThat(mapper.record(rid).error).contains("连接被拒绝");
    }
    assertThat(tasks.find(task).status()).isEqualTo(TaskStatus.FAILED);

    MailTransport none = (to, subject, body) -> {
      throw new MailTransport.NotConfigured();
    };
    notify.toUser(u.id(), "DEPOSIT_CREDITED", Map.of("amount", "2", "chain", "BSC"));
    long rid2 = jdbc.queryForObject("SELECT MAX(id) FROM notify_record WHERE user_id = ? AND channel = 'EMAIL'", Long.class, u.id());
    long task2 = taskOf(rid2);
    jdbc.update("UPDATE common_task SET status = 'RUNNING', attempts = attempts + 1 WHERE id = ?", task2);
    new NotifyWorker(tasks, mapper, none, accounts, json, webPush, "").handle(tasks.find(task2));
    assertThat(mapper.record(rid2).status).isEqualTo("FAILED");
    assertThat(mapper.record(rid2).error).isEqualTo("邮件未配置");
    assertThat(tasks.find(task2).status()).isEqualTo(TaskStatus.SUCCEEDED);
  }

  long taskOf(long recordId) {
    return jdbc.queryForObject("SELECT id FROM common_task WHERE task_type = 'MAIL_SEND' AND JSON_EXTRACT(payload, '$.recordId') = ?", Long.class, recordId);
  }

  @Test
  void UX9_注册发欢迎站内信_引导认证和充值_只发站内信_中英文() throws Exception {
    User u = register();
    assertThat(records(u.id())).as("只有站内信，不推送、不发邮件").containsExactly("INBOX SENT -");
    assertThat(mailsTo(u.email())).as("只有注册验证码一封邮件").hasSize(1);
    String zh = call(get("/api/notifications?lang=zh-CN"), u.cookie(), null).getContentAsString();
    assertThat(zh).contains("\"total\":1").contains("\"kind\":\"system\"").contains("\"title\":\"欢迎来到 Quick Come\"")
        .contains("完成认证").contains("转入 USDT").contains("新手引导").contains("平台只对赢家的盈利部分抽成");
    String en = call(get("/api/notifications?lang=en"), u.cookie(), null).getContentAsString();
    assertThat(en).contains("\"title\":\"Welcome to Quick Come\"").contains("verify your identity").contains("send USDT").contains("Getting started");
    assertThat(call(get("/api/notifications/unread-count"), u.cookie(), null).getContentAsString()).isEqualTo("{\"count\":1}");
  }

  @Test
  void UX9_UX11_V31_欢迎模板只发站内信_热钱包余额模板改成币种加余额不足() {
    for (String code : List.of("WELCOME")) {
      Map<String, Object> t = jdbc.queryForMap("SELECT audience, kind, channels, body_zh, body_en FROM notify_template WHERE code = ?", code);
      assertThat(t).as(code).containsEntry("audience", "USER").containsEntry("channels", "INBOX");
      assertThat((String) t.get("body_en")).as(code + " 英文里没有中文").doesNotContainPattern("[\\u4e00-\\u9fff]");
    }
    assertThat(jdbc.queryForObject("SELECT kind FROM notify_template WHERE code = 'WELCOME'", String.class)).isEqualTo("system");
    Rows.TemplateRow hot = mapper.template("HOT_WALLET_LOW");
    Render.Text zh = Render.render(hot, Map.of("chain", "TRC20", "address", "TXyz", "what", "TRX"), Render.ZH);
    assertThat(zh.body()).isEqualTo("TRC20 热钱包 TXyz 的 TRX 余额不足，提现暂停上链，请补充。");
    Render.Text en = Render.render(hot, Map.of("chain", "BSC", "address", "0xabc", "what", "BNB"), Render.EN);
    assertThat(en.body()).startsWith("The BNB balance of the BSC hot wallet 0xabc is too low.").doesNotContainPattern("[\\u4e00-\\u9fff]");
  }

  @Test
  void 只能用发给客户的模板通知客户() {
    assertThatThrownBy(() -> notify.toUser(1, "EMAIL_CODE", Map.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> notify.toUser(1, "NOT_EXIST", Map.of())).isInstanceOf(IllegalArgumentException.class);
  }
}
