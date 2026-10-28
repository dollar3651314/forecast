package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.CustomerFlowApi;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.ledger.api.LedgerQueryApi;
import com.forecast.app.riskalert.scan.RiskScanner;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * forecast S17B 人工调账（需求说明书 v1.5 第 21 节，AC-119～AC-128）：生产配置下（默认测试配置，不设 FC_ENV_LABEL）可用。
 * 账本平衡、扣款不超过可用余额（不动下注锁定）、幂等、上限要再确认、权限、审计、通知 #24、客户端流水的类别、风控口径和新告警、
 * 记录列表和导出（审计）、报表和对账页单列。真实 MySQL、Redis。
 */
class AdminManualAdjustTest extends AdminTestSupport {

  @Autowired LedgerApi ledger;
  @Autowired LedgerQueryApi query;
  @Autowired CustomerFlowApi flows;
  @Autowired RiskScanner scanner;
  final ObjectMapper json = new ObjectMapper();

  @AfterEach
  void restore() {
    jdbc.update("UPDATE common_config SET value = '10000', updated_by = NULL WHERE config_key IN (?, ?)", ConfigKey.MANUAL_ADJUST_MAX.key,
        ConfigKey.RISK_MANUAL_CREDIT_DAY.key);
    config.refresh();
  }

  long customer() {
    String email = unique("ma") + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    ledger.openAccount(id);
    return id;
  }

  Cookie session(long userId) throws Exception {
    String token = UUID.randomUUID().toString();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    jdbc.update("INSERT INTO account_session (user_id, token_hash, created_at, last_seen_at, expires_at) VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), "
        + "UTC_TIMESTAMP(3) + INTERVAL 1 DAY)", userId, hash);
    return new Cookie("fc_session", token);
  }

  static String body(String direction, String category, String amount, String reason, String requestId, Boolean checked, Boolean over, String refNo) {
    StringBuilder b = new StringBuilder("{");
    if (direction != null) b.append("\"direction\":\"").append(direction).append("\",");
    if (category != null) b.append("\"category\":\"").append(category).append("\",");
    if (amount != null) b.append("\"amount\":\"").append(amount).append("\",");
    if (reason != null) b.append("\"reason\":\"").append(reason).append("\",");
    if (requestId != null) b.append("\"requestId\":\"").append(requestId).append("\",");
    if (checked != null) b.append("\"checked\":").append(checked).append(",");
    if (over != null) b.append("\"overLimitConfirmed\":").append(over).append(",");
    if (refNo != null) b.append("\"refNo\":\"").append(refNo).append("\",");
    if (b.length() > 1) b.setLength(b.length() - 1);
    return b.append("}").toString();
  }

  static String req() {
    return "req-" + UUID.randomUUID();
  }

  MockHttpServletResponse adjust(Admin a, long uid, String body) throws Exception {
    return call(post("/api/admin/customers/" + uid + "/adjust"), a.cookie(), body);
  }

  MockHttpServletResponse credit(Admin a, long uid, String amount, String category) throws Exception {
    return adjust(a, uid, body("credit", category, amount, "测试加款", req(), true, null, null));
  }

  JsonNode read(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  BigDecimal available(long uid) {
    return ledger.snapshot(uid).available();
  }

  BigDecimal platform() {
    return jdbc.queryForObject("SELECT available FROM ledger_account WHERE kind = 'PLATFORM_MANUAL_ADJUST'", BigDecimal.class);
  }

  @Test
  void 加款扣款_账本平衡_平台人工调账账户对手方_审计记前后余额_通知24_客户端流水带类别() throws Exception {
    Admin s = superAdmin();
    long uid = customer();
    Cookie c = session(uid);
    MockHttpServletResponse r = call(get("/api/admin/customers/" + uid), s.cookie(), null);
    assertThat(read(r).get("adjustMax").asString()).as("生产也有：单笔上限默认 10,000").isEqualTo("10000");
    assertThat(read(r).get("testCredit").isNull()).as("生产配置没有测试入金").isTrue();

    BigDecimal p0 = platform();
    r = adjust(s, uid, body("credit", "deposit_missing", "800", "TRC20 充值没识别，链上已核对", req(), true, null, "0xabc123"));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode out = read(r);
    assertThat(out.get("adjustment").get("direction").asString()).isEqualTo("credit");
    assertThat(out.get("adjustment").get("category").asString()).isEqualTo("deposit_missing");
    assertThat(out.get("adjustment").get("availableBefore").asString()).isEqualTo("0");
    assertThat(out.get("adjustment").get("availableAfter").asString()).isEqualTo("800");
    assertThat(out.get("adjustment").get("refNo").asString()).isEqualTo("0xabc123");
    assertThat(out.get("duplicate").asBoolean()).isFalse();
    assertThat(out.get("customer").get("ledger").get(0).get("type").asString()).isEqualTo("adjustCredit");
    assertThat(available(uid)).isEqualByComparingTo("800");
    assertThat(platform()).isEqualByComparingTo(p0.subtract(new BigDecimal("800")));

    r = adjust(s, uid, body("debit", "wrong_credit", "300", "重复入账扣回", req(), true, null, null));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(available(uid)).isEqualByComparingTo("500");
    assertThat(platform()).as("扣款时平台人工调账账户 +x").isEqualByComparingTo(p0.subtract(new BigDecimal("500")));
    assertThat(query.mismatches()).isEmpty();
    assertThat(jdbc.queryForObject("SELECT SUM(l.amount) FROM ledger_entry l JOIN ledger_adjust a ON l.biz_id = CAST(a.id AS CHAR) "
        + "WHERE l.biz_type IN ('ADJUST', 'ADJUST_DEPOSIT') AND a.user_id = ?", BigDecimal.class, uid)).as("每笔客户和平台合计为 0").isEqualByComparingTo("0");

    // 审计：动作分开、高危、前后余额
    Map<String, Object> a = jdbc.queryForMap("SELECT high_risk, reason, before_json, after_json FROM admin_audit_log WHERE action = 'customer.adjust_credit' "
        + "AND target = ? ORDER BY id DESC LIMIT 1", String.valueOf(uid));
    assertThat(a.get("high_risk")).isIn(true, 1, (byte) 1);
    assertThat(a.get("reason")).isEqualTo("TRC20 充值没识别，链上已核对");
    assertThat(String.valueOf(a.get("before_json"))).contains("\"available\":\"0\"");
    assertThat(String.valueOf(a.get("after_json"))).contains("\"available\":\"800\"").contains("\"category\":\"deposit_missing\"").contains("0xabc123")
        .contains("\"overLimit\":false");
    assertThat(audits("customer.adjust_debit")).contains("SUCCESS " + s.username() + " 重复入账扣回");

    // 通知 #24：站内信 + 邮件，两笔各一条
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE user_id = ? AND template_code = 'BALANCE_ADJUSTED'", Integer.class, uid))
        .isEqualTo(2);
    assertThat(jdbc.queryForList("SELECT channel FROM notify_record WHERE user_id = ? AND template_code = 'BALANCE_ADJUSTED' ORDER BY id", String.class, uid))
        .contains("EMAIL");
    String params = jdbc.queryForObject("SELECT params FROM notify_message WHERE user_id = ? AND template_code = 'BALANCE_ADJUSTED' ORDER BY id LIMIT 1",
        String.class, uid);
    assertThat(params).contains("\"dir\":\"加款\"").contains("\"category\":\"充值未到账补入\"").contains("\"amount\":\"800.00\"").contains("\"available\":\"800.00\"")
        .doesNotContain("TRC20 充值没识别");
    JsonNode inbox = json.readTree(mvc.perform(get("/api/notifications").cookie(c)).andReturn().getResponse().getContentAsString());
    assertThat(inbox.toString()).contains("你的余额已调整").contains("/assets/ledger");

    // 客户端流水
    JsonNode page = json.readTree(mvc.perform(get("/api/ledger/entries?range=all").cookie(c)).andReturn().getResponse().getContentAsString());
    assertThat(page.get("items").get(0).get("type").asString()).isEqualTo("adjustDebit");
    assertThat(page.get("items").get(0).get("category").asString()).isEqualTo("wrong_credit");
    assertThat(page.get("items").get(0).get("amount").asString()).isEqualTo("-300");
    assertThat(page.get("items").get(1).get("type").asString()).isEqualTo("adjustCredit");
    assertThat(page.get("items").get(1).get("category").asString()).isEqualTo("deposit_missing");
    JsonNode only = json.readTree(mvc.perform(get("/api/ledger/entries?range=all&type=adjustCredit").cookie(c)).andReturn().getResponse().getContentAsString());
    assertThat(only.get("total").asLong()).isEqualTo(1);
  }

  @Test
  void 扣款不能超过可用余额_不动下注锁定和提现冻结_余额不会变负() throws Exception {
    Admin s = superAdmin();
    long uid = customer();
    assertThat(credit(s, uid, "100", "other").getStatus()).isEqualTo(200);
    // 下注锁定 60（直接记账，模拟一笔下注）
    String bet = "ma-bet-" + uid;
    ledger.applyAll(List.of(new LedgerCommand(uid, Bucket.AVAILABLE, new BigDecimal("-60"), LedgerEntryType.BET_LOCK, "BET", bet, null),
        new LedgerCommand(uid, Bucket.BET_LOCKED, new BigDecimal("60"), LedgerEntryType.BET_LOCK, "BET", bet, null)));
    MockHttpServletResponse r = adjust(s, uid, body("debit", "other", "50", "超过可用", req(), true, null, null));
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(read(r).get("code").asString()).isEqualTo("ADJUST_INSUFFICIENT");
    assertThat(read(r).get("message").asString()).contains("40");
    assertThat(adjust(s, uid, body("debit", "other", "40", "正好可用", req(), true, null, null)).getStatus()).isEqualTo(200);
    assertThat(ledger.snapshot(uid).available()).isEqualByComparingTo("0");
    assertThat(ledger.snapshot(uid).betLocked()).as("下注锁定不动").isEqualByComparingTo("60");
    assertThat(adjust(s, uid, body("debit", "other", "0.01", "已经是 0", req(), true, null, null)).getStatus()).isEqualTo(409);
    assertThat(audits("customer.adjust_debit")).anyMatch(l -> l.startsWith("FAILURE " + s.username() + " 超过可用"));
    assertThat(query.mismatches()).isEmpty();
  }

  @Test
  void 幂等_同一个请求号只调一次_换内容409_并发也只调一次() throws Exception {
    Admin s = superAdmin();
    long uid = customer();
    String req = req();
    assertThat(adjust(s, uid, body("credit", "other", "70", "第一次", req, true, null, null)).getStatus()).isEqualTo(200);
    MockHttpServletResponse r = adjust(s, uid, body("credit", "other", "70", "重复点击", req, true, null, null));
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(read(r).get("duplicate").asBoolean()).isTrue();
    assertThat(adjust(s, uid, body("credit", "other", "71", "换了金额", req, true, null, null)).getStatus()).isEqualTo(409);
    assertThat(adjust(s, uid, body("debit", "other", "70", "换了方向", req, true, null, null)).getStatus()).isEqualTo(409);
    assertThat(available(uid)).isEqualByComparingTo("70");
    String req2 = req();
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Callable<Integer>> calls = new java.util.ArrayList<>();
      for (int i = 0; i < 6; i++) calls.add(() -> adjust(s, uid, body("credit", "other", "5", "并发", req2, true, null, null)).getStatus());
      for (Future<Integer> f : pool.invokeAll(calls)) assertThat(f.get()).isEqualTo(200);
    } finally {
      pool.shutdownNow();
    }
    assertThat(available(uid)).isEqualByComparingTo("75");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_adjust WHERE user_id = ?", Integer.class, uid)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE user_id = ? AND template_code = 'BALANCE_ADJUSTED'", Integer.class, uid))
        .as("通知也只发一次").isEqualTo(2);
  }

  @Test
  void 单笔上限_超过要再勾一次确认_记录和审计标出_必填和类别方向() throws Exception {
    Admin s = superAdmin();
    long uid = customer();
    MockHttpServletResponse r = adjust(s, uid, body("credit", "other", "10000.01", "超过上限", req(), true, null, null));
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(read(r).get("code").asString()).isEqualTo("ADJUST_OVER_LIMIT_UNCONFIRMED");
    r = adjust(s, uid, body("credit", "other", "10000.01", "超过上限，已确认", req(), true, true, null));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(read(r).get("adjustment").get("overLimit").asBoolean()).isTrue();
    assertThat(jdbc.queryForObject("SELECT over_limit FROM ledger_adjust WHERE user_id = ?", Boolean.class, uid)).isTrue();
    assertThat(jdbc.queryForObject("SELECT after_json FROM admin_audit_log WHERE action = 'customer.adjust_credit' AND target = ? ORDER BY id DESC LIMIT 1",
        String.class, String.valueOf(uid))).contains("\"overLimit\":true");
    assertThat(adjust(s, uid, body("credit", "other", "10000", "正好上限不用再确认", req(), true, null, null)).getStatus()).isEqualTo(200);

    assertThat(adjust(s, uid, body("credit", "wrong_credit", "1", "加款不能用误入账扣回", req(), true, null, null)).getContentAsString())
        .contains("\"field\":\"category\"");
    assertThat(adjust(s, uid, body("debit", "deposit_missing", "1", "扣款不能用充值补入", req(), true, null, null)).getContentAsString())
        .contains("\"field\":\"category\"");
    assertThat(adjust(s, uid, body("credit", null, "1", "没有类别", req(), true, null, null)).getContentAsString()).contains("\"field\":\"category\"");
    assertThat(adjust(s, uid, body("sideways", "other", "1", "方向不对", req(), true, null, null)).getContentAsString()).contains("\"field\":\"direction\"");
    assertThat(adjust(s, uid, body("credit", "other", "1", null, req(), true, null, null)).getContentAsString()).contains("\"field\":\"reason\"");
    assertThat(adjust(s, uid, body("credit", "other", "1", "没勾选", req(), null, null, null)).getContentAsString()).contains("\"field\":\"checked\"");
    assertThat(adjust(s, uid, body("credit", "other", "-1", "负数", req(), true, null, null)).getContentAsString()).contains("\"field\":\"amount\"");
    assertThat(adjust(s, uid, body("credit", "other", "1", "参考号太长", req(), true, null, "x".repeat(129))).getContentAsString())
        .contains("\"field\":\"refNo\"");
    assertThat(call(post("/api/admin/customers/987654321099/adjust"), s.cookie(), body("credit", "other", "1", "没有客户", req(), true, null, null)).getStatus())
        .isEqualTo(404);
    assertThat(available(uid)).isEqualByComparingTo("20000.01");
  }

  @Test
  void 权限点balance_adjust高危_没有时403_列表要recon_view() throws Exception {
    long uid = customer();
    Admin viewer = withRoles(role(Permission.CUSTOMER_VIEW, Permission.CUSTOMER_EDIT, Permission.BALANCE_TEST_CREDIT));
    assertThat(credit(viewer, uid, "1", "other").getStatus()).isEqualTo(403);
    assertThat(call(get("/api/admin/balance-adjustments"), viewer.cookie(), null).getStatus()).isEqualTo(403);
    Admin ops = withRoles(role(Permission.BALANCE_ADJUST));
    assertThat(credit(ops, uid, "1", "other").getStatus()).isEqualTo(200);
    Admin fin = withRoles(role(Permission.RECON_VIEW));
    assertThat(call(get("/api/admin/balance-adjustments"), fin.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(Permission.BALANCE_ADJUST.highRisk).isTrue();
    assertThat(Permission.BALANCE_ADJUST.code).isEqualTo("balance:adjust");
  }

  @Test
  void 风控_充值未到账补入当充值算_其他类别不算_24小时人工加款累计超过阈值告警一次() throws Exception {
    Admin s = superAdmin();
    long uid = customer();
    assertThat(credit(s, uid, "300", "deposit_missing").getStatus()).isEqualTo(200);
    assertThat(credit(s, uid, "200", "other").getStatus()).isEqualTo(200);
    assertThat(flows.netDeposits(uid)).as("只有充值未到账补入算充值").isEqualByComparingTo("300");
    assertThat(flows.depositsSince(uid, LocalDateTime.of(2026, 1, 1, 0, 0)).firstAt()).isNotNull();
    assertThat(flows.realized(uid, null).net()).as("人工调账不算盈亏").isEqualByComparingTo("0");

    // 阈值改成 1000：累计 500 → 再加 600（1100，越过）→ 再加 50（已经在上面，不再告警）
    jdbc.update("UPDATE common_config SET value = '1000' WHERE config_key = ?", ConfigKey.RISK_MANUAL_CREDIT_DAY.key);
    config.refresh();
    scanner.scanAll();
    assertThat(alerts(uid)).isZero();
    assertThat(credit(s, uid, "600", "other").getStatus()).isEqualTo(200);
    scanner.scanAll();
    assertThat(alerts(uid)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT detail FROM riskalert_alert WHERE user_id = ? AND kind = 'MANUAL_CREDIT_LARGE'", String.class, uid))
        .contains("\"total\": \"1100.00\"").contains("\"limit\": \"1000.00\"");
    assertThat(credit(s, uid, "50", "other").getStatus()).isEqualTo(200);
    scanner.scanAll();
    assertThat(alerts(uid)).as("同一次越过只告警一次").isEqualTo(1);
    // 扣款不算加款
    assertThat(adjust(s, uid, body("debit", "other", "10", "扣款", req(), true, null, null)).getStatus()).isEqualTo(200);
    scanner.scanAll();
    assertThat(alerts(uid)).isEqualTo(1);
  }

  int alerts(long uid) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM riskalert_alert WHERE user_id = ? AND kind = 'MANUAL_CREDIT_LARGE'", Integer.class, uid);
  }

  @Test
  void 记录列表筛选_导出CSV写审计_报表和对账页单列() throws Exception {
    Admin s = superAdmin();
    long uid = customer();
    JsonNode before = read(call(get("/api/admin/reports/settle?from=2026-10-14&to=2026-10-14"), s.cookie(), null));
    assertThat(adjust(s, uid, body("credit", "deposit_missing", "123.45", "列表用, 带逗号", req(), true, null, "=HYPERLINK(1)")).getStatus()).isEqualTo(200);
    assertThat(adjust(s, uid, body("debit", "other", "23.45", "列表用扣款", req(), true, null, null)).getStatus()).isEqualTo(200);

    JsonNode page = read(call(get("/api/admin/balance-adjustments?customerId=" + uid), s.cookie(), null));
    assertThat(page.get("total").asLong()).isEqualTo(2);
    assertThat(page.get("items").get(0).get("direction").asString()).isEqualTo("debit");
    assertThat(page.get("items").get(1).get("customerEmail").asString()).endsWith("@example.com");
    assertThat(page.get("items").get(1).get("operator").asString()).isEqualTo(s.username());
    page = read(call(get("/api/admin/balance-adjustments?customerId=" + uid + "&direction=credit&category=deposit_missing&from=2026-10-14&to=2026-10-14"),
        s.cookie(), null));
    assertThat(page.get("total").asLong()).isEqualTo(1);
    assertThat(call(get("/api/admin/balance-adjustments?direction=up"), s.cookie(), null).getContentAsString()).contains("\"field\":\"direction\"");

    MockHttpServletResponse r = call(get("/api/admin/balance-adjustments/export?customerId=" + uid), s.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentType()).startsWith("text/csv");
    String csv = r.getContentAsString(StandardCharsets.UTF_8);
    assertThat(csv).startsWith("﻿调账编号,").contains("充值未到账补入").contains("\"列表用, 带逗号\"").contains("'=HYPERLINK(1)").contains("人工扣款");
    assertThat(csv.split("\r\n")).hasSize(3);
    assertThat(call(get("/api/admin/balance-adjustments/export?lang=en&customerId=" + uid), s.cookie(), null).getContentAsString())
        .contains("Missing deposit credited");
    assertThat(jdbc.queryForObject("SELECT after_json FROM admin_audit_log WHERE action = 'adjust.export' ORDER BY id DESC LIMIT 1", String.class))
        .contains("\"rows\":2").contains(String.valueOf(uid));

    JsonNode after = read(call(get("/api/admin/reports/settle?from=2026-10-14&to=2026-10-14"), s.cookie(), null));
    assertThat(new BigDecimal(after.get("manualCredit").asString()).subtract(new BigDecimal(before.get("manualCredit").asString()))).isEqualByComparingTo("123.45");
    assertThat(new BigDecimal(after.get("manualDebit").asString()).subtract(new BigDecimal(before.get("manualDebit").asString()))).isEqualByComparingTo("23.45");
    assertThat(after.get("revenue").asString()).as("不进平台收入").isEqualTo(before.get("revenue").asString());

    JsonNode accounts = read(call(get("/api/admin/recon/platform-accounts"), s.cookie(), null));
    JsonNode m = null;
    for (JsonNode n : accounts) if ("manual_adjust".equals(n.get("kind").asString())) m = n;
    assertThat(m).isNotNull();
    assertThat(m.get("matches").asBoolean()).isTrue();
  }
}
