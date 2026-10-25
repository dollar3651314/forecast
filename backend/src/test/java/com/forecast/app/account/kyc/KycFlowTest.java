package com.forecast.app.account.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.account.api.KycStatus;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.storage.ObjectStorage;
import com.forecast.app.notification.api.MailApi;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 个人资料和 KYC，用真实的 MySQL（C37）；对象存储用本地临时目录。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KycFlowTest {

  private static final AtomicLong N = new AtomicLong(System.currentTimeMillis());
  static final byte[] JPG = withHeader(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, 2000);
  static final byte[] PNG = withHeader(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 3000);

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired AccountApi accounts;
  @Autowired ObjectStorage storage;
  @MockitoBean MailApi mail;

  static byte[] withHeader(byte[] header, int size) {
    byte[] b = Arrays.copyOf(header, size);
    for (int i = header.length; i < size; i++) b[i] = (byte) (i * 31);
    return b;
  }

  record User(long id, String email, Cookie cookie) {}

  @SuppressWarnings("unchecked")
  private User register() throws Exception {
    String email = "k" + N.incrementAndGet() + "@example.com";
    mvc.perform(post("/api/auth/email-code").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}"));
    ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(mail, atLeastOnce()).send(eq(email), eq("EMAIL_CODE"), p.capture());
    MockHttpServletResponse r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"code\":\"" + p.getValue().get("code")
        + "\",\"password\":\"password123\",\"firstName\":\"Siyuan\",\"lastName\":\"Ouyang\",\"birthDate\":\"1990-05-01\",\"nationality\":\"CN\"}")).andReturn().getResponse();
    assertThat(r.getStatus()).isEqualTo(200);
    return new User(jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email), email, r.getCookie("fc_session"));
  }

  private MockHttpServletResponse submit(User u, String docType, String docNumber, byte[] front, byte[] back, byte[] selfie) throws Exception {
    var b = multipart("/api/me/kyc").cookie(u.cookie());
    if (docType != null) b.param("docType", docType);
    if (docNumber != null) b.param("docNumber", docNumber);
    if (front != null) b.file(new MockMultipartFile("front", "f.jpg", "image/jpeg", front));
    if (back != null) b.file(new MockMultipartFile("back", "b.png", "image/png", back));
    if (selfie != null) b.file(new MockMultipartFile("selfie", "s.jpg", "image/jpeg", selfie));
    return mvc.perform(b).andReturn().getResponse();
  }

  private long latestApplication(long userId) {
    return jdbc.queryForObject("SELECT MAX(id) FROM account_kyc_application WHERE user_id = ?", Long.class, userId);
  }

  private String docNo() {
    return "D" + N.incrementAndGet();
  }

  @Test
  void 提交后审核中_证件号只显示后4位_数据库只存路径_文件在对象存储里() throws Exception {
    User u = register();
    String doc = docNo();
    MockHttpServletResponse r = submit(u, "idCard", doc, JPG, PNG, JPG);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(r.getContentAsString()).contains("\"status\":\"pending\"").contains("****" + doc.substring(doc.length() - 4)).doesNotContain(doc).doesNotContain("kyc/");
    var row = jdbc.queryForMap("SELECT front_key, back_key, selfie_key FROM account_kyc_application WHERE user_id = ?", u.id());
    assertThat((String) row.get("front_key")).endsWith("-front.jpg");
    assertThat((String) row.get("back_key")).endsWith("-back.png");
    assertThat(storage.get((String) row.get("back_key"))).isEqualTo(PNG);
    assertThat(accounts.kycStatus(u.id())).isEqualTo(KycStatus.PENDING);
  }

  @Test
  void 不是图片_超过2MB_缺文件_证件类型错误都一次返回() throws Exception {
    User u = register();
    byte[] big = withHeader(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 2 * 1024 * 1024 + 1);
    // 正式运行时超过 2MB 的文件先被上传大小限制拦下（413）；MockMvc 不执行这个限制，这里验证的是业务代码里的第二道检查
    MockHttpServletResponse tooBig = submit(u, "idCard", docNo(), big, PNG, JPG);
    assertThat(tooBig.getStatus()).isEqualTo(400);
    assertThat(tooBig.getContentAsString()).contains("\"field\":\"front\",\"code\":\"FILE_TOO_LARGE\"");
    MockHttpServletResponse r = submit(u, "driver", "", "这不是图片".getBytes(), null, PNG);
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("DOC_TYPE_INVALID").contains("\"docNumber\"").contains("FILE_TYPE_INVALID").contains("\"back\"");
    assertThat(accounts.kycStatus(u.id())).isEqualTo(KycStatus.NONE);
  }

  @Test
  void 审核中不能重复提交_驳回必须填原因_驳回后可以重新提交() throws Exception {
    User u = register();
    submit(u, "passport", docNo(), JPG, JPG, JPG);
    assertThat(submit(u, "passport", docNo(), JPG, JPG, JPG).getContentAsString()).contains("KYC_PENDING");
    long app = latestApplication(u.id());
    assertThatThrownBy(() -> accounts.rejectKyc(app, 1, " ")).isInstanceOf(BizException.class).extracting("code").isEqualTo("REJECT_REASON_REQUIRED");
    accounts.rejectKyc(app, 1, "照片模糊");
    assertThat(mvc.perform(get("/api/me/kyc").cookie(u.cookie())).andReturn().getResponse().getContentAsString()).contains("\"status\":\"rejected\"").contains("照片模糊");
    // 驳回结果通过站内信和邮件通知客户（AC-05）：站内信一条，邮件写成任务
    // V30 起驳回用 KYC_REJECTED、通过用 KYC_APPROVED（体验建议第 11 条），旧模板 KYC_RESULT 不再发送
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE user_id = ? AND template_code = 'KYC_REJECTED'", Long.class, u.id())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT status FROM notify_record WHERE user_id = ? AND template_code = 'KYC_REJECTED' AND channel = 'EMAIL'", String.class, u.id()))
        .isEqualTo("PENDING");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE user_id = ? AND template_code = 'KYC_RESULT'", Long.class, u.id())).isZero();
    assertThat(submit(u, "passport", docNo(), JPG, JPG, JPG).getContentAsString()).contains("\"status\":\"pending\"");
  }

  @Test
  void 通过后资料锁定_不能再提交_只能审核待审核的申请() throws Exception {
    User u = register();
    submit(u, "idCard", docNo(), JPG, JPG, JPG);
    long app = latestApplication(u.id());
    accounts.approveKyc(app, 1);
    assertThat(accounts.kycStatus(u.id())).isEqualTo(KycStatus.APPROVED);
    // 通过的站内信：forecast 基线 V3 的文案（复制源的"充值、交易、提现均已开通"去掉了交易，S9 由 PM 定稿）
    String inbox = mvc.perform(get("/api/notifications?lang=zh-CN").cookie(u.cookie())).andReturn().getResponse().getContentAsString();
    assertThat(inbox).contains("\"title\":\"身份认证已通过\"").contains("提现已开通").doesNotContainPattern("交易(?!所)");
    assertThatThrownBy(() -> accounts.approveKyc(app, 1)).isInstanceOf(BizException.class).extracting("code").isEqualTo("KYC_NOT_PENDING");
    assertThat(mvc.perform(get("/api/me/profile").cookie(u.cookie())).andReturn().getResponse().getContentAsString()).contains("\"locked\":true");
    MockHttpServletResponse r = mvc.perform(put("/api/me/profile").cookie(u.cookie()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"firstName\":\"A\",\"lastName\":\"B\",\"birthDate\":\"1990-01-01\",\"nationality\":\"CN\"}")).andReturn().getResponse();
    assertThat(r.getContentAsString()).contains("PROFILE_LOCKED");
    assertThat(submit(u, "idCard", docNo(), JPG, JPG, JPG).getContentAsString()).contains("KYC_APPROVED");
  }

  @Test
  void 同一证件不能被两个账户同时使用() throws Exception {
    String doc = docNo();
    User a = register();
    User b = register();
    submit(a, "idCard", doc, JPG, JPG, JPG);
    assertThat(submit(b, "idCard", doc.toLowerCase(), JPG, JPG, JPG).getContentAsString()).contains("DOC_NUMBER_USED");
  }

  @Test
  void 资料可以查看和修改_字段错误一次返回() throws Exception {
    User u = register();
    String got = mvc.perform(get("/api/me/profile").cookie(u.cookie())).andReturn().getResponse().getContentAsString();
    assertThat(got).contains("\"firstName\":\"Siyuan\"").contains("\"nationality\":\"CN\"").contains("\"locked\":false");
    MockHttpServletResponse ok = mvc.perform(put("/api/me/profile").cookie(u.cookie()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"firstName\":\"Wei\",\"middleName\":\"\",\"lastName\":\"Zhang\",\"birthDate\":\"1988-02-03\",\"nationality\":\"HK\"}")).andReturn().getResponse();
    assertThat(ok.getContentAsString()).contains("\"firstName\":\"Wei\"").contains("\"nationality\":\"HK\"");
    MockHttpServletResponse bad = mvc.perform(put("/api/me/profile").cookie(u.cookie()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"firstName\":\"\",\"lastName\":\"\",\"birthDate\":\"2999-01-01\",\"nationality\":\"hk\"}")).andReturn().getResponse();
    assertThat(bad.getContentAsString()).contains("firstName").contains("lastName").contains("BIRTH_DATE_INVALID").contains("NATIONALITY_INVALID");
  }

  @Test
  void 文件头判断图片类型() {
    assertThat(KycService.imageExt(JPG)).isEqualTo("jpg");
    assertThat(KycService.imageExt(PNG)).isEqualTo("png");
    assertThat(KycService.imageExt("GIF89a....".getBytes())).isNull();
    assertThat(KycController.mask("AB12345678")).isEqualTo("****5678");
  }
}
