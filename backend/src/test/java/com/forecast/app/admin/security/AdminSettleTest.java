package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台的结算接口（forecast S5）：超管手工填结果直接结算（{@code resolve:confirm}，高危：原因必填、勾选确认、审计"前 → 后"；AC-13：只有 {@code resolve:propose} 的 403）、
 * 人工作废（{@code resolve:void}）、结算记录和结算异常（{@code pool_recon:view}）、彩池对账差异（{@code pool_recon:view} / {@code pool_recon:resolve}）。
 */
class AdminSettleTest extends AdminTestSupport {

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

  long customer() {
    String email = unique("settle") + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    ledger.openAccount(id);
    ledger.apply(new LedgerCommand(id, Bucket.AVAILABLE, new BigDecimal("100"), LedgerEntryType.DEPOSIT, "TEST", "dep-" + id, "测试入金"));
    return id;
  }

  record M(String no, String yes, String nope, long a, long b) {}

  /** 两个客户各押一边（60 / 40），时钟拨到事件结束之后，市场进入待裁决 */
  M pending() {
    I18nText t = new I18nText("Settle", "结算", "結算");
    Instant now = Instant.now(clock);
    MarketAdminApi.Detail d = markets.createDraft(new MarketAdminApi.DraftInput("FOOTBALL", t, t, "AUTO", "a", "b", null, now.minusSeconds(60).toString(),
        now.plus(Duration.ofMinutes(1)).toString(), now.plus(Duration.ofMinutes(1)).toString(), now.plus(Duration.ofMinutes(2)).toString(), null, null, null,
        List.of(new MarketAdminApi.OptionInput(t, t), new MarketAdminApi.OptionInput(t, t))), 1);
    markets.publish(Long.parseLong(d.publicNo()), true, 1);
    long a = customer();
    long b = customer();
    bets.place(new BetService.Place(a, d.publicNo(), d.options().get(0).optionNo(), "60", UUID.randomUUID().toString(), now));
    bets.place(new BetService.Place(b, d.publicNo(), d.options().get(1).optionNo(), "40", UUID.randomUUID().toString(), now));
    ((MutableClock) clock).advance(Duration.ofMinutes(3));
    marketService.closeDue();
    marketService.endDue();
    return new M(d.publicNo(), d.options().get(0).optionNo(), d.options().get(1).optionNo(), a, b);
  }

  String settleBody(String optionNo, String actualStartAt, Boolean checked, String reason) throws Exception {
    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
    if (optionNo != null) m.put("optionNo", optionNo);
    if (actualStartAt != null) m.put("actualStartAt", actualStartAt);
    if (checked != null) m.put("checked", checked);
    if (reason != null) m.put("reason", reason);
    return json.writeValueAsString(m);
  }

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  @Test
  void 超管手工填结果直接结算_高危要原因和勾选_写审计_重复提交只结算一次() throws Exception {
    M m = pending();
    Admin s = superAdmin();
    String url = "/api/admin/markets/" + m.no() + "/settle";
    // 一次列出全部错误（C56）：缺原因 / 没勾选 / 选项不对 / 实际开赛时间格式不对
    MockHttpServletResponse r = call(post(url), s.cookie(), settleBody(m.yes(), null, true, null));
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("reason");
    r = call(post(url), s.cookie(), settleBody(m.yes(), null, null, "官方比分"));
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("checked");
    r = call(post(url), s.cookie(), settleBody("123", "昨天", true, "官方比分"));
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("optionNo").contains("actualStartAt");
    assertThat(jdbc.queryForObject("SELECT settle_phase FROM market_question WHERE public_no = ?", String.class, Long.parseLong(m.no()))).isEqualTo("NONE");
    // 通过
    r = call(post(url), s.cookie(), settleBody(m.yes(), null, true, "官方比分 2:1"));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode o = body(r);
    assertThat(o.get("marketStatus").asString()).isEqualTo("settled");
    assertThat(o.get("settlePhase").asString()).isEqualTo("done");
    assertThat(o.get("payout").get("sumPayout").asString()).isEqualTo("98.00"); // 60 + 40 − 2
    assertThat(o.get("payout").get("sumFee").asString()).isEqualTo("2.00");
    assertThat(o.get("payout").get("platformIncome").asString()).isEqualTo("2.00");
    assertThat(o.get("payout").get("winningOptionNo").asString()).isEqualTo(m.yes());
    assertThat(o.get("payout").get("resolvedBy").asString()).isEqualTo(String.valueOf(s.id()));
    assertThat(ledger.snapshot(m.a()).available()).isEqualByComparingTo("138"); // 40 + 98
    assertThat(audits("market.settle_manual")).contains("SUCCESS " + s.username() + " 官方比分 2:1");
    assertThat(jdbc.queryForObject("SELECT CONCAT(high_risk, ' ', before_json, ' ', after_json) FROM admin_audit_log WHERE action = 'market.settle_manual' AND target = ? "
        + "AND result = 'SUCCESS'", String.class, m.no())).startsWith("1 ").contains("\"pending_resolution\"").contains("\"settled\"").contains("98.00");
    // 重复提交（换一个结果也一样）：只结算一次
    r = call(post(url), s.cookie(), settleBody(m.nope(), null, true, "再点一次"));
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(body(r).get("payout").get("winningOptionNo").asString()).isEqualTo(m.yes());
    assertThat(ledger.snapshot(m.a()).available()).isEqualByComparingTo("138");
    // 结算情况、结算记录、明细
    Admin viewer = withRoles(role(Permission.MARKET_VIEW));
    r = call(get("/api/admin/markets/" + m.no() + "/settlement"), viewer.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    String runNo = body(r).get("payout").get("runNo").asString();
    assertThat(call(get("/api/admin/settle/runs"), viewer.cookie(), null).getStatus()).as("结算记录要 pool_recon:view").isEqualTo(403);
    Admin finance = withRoles(role(Permission.POOL_RECON_VIEW));
    r = call(get("/api/admin/settle/runs?status=done&kind=payout"), finance.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentAsString()).contains(runNo);
    r = call(get("/api/admin/settle/runs/" + runNo), finance.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    JsonNode items = body(r).get("payoutItems").get("items");
    assertThat(items.size()).isEqualTo(2);
    assertThat(items.get(0).get("win").asBoolean()).isTrue();
    assertThat(items.get(0).get("payout").asString()).isEqualTo("98.00");
    assertThat(call(get("/api/admin/settle/runs?status=bad"), finance.cookie(), null).getStatus()).isEqualTo(400);
  }

  @Test
  void AC13_只有提议权限的403_只有确认权限的不能作废_不能结算的状态409() throws Exception {
    M m = pending();
    Admin proposer = withRoles(role(Permission.RESOLVE_PROPOSE, Permission.MARKET_VIEW));
    assertThat(call(post("/api/admin/markets/" + m.no() + "/settle"), proposer.cookie(), settleBody(m.yes(), null, true, "x")).getStatus()).isEqualTo(403);
    Admin confirmer = withRoles(role(Permission.RESOLVE_CONFIRM));
    assertThat(call(post("/api/admin/markets/" + m.no() + "/void"), confirmer.cookie(), "{\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    // 作废（E5）：原因必填，全额退款
    Admin voider = withRoles(role(Permission.RESOLVE_VOID));
    assertThat(call(post("/api/admin/markets/" + m.no() + "/void"), voider.cookie(), "{}").getStatus()).isEqualTo(400);
    MockHttpServletResponse r = call(post("/api/admin/markets/" + m.no() + "/void"), voider.cookie(), "{\"reason\":\"比赛取消\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).get("marketStatus").asString()).isEqualTo("voided");
    assertThat(body(r).get("refund").get("status").asString()).isEqualTo("done");
    assertThat(ledger.snapshot(m.a()).available()).isEqualByComparingTo("100");
    assertThat(audits("market.void_manual")).contains("SUCCESS " + voider.username() + " 比赛取消");
    // 已作废的不能再结算、不能再作废
    assertThat(call(post("/api/admin/markets/" + m.no() + "/settle"), confirmer.cookie(), settleBody(m.yes(), null, true, "x")).getStatus()).isEqualTo(409);
    assertThat(call(post("/api/admin/markets/" + m.no() + "/void"), voider.cookie(), "{\"reason\":\"x\"}").getStatus()).isEqualTo(409);
    // 还在下注的市场不能结算
    I18nText t = new I18nText("Open", "进行中", "進行中");
    Instant now = Instant.now(clock);
    MarketAdminApi.Detail open = markets.createDraft(new MarketAdminApi.DraftInput("FOOTBALL", t, t, "AUTO", "a", "b", null, now.minusSeconds(60).toString(),
        now.plus(Duration.ofDays(1)).toString(), now.plus(Duration.ofDays(1)).toString(), now.plus(Duration.ofDays(2)).toString(), null, null, null,
        List.of(new MarketAdminApi.OptionInput(t, t), new MarketAdminApi.OptionInput(t, t))), 1);
    markets.publish(Long.parseLong(open.publicNo()), true, 1);
    r = call(post("/api/admin/markets/" + open.publicNo() + "/settle"), confirmer.cookie(), settleBody(open.options().get(0).optionNo(), null, true, "x"));
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(body(r).get("code").asString()).isEqualTo("MARKET_NOT_SETTLEABLE");
    assertThat(call(post("/api/admin/markets/99999999/settle"), confirmer.cookie(), settleBody("1", null, true, "x")).getStatus()).isEqualTo(404);
  }

  @Test
  void 彩池对账差异_查看要view_处理要resolve_说明必填_写审计() throws Exception {
    String ref = unique("M:test");
    jdbc.update("INSERT INTO settle_pool_recon_issue (ref_key, market_id, check_date, kind, source, expected, actual, diff, detail, status, created_at) "
        + "VALUES (?, NULL, UTC_DATE(), 'POOL_VS_BETS', 'DAILY', 10, 11, 1, '测试差异', 'OPEN', UTC_TIMESTAMP(3))", ref);
    long id = jdbc.queryForObject("SELECT id FROM settle_pool_recon_issue WHERE ref_key = ?", Long.class, ref);
    try {
      Admin viewer = withRoles(role(Permission.POOL_RECON_VIEW));
      MockHttpServletResponse r = call(get("/api/admin/pool-recon/issues?status=open"), viewer.cookie(), null);
      assertThat(r.getStatus()).isEqualTo(200);
      assertThat(r.getContentAsString()).contains(ref).contains("\"diff\":\"1.00\"");
      assertThat(call(post("/api/admin/pool-recon/issues/" + id + "/resolve"), viewer.cookie(), "{\"note\":\"x\"}").getStatus()).isEqualTo(403);
      Admin handler = withRoles(role(Permission.POOL_RECON_RESOLVE));
      assertThat(call(post("/api/admin/pool-recon/issues/" + id + "/resolve"), handler.cookie(), "{}").getStatus()).isEqualTo(400);
      r = call(post("/api/admin/pool-recon/issues/" + id + "/resolve"), handler.cookie(), "{\"note\":\"核对后改正\"}");
      assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
      assertThat(body(r).get("status").asString()).isEqualTo("resolved");
      assertThat(body(r).get("resolvedBy").asLong()).isEqualTo(handler.id());
      assertThat(call(post("/api/admin/pool-recon/issues/" + id + "/resolve"), handler.cookie(), "{\"note\":\"x\"}").getStatus()).isEqualTo(409);
      assertThat(audits("pool_recon.resolve")).contains("SUCCESS " + handler.username() + " 核对后改正");
      // 重试要 pool_recon:resolve，原因必填；不是结算异常的单 409
      assertThat(call(post("/api/admin/settle/runs/1/retry"), viewer.cookie(), "{\"reason\":\"x\"}").getStatus()).isEqualTo(403);
      assertThat(call(post("/api/admin/settle/runs/1/retry"), handler.cookie(), "{}").getStatus()).isEqualTo(400);
      assertThat(call(post("/api/admin/settle/runs/1/retry"), handler.cookie(), "{\"reason\":\"x\"}").getStatus()).isEqualTo(404);
    } finally {
      jdbc.update("UPDATE settle_pool_recon_issue SET status = 'RESOLVED' WHERE id = ?", id);
    }
  }
}
