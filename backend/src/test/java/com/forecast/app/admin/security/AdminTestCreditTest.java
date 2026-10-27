package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.ledger.api.CustomerFlowApi;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerQueryApi;
import com.forecast.app.ledger.api.TestCreditApi;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * forecast S17A 测试入金：<b>测试环境</b>（FC_ENV_LABEL=test，和部署工具包给测试环境写的一样）下的接口、账本、幂等、上限、权限、审计、对账、报表、
 * 客户端流水和风控口径。生产配置（不设 FC_ENV_LABEL）下接口 404、按钮的数据为空见 {@link AdminTestCreditProdTest}。真实 MySQL、Redis。
 */
@TestPropertySource(properties = "FC_ENV_LABEL=test")
class AdminTestCreditTest extends AdminTestSupport {

  @Autowired LedgerApi ledger;
  @Autowired LedgerQueryApi query;
  @Autowired CustomerFlowApi flows;
  @Autowired TestCreditApi testCredit;
  final ObjectMapper json = new ObjectMapper();

  @AfterEach
  void restoreLimit() {
    jdbc.update("UPDATE common_config SET value = '10000', updated_by = NULL WHERE config_key = ?", ConfigKey.TEST_CREDIT_MAX.key);
    config.refresh();
  }

  long customer() {
    String email = unique("tc") + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    ledger.openAccount(id);
    return id;
  }

  /** 客户的登录会话（直接写库，令牌只在测试里） */
  Cookie session(long userId) throws Exception {
    String token = UUID.randomUUID().toString();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    jdbc.update("INSERT INTO account_session (user_id, token_hash, created_at, last_seen_at, expires_at) VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), "
        + "UTC_TIMESTAMP(3) + INTERVAL 1 DAY)", userId, hash);
    return new Cookie("fc_session", token);
  }

  static String body(String amount, String reason, String requestId, Boolean checked) {
    StringBuilder b = new StringBuilder("{");
    if (amount != null) b.append("\"amount\":\"").append(amount).append("\",");
    if (reason != null) b.append("\"reason\":\"").append(reason).append("\",");
    if (requestId != null) b.append("\"requestId\":\"").append(requestId).append("\",");
    if (checked != null) b.append("\"checked\":").append(checked).append(",");
    if (b.length() > 1) b.setLength(b.length() - 1);
    return b.append("}").toString();
  }

  MockHttpServletResponse credit(Admin a, long uid, String body) throws Exception {
    return call(post("/api/admin/customers/" + uid + "/test-credit"), a.cookie(), body);
  }

  JsonNode read(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  BigDecimal available(long uid) {
    return ledger.snapshot(uid).available();
  }

  int entries(long uid, String type) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry l JOIN ledger_account a ON a.id = l.account_id WHERE a.user_id = ? AND l.entry_type = ?",
        Integer.class, uid, type);
  }

  @Test
  void 测试环境_详情给出上限_入金后可用余额增加_平台测试入金账户为负_账本平衡_写审计记前后余额() throws Exception {
    Admin s = superAdmin();
    long uid = customer();
    MockHttpServletResponse r = call(get("/api/admin/customers/" + uid), s.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(read(r).get("testCredit").get("maxAmount").asString()).as("测试环境：后台据此显示按钮，单笔上限默认 10,000").isEqualTo("10000");

    BigDecimal platformBefore = jdbc.queryForObject("SELECT available FROM ledger_account WHERE kind = 'PLATFORM_TEST_CREDIT'", BigDecimal.class);
    String req = "req-" + UUID.randomUUID();
    r = credit(s, uid, body("1000.5", "测试下注流程", req, true));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode out = read(r);
    assertThat(out.get("credit").get("amount").asString()).isEqualTo("1000.5");
    assertThat(out.get("credit").get("availableBefore").asString()).isEqualTo("0");
    assertThat(out.get("credit").get("availableAfter").asString()).isEqualTo("1000.5");
    assertThat(out.get("credit").get("duplicate").asBoolean()).isFalse();
    assertThat(out.get("customer").get("account").get("available").asString()).isEqualTo("1000.5");
    assertThat(out.get("customer").get("ledger").get(0).get("type").asString()).as("后台客户流水显示测试入金").isEqualTo("testCredit");
    assertThat(available(uid)).isEqualByComparingTo("1000.5");
    BigDecimal platformAfter = jdbc.queryForObject("SELECT available FROM ledger_account WHERE kind = 'PLATFORM_TEST_CREDIT'", BigDecimal.class);
    assertThat(platformAfter).as("对手方是平台测试入金账户（−x）").isEqualByComparingTo(platformBefore.subtract(new BigDecimal("1000.5")));
    assertThat(platformAfter).isNegative();
    assertThat(entries(uid, "TEST_CREDIT")).isEqualTo(1);
    assertThat(entries(uid, "DEPOSIT")).as("不是真实充值").isZero();
    assertThat(query.mismatches()).as("每个账户每一项的账本合计 = 余额").isEmpty();
    String creditId = jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM ledger_test_credit WHERE request_id = ?", String.class, req);
    assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM ledger_entry WHERE biz_type = 'TEST_CREDIT' AND biz_id = ?", BigDecimal.class, creditId))
        .as("这一笔客户 +x、平台 −x，合计为 0").isEqualByComparingTo("0");
    assertThat(jdbc.queryForObject("SELECT reason FROM ledger_test_credit WHERE request_id = ?", String.class, req)).isEqualTo("测试下注流程");

    assertThat(audits("customer.test_credit")).contains("SUCCESS " + s.username() + " 测试下注流程");
    Map<String, Object> a = jdbc.queryForMap("SELECT target, high_risk, before_json, after_json FROM admin_audit_log WHERE action = 'customer.test_credit' AND target = ? "
        + "ORDER BY id DESC LIMIT 1", String.valueOf(uid));
    assertThat(a.get("high_risk")).isIn(true, 1, (byte) 1);
    assertThat(String.valueOf(a.get("before_json"))).contains("\"available\":\"0\"");
    assertThat(String.valueOf(a.get("after_json"))).contains("\"available\":\"1000.5\"").contains("\"amount\":\"1000.5\"").contains(req);
  }

  @Test
  void 幂等_同一个请求号只入一次_换了金额或客户409_并发重复点击也只入一次() throws Exception {
    Admin s = superAdmin();
    long uid = customer();
    long other = customer();
    String req = "req-" + UUID.randomUUID();
    assertThat(credit(s, uid, body("200", "第一次", req, true)).getStatus()).isEqualTo(200);
    MockHttpServletResponse r = credit(s, uid, body("200", "重复点击", req, true));
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(read(r).get("credit").get("duplicate").asBoolean()).as("返回第一次的结果").isTrue();
    assertThat(available(uid)).isEqualByComparingTo("200");
    assertThat(entries(uid, "TEST_CREDIT")).isEqualTo(1);
    r = credit(s, uid, body("300", "换了金额", req, true));
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(read(r).get("code").asString()).isEqualTo("ADJUST_REQUEST_REUSED");
    assertThat(credit(s, other, body("200", "换了客户", req, true)).getStatus()).isEqualTo(409);
    assertThat(available(other)).isEqualByComparingTo("0");

    // 并发：同一个请求号同时来 8 次，只入一次
    String req2 = "req-" + UUID.randomUUID();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<Integer>> calls = new java.util.ArrayList<>();
      for (int i = 0; i < 8; i++) calls.add(() -> credit(s, uid, body("50", "并发", req2, true)).getStatus());
      for (Future<Integer> f : pool.invokeAll(calls)) assertThat(f.get()).isEqualTo(200);
    } finally {
      pool.shutdownNow();
    }
    assertThat(available(uid)).isEqualByComparingTo("250");
    assertThat(entries(uid, "TEST_CREDIT")).isEqualTo(2);
    assertThat(query.mismatches()).isEmpty();
  }

  @Test
  void 单笔上限_默认10000_参数设置可改_原因勾选金额必填() throws Exception {
    Admin s = superAdmin();
    long uid = customer();
    MockHttpServletResponse r = credit(s, uid, body("10000.01", "超过上限", "req-" + UUID.randomUUID(), true));
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(read(r).get("code").asString()).isEqualTo("ADJUST_OVER_LIMIT");
    assertThat(credit(s, uid, body("10000", "正好上限", "req-" + UUID.randomUUID(), true)).getStatus()).isEqualTo(200);
    // 参数设置"余额调整"一组（betting_params:edit），改成 50 以后 60 超限
    r = call(get("/api/admin/params/betting"), s.cookie(), null);
    assertThat(r.getContentAsString()).contains("adjust.testCreditMax");
    r = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/params/betting/adjust.testCreditMax"), s.cookie(),
        "{\"value\":\"50\",\"reason\":\"测试上限\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(testCredit.maxAmount()).isEqualByComparingTo("50");
    assertThat(credit(s, uid, body("60", "超过新上限", "req-" + UUID.randomUUID(), true)).getStatus()).isEqualTo(400);
    assertThat(available(uid)).isEqualByComparingTo("10000");

    assertThat(credit(s, uid, body("10", null, "req-" + UUID.randomUUID(), true)).getContentAsString()).contains("\"field\":\"reason\"");
    assertThat(credit(s, uid, body("10", "没勾选", "req-" + UUID.randomUUID(), null)).getContentAsString()).contains("\"field\":\"checked\"");
    for (String bad : List.of("0", "-5", "abc", "0.000000001")) {
      assertThat(credit(s, uid, body(bad, "金额不对", "req-" + UUID.randomUUID(), true)).getContentAsString()).as(bad).contains("\"field\":\"amount\"");
    }
    assertThat(credit(s, uid, body("10", "请求号不对", "短", true)).getContentAsString()).contains("\"field\":\"requestId\"");
    assertThat(call(post("/api/admin/customers/987654321098/test-credit"), s.cookie(), body("10", "没有这个客户", "req-" + UUID.randomUUID(), true)).getStatus())
        .isEqualTo(404);
    assertThat(available(uid)).isEqualByComparingTo("10000");
    assertThat(audits("customer.test_credit")).anyMatch(l -> l.startsWith("FAILURE " + s.username() + " 超过新上限"));
  }

  @Test
  void 权限点balance_test_credit_高危_没有时403() throws Exception {
    long uid = customer();
    Admin viewer = withRoles(role(Permission.CUSTOMER_VIEW, Permission.CUSTOMER_EDIT));
    assertThat(credit(viewer, uid, body("10", "没有权限", "req-" + UUID.randomUUID(), true)).getStatus()).isEqualTo(403);
    Admin ops = withRoles(role(Permission.CUSTOMER_VIEW, Permission.BALANCE_TEST_CREDIT));
    assertThat(credit(ops, uid, body("10", "有权限", "req-" + UUID.randomUUID(), true)).getStatus()).isEqualTo(200);
    assertThat(Permission.BALANCE_TEST_CREDIT.highRisk).isTrue();
    assertThat(Permission.BALANCE_TEST_CREDIT.code).isEqualTo("balance:test_credit");
  }

  @Test
  void 客户端流水显示测试入金_不算真实充值的筛选_风控和提现按充值算_对账和报表单独一行() throws Exception {
    Admin s = superAdmin();
    long uid = customer();
    Cookie c = session(uid);
    JsonNode before = read(call(get("/api/admin/reports/settle?from=2026-10-14&to=2026-10-14"), s.cookie(), null));
    assertThat(credit(s, uid, body("300", "客户端看流水", "req-" + UUID.randomUUID(), true)).getStatus()).isEqualTo(200);

    JsonNode page = json.readTree(mvc.perform(get("/api/ledger/entries?range=all").cookie(c)).andReturn().getResponse().getContentAsString());
    assertThat(page.get("items").get(0).get("type").asString()).isEqualTo("testCredit");
    assertThat(page.get("items").get(0).get("amount").asString()).isEqualTo("300");
    JsonNode deposits = json.readTree(mvc.perform(get("/api/ledger/entries?range=all&type=deposit").cookie(c)).andReturn().getResponse().getContentAsString());
    assertThat(deposits.get("total").asLong()).as("不和真实充值混在一起").isZero();
    JsonNode only = json.readTree(mvc.perform(get("/api/ledger/entries?range=all&type=testCredit").cookie(c)).andReturn().getResponse().getContentAsString());
    assertThat(only.get("total").asLong()).isEqualTo(1);

    // 风控和提现（R8、"充了不玩就提"、KYC 阈值）把测试入金当充值（开发记录 S17 第 1.4 节）
    assertThat(flows.netDeposits(uid)).isEqualByComparingTo("300");
    CustomerFlowApi.Deposits d = flows.depositsSince(uid, LocalDateTime.of(2026, 1, 1, 0, 0));
    assertThat(d.net()).isEqualByComparingTo("300");
    assertThat(d.firstAt()).isNotNull();

    // 报表：单独一列"测试入金"，不进平台收入、不进补偿支出
    JsonNode after = read(call(get("/api/admin/reports/settle?from=2026-10-14&to=2026-10-14"), s.cookie(), null));
    assertThat(new BigDecimal(after.get("testCredit").asString()).subtract(new BigDecimal(before.get("testCredit").asString()))).isEqualByComparingTo("300");
    assertThat(after.get("testCredits").asInt() - before.get("testCredits").asInt()).isEqualTo(1);
    assertThat(after.get("revenue").asString()).isEqualTo(before.get("revenue").asString());
    assertThat(after.get("compensationExpense").asString()).isEqualTo(before.get("compensationExpense").asString());
    assertThat(after.get("days").get(0).get("date").asString()).isEqualTo("2026-10-14");
    assertThat(new BigDecimal(after.get("days").get(0).get("testCredit").asString())).isPositive();

    // 对账页"平台账户"：测试入金单独一行，余额 = 账本合计
    JsonNode accounts = read(call(get("/api/admin/recon/platform-accounts"), s.cookie(), null));
    JsonNode tc = null;
    for (JsonNode n : accounts) if ("test_credit".equals(n.get("kind").asString())) tc = n;
    assertThat(tc).isNotNull();
    assertThat(new BigDecimal(tc.get("balance").asString())).isNegative();
    assertThat(tc.get("matches").asBoolean()).isTrue();
    assertThat(accounts.size()).as("平台收入、补偿支出、测试入金").isGreaterThanOrEqualTo(3);
  }
}
