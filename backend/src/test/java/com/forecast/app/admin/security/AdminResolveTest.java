package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.market.core.MarketService;
import com.forecast.app.pool.bet.BetService;
import com.forecast.app.support.MutableClock;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台的裁决台、公示与争议、补偿接口（forecast S7；需求说明书第 2.2 节，AC-13、AC-30、AC-35）：
 * 系统里只有一个超管时他一个人能走完全部流程，每一步写审计；只有提议权限的人调确认接口 403；确认作废还要 resolve:void；处理争议要 dispute:handle；
 * 补偿要 settle:compensate（高危：原因必填、勾选确认）。S5 的临时入口"超管手工填结果直接结算"已删除（SQ-10）。
 */
class AdminResolveTest extends AdminTestSupport {

  @Autowired MarketAdminApi markets;
  @Autowired MarketService marketService;
  @Autowired BetService bets;
  @Autowired LedgerApi ledger;
  @Autowired Clock clock;
  final ObjectMapper json = new ObjectMapper();

  @AfterEach
  void resetClock() {
    ((MutableClock) clock).reset();
  }

  record Customer(long id, Cookie cookie) {}

  Customer customer() throws Exception {
    String email = unique("rs") + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    ledger.openAccount(id);
    ledger.apply(new LedgerCommand(id, Bucket.AVAILABLE, new BigDecimal("100"), LedgerEntryType.DEPOSIT, "TEST", "dep-" + id, "测试入金"));
    String token = UUID.randomUUID().toString();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    jdbc.update("INSERT INTO account_session (user_id, token_hash, created_at, last_seen_at, expires_at) VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), "
        + "UTC_TIMESTAMP(3) + INTERVAL 1 DAY)", id, hash);
    return new Customer(id, new Cookie("fc_session", token));
  }

  record M(String no, String yes, String nope, Customer a, Customer b) {}

  /** 平台裁决（时事、争议窗口 2 分钟）的市场：A 押"是" 60、B 押"否" 40，时钟拨到事件结束之后（待裁决） */
  M pending() throws Exception {
    I18nText t = new I18nText("Resolve", "裁决", "裁決");
    Instant now = Instant.now(clock);
    MarketAdminApi.Detail d = markets.createDraft(new MarketAdminApi.DraftInput("MACRO", t, t, "PLATFORM", "a", "b", null, now.minusSeconds(60).toString(),
        now.plus(Duration.ofMinutes(1)).toString(), now.plus(Duration.ofMinutes(1)).toString(), now.plus(Duration.ofMinutes(2)).toString(), null, 2, null,
        List.of(new MarketAdminApi.OptionInput(t, t), new MarketAdminApi.OptionInput(t, t))), 1);
    markets.publish(Long.parseLong(d.publicNo()), true, 1);
    Customer a = customer();
    Customer b = customer();
    bets.place(new BetService.Place(a.id(), d.publicNo(), d.options().get(0).optionNo(), "60", UUID.randomUUID().toString(), now));
    bets.place(new BetService.Place(b.id(), d.publicNo(), d.options().get(1).optionNo(), "40", UUID.randomUUID().toString(), now));
    ((MutableClock) clock).advance(Duration.ofMinutes(3));
    marketService.closeDue();
    marketService.endDue();
    return new M(d.publicNo(), d.options().get(0).optionNo(), d.options().get(1).optionNo(), a, b);
  }

  String proposal(String optionNo, Boolean voidMarket, String basis, Map<String, Object> extra) throws Exception {
    Map<String, Object> m = new LinkedHashMap<>();
    if (optionNo != null) m.put("optionNo", optionNo);
    if (voidMarket != null) m.put("voidMarket", voidMarket);
    if (basis != null) m.put("basis", basis);
    m.put("evidenceUrls", List.of("https://example.org/official"));
    m.put("note", "按官方公布");
    if (extra != null) m.putAll(extra);
    return json.writeValueAsString(m);
  }

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  String balance(long uid) {
    return ledger.snapshot(uid).available().stripTrailingZeros().toPlainString();
  }

  @Test
  void AC13_只有一个超管_一个人走完提议确认公示争议改判补偿_每一步写审计() throws Exception {
    M m = pending();
    Admin s = superAdmin();
    String base = "/api/admin/resolve/cases/" + m.no();
    // 裁决台：待提议里有它
    MockHttpServletResponse r = call(get("/api/admin/resolve/cases?tab=awaiting_proposal&pageSize=100"), s.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentAsString()).contains(m.no());
    // 上传截图
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 9};
    r = mvc.perform(multipart(base + "/attachments").file(new MockMultipartFile("file", "a.png", "image/png", png)).cookie(s.cookie()).with(x -> {
      x.setRemoteAddr(IP);
      return x;
    })).andReturn().getResponse();
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    String att = body(r).get("attachmentNo").asString();
    r = call(get("/api/admin/resolve/attachments/" + att), s.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentType()).isEqualTo("image/png");
    assertThat(r.getContentAsByteArray()).isEqualTo(png);
    // 提议（带截图）：详情里带按提议结果的结算预览（守恒数字）
    r = call(post(base + "/proposals"), s.cookie(), proposal(m.yes(), null, null, Map.of("attachmentNos", List.of(att))));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    String proposalId = body(r).get("detail").get("summary").get("proposal").get("proposalId").asString();
    JsonNode pv = body(r).get("proposalPreview");
    assertThat(pv.get("outcome").asString()).isEqualTo("settle");
    assertThat(pv.get("sumPayout").asString()).isEqualTo("98.00");
    assertThat(pv.get("platformIncome").asString()).isEqualTo("2.00");
    assertThat(pv.get("conserved").asBoolean()).isTrue();
    // 确认：高危，原因和勾选一次列出
    r = call(post(base + "/proposals/" + proposalId + "/confirm"), s.cookie(), "{}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("reason").contains("checked");
    r = call(post(base + "/proposals/" + proposalId + "/confirm"), s.cookie(), "{\"checked\":true,\"reason\":\"核对官网\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).get("detail").get("summary").get("marketStatus").asString()).isEqualTo("publicizing");
    // 客户提交争议（客户端接口）
    r = call(post("/api/markets/" + m.no() + "/disputes").cookie(m.b().cookie()), null, "{\"claimedOptionNo\":\"" + m.nope() + "\",\"reason\":\"官网写的是否\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).get("status").asString()).isEqualTo("open");
    r = call(post("/api/markets/" + m.no() + "/disputes").cookie(m.b().cookie()), null, "{\"claimedOptionNo\":\"void\",\"reason\":\"再来\"}");
    assertThat(r.getStatus()).isEqualTo(409);
    Customer outsider = customer();
    r = call(post("/api/markets/" + m.no() + "/disputes").cookie(outsider.cookie()), null, "{\"claimedOptionNo\":\"void\",\"reason\":\"x\"}");
    assertThat(r.getStatus()).as("没下注").isEqualTo(403);
    // 争议列表（看得到争议人编号）
    r = call(get("/api/admin/resolve/disputes?status=open"), s.cookie(), null);
    assertThat(r.getContentAsString()).contains("\"userId\":" + m.b().id());
    // 改判：高危
    String decision = "{\"decision\":\"overturn\",\"optionNo\":\"" + m.nope() + "\",\"note\":\"核对后改判为否\"";
    r = call(post(base + "/disputes/decide"), s.cookie(), decision + "}");
    assertThat(r.getStatus()).isEqualTo(400);
    r = call(post(base + "/disputes/decide"), s.cookie(), decision + ",\"checked\":true,\"reason\":\"争议成立\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).get("summary").get("marketStatus").asString()).isEqualTo("settled");
    assertThat(balance(m.b().id())).isEqualTo("157");
    // 补偿（假设又发现改判也错了，正确是"是"）：预览 → 高危确认
    r = call(get("/api/admin/markets/" + m.no() + "/compensation-preview?correctOptionNo=" + m.yes()), s.cookie(), null);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).get("total").asString()).isEqualTo("98.00");
    r = call(post("/api/admin/markets/" + m.no() + "/compensations"), s.cookie(), "{\"correctOptionNo\":\"" + m.yes() + "\"}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("reason").contains("checked");
    r = call(post("/api/admin/markets/" + m.no() + "/compensations"), s.cookie(), "{\"correctOptionNo\":\"" + m.yes() + "\",\"checked\":true,\"reason\":\"再次核对\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(balance(m.a().id())).isEqualTo("138");
    assertThat(balance(m.b().id())).as("只补不扣").isEqualTo("157");
    r = call(get("/api/admin/reports/settle?from=2000-01-01&to=2000-01-02"), s.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    // 每一步都有审计；高危的标高危
    assertThat(audits("resolve.attachment")).containsExactly("SUCCESS " + s.username() + " -");
    assertThat(audits("resolve.propose")).containsExactly("SUCCESS " + s.username() + " 按官方公布");
    assertThat(audits("resolve.confirm")).as("字段不全的在审计之前就拒绝了").containsExactly("SUCCESS " + s.username() + " 核对官网");
    assertThat(audits("dispute.decide")).contains("SUCCESS " + s.username() + " 争议成立");
    assertThat(audits("settle.compensate")).contains("SUCCESS " + s.username() + " 再次核对");
    assertThat(jdbc.queryForObject("SELECT high_risk FROM admin_audit_log WHERE action = 'dispute.decide' AND result = 'SUCCESS' AND target = ?", Boolean.class,
        m.no())).isTrue();
  }

  @Test
  void AC13_权限_只有提议权限的403_确认作废还要作废权限_处理争议要dispute_handle_补偿要settle_compensate() throws Exception {
    M m = pending();
    String base = "/api/admin/resolve/cases/" + m.no();
    Admin proposer = withRoles(role(Permission.RESOLVE_PROPOSE));
    MockHttpServletResponse r = call(post(base + "/proposals"), proposer.cookie(), proposal(null, true, "E11_UNDETERMINABLE", null));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    String pid = body(r).get("detail").get("summary").get("proposal").get("proposalId").asString();
    assertThat(body(r).get("proposalPreview").isNull()).as("作废的提议没有结算预览").isTrue();
    assertThat(call(post(base + "/proposals/" + pid + "/confirm"), proposer.cookie(), "{\"checked\":true,\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    assertThat(call(post(base + "/decide"), proposer.cookie(), proposal(m.yes(), null, null, Map.of("checked", true, "reason", "x"))).getStatus()).isEqualTo(403);
    // 只有确认权限：确认作废的提议 403（还要 resolve:void）
    Admin confirmer = withRoles(role(Permission.RESOLVE_CONFIRM));
    r = call(post(base + "/proposals/" + pid + "/confirm"), confirmer.cookie(), "{\"checked\":true,\"reason\":\"x\"}");
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(jdbc.queryForObject("SELECT status FROM market_question WHERE public_no = ?", String.class, Long.parseLong(m.no()))).isEqualTo("PENDING_RESOLUTION");
    // 退回（只要确认权限，原因必填）
    assertThat(call(post(base + "/proposals/" + pid + "/return"), confirmer.cookie(), "{}").getStatus()).isEqualTo(400);
    assertThat(call(post(base + "/proposals/" + pid + "/return"), confirmer.cookie(), "{\"reason\":\"不应作废\"}").getStatus()).isEqualTo(200);
    // 直接确认结果（只要确认权限）
    r = call(post(base + "/decide"), confirmer.cookie(), proposal(m.yes(), null, null, Map.of("checked", true, "reason", "官方公布")));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(audits("resolve.decide")).contains("SUCCESS " + confirmer.username() + " 官方公布");
    // 争议：查看要 dispute:view，处理要 dispute:handle
    r = call(post("/api/markets/" + m.no() + "/disputes").cookie(m.a().cookie()), null, "{\"claimedOptionNo\":\"void\",\"reason\":\"无法判定\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(call(get("/api/admin/resolve/disputes"), proposer.cookie(), null).getStatus()).isEqualTo(403);
    Admin viewer = withRoles(role(Permission.DISPUTE_VIEW));
    assertThat(call(get("/api/admin/resolve/disputes"), viewer.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(call(get("/api/admin/resolve/disputes/" + m.no()), viewer.cookie(), null).getStatus()).isEqualTo(200);
    String uphold = "{\"decision\":\"uphold\",\"note\":\"维持\",\"checked\":true,\"reason\":\"x\"}";
    assertThat(call(post(base + "/disputes/decide"), viewer.cookie(), uphold).getStatus()).isEqualTo(403);
    Admin handler = withRoles(role(Permission.DISPUTE_HANDLE));
    r = call(post(base + "/disputes/decide"), handler.cookie(), uphold);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT status FROM market_question WHERE public_no = ?", String.class, Long.parseLong(m.no()))).isEqualTo("SETTLED");
    // 补偿要 settle:compensate；报表要 report:view
    assertThat(call(get("/api/admin/markets/" + m.no() + "/compensation-preview?correctOptionNo=void"), handler.cookie(), null).getStatus()).isEqualTo(403);
    Admin comp = withRoles(role(Permission.SETTLE_COMPENSATE));
    assertThat(call(get("/api/admin/markets/" + m.no() + "/compensation-preview?correctOptionNo=void"), comp.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(call(get("/api/admin/reports/settle?from=2026-01-01&to=2026-01-02"), comp.cookie(), null).getStatus()).isEqualTo(403);
    Admin finance = withRoles(role(Permission.REPORT_VIEW));
    assertThat(call(get("/api/admin/reports/settle?from=2026-01-01&to=2026-01-02"), finance.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(call(get("/api/admin/settle/compensations"), finance.cookie(), null).getStatus()).isEqualTo(200);
    // 裁决台列表、详情、结算预览要 resolve:propose
    assertThat(call(get("/api/admin/resolve/cases"), handler.cookie(), null).getStatus()).isEqualTo(403);
    assertThat(call(get("/api/admin/markets/" + m.no() + "/settle-preview?optionNo=" + m.yes()), proposer.cookie(), null).getStatus()).isEqualTo(200);
  }

  @Test
  void SQ10_S5的临时入口超管手工填结果直接结算已删除_人工作废照常() throws Exception {
    M m = pending();
    Admin s = superAdmin();
    MockHttpServletResponse r = call(post("/api/admin/markets/" + m.no() + "/settle"), s.cookie(), "{\"optionNo\":\"" + m.yes() + "\",\"checked\":true,\"reason\":\"x\"}");
    assertThat(r.getStatus()).isIn(404, 405);
    assertThat(jdbc.queryForObject("SELECT status FROM market_question WHERE public_no = ?", String.class, Long.parseLong(m.no()))).isEqualTo("PENDING_RESOLUTION");
    // 人工作废（resolve:void）：写裁决记录
    Admin voider = withRoles(role(Permission.RESOLVE_VOID));
    r = call(post("/api/admin/markets/" + m.no() + "/void"), voider.cookie(), "{\"reason\":\"比赛取消\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).get("marketStatus").asString()).isEqualTo("voided");
    assertThat(jdbc.queryForObject("SELECT CONCAT(kind, ' ', actor, ' ', note) FROM resolve_record r JOIN market_question q ON q.id = r.market_id WHERE q.public_no = ?",
        String.class, Long.parseLong(m.no()))).isEqualTo("VOID " + voider.id() + " 比赛取消");
    // 已作废的不能再作废
    assertThat(call(post("/api/admin/markets/" + m.no() + "/void"), voider.cookie(), "{\"reason\":\"x\"}").getStatus()).isEqualTo(409);
  }
}
