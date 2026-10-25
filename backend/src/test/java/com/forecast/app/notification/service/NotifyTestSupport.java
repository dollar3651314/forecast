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

/** 通知测试的公共部分：测试进程里的 SMTP 服务器（GreenMail，端口 3025，不连外网）、真实注册流程、带 Cookie 的请求。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
abstract class NotifyTestSupport {

  static GreenMail smtp;

  @BeforeAll
  static void startSmtp() {
    smtp = new GreenMail(new ServerSetup(3025, "localhost", ServerSetup.PROTOCOL_SMTP));
    smtp.start();
  }

  @AfterAll
  static void stopSmtp() {
    smtp.stop();
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired NotifyApi notify;
  @Autowired NotifyAdminApi admin;
  @Autowired NotifyWorker worker;
  @Autowired NotifyMapper mapper;
  @Autowired TaskService tasks;
  @Autowired AccountApi accounts;
  @Autowired ObjectMapper json;
  @Autowired WebPush webPush;

  record User(long id, String email, Cookie cookie) {}

  static String ip() {
    return "10.9." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256);
  }

  MockHttpServletResponse call(MockHttpServletRequestBuilder b, Cookie c, String body) throws Exception {
    String ip = ip();
    b = b.with(r -> {
      r.setRemoteAddr(ip);
      return r;
    });
    if (c != null) b = b.cookie(c);
    if (body != null) b = b.contentType(MediaType.APPLICATION_JSON).content(body);
    return mvc.perform(b).andReturn().getResponse();
  }

  List<MimeMessage> mailsTo(String email) {
    return Arrays.stream(smtp.getReceivedMessages()).filter(m -> {
      try {
        return Arrays.stream(m.getAllRecipients()).anyMatch(a -> a.toString().equals(email));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }).toList();
  }

  static String body(MimeMessage m) throws Exception {
    return ((String) m.getContent()).replace("\r\n", "\n").strip();
  }

  /** 真实注册流程：验证码邮件经过任务表和 SMTP 发出，从收到的邮件里取验证码 */
  User register() throws Exception {
    String email = "n" + System.nanoTime() + "@example.com";
    assertThat(call(post("/api/auth/email-code"), null, "{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}").getStatus()).isEqualTo(200);
    worker.drain();
    List<MimeMessage> mails = mailsTo(email);
    assertThat(mails).hasSize(1);
    Matcher m = Pattern.compile("验证码是 (\\d{6})").matcher(body(mails.getFirst()));
    assertThat(m.find()).isTrue();
    MockHttpServletResponse r = call(post("/api/auth/register"), null, "{\"email\":\"" + email + "\",\"code\":\"" + m.group(1)
        + "\",\"password\":\"password123\",\"firstName\":\"A\",\"lastName\":\"B\",\"birthDate\":\"1990-05-01\",\"nationality\":\"CN\",\"adultConfirmed\":true}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    return new User(id, email, r.getCookie("fc_session"));
  }

  /**
   * 真实注册，并去掉注册时自动发的欢迎站内信（体验优化 1021 UX-9，模板 WELCOME）：
   * 先确认它只有一条站内信、没有推送和邮件，再删掉，后面的断言只看这个测试自己发的通知。
   */
  User registerClean() throws Exception {
    User u = register();
    assertThat(records(u.id())).containsExactly("INBOX SENT -");
    assertThat(jdbc.queryForList("SELECT template_code FROM notify_message WHERE user_id = ?", String.class, u.id())).containsExactly("WELCOME");
    jdbc.update("DELETE FROM notify_message WHERE user_id = ?", u.id());
    jdbc.update("DELETE FROM notify_record WHERE user_id = ?", u.id());
    return u;
  }

  /** 这个客户的通知记录："渠道 状态 原因"，按时间先后 */
  List<String> records(long uid) {
    return jdbc.queryForList("SELECT CONCAT(channel, ' ', status, ' ', COALESCE(error, '-')) FROM notify_record WHERE user_id = ? ORDER BY id", String.class, uid);
  }
}
