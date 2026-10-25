package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台（从复制源复制）：E1 编辑客户资料、E6 最近登录 IP。forecast S1 删掉了新增品种、最后报价时间、模拟起始价的测试（差价合约）。
 */
class AdminV2Test extends AdminTestSupport {

  @Autowired LedgerApi ledger;
  final ObjectMapper json = new ObjectMapper();

  record Customer(long id, String email, Cookie cookie) {}

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  /** 建一个有余额、能登录的客户；会话带 IP（直接写库，令牌只在测试里） */
  Customer customer(String balance, String ip) throws Exception {
    String email = unique("v2") + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    jdbc.update("INSERT INTO account_profile (user_id, first_name, middle_name, last_name, birth_date, nationality) VALUES (?, 'Lin', '', 'Wang', '1990-01-02', 'SG')",
        id);
    ledger.openAccount(id);
    ledger.apply(new LedgerCommand(id, Bucket.AVAILABLE, new BigDecimal(balance), LedgerEntryType.DEPOSIT, "TEST", "dep-" + id, "测试入金"));
    String token = UUID.randomUUID().toString();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    jdbc.update("INSERT INTO account_session (user_id, token_hash, ip, created_at, last_seen_at, expires_at) VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), "
        + "UTC_TIMESTAMP(3) + INTERVAL 1 DAY)", id, hash, ip);
    return new Customer(id, email, new Cookie("fc_session", token));
  }

  String audit(String action, String target) {
    return jdbc.queryForObject("SELECT CONCAT(result, '|', high_risk, '|', COALESCE(reason, ''), '|', COALESCE(before_json, ''), '|', COALESCE(after_json, '')) "
        + "FROM admin_audit_log WHERE action = ? AND target = ? ORDER BY id DESC LIMIT 1", String.class, action, target);
  }

  // ---------------- E1 编辑客户资料（AC2-14） ----------------

  @Test
  void E1_编辑客户资料_权限点高危_原因必填_字段错误一次返回_不能改邮箱和状态_审计记前后() throws Exception {
    assertThat(Permission.CUSTOMER_EDIT.highRisk).isTrue();
    Customer c = customer("10", "198.51.100.9");
    String path = "/api/admin/customers/" + c.id() + "/profile";
    String ok = "{\"firstName\":\"Lina\",\"middleName\":\"\",\"lastName\":\"Wang\",\"birthDate\":\"1991-03-04\",\"nationality\":\"HK\"";

    Admin viewer = withRoles(role(Permission.CUSTOMER_VIEW));
    assertThat(call(put(path), viewer.cookie(), ok + ",\"reason\":\"客户来电\"}").getStatus()).as("没有 customer:edit").isEqualTo(403);

    Admin editor = withRoles(role(Permission.CUSTOMER_VIEW, Permission.CUSTOMER_EDIT));
    MockHttpServletResponse noReason = call(put(path), editor.cookie(), ok + "}");
    assertThat(noReason.getStatus()).isEqualTo(400);
    assertThat(noReason.getContentAsString()).contains("\"field\":\"reason\"");

    // 邮箱、状态不能在编辑里改
    MockHttpServletResponse email = call(put(path), editor.cookie(), ok + ",\"email\":\"x@example.com\",\"status\":\"frozen\",\"reason\":\"改邮箱\"}");
    assertThat(email.getStatus()).isEqualTo(400);
    assertThat(email.getContentAsString()).contains("{\"field\":\"email\",\"code\":\"notEditable\"}").contains("{\"field\":\"status\",\"code\":\"notEditable\"}");

    // 和客户端注册同样的规则，字段错误逐条一次返回
    MockHttpServletResponse bad = call(put(path), editor.cookie(),
        "{\"firstName\":\" \",\"middleName\":\"" + "a".repeat(51) + "\",\"lastName\":\"W4ng\",\"birthDate\":\"2010-01-01\",\"nationality\":\"sg\",\"reason\":\"测试\"}");
    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat(bad.getContentAsString()).contains("{\"field\":\"firstName\",\"code\":\"required\"}").contains("{\"field\":\"middleName\",\"code\":\"nameTooLong\"}")
        .contains("{\"field\":\"lastName\",\"code\":\"nameChars\"}").contains("{\"field\":\"birthDate\",\"code\":\"ageUnder18\"}")
        .contains("{\"field\":\"nationality\",\"code\":\"nationalityInvalid\"}");
    assertThat(body(call(put(path), editor.cookie(), "{\"firstName\":\"A\",\"lastName\":\"B\",\"birthDate\":\"1990-02-30\",\"nationality\":\"SG\",\"reason\":\"x\"}"))
        .toString()).contains("\"code\":\"dateInvalid\"");
    assertThat(audit("customer.edit", String.valueOf(c.id()))).startsWith("FAILURE|1|x（失败：VALIDATION_FAILED）");

    // 成功：只记改了的字段；邮箱和状态不变
    JsonNode d = body(call(put(path), editor.cookie(), ok + ",\"reason\":\"客户提供了新证件\"}"));
    assertThat(d.get("firstName").asString()).isEqualTo("Lina");
    assertThat(d.get("birthDate").asString()).isEqualTo("1991-03-04");
    assertThat(d.get("nationality").asString()).isEqualTo("HK");
    assertThat(d.get("name").asString()).isEqualTo("Lina Wang");
    assertThat(d.get("email").asString()).isEqualTo(c.email());
    assertThat(d.get("status").asString()).isEqualTo("active");
    String a = audit("customer.edit", String.valueOf(c.id()));
    assertThat(a).startsWith("SUCCESS|1|客户提供了新证件|");
    assertThat(a.split("\\|")[3]).contains("\"firstName\":\"Lin\"").contains("\"birthDate\":\"1990-01-02\"").contains("\"nationality\":\"SG\"").doesNotContain("lastName");
    assertThat(a.split("\\|")[4]).contains("\"firstName\":\"Lina\"").contains("\"nationality\":\"HK\"").doesNotContain("kycApproved");

    // KYC 已通过：允许改，但审计里标出（和证件不一致）
    jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status, reviewed_at) "
        + "VALUES (?, 'PASSPORT', ?, 'f', 'b', 's', 'APPROVED', UTC_TIMESTAMP(3))", c.id(), unique("P"));
    assertThat(body(call(get("/api/admin/customers/" + c.id()), editor.cookie(), null)).get("kycStatus").asString()).isEqualTo("approved");
    JsonNode d2 = body(call(put(path), editor.cookie(), ok.replace("Lina", "Linda") + ",\"reason\":\"证件姓名更正\"}"));
    assertThat(d2.get("firstName").asString()).isEqualTo("Linda");
    assertThat(audit("customer.edit", String.valueOf(c.id())).split("\\|")[4]).contains("\"kycApproved\":true").contains("kycMismatch");
  }

  @Test
  void E6_客户详情有最近登录IP() throws Exception {
    Customer c = customer("10", "203.0.113.77");
    Admin viewer = withRoles(role(Permission.CUSTOMER_VIEW));
    JsonNode d = body(call(get("/api/admin/customers/" + c.id()), viewer.cookie(), null));
    assertThat(d.get("lastLoginIp").asString()).isEqualTo("203.0.113.77");
    assertThat(d.get("lastLoginIpAt").asString()).endsWith("Z");
    String email = unique("nolog") + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long never = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    ledger.openAccount(never);
    assertThat(body(call(get("/api/admin/customers/" + never), viewer.cookie(), null)).get("lastLoginIp").isNull()).isTrue();
  }

  // ---------------- E3 新增品种（AC2-16）、E6 最后报价时间 ----------------

  String newCode() {
    String s = ("ZT" + Long.toString(System.nanoTime(), 36)).toUpperCase(Locale.ROOT);
    return s.length() > 12 ? s.substring(0, 12) : s;
  }

  String createBody(String code, String extra) {
    return "{\"code\":\"" + code + "\",\"nameZh\":\"测试币\",\"nameEn\":\"Test Coin\",\"category\":\"crypto\",\"digits\":2,\"contractSize\":\"1\","
        + "\"quoteCurrency\":\"USD\",\"maxLeverage\":50,\"maintenanceMarginRate\":\"0.005\",\"openFeeRate\":\"0.0005\",\"minLots\":\"0.01\",\"maxLots\":\"10\","
        + "\"lotStep\":\"0.01\",\"markup\":\"0.05\",\"hot\":false" + extra + "}";
  }
}
