package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.forecast.app.common.storage.ObjectStorage;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 后台业务接口（S16b）：客户、冻结（AC-04）、调余额（G5）、KYC 审核（AC-05）、出金审核和紧急取消（AC-21）、对账、任务、通知。 */
class AdminOpsTest extends AdminTestSupport {

  @Autowired LedgerApi ledger;
  @Autowired ObjectStorage storage;
  @Autowired Clock clock;
  final ObjectMapper json = new ObjectMapper();

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  /** 建一个客户：资料、交易账户、余额 */
  long customer(String name, String balance) {
    String email = unique("c") + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    jdbc.update("INSERT INTO account_profile (user_id, first_name, middle_name, last_name, birth_date, nationality) VALUES (?, ?, '', 'Test', '1990-01-02', 'SG')",
        id, name);
    ledger.openAccount(id);
    if (new BigDecimal(balance).signum() > 0) {
      ledger.apply(new LedgerCommand(id, Bucket.AVAILABLE, new BigDecimal(balance), LedgerEntryType.DEPOSIT, "TEST", "dep-" + id, "测试入金"));
    }
    return id;
  }

  /** 按提现服务的写法建一笔提现（冻结余额） */
  long withdrawal(long uid, String amount, String status) {
    LocalDateTime now = LocalDateTime.now(clock);
    // 按 V22 之前的旧数据写：对外编号等于内部 id（旧数据照常显示、照常能审核，AC2-22）
    jdbc.update("INSERT INTO wallet_withdrawal (public_no, user_id, network, address, amount, fee, status, cooling_ends_at, created_at) VALUES (?, ?, 'BSC', ?, ?, 0.5, ?, ?, ?)",
        -System.nanoTime(), uid, "0x" + "ab".repeat(20), new BigDecimal(amount), status, now, now);
    long id = jdbc.queryForObject("SELECT MAX(id) FROM wallet_withdrawal WHERE user_id = ?", Long.class, uid);
    jdbc.update("UPDATE wallet_withdrawal SET public_no = id WHERE id = ?", id);
    ledger.apply(new LedgerCommand(uid, Bucket.AVAILABLE, new BigDecimal(amount).negate(), LedgerEntryType.WITHDRAW_FREEZE, "WITHDRAW", String.valueOf(id), "提现冻结"));
    ledger.apply(new LedgerCommand(uid, Bucket.WITHDRAW_FROZEN, new BigDecimal(amount), LedgerEntryType.WITHDRAW_FREEZE, "WITHDRAW", String.valueOf(id), "提现冻结"));
    return id;
  }

  BigDecimal balance(long uid) {
    return jdbc.queryForObject("SELECT available FROM ledger_account WHERE user_id = ?", BigDecimal.class, uid);
  }

  @Test
  void 客户列表和详情_按名字搜索_详情有账户和流水() throws Exception {
    Admin a = withRoles(role(Permission.CUSTOMER_VIEW));
    String name = unique("Alice");
    long uid = customer(name, "1234.5");
    JsonNode list = body(call(get("/api/admin/customers?keyword=" + name), a.cookie(), null));
    assertThat(list.get("total").asLong()).isEqualTo(1);
    JsonNode row = list.get("items").get(0);
    assertThat(row.get("id").asLong()).isEqualTo(uid);
    assertThat(row.get("name").asString()).isEqualTo(name + " Test");
    assertThat(row.get("balance").asString()).isEqualTo("1234.5");
    assertThat(row.get("status").asString()).isEqualTo("active");
    assertThat(row.get("kycStatus").asString()).isEqualTo("none");
    assertThat(body(call(get("/api/admin/customers?keyword=" + uid), a.cookie(), null)).get("total").asLong()).as("按客户号").isGreaterThanOrEqualTo(1);
    JsonNode d = body(call(get("/api/admin/customers/" + uid), a.cookie(), null));
    assertThat(d.get("account").get("available").asString()).isEqualTo("1234.5");
    assertThat(d.get("birthDate").asString()).isEqualTo("1990-01-02");
    assertThat(d.get("ledger").get(0).get("type").asString()).isEqualTo("deposit");
    assertThat(d.get("ledger").get(0).get("balanceAfter").asString()).isEqualTo("1234.5");
    assertThat(call(get("/api/admin/customers/999999999"), a.cookie(), null).getStatus()).isEqualTo(404);
    assertThat(call(post("/api/admin/customers/" + uid + "/freeze"), a.cookie(), "{\"frozen\":true,\"reason\":\"x\"}").getStatus()).as("没有冻结权限")
        .isEqualTo(403);
  }

  @Test
  void AC04_冻结后客户登录立即失效_有原因和审计_解冻后清空原因() throws Exception {
    Admin a = withRoles(role(Permission.CUSTOMER_VIEW, Permission.CUSTOMER_FREEZE));
    long uid = customer("Bob", "10");
    jdbc.update("INSERT INTO account_session (user_id, token_hash, created_at, last_seen_at, expires_at) VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), "
        + "UTC_TIMESTAMP(3) + INTERVAL 1 DAY)", uid, String.format("%064x", System.nanoTime()));
    assertThat(call(post("/api/admin/customers/" + uid + "/freeze"), a.cookie(), "{\"frozen\":true}").getStatus()).isEqualTo(400);
    JsonNode d = body(call(post("/api/admin/customers/" + uid + "/freeze"), a.cookie(), "{\"frozen\":true,\"reason\":\"疑似盗号\"}"));
    assertThat(d.get("status").asString()).isEqualTo("frozen");
    assertThat(d.get("frozenReason").asString()).isEqualTo("疑似盗号");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_session WHERE user_id = ? AND revoked_at IS NULL", Long.class, uid)).isZero();
    assertThat(audits("customer.freeze")).anyMatch(l -> l.equals("SUCCESS " + a.username() + " 疑似盗号"));
    JsonNode u = body(call(post("/api/admin/customers/" + uid + "/freeze"), a.cookie(), "{\"frozen\":false,\"reason\":\"核实无误\"}"));
    assertThat(u.get("status").asString()).isEqualTo("active");
    assertThat(u.get("frozenReason").isNull()).isTrue();
  }

  @Test
  void AC05_KYC列表_详情_看照片_驳回必须填原因_通过后客户收到通知() throws Exception {
    Admin a = withRoles(role(Permission.KYC_VIEW, Permission.KYC_REVIEW));
    long uid = customer("Dave", "0");
    String prefix = "kyc/" + uid + "/test-";
    storage.put(prefix + "front.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2}, "image/jpeg");
    jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', ?, ?, ?, ?, 'PENDING')",
        uid, unique("P"), prefix + "front.jpg", prefix + "back.jpg", prefix + "selfie.png");
    long app = jdbc.queryForObject("SELECT MAX(id) FROM account_kyc_application WHERE user_id = ?", Long.class, uid);
    assertThat(body(call(get("/api/admin/menu-badges"), a.cookie(), null)).get("kyc").asLong()).isGreaterThanOrEqualTo(1);
    JsonNode list = body(call(get("/api/admin/kyc?status=pending&keyword=" + uid), a.cookie(), null));
    assertThat(list.get("items").get(0).get("docType").asString()).isEqualTo("passport");
    MockHttpServletResponse img = call(get("/api/admin/kyc/" + app + "/images/front"), a.cookie(), null);
    assertThat(img.getContentType()).isEqualTo("image/jpeg");
    assertThat(img.getHeader("Cache-Control")).contains("no-store");
    assertThat(img.getContentAsByteArray()).hasSize(4);
    assertThat(call(get("/api/admin/kyc/" + app + "/images/back"), a.cookie(), null).getStatus()).as("文件不在").isEqualTo(404);
    assertThat(call(get("/api/admin/kyc/" + app + "/images/other"), a.cookie(), null).getStatus()).isEqualTo(404);
    assertThat(call(post("/api/admin/kyc/" + app + "/review"), a.cookie(), "{\"approve\":false}").getContentAsString()).contains("\"field\":\"reason\"");
    JsonNode r = body(call(post("/api/admin/kyc/" + app + "/review"), a.cookie(), "{\"approve\":false,\"reason\":\"照片模糊\"}"));
    assertThat(r.get("status").asString()).isEqualTo("rejected");
    assertThat(r.get("reviewer").asString()).isEqualTo(a.username());
    assertThat(r.get("rejectReason").asString()).isEqualTo("照片模糊");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE user_id = ? AND template_code = 'KYC_REJECTED'", Long.class, uid)).isEqualTo(1);
    assertThat(call(post("/api/admin/kyc/" + app + "/review"), a.cookie(), "{\"approve\":true}").getContentAsString()).contains("KYC_NOT_PENDING");
  }

  @Test
  void 出金审核_列表筛选_通过_驳回退回_AC21紧急取消有审计() throws Exception {
    Admin a = withRoles(role(Permission.WITHDRAWAL_VIEW, Permission.WITHDRAWAL_REVIEW));
    Admin risk = withRoles(role(Permission.WITHDRAWAL_VIEW, Permission.WITHDRAWAL_EMERGENCY_CANCEL));
    String name = unique("Eve");
    long uid = customer(name, "10000");
    long small = withdrawal(uid, "100", "REVIEWING");
    long large = withdrawal(uid, "3500", "REVIEWING");
    long rej = withdrawal(uid, "200", "REVIEWING");
    JsonNode open = body(call(get("/api/admin/withdrawals?status=open&keyword=" + name), a.cookie(), null));
    assertThat(open.get("total").asLong()).isEqualTo(3);
    JsonNode first = open.get("items").get(0);
    assertThat(first.get("status").asString()).isEqualTo("pending");
    assertThat(first.get("customerName").asString()).isEqualTo(name + " Test");
    assertThat(first.get("firstUse").asBoolean()).isTrue();
    assertThat(body(call(get("/api/admin/withdrawals?status=open&keyword=" + name + "&minAmount=1000"), a.cookie(), null)).get("total").asLong()).isEqualTo(1);
    assertThat(body(call(get("/api/admin/withdrawals/summary"), a.cookie(), null)).get("pending").get("count").asLong()).isGreaterThanOrEqualTo(3);
    JsonNode detail = body(call(get("/api/admin/withdrawals/" + small), a.cookie(), null));
    assertThat(detail.get("withdrawal").get("amount").asString()).isEqualTo("100");
    assertThat(detail.get("balance").asString()).isEqualTo("6200");
    // 旧数据（V22 之前）的编号就是内部 id，照常显示、按编号能搜到（AC2-22）
    assertThat(detail.get("withdrawal").get("id").asString()).isEqualTo(String.valueOf(small));
    JsonNode byNo = body(call(get("/api/admin/withdrawals?keyword=" + small), a.cookie(), null));
    assertThat(byNo.get("items").toString()).contains("\"id\":\"" + small + "\"");

    JsonNode ok = body(call(post("/api/admin/withdrawals/" + small + "/approve"), a.cookie(), null));
    assertThat(ok.get("status").asString()).as("待上链显示为上链中").isEqualTo("broadcasting");
    assertThat(ok.get("reviewer").asString()).isEqualTo(a.username());
    assertThat(call(post("/api/admin/withdrawals/" + small + "/approve"), a.cookie(), null).getContentAsString()).contains("WITHDRAW_NOT_REVIEWABLE");
    JsonNode r = body(call(post("/api/admin/withdrawals/" + rej + "/reject"), a.cookie(), "{\"reason\":\"地址有风险\"}"));
    assertThat(r.get("status").asString()).isEqualTo("rejected");
    assertThat(r.get("reason").asString()).isEqualTo("地址有风险");
    assertThat(balance(uid)).isEqualByComparingTo("6400");

    JsonNode delayed = body(call(post("/api/admin/withdrawals/" + large + "/approve"), a.cookie(), null));
    assertThat(delayed.get("status").asString()).isEqualTo("delayed");
    assertThat(delayed.get("delayRemainingMinutes").asLong()).isEqualTo(360);
    assertThat(call(post("/api/admin/withdrawals/" + large + "/emergency-cancel"), a.cookie(), "{\"reason\":\"x\"}").getStatus()).as("没有紧急取消权限")
        .isEqualTo(403);
    assertThat(call(post("/api/admin/withdrawals/" + large + "/emergency-cancel"), risk.cookie(), "{}").getStatus()).isEqualTo(400);
    JsonNode c = body(call(post("/api/admin/withdrawals/" + large + "/emergency-cancel"), risk.cookie(), "{\"reason\":\"疑似盗号\"}"));
    assertThat(c.get("status").asString()).isEqualTo("cancelled");
    assertThat(balance(uid)).isEqualByComparingTo("9900");
    assertThat(jdbc.queryForObject("SELECT high_risk FROM admin_audit_log WHERE action = 'withdrawal.emergency_cancel' AND target = ? AND result = 'SUCCESS'",
        Boolean.class, String.valueOf(large))).isTrue();
  }

  @Test
  void 入金记录_按状态和交易哈希() throws Exception {
    Admin a = withRoles(role(Permission.DEPOSIT_VIEW));
    long uid = customer("Frank", "0");
    String tx = "0x" + Long.toHexString(System.nanoTime()) + "0".repeat(64 - Long.toHexString(System.nanoTime()).length());
    jdbc.update("INSERT INTO wallet_deposit (public_no, chain, tx_hash, log_index, block_number, block_hash, from_address, to_address, user_id, amount, status, confirmations) "
        + "VALUES (?, 'ERC20', ?, 0, 1, '0x1', '0xa', '0xb', ?, 0.5, 'BELOW_MIN', 12)", com.forecast.app.common.id.Snowflake.shared(1).next(), tx.substring(0, 66), uid);
    JsonNode p = body(call(get("/api/admin/deposits?status=ignored&keyword=" + tx.substring(0, 66)), a.cookie(), null));
    assertThat(p.get("total").asLong()).isEqualTo(1);
    assertThat(p.get("items").get(0).get("status").asString()).isEqualTo("ignored");
    assertThat(p.get("items").get(0).get("requiredConfirmations").asInt()).isEqualTo(12);
    assertThat(body(call(get("/api/admin/deposits?keyword=nobody-matches-this"), a.cookie(), null)).get("total").asLong()).isZero();
  }

  @Test
  void 对账列表和标记已处理_任务列表和手动重试() throws Exception {
    Admin a = withRoles(role(Permission.RECON_VIEW, Permission.RECON_RESOLVE, Permission.TASK_VIEW, Permission.TASK_RETRY));
    jdbc.update("INSERT INTO wallet_recon_issue (kind, ref_id, expected, actual, detail) VALUES ('WITHDRAW_FROZEN_MISMATCH', ?, 10, 12, '测试')", unique("ref"));
    long issue = jdbc.queryForObject("SELECT MAX(id) FROM wallet_recon_issue", Long.class);
    JsonNode list = body(call(get("/api/admin/recon?status=open&pageSize=100"), a.cookie(), null));
    assertThat(list.toString()).contains("\"id\":\"" + issue + "\"").contains("\"type\":\"withdraw_frozen_mismatch\"").contains("\"diff\":\"2\"");
    assertThat(call(post("/api/admin/recon/" + issue + "/resolve"), a.cookie(), "{\"note\":\" \"}").getStatus()).isEqualTo(400);
    assertThat(call(post("/api/admin/recon/" + issue + "/resolve"), a.cookie(), "{\"note\":\"已人工核对\"}").getStatus()).isEqualTo(200);
    assertThat(call(post("/api/admin/recon/" + issue + "/resolve"), a.cookie(), "{\"note\":\"再来\"}").getContentAsString()).contains("ISSUE_NOT_OPEN");

    jdbc.update("INSERT INTO common_task (task_type, payload, status, attempts, max_attempts, last_error) VALUES ('MAIL_SEND', '{\"recordId\": 42}', 'FAILED', 5, 5, '连不上')");
    long task = jdbc.queryForObject("SELECT MAX(id) FROM common_task", Long.class);
    JsonNode tasks = body(call(get("/api/admin/tasks?type=email&status=failed&pageSize=100"), a.cookie(), null));
    assertThat(tasks.toString()).contains("\"id\":\"" + task + "\",\"type\":\"email\",\"bizRef\":\"42\",\"status\":\"failed\"");
    assertThat(call(post("/api/admin/tasks/" + task + "/retry"), a.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT CONCAT(status, attempts) FROM common_task WHERE id = ?", String.class, task)).isEqualTo("PENDING0");
    assertThat(call(post("/api/admin/tasks/" + task + "/retry"), a.cookie(), null).getContentAsString()).contains("TASK_NOT_FAILED");
    jdbc.update("UPDATE common_task SET status = 'SUCCEEDED' WHERE id = ?", task);
  }

  @Test
  void 通知模板改文案_内置不能停用_只能用列出的变量_手动发送_通知记录() throws Exception {
    Admin a = withRoles(role(Permission.NOTIFY_TEMPLATE_VIEW, Permission.NOTIFY_TEMPLATE_EDIT, Permission.NOTIFY_SEND, Permission.NOTIFY_RECORD_VIEW));
    String original = jdbc.queryForObject("SELECT title_zh FROM notify_template WHERE code = 'DEPOSIT_CREDITED'", String.class);
    try {
      JsonNode ts = body(call(get("/api/admin/notify/templates"), a.cookie(), null));
      // 按编号排序；S4 起 BET_PLACED 排在最前（#8 下注成功）
      assertThat(ts.get(0).get("code").asString()).isEqualTo("BET_PLACED");
      JsonNode dep = null;
      for (JsonNode x : ts) if (x.get("code").asString().equals("DEPOSIT_CREDITED")) dep = x;
      assertThat(dep).isNotNull();
      assertThat(dep.get("channels").toString()).isEqualTo("[\"inbox\",\"push\",\"email\"]");
      assertThat(ts.get(0).get("channels").toString()).as("#8 只发站内信").isEqualTo("[\"inbox\"]");
      assertThat(call(put("/api/admin/notify/templates/DEPOSIT_CREDITED"), a.cookie(), "{\"enabled\":false}").getContentAsString())
          .contains("BUILT_IN_CANNOT_DISABLE");
      assertThat(call(put("/api/admin/notify/templates/DEPOSIT_CREDITED"), a.cookie(), "{\"bodyZh\":\"到账 {amount} {secret}\"}").getContentAsString())
          .contains("\"field\":\"bodyZh\",\"code\":\"unknownVariable\"");
      JsonNode t = body(call(put("/api/admin/notify/templates/DEPOSIT_CREDITED"), a.cookie(), "{\"titleZh\":\"入金成功\"}"));
      assertThat(t.get("titleZh").asString()).isEqualTo("入金成功");
      assertThat(t.get("bodyZh").asString()).as("没传的不改").contains("{amount}");
    } finally {
      jdbc.update("UPDATE notify_template SET title_zh = ? WHERE code = 'DEPOSIT_CREDITED'", original);
    }
    String grace = unique("Grace");
    long uid = customer(grace, "0");
    assertThat(call(post("/api/admin/notify/send"), a.cookie(), "{\"customerId\":" + uid + ",\"channels\":[\"inbox\"],\"title\":\"t\",\"body\":\"b\"}")
        .getContentAsString()).contains("\"field\":\"reason\"");
    MockHttpServletResponse sent = call(post("/api/admin/notify/send"), a.cookie(),
        "{\"customerId\":" + uid + ",\"channels\":[\"inbox\",\"email\"],\"title\":\"维护通知\",\"body\":\"今晚维护\",\"reason\":\"系统维护\"}");
    assertThat(sent.getStatus()).as(sent.getContentAsString()).isEqualTo(200);
    // 按唯一的客户名搜：搜数字（客户号）时，邮箱里含这串数字的其他客户也会匹配到（共用测试库时偶尔失败）
    JsonNode recs = body(call(get("/api/admin/notify/records?keyword=" + grace), a.cookie(), null));
    assertThat(recs.get("total").asLong()).isEqualTo(2);
    assertThat(recs.get("items").get(0).get("source").asString()).isEqualTo("manual");
    assertThat(recs.get("items").get(0).get("operator").asString()).isEqualTo(a.username());
    assertThat(body(call(get("/api/admin/customers/search?keyword=" + uid), a.cookie(), null)).get(0).get("id").asLong()).isEqualTo(uid);
    assertThat(audits("notify.manual_send")).anyMatch(l -> l.equals("SUCCESS " + a.username() + " 系统维护"));
  }
}
