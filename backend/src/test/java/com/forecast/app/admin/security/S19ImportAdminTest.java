package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.growth.waitlist.WaitlistService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * forecast S19（P15）：开放交易时从 Cloudflare 导入等候页名单（POST /api/admin/waitlist/import，content:publish ★）。真实 MySQL（C37），
 * 每个用例自己的邮箱（C80）；上线通知是全站唯一的一行，用例结束删掉。
 * <ul>
 *   <li>权限、原因和勾选必填、文件格式；审计只记数量、不记邮箱</li>
 *   <li>规则表（开发记录 S19 第 2.9 节）：已确认新增 / 待确认改已确认 / 已确认不动 / 后台已退订不动；已退订：后台有就改成退订、没有不新增</li>
 *   <li>可以重复导入（第二次全部"不变"）；导入本身不发任何邮件；之后上线通知每人只发一封，再导入、再跑任务也不重发</li>
 *   <li>格式不对的项逐行报出、合法的照常导入；退订串沿用 Cloudflare 的</li>
 * </ul>
 */
class S19ImportAdminTest extends AdminTestSupport {

  @Autowired WaitlistService waitlist;
  private static final ObjectMapper JSON = new ObjectMapper();

  @AfterEach
  void clearLaunch() {
    jdbc.update("DELETE FROM growth_launch");
  }

  private static String email(String tag) {
    return "cf-" + tag + "-" + System.nanoTime() + "@example.com";
  }

  private static String token(String seed) {
    String h = Integer.toHexString(seed.hashCode() & 0x7fffffff);
    return (h + "0".repeat(32)).substring(0, 32);
  }

  private static Map<String, Object> item(String email, String status) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("email", email);
    m.put("lang", "zh-TW");
    m.put("status", status);
    m.put("src", "threads");
    m.put("cmp", "tw1");
    m.put("ref", null);
    m.put("start", null);
    m.put("createdAt", "2026-10-28T08:00:00.000Z");
    m.put("confirmedAt", "confirmed".equals(status) ? "2026-10-28T08:05:00.000Z" : null);
    m.put("unsubscribedAt", "unsubscribed".equals(status) ? "2026-10-28T09:00:00.000Z" : null);
    m.put("unsubToken", token(email));
    m.put("ipHash", "a".repeat(64));
    return m;
  }

  private String body(List<Map<String, Object>> items, String reason) throws Exception {
    return JSON.writeValueAsString(Map.of("checked", true, "reason", reason, "file", Map.of("format", "fc-cf-waitlist-v1", "items", items)));
  }

  private JsonNode importOk(Admin a, List<Map<String, Object>> items) throws Exception {
    MockHttpServletResponse r = call(post("/api/admin/waitlist/import"), a.cookie(), body(items, "开放交易，导入名单"));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return JSON.readTree(r.getContentAsString());
  }

  private String status(String email) {
    List<String> s = jdbc.queryForList("SELECT status FROM growth_waitlist WHERE email = ?", String.class, email);
    return s.isEmpty() ? null : s.get(0);
  }

  private int mailsTo(List<String> emails) {
    int n = 0;
    for (String e : emails) n += jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE recipient = ?", Integer.class, e);
    return n;
  }

  /** 后台自己的一个订阅（直接写表，状态随意） */
  private void existing(String email, String status) {
    jdbc.update("INSERT INTO growth_waitlist (email, lang, status, confirm_token, confirm_sent_at, confirm_sends, unsub_token, ip_hash, created_at, updated_at) "
        + "VALUES (?, 'en', ?, ?, UTC_TIMESTAMP(3), 1, ?, '', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", email, status, token("c" + email), token("u" + email));
  }

  @Test
  void 权限_原因和勾选必填_文件格式_审计只记数量() throws Exception {
    Admin viewer = withRoles(role(Permission.GROWTH_VIEW));
    String e = email("perm");
    assertThat(call(post("/api/admin/waitlist/import"), viewer.cookie(), body(List.of(item(e, "confirmed")), "x")).getStatus()).isEqualTo(403);
    Admin pub = withRoles(role(Permission.CONTENT_PUBLISH));
    MockHttpServletResponse missing = call(post("/api/admin/waitlist/import"), pub.cookie(), "{}");
    assertThat(missing.getStatus()).isEqualTo(400);
    assertThat(missing.getContentAsString()).contains("\"field\":\"reason\"").contains("\"field\":\"checked\"").contains("\"field\":\"file\"");
    MockHttpServletResponse wrong = call(post("/api/admin/waitlist/import"), pub.cookie(),
        "{\"checked\":true,\"reason\":\"x\",\"file\":{\"format\":\"something-else\",\"items\":[]}}");
    assertThat(wrong.getContentAsString()).contains("\"field\":\"file\",\"code\":\"formatInvalid\"");
    MockHttpServletResponse empty = call(post("/api/admin/waitlist/import"), pub.cookie(),
        "{\"checked\":true,\"reason\":\"x\",\"file\":{\"format\":\"fc-cf-waitlist-v1\",\"items\":[]}}");
    assertThat(empty.getContentAsString()).contains("\"field\":\"file\",\"code\":\"empty\"");
    assertThat(status(e)).isNull();
    JsonNode r = importOk(pub, List.of(item(e, "confirmed")));
    assertThat(r.get("added").asInt()).isEqualTo(1);
    assertThat(audits("waitlist.import")).contains("SUCCESS " + pub.username() + " 开放交易，导入名单");
    String afterJson = jdbc.queryForObject("SELECT after_json FROM admin_audit_log WHERE action = 'waitlist.import' ORDER BY id DESC LIMIT 1", String.class);
    assertThat(afterJson).contains("\"added\":1").doesNotContain("@");
  }

  @Test
  void 规则表_可以重复导入_不发任何邮件() throws Exception {
    Admin pub = withRoles(role(Permission.CONTENT_PUBLISH));
    String fresh = email("fresh");
    String pending = email("pending");
    String already = email("already");
    String notified = email("notified");
    String gone = email("gone");
    String cfUnsubKnown = email("cf-unsub-known");
    String cfUnsubUnknown = email("cf-unsub-unknown");
    existing(pending, "PENDING");
    existing(already, "CONFIRMED");
    existing(notified, "NOTIFIED");
    existing(gone, "UNSUBSCRIBED");
    existing(cfUnsubKnown, "CONFIRMED");
    List<Map<String, Object>> items = new ArrayList<>();
    for (String e : List.of(fresh, pending, already, notified, gone)) items.add(item(e, "confirmed"));
    items.add(item(cfUnsubKnown, "unsubscribed"));
    items.add(item(cfUnsubUnknown, "unsubscribed"));
    List<String> all = List.of(fresh, pending, already, notified, gone, cfUnsubKnown, cfUnsubUnknown);
    int mailsBefore = mailsTo(all);

    JsonNode r = importOk(pub, items);
    assertThat(r.get("total").asInt()).isEqualTo(7);
    assertThat(r.get("added").asInt()).isEqualTo(1);
    assertThat(r.get("confirmed").asInt()).isEqualTo(1);
    assertThat(r.get("unsubscribed").asInt()).isEqualTo(1);
    assertThat(r.get("unchanged").asInt()).isEqualTo(3);
    assertThat(r.get("notAdded").asInt()).isEqualTo(1);
    assertThat(r.get("invalid").asInt()).isZero();
    assertThat(status(fresh)).isEqualTo("CONFIRMED");
    assertThat(status(pending)).isEqualTo("CONFIRMED");
    assertThat(status(already)).isEqualTo("CONFIRMED");
    assertThat(status(notified)).isEqualTo("NOTIFIED");
    assertThat(status(gone)).as("退订优先：不替人重新订阅").isEqualTo("UNSUBSCRIBED");
    assertThat(status(cfUnsubKnown)).isEqualTo("UNSUBSCRIBED");
    assertThat(status(cfUnsubUnknown)).as("不保存退订者的邮箱").isNull();
    // 新增的：语言、来源、时间、退订串照搬 Cloudflare 上的
    Map<String, Object> row = jdbc.queryForMap("SELECT lang, src, cmp, unsub_token, ip_hash, DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s') AS c, "
        + "DATE_FORMAT(confirmed_at, '%Y-%m-%dT%H:%i:%s') AS cf FROM growth_waitlist WHERE email = ?", fresh);
    assertThat(row).containsEntry("lang", "zh-TW").containsEntry("src", "threads").containsEntry("cmp", "tw1").containsEntry("unsub_token", token(fresh))
        .containsEntry("ip_hash", "a".repeat(64)).containsEntry("c", "2026-10-28T08:00:00").containsEntry("cf", "2026-10-28T08:05:00");
    // 第二次导入同一个文件：全部不变
    JsonNode again = importOk(pub, items);
    assertThat(again.get("added").asInt() + again.get("confirmed").asInt() + again.get("unsubscribed").asInt()).isZero();
    assertThat(again.get("unchanged").asInt()).isEqualTo(6);
    assertThat(again.get("notAdded").asInt()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM growth_waitlist WHERE email = ?", Integer.class, fresh)).isEqualTo(1);
    assertThat(mailsTo(all)).as("导入不发任何邮件").isEqualTo(mailsBefore);
  }

  @Test
  void 格式不对的项逐行报出_合法的照常导入() throws Exception {
    Admin pub = withRoles(role(Permission.CONTENT_PUBLISH));
    String ok = email("ok");
    Map<String, Object> badEmail = item("not-an-email", "confirmed");
    Map<String, Object> badStatus = item(email("st"), "pending");
    Map<String, Object> badTime = item(email("tm"), "confirmed");
    badTime.put("confirmedAt", "昨天");
    JsonNode r = importOk(pub, List.of(badEmail, item(ok, "confirmed"), badStatus, badTime));
    assertThat(r.get("added").asInt()).isEqualTo(1);
    assertThat(r.get("invalid").asInt()).isEqualTo(3);
    assertThat(r.get("problems").toString()).contains("{\"row\":1,\"field\":\"email\",\"code\":\"emailInvalid\"}")
        .contains("{\"row\":3,\"field\":\"status\",\"code\":\"statusInvalid\"}").contains("{\"row\":4,\"field\":\"confirmedAt\",\"code\":\"timeInvalid\"}");
    assertThat(status(ok)).isEqualTo("CONFIRMED");
  }

  @Test
  void 导入后上线通知每人只发一封_正在发时导入的接着发_发完以后再导入的不会收到() throws Exception {
    Admin pub = withRoles(role(Permission.CONTENT_PUBLISH));
    String a = email("la");
    String b = email("lb");
    String c = email("lc");
    importOk(pub, List.of(item(a, "confirmed")));
    waitlist.startLaunch(1, "正式上线");
    // 正在发（SENDING）时又导入一个：任务接着发到
    JsonNode r = importOk(pub, List.of(item(a, "confirmed"), item(b, "confirmed")));
    assertThat(r.get("afterLaunch").asInt()).isZero();
    assertThat(r.get("launch").get("status").asString()).isEqualTo("sending");
    waitlist.sendLaunchBatch();
    waitlist.sendLaunchBatch();
    assertThat(status(a)).isEqualTo("NOTIFIED");
    assertThat(status(b)).isEqualTo("NOTIFIED");
    int launchMails = jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE recipient IN (?, ?) AND template_code = 'WAITLIST_LAUNCH'", Integer.class, a, b);
    assertThat(launchMails).isEqualTo(2);
    // 发完以后再导入：已通知的不动、不重发；新导入的记成已确认，但结果里写明不会再收到上线通知
    JsonNode late = importOk(pub, List.of(item(a, "confirmed"), item(b, "confirmed"), item(c, "confirmed")));
    assertThat(late.get("launch").get("status").asString()).isEqualTo("done");
    assertThat(late.get("afterLaunch").asInt()).isEqualTo(1);
    waitlist.sendLaunchBatch();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE recipient IN (?, ?, ?) AND template_code = 'WAITLIST_LAUNCH'", Integer.class, a, b, c))
        .as("全站只发一次：不重发，发完以后导入的也不发").isEqualTo(2);
    assertThat(status(c)).isEqualTo("CONFIRMED");
  }
}
