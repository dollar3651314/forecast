package com.forecast.app.account.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.notification.api.MailApi;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 安全审查 SEC-03（测试报告/v1-安全审查.md）：发验证码限次。用真实的 MySQL 和 Redis（实际执行）。
 * 每个验证码只能试 5 次，但原来可以每 60 秒换一个新验证码继续猜：一天 1440 个验证码 × 5 次 = 7200 次，猜中 6 位重置密码验证码的概率每天约 0.7%，
 * 一个月约 20%，猜中就能接管账号。现在同一邮箱同一用途 24 小时最多 10 次（最多 50 次猜测）；同一 IP 每小时最多 30 次（这里设成 3 次来测）。
 */
@SpringBootTest(properties = "forecast.auth.code-ip-hourly-limit=3")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SendCodeLimitTest {

  private static final AtomicLong N = new AtomicLong(System.currentTimeMillis());

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean MailApi mail;

  private static String email() {
    return "lim" + N.incrementAndGet() + "@example.com";
  }

  /** 每个测试用没用过的 IP（Redis 计数跨测试保留一小时） */
  private static String ip() {
    long n = N.incrementAndGet();
    return "10." + (n >> 16 & 255) + "." + (n >> 8 & 255) + "." + (n & 255);
  }

  private MockHttpServletResponse sendCode(String email, String purpose, String ip) throws Exception {
    return mvc.perform(post("/api/auth/email-code").with(r -> {
      r.setRemoteAddr(ip);
      return r;
    }).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"purpose\":\"" + purpose + "\"}")).andReturn().getResponse();
  }

  private void pastCodes(String email, String purpose, int count, int hoursAgo) {
    for (int i = 0; i < count; i++) {
      jdbc.update("INSERT INTO account_email_code (email, purpose, code_hash, expires_at, created_at) "
          + "VALUES (?, ?, REPEAT('0', 64), DATE_SUB(UTC_TIMESTAMP(3), INTERVAL ? HOUR), DATE_SUB(UTC_TIMESTAMP(3), INTERVAL ? HOUR))", email, purpose, hoursAgo, hoursAgo);
    }
  }

  @Test
  void SEC03_同一邮箱同一用途24小时最多发10次_超过的不发_24小时以前的不算() throws Exception {
    String email = email();
    jdbc.update("INSERT INTO account_user (email, password_hash, status) VALUES (?, 'x', 'ACTIVE')", email);
    pastCodes(email, "RESET_PASSWORD", 9, 2);
    pastCodes(email, "RESET_PASSWORD", 20, 25);
    // 第 10 次：还可以
    assertThat(sendCode(email, "RESET_PASSWORD", ip()).getStatus()).isEqualTo(200);
    // 过了 60 秒的重发间隔，第 11 次：拒绝（换 IP 也一样）
    jdbc.update("UPDATE account_email_code SET created_at = DATE_SUB(created_at, INTERVAL 2 MINUTE) WHERE email = ?", email);
    MockHttpServletResponse r = sendCode(email, "RESET_PASSWORD", ip());
    assertThat(r.getStatus()).isEqualTo(429);
    assertThat(r.getContentAsString()).contains("CODE_TOO_FREQUENT");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_email_code WHERE email = ? AND created_at > DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 24 HOUR)",
        Integer.class, email)).isEqualTo(10);
    // 另一种用途单独计数
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_email_code WHERE email = ? AND purpose = 'REGISTER'", Integer.class, email)).isZero();
  }

  @Test
  void SEC03_同一IP每小时超过上限就不再发_不管邮箱存不存在_换IP不受影响() throws Exception {
    String ip = ip();
    // 3 次以内：注册用的新邮箱、重置密码用的不存在的邮箱都算
    assertThat(sendCode(email(), "REGISTER", ip).getStatus()).isEqualTo(200);
    assertThat(sendCode(email(), "RESET_PASSWORD", ip).getStatus()).isEqualTo(200);
    assertThat(sendCode(email(), "REGISTER", ip).getStatus()).isEqualTo(200);
    // 第 4 次：拒绝，邮件不发
    String blocked = email();
    MockHttpServletResponse r = sendCode(blocked, "REGISTER", ip);
    assertThat(r.getStatus()).isEqualTo(429);
    assertThat(r.getContentAsString()).contains("CODE_TOO_FREQUENT");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_email_code WHERE email = ?", Integer.class, blocked)).isZero();
    // 不存在的邮箱也一样拒绝（不能用来区分邮箱是否注册过）
    assertThat(sendCode(email(), "RESET_PASSWORD", ip).getStatus()).isEqualTo(429);
    // 别的 IP 不受影响
    assertThat(sendCode(email(), "REGISTER", ip()).getStatus()).isEqualTo(200);
  }
}
