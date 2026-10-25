package com.forecast.app.account.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.notification.api.MailApi;
import com.forecast.app.ledger.api.LedgerApi;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 注册、登录、锁定、改密、冻结的完整流程，用真实的 MySQL 和 Redis（C37）。邮件用替身截取验证码。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowTest {

  private static final AtomicLong N = new AtomicLong(System.currentTimeMillis());

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired LedgerApi ledger;
  @Autowired AccountApi accounts;
  @MockitoBean MailApi mail;

  private static String email() {
    return "u" + N.incrementAndGet() + "@example.com";
  }

  private static String ip() {
    long n = N.incrementAndGet();
    return "10." + (n >> 16 & 255) + "." + (n >> 8 & 255) + "." + (n & 255);
  }

  private MockHttpServletResponse call(MockHttpServletRequestBuilder b, String ip, Cookie session, String json) throws Exception {
    b = b.with(r -> {
      r.setRemoteAddr(ip);
      return r;
    });
    if (session != null) b = b.cookie(session);
    if (json != null) b = b.contentType(MediaType.APPLICATION_JSON).content(json);
    return mvc.perform(b).andReturn().getResponse();
  }

  /** 发验证码并从邮件替身里取出验证码 */
  @SuppressWarnings("unchecked")
  private String sendCode(String email, String purpose) throws Exception {
    MockHttpServletResponse sent = call(post("/api/auth/email-code"), ip(), null, "{\"email\":\"" + email + "\",\"purpose\":\"" + purpose + "\"}");
    assertThat(sent.getStatus()).isEqualTo(200);
    assertThat(sent.getContentAsString()).isEqualTo("{\"cooldownSeconds\":60}");
    ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
    verify(mail, atLeastOnce()).send(eq(email), eq("EMAIL_CODE"), params.capture());
    return params.getValue().get("code");
  }

  private String registerJson(String email, String code, String password) {
    return "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"password\":\"" + password
        + "\",\"firstName\":\"Siyuan\",\"middleName\":\"\",\"lastName\":\"Ouyang\",\"birthDate\":\"1990-05-01\",\"nationality\":\"CN\",\"adultConfirmed\":true}";
  }

  /** 注册一个用户，返回会话 Cookie */
  private Cookie register(String email, String password) throws Exception {
    String code = sendCode(email, "REGISTER");
    MockHttpServletResponse r = call(post("/api/auth/register"), ip(), null, registerJson(email, code, password));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return r.getCookie("fc_session");
  }

  private Cookie login(String email, String password, String ip) throws Exception {
    MockHttpServletResponse r = call(post("/api/auth/login"), ip, null, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return r.getCookie("fc_session");
  }

  private int me(Cookie c) throws Exception {
    return call(get("/api/me/session"), ip(), c, null).getStatus();
  }

  /** 这个客户的账号安全事件类型（v2 提现自动审批 R6），按时间先后 */
  private java.util.List<String> securityEvents(String email) {
    return jdbc.queryForList("SELECT type FROM account_security_event WHERE user_id = ? ORDER BY id", String.class, userId(email));
  }

  private long userId(String email) {
    return jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
  }

  @Test
  void 注册后已登录_资料和交易账户都建好() throws Exception {
    String email = email();
    Cookie c = register(email, "password123");
    assertThat(c.isHttpOnly()).isTrue();
    assertThat(me(c)).isEqualTo(200);
    long id = userId(email);
    assertThat(jdbc.queryForObject("SELECT nationality FROM account_profile WHERE user_id = ?", String.class, id)).isEqualTo("CN");
    assertThat(ledger.snapshot(id).balance()).isEqualByComparingTo("0");
    assertThat(jdbc.queryForObject("SELECT password_hash FROM account_user WHERE id = ?", String.class, id)).startsWith("$2").doesNotContain("password123");
  }

  @Test
  void 验证码错误被拒_试5次后作废_用过不能再用() throws Exception {
    String email = email();
    String code = sendCode(email, "REGISTER");
    String wrong = code.equals("000000") ? "111111" : "000000";
    for (int i = 0; i < 5; i++) {
      MockHttpServletResponse r = call(post("/api/auth/register"), ip(), null, registerJson(email, wrong, "password123"));
      assertThat(r.getContentAsString()).contains("CODE_INVALID");
    }
    assertThat(call(post("/api/auth/register"), ip(), null, registerJson(email, code, "password123")).getContentAsString()).as("试错 5 次后正确的也不行").contains("CODE_INVALID");
  }

  @Test
  void 所有字段错误一次返回() throws Exception {
    MockHttpServletResponse r = call(post("/api/auth/register"), ip(), null,
        "{\"email\":\"bad\",\"code\":\"12\",\"password\":\"short\",\"firstName\":\"\",\"lastName\":\"\",\"birthDate\":\"2999-01-01\",\"nationality\":\"china\"}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("VALIDATION_FAILED").contains("EMAIL_INVALID").contains("CODE_FORMAT").contains("PASSWORD_LENGTH")
        .contains("PASSWORD_MIX").contains("firstName").contains("lastName").contains("BIRTH_DATE_INVALID").contains("NATIONALITY_INVALID")
        .as("S11：没勾选已满 18 岁并同意条款").contains("{\"field\":\"adultConfirmed\",\"code\":\"ADULT_REQUIRED\"}");
  }

  @Test
  void 注册_必须勾选已满18岁并同意条款_未满18岁拒绝_通过后记下勾选时间() throws Exception {
    String email = email();
    String code = sendCode(email, "REGISTER");
    String base = "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"password\":\"password123\",\"firstName\":\"A\",\"lastName\":\"B\",\"nationality\":\"CN\"";
    // 没勾、勾了 false：拒绝（验证码没被消耗）
    for (String adult : new String[] {"", ",\"adultConfirmed\":false"}) {
      MockHttpServletResponse r = call(post("/api/auth/register"), ip(), null, base + ",\"birthDate\":\"1990-05-01\"" + adult + "}");
      assertThat(r.getStatus()).isEqualTo(400);
      assertThat(r.getContentAsString()).contains("ADULT_REQUIRED");
    }
    // 未满 18 周岁（按 UTC 日期，差一天也不行）：字段 birthDate、代码 AGE_UNDER_18
    String under = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusYears(18).plusDays(1).toString();
    MockHttpServletResponse young = call(post("/api/auth/register"), ip(), null, base + ",\"birthDate\":\"" + under + "\",\"adultConfirmed\":true}");
    assertThat(young.getStatus()).isEqualTo(400);
    assertThat(young.getContentAsString()).contains("{\"field\":\"birthDate\",\"code\":\"AGE_UNDER_18\"}");
    // 正好 18 周岁：通过，记下勾选时间
    String exactly = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusYears(18).toString();
    MockHttpServletResponse ok = call(post("/api/auth/register"), ip(), null, base + ",\"birthDate\":\"" + exactly + "\",\"adultConfirmed\":true}");
    assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT terms_accepted_at IS NOT NULL FROM account_user WHERE email = ?", Boolean.class, email)).isTrue();
    assertThat(jdbc.queryForObject("SELECT last_login_at IS NULL FROM account_user WHERE email = ?", Boolean.class, email)).as("注册时的自动登录不记登录时间（SQ-20 的判断）").isTrue();
  }

  @Test
  void 密码必须同时包含字母和数字() throws Exception {
    for (String pw : new String[] {"onlyletters", "1234567890"}) {
      MockHttpServletResponse r = call(post("/api/auth/register"), ip(), null, "{\"email\":\"" + email() + "\",\"code\":\"123456\",\"password\":\"" + pw
          + "\",\"firstName\":\"A\",\"lastName\":\"B\",\"birthDate\":\"1990-01-01\",\"nationality\":\"CN\",\"adultConfirmed\":true}");
      assertThat(r.getStatus()).isEqualTo(400);
      assertThat(r.getContentAsString()).contains("PASSWORD_MIX").doesNotContain("PASSWORD_LENGTH");
    }
  }

  @Test
  void 邮箱已注册时不能再注册_验证码60秒内不能重发() throws Exception {
    String email = email();
    register(email, "password123");
    assertThat(call(post("/api/auth/email-code"), ip(), null, "{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}").getContentAsString()).contains("EMAIL_TAKEN");
    String other = email();
    sendCode(other, "REGISTER");
    MockHttpServletResponse again = call(post("/api/auth/email-code"), ip(), null, "{\"email\":\"" + other + "\",\"purpose\":\"REGISTER\"}");
    assertThat(again.getStatus()).isEqualTo(429);
    // 429 也返回还要等多少秒（1–60），客户端按它倒计时，不写死 60 秒
    assertThat(again.getContentAsString()).contains("\"code\":\"CODE_TOO_FREQUENT\"").containsPattern("\"cooldownSeconds\":([1-9]|[1-5][0-9]|60)[,}]");
    long left = Long.parseLong(again.getContentAsString().replaceAll(".*\"cooldownSeconds\":(\\d+).*", "$1"));
    assertThat(again.getHeader("Retry-After")).isEqualTo(String.valueOf(left));
    // 已经过了 45 秒：只剩 15 秒左右
    jdbc.update("UPDATE account_email_code SET created_at = DATE_SUB(created_at, INTERVAL 45 SECOND) WHERE email = ?", other);
    String body = call(post("/api/auth/email-code"), ip(), null, "{\"email\":\"" + other + "\",\"purpose\":\"REGISTER\"}").getContentAsString();
    long later = Long.parseLong(body.replaceAll(".*\"cooldownSeconds\":(\\d+).*", "$1"));
    assertThat(later).isBetween(13L, 15L);
    // 其他错误不带 cooldownSeconds
    assertThat(call(post("/api/auth/email-code"), ip(), null, "{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}").getContentAsString()).doesNotContain("cooldownSeconds");
  }

  @Test
  void 同一账号连续失败5次锁定_换IP仍锁定_锁定期满自动解锁() throws Exception {
    String email = email();
    register(email, "password123");
    for (int i = 0; i < 5; i++) {
      assertThat(call(post("/api/auth/login"), ip(), null, "{\"email\":\"" + email + "\",\"password\":\"wrong-pass\"}").getContentAsString()).contains("INVALID_CREDENTIALS");
    }
    MockHttpServletResponse locked = call(post("/api/auth/login"), ip(), null, "{\"email\":\"" + email + "\",\"password\":\"password123\"}");
    assertThat(locked.getStatus()).isEqualTo(429);
    assertThat(locked.getContentAsString()).contains("LOGIN_LOCKED");
    // 锁定期满：把计数的过期时间改成 1 秒，等它到期
    redis.expire(LoginLimiter.accountKey(email), Duration.ofSeconds(1));
    Thread.sleep(1500);
    login(email, "password123", ip());
  }

  @Test
  void 同一IP换不同账号失败5次也锁定该IP() throws Exception {
    String ip = ip();
    for (int i = 0; i < 5; i++) call(post("/api/auth/login"), ip, null, "{\"email\":\"" + email() + "\",\"password\":\"wrong-pass\"}");
    String email = email();
    register(email, "password123");
    assertThat(call(post("/api/auth/login"), ip, null, "{\"email\":\"" + email + "\",\"password\":\"password123\"}").getContentAsString()).contains("LOGIN_LOCKED");
    login(email, "password123", ip());
  }

  @Test
  void 不存在的邮箱和错误的密码返回同一个错误码() throws Exception {
    String a = call(post("/api/auth/login"), ip(), null, "{\"email\":\"" + email() + "\",\"password\":\"whatever1\"}").getContentAsString();
    assertThat(a).contains("INVALID_CREDENTIALS");
  }

  @Test
  void 重置密码后所有设备的登录失效() throws Exception {
    String email = email();
    Cookie phone = register(email, "password123");
    Cookie pc = login(email, "password123", ip());
    String code = sendCode(email, "RESET_PASSWORD");
    assertThat(call(post("/api/auth/password/reset"), ip(), null, "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"password\":\"newpass456\"}").getStatus()).isEqualTo(204);
    assertThat(me(phone)).isEqualTo(401);
    assertThat(me(pc)).isEqualTo(401);
    login(email, "newpass456", ip());
    assertThat(securityEvents(email)).as("记安全事件（提现自动审批 R6）").containsExactly("PASSWORD_RESET");
  }

  @Test
  void 修改密码后其他设备失效_当前设备保留_原密码错误被拒() throws Exception {
    String email = email();
    Cookie phone = register(email, "password123");
    Cookie pc = login(email, "password123", ip());
    assertThat(call(post("/api/auth/password/change"), ip(), phone, "{\"currentPassword\":\"wrong-old\",\"newPassword\":\"newpass456\"}").getContentAsString()).contains("CURRENT_PASSWORD_WRONG");
    assertThat(call(post("/api/auth/password/change"), ip(), phone, "{\"currentPassword\":\"password123\",\"newPassword\":\"newpass456\"}").getStatus()).isEqualTo(204);
    assertThat(me(phone)).isEqualTo(200);
    assertThat(me(pc)).isEqualTo(401);
    assertThat(securityEvents(email)).as("原密码错误的那次不记").containsExactly("PASSWORD_CHANGE");
  }

  @Test
  void 冻结后登录立即失效且不能再登录() throws Exception {
    String email = email();
    Cookie c = register(email, "password123");
    accounts.freeze(userId(email));
    assertThat(me(c)).isEqualTo(401);
    assertThat(call(post("/api/auth/login"), ip(), null, "{\"email\":\"" + email + "\",\"password\":\"password123\"}").getContentAsString()).contains("USER_FROZEN");
    accounts.unfreeze(userId(email));
    login(email, "password123", ip());
    assertThat(securityEvents(email)).containsExactly("FREEZE", "UNFREEZE");
    assertThat(accounts.lastSecurityEventSince(userId(email), java.time.LocalDateTime.now().minusYears(1))).map(e -> e.type()).contains("UNFREEZE");
  }

  @Test
  void 登出后会话失效_未登录访问返回401_公开接口不需要登录() throws Exception {
    String email = email();
    Cookie c = register(email, "password123");
    assertThat(call(post("/api/auth/logout"), ip(), c, null).getStatus()).isEqualTo(204);
    assertThat(me(c)).isEqualTo(401);
    assertThat(me(null)).isEqualTo(401);
    assertThat(call(get("/api/health"), ip(), null, null).getStatus()).isEqualTo(200);
  }

  @Test
  void 重置密码时邮箱不存在也返回成功_不暴露哪些邮箱注册过() throws Exception {
    // 没注册过的邮箱：不发，但返回和发了一样的结果（不暴露邮箱是否注册过）
    MockHttpServletResponse unknown = call(post("/api/auth/email-code"), ip(), null, "{\"email\":\"" + email() + "\",\"purpose\":\"RESET_PASSWORD\"}");
    assertThat(unknown.getStatus()).isEqualTo(200);
    assertThat(unknown.getContentAsString()).isEqualTo("{\"cooldownSeconds\":60}");
  }
}
