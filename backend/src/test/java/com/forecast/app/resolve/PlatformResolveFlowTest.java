package com.forecast.app.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.resolve.api.ResolveDeskApi;
import com.forecast.app.settle.api.SettleAdminApi;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 平台裁决的主流程（forecast S7；需求说明书第 3.2、3.6、4.4、7 节，AC-30～AC-34、AC-36；真实 MySQL、Redis）。
 * 例子：两个选项，A 押选项 1 共 60、B 押选项 2 共 40，T = 100，费率 5%。选项 1 赢：A 派彩 60 + 40 − 2 = 98（可用 138），B 可用 60；
 * 选项 2 赢：B 派彩 40 + 60 − 3 = 97（可用 157），A 可用 40。涉及结算的用例两种事务边界都跑（batch-size 0 和 500）。
 */
class PlatformResolveFlowTest extends PlatformTestSupport {

  @Autowired SettleAdminApi settleAdmin;

  record Setup(Market m, User a, User b) {}

  /** 平台裁决的市场，A 押选项 1 共 60（两笔）、B 押选项 2 共 40，截止、事件结束 → 待裁决 */
  Setup pending(int window) throws Exception {
    Market m = platformMarket(2, window);
    User a = user("100");
    User b = user("100");
    bet(a.id(), m, 0, "35");
    bet(a.id(), m, 0, "25");
    bet(b.id(), m, 1, "40");
    closeAndEnd(m);
    assertThat(status(m)).isEqualTo("PENDING_RESOLUTION");
    return new Setup(m, a, b);
  }

  @Test
  void AC30_进入待裁决就进裁决台_提议不确认不会公示_退回后重新提议_提议人自己可以确认() throws Exception {
    Setup s = pending(2);
    Market m = s.m();
    assertThat(stage(m)).isEqualTo("OPEN");
    assertThat(desk.cases("awaiting_proposal", 1, 100).items()).anyMatch(i -> i.marketNo().equals(m.no()) && i.origin().equals("platform"));
    // 提议：市场状态不变（提议只是一条记录），提醒确认的人
    ResolveDeskApi.CaseDetail d = desk.propose(no(m), result(m, 0), 7);
    String first = d.summary().proposal().proposalId();
    assertThat(d.summary().marketStatus()).isEqualTo("pending_resolution");
    assertThat(d.summary().proposal().optionNo()).isEqualTo(m.options().get(0));
    assertThat(d.summary().proposal().evidenceUrls()).containsExactly("https://www.federalreserve.gov/newsevents/pressreleases.htm");
    assertThat(desk.cases("awaiting_confirm", 1, 100).items()).anyMatch(i -> i.marketNo().equals(m.no()));
    verify(staff).notifyStaff(eq("RESOLVE_PROPOSED"), argThat(p -> m.no().equals(p.get("marketNo")) && "7".equals(p.get("proposer"))));
    // 没确认：公示期满的任务、结算都不会动它
    advance(Duration.ofHours(30));
    publicity.settleDue();
    assertThat(status(m)).isEqualTo("PENDING_RESOLUTION");
    assertThat(settleRuns(m)).isZero();
    // 已经有待确认的提议时不能再提议
    assertThatThrownBy(() -> desk.propose(no(m), result(m, 1), 8)).extracting(e -> ((BizException) e).code()).isEqualTo("PROPOSAL_PENDING");
    // 退回（理由必填）：市场留在待裁决
    assertThatThrownBy(() -> desk.returnProposal(no(m), first, " ", 1)).isInstanceOf(ValidationException.class);
    d = desk.returnProposal(no(m), first, "依据链接不是一手官方页面", 1);
    assertThat(d.summary().proposal()).isNull();
    assertThat(d.summary().marketStatus()).isEqualTo("pending_resolution");
    // 重新提议，提议人（7）自己有确认权限时可以确认自己的提议（RQ-17，不拒绝）
    String second = desk.propose(no(m), result(m, 0), 7).summary().proposal().proposalId();
    assertThatThrownBy(() -> desk.confirm(no(m), first, 7, true)).as("退回的提议不能再确认").extracting(e -> ((BizException) e).code())
        .isEqualTo("PROPOSAL_CHANGED");
    d = desk.confirm(no(m), second, 7, true);
    assertThat(d.summary().marketStatus()).isEqualTo("publicizing");
    assertThat(d.summary().stage()).isEqualTo("publicizing");
    assertThat(d.resolvedBy()).isEqualTo("7");
    assertThat(d.summary().resultOptionNo()).isEqualTo(m.options().get(0));
    assertThat(Duration.between(Instant.now(clock), deadline(m))).isEqualTo(Duration.ofMinutes(2));
    assertThat(records(m)).containsExactly("PROPOSE", "RETURN", "PROPOSE", "CONFIRM");
    // #10：有下注的两个人各一条（站内信 + 推送）
    assertThat(inbox(s.a().id(), "RESULT_PUBLICIZED")).isEqualTo(1);
    assertThat(inbox(s.b().id(), "RESULT_PUBLICIZED")).isEqualTo(1);
    // 公示中不能再提议、不能再确认
    assertThatThrownBy(() -> desk.propose(no(m), result(m, 1), 7)).extracting(e -> ((BizException) e).code()).isEqualTo("MARKET_NOT_RESOLVABLE");
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void AC31_AC34_公示期满没有争议_到点自动结算_详情公开结果依据裁决人编号(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Setup s = pending(2);
    Market m = s.m();
    publicize(m, 0, 7);
    // 截止前 1 毫秒：不结算
    assertThat(expire(m, Duration.ofMillis(-1))).doesNotContainKey(m.id());
    assertThat(status(m)).isEqualTo("PUBLICIZING");
    // 到点：结算（AC-31"1 分钟内"：任务每 15 秒一轮）
    assertThat(expire(m, Duration.ZERO)).containsEntry(m.id(), "settled");
    assertThat(status(m)).isEqualTo("SETTLED");
    assertThat(available(s.a().id())).isEqualByComparingTo("138");
    assertThat(available(s.b().id())).isEqualByComparingTo("60");
    settledConserved(m, "100");
    assertThat(records(m)).endsWith("CONFIRM", "PUBLICITY_END");
    assertThat(jdbc.queryForObject("SELECT resolved_by FROM settle_run WHERE market_id = ? AND kind = 'PAYOUT'", String.class, m.id())).isEqualTo("7");
    // 再跑不重复
    assertThat(publicity.settleDue()).doesNotContainKey(m.id());
    assertThat(settleRuns(m)).isEqualTo(1);
    // AC-34：详情公开结果、依据链接、裁决时间、裁决人编号；没有争议人身份、没有姓名
    JsonNode d = getJson("/api/markets/" + m.no(), s.b().cookie());
    assertThat(d.get("status").asString()).isEqualTo("settled");
    assertThat(d.get("winningOptionNo").asString()).isEqualTo(m.options().get(0));
    JsonNode r = d.get("resolution");
    assertThat(r.get("mode").asString()).isEqualTo("platform");
    assertThat(r.get("resultOptionNo").asString()).isEqualTo(m.options().get(0));
    assertThat(r.get("evidenceUrls").get(0).asString()).startsWith("https://www.federalreserve.gov");
    assertThat(r.get("explanation").asString()).isEqualTo("以美联储官网 11 月声明为准");
    assertThat(r.get("resolvedBy").asString()).isEqualTo("7");
    assertThat(r.get("resolvedAt").asString()).isNotBlank();
    assertThat(r.get("disputeDeadlineAt").asString()).isNotBlank();
    assertThat(r.get("dispute").isNull()).isTrue();
    assertThat(r.get("canDispute").asBoolean()).isFalse();
    assertThat(keys(r)).doesNotContain("userId", "reviewer", "reviewerNo", "adminName");
  }

  @Test
  void AC32_只有下注的人能提交争议_每人一次_理由必填不超过1000字_窗口结束后不行() throws Exception {
    Setup s = pending(2);
    Market m = s.m();
    User c = user("100"); // 没下注
    // 还在待裁决（没公示）：不能提交
    assertThat(tryDispute(s.a().id(), m, m.options().get(1), "不对")).isEqualTo("DISPUTE_CLOSED");
    publicize(m, 0, 1);
    assertThat(tryDispute(c.id(), m, m.options().get(1), "不对")).isEqualTo("DISPUTE_NOT_BETTOR");
    assertThat(tryDispute(s.b().id(), m, m.options().get(1), " ")).isEqualTo("VALIDATION:[reason=required]");
    assertThat(tryDispute(s.b().id(), m, m.options().get(1), "x".repeat(1001))).isEqualTo("VALIDATION:[reason=tooLong]");
    assertThat(tryDispute(s.b().id(), m, m.options().get(1), "x".repeat(1000))).isEqualTo("OK");
    assertThat(status(m)).isEqualTo("DISPUTED");
    assertThat(stage(m)).isEqualTo("DISPUTED");
    verify(staff).notifyStaff(eq("DISPUTE_NEW"), argThat(p -> m.no().equals(p.get("marketNo")) && "1".equals(p.get("count"))));
    // 每人每市场 1 次
    assertThat(tryDispute(s.b().id(), m, "void", "再提一次")).isEqualTo("DISPUTE_EXISTS");
    // 和公示的结果一样不算争议；选项不对
    assertThat(tryDispute(s.a().id(), m, m.options().get(0), "我觉得对")).isEqualTo("VALIDATION:[claimedOptionNo=sameAsResult]");
    assertThat(tryDispute(s.a().id(), m, "123", "x")).isEqualTo("VALIDATION:[claimedOptionNo=invalid]");
    // 争议中、窗口还没结束：别的下注人还能提交（认为应作废）
    assertThat(tryDispute(s.a().id(), m, "void", "规则有歧义")).isEqualTo("OK");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resolve_dispute WHERE market_id = ?", Integer.class, m.id())).isEqualTo(2);
    // 窗口结束（收到时间 = 截止时间）：拒绝；争议中的市场到期不会自动结算
    advance(Duration.between(Instant.now(clock), deadline(m)));
    User d = user("100");
    assertThat(tryDispute(s.a().id(), m, "void", "x", deadline(m))).isEqualTo("DISPUTE_CLOSED");
    assertThat(tryDispute(d.id(), m, "void", "x", deadline(m).minusMillis(1))).as("窗口内，但没下注").isEqualTo("DISPUTE_NOT_BETTOR");
    publicity.settleDue();
    assertThat(status(m)).isEqualTo("DISPUTED");
    assertThat(settleRuns(m)).isZero();
    // 详情：客户自己的争议、能不能再提交；看不到别人的争议
    JsonNode r = getJson("/api/markets/" + m.no(), s.b().cookie()).get("resolution");
    assertThat(r.get("myDispute").get("claimedOptionNo").asString()).isEqualTo(m.options().get(1));
    assertThat(r.get("myDispute").get("status").asString()).isEqualTo("open");
    assertThat(r.get("disputeCount").asInt()).isEqualTo(2);
    assertThat(r.get("canDispute").asBoolean()).isFalse();
    assertThat(r.toString()).doesNotContain(String.valueOf(s.a().id())).doesNotContain("规则有歧义");
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void AC33_争议改判_按新结果直接结算_所有下注人收到改判通知_争议人收到结论_裁决人编号是处理争议的人(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Setup s = pending(2);
    Market m = s.m();
    publicize(m, 0, 7);
    assertThat(tryDispute(s.b().id(), m, m.options().get(1), "官网写的是选项 2")).isEqualTo("OK");
    // 改判的结果和原结果相同、没写结论：一次列出
    assertThatThrownBy(() -> desk.decideDisputes(no(m), new ResolveDeskApi.DecisionInput("overturn", m.options().get(0), ""), 9))
        .isInstanceOf(ValidationException.class).satisfies(e -> assertThat(((ValidationException) e).fields().toString()).contains("note").contains("sameAsResult"));
    ResolveDeskApi.CaseDetail d = desk.decideDisputes(no(m), new ResolveDeskApi.DecisionInput("overturn", m.options().get(1), "核对官网，改判为选项 2"), 9);
    if (batch > 0) settle.drainAll();
    assertThat(d.decision()).isEqualTo("overturn");
    assertThat(status(m)).isEqualTo("SETTLED");
    assertThat(available(s.b().id())).isEqualByComparingTo("157");
    assertThat(available(s.a().id())).isEqualByComparingTo("40");
    settledConserved(m, "100");
    // #16 所有下注人；#15 争议人；结算通知（#11 / #12）照常
    assertThat(inbox(s.a().id(), "RESULT_OVERTURNED")).isEqualTo(1);
    assertThat(inbox(s.b().id(), "RESULT_OVERTURNED")).isEqualTo(1);
    assertThat(inbox(s.b().id(), "DISPUTE_DECIDED")).isEqualTo(1);
    assertThat(inbox(s.a().id(), "DISPUTE_DECIDED")).isZero();
    assertThat(inbox(s.b().id(), "SETTLE_WON")).isEqualTo(1);
    assertThat(inbox(s.a().id(), "SETTLE_LOST")).isEqualTo(1);
    assertThat(notices(s.b())).contains("你对「美联储 11 月议息 简」提交的争议已处理：改判。").contains("结论和理由：核对官网，改判为选项 2");
    assertThat(jdbc.queryForObject("SELECT status FROM resolve_dispute WHERE market_id = ?", String.class, m.id())).isEqualTo("OVERTURNED");
    assertThat(records(m)).endsWith("CONFIRM", "DISPUTE_OVERTURN");
    // 公开：最终结果、公示时的结果、结论；裁决人编号是处理争议的管理员（9）；不显示争议人
    JsonNode r = getJson("/api/markets/" + m.no(), s.a().cookie()).get("resolution");
    assertThat(r.get("resultOptionNo").asString()).isEqualTo(m.options().get(1));
    assertThat(r.get("publicizedOptionNo").asString()).isEqualTo(m.options().get(0));
    assertThat(r.get("resolvedBy").asString()).isEqualTo("9");
    assertThat(r.get("dispute").get("decision").asString()).isEqualTo("overturned");
    assertThat(r.get("dispute").get("conclusion").asString()).isEqualTo("核对官网，改判为选项 2");
    assertThat(r.get("myDispute").isNull()).isTrue();
    assertThat(r.toString()).doesNotContain(String.valueOf(s.b().id())).doesNotContain("官网写的是选项 2");
    assertThat(jdbc.queryForObject("SELECT resolved_by FROM market_question WHERE id = ?", String.class, m.id())).isEqualTo("9");
    // 处理过了：不能再处理
    assertThatThrownBy(() -> desk.decideDisputes(no(m), new ResolveDeskApi.DecisionInput("uphold", null, "x"), 9)).extracting(e -> ((BizException) e).code())
        .isEqualTo("DISPUTE_NOT_OPEN");
  }

  @Test
  void 争议维持_按原结果结算_无法判定_作废全额退款() throws Exception {
    Setup s = pending(2);
    Market m = s.m();
    publicize(m, 0, 7);
    assertThat(tryDispute(s.b().id(), m, m.options().get(1), "不对")).isEqualTo("OK");
    desk.decideDisputes(no(m), new ResolveDeskApi.DecisionInput("uphold", null, "核对后维持原结果"), 9);
    assertThat(status(m)).isEqualTo("SETTLED");
    assertThat(available(s.a().id())).isEqualByComparingTo("138");
    assertThat(jdbc.queryForObject("SELECT resolved_by FROM market_question WHERE id = ?", String.class, m.id())).as("维持：裁决人仍是确认结果的人").isEqualTo("7");
    assertThat(inbox(s.b().id(), "DISPUTE_DECIDED")).isEqualTo(1);
    assertThat(inbox(s.b().id(), "RESULT_OVERTURNED")).isZero();
    // 无法判定 → 作废
    Setup s2 = pending(2);
    publicize(s2.m(), 0, 7);
    assertThat(tryDispute(s2.a().id(), s2.m(), "void", "规则写的是 9 月，结果是 11 月")).isEqualTo("OK");
    desk.decideDisputes(no(s2.m()), new ResolveDeskApi.DecisionInput("void", null, "规则无法判定，作废"), 9);
    assertThat(status(s2.m())).isEqualTo("VOIDED");
    assertThat(available(s2.a().id())).isEqualByComparingTo("100");
    assertThat(available(s2.b().id())).isEqualByComparingTo("100");
    assertThat(inbox(s2.a().id(), "MARKET_REFUNDED")).isEqualTo(1);
    assertThat(inbox(s2.a().id(), "DISPUTE_DECIDED")).isEqualTo(1);
    assertThat(stage(s2.m())).isEqualTo("CLOSED");
    JsonNode r = getJson("/api/markets/" + s2.m().no(), s2.a().cookie()).get("resolution");
    assertThat(r.get("voided").asBoolean()).isTrue();
    assertThat(r.get("dispute").get("decision").asString()).isEqualTo("voided");
  }

  @Test
  void AC36_待提议待确认争议处理超时_各提醒一次_裁决台标红() throws Exception {
    Setup s = pending(2);
    Market m = s.m();
    // 时事：事件结束后 24 小时内提议
    advance(Duration.ofHours(24).minusSeconds(1));
    publicity.remindOverdue();
    verify(staff, never()).notifyStaff(eq("RESOLVE_OVERDUE"), argThat(p -> m.no().equals(p.get("marketNo"))));
    advance(Duration.ofSeconds(1));
    publicity.remindOverdue();
    publicity.remindOverdue();
    verify(staff, times(1)).notifyStaff(eq("RESOLVE_OVERDUE"), argThat(p -> m.no().equals(p.get("marketNo")) && "PROPOSE".equals(p.get("what"))));
    ResolveDeskApi.CaseItem item = desk.cases("overdue", 1, 100).items().stream().filter(i -> i.marketNo().equals(m.no())).findFirst().orElseThrow();
    assertThat(item.proposeOverdue()).isTrue();
    assertThat(item.overdue()).isTrue();
    // 提议以后 2 小时没确认
    String p = desk.propose(no(m), result(m, 0), 7).summary().proposal().proposalId();
    advance(Duration.ofHours(2));
    publicity.remindOverdue();
    publicity.remindOverdue();
    verify(staff, times(1)).notifyStaff(eq("RESOLVE_OVERDUE"), argThat(x -> m.no().equals(x.get("marketNo")) && "CONFIRM".equals(x.get("what"))));
    assertThat(desk.detail(no(m)).orElseThrow().summary().proposal().confirmOverdue()).isTrue();
    // 确认 → 争议 → 24 小时没处理
    desk.confirm(no(m), p, 1, true);
    assertThat(tryDispute(s.b().id(), m, m.options().get(1), "不对")).isEqualTo("OK");
    advance(Duration.ofHours(24));
    publicity.remindOverdue();
    publicity.remindOverdue();
    verify(staff, times(1)).notifyStaff(eq("RESOLVE_OVERDUE"), argThat(x -> m.no().equals(x.get("marketNo")) && "DISPUTE".equals(x.get("what"))));
    assertThat(desk.detail(no(m)).orElseThrow().summary().disputeOverdue()).isTrue();
    // 邮件里的说法按模板翻译（员工邮件中英文各一份）
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_template WHERE code = 'RESOLVE_OVERDUE' AND audience = 'STAFF'", Integer.class)).isEqualTo(1);
  }

  @Test
  void E6延期_等待期间不能提议_解除后可以_E7腰斩48小时没宣布_系统作废全额退款() throws Exception {
    Setup s = pending(2);
    Market m = s.m();
    assertThatThrownBy(() -> desk.hold(no(m), "later", "x", 1)).isInstanceOf(ValidationException.class);
    ResolveDeskApi.CaseDetail d = desk.hold(no(m), "postponed", "官方宣布延期", 1);
    assertThat(d.summary().hold()).isEqualTo("postponed");
    assertThat(Instant.parse(d.summary().holdUntil())).as("原定开赛后 48 小时").isEqualTo(eventStart(m).plus(Duration.ofHours(48)));
    assertThatThrownBy(() -> desk.propose(no(m), result(m, 0), 1)).extracting(e -> ((BizException) e).code()).isEqualTo("RESOLVE_ON_HOLD");
    assertThatThrownBy(() -> desk.decide(no(m), result(m, 0), 1, true)).extracting(e -> ((BizException) e).code()).isEqualTo("RESOLVE_ON_HOLD");
    assertThat(desk.cases("on_hold", 1, 100).items()).anyMatch(i -> i.marketNo().equals(m.no()));
    // 等待期间不算提议超时
    advance(Duration.ofHours(30));
    publicity.remindOverdue();
    verify(staff, never()).notifyStaff(eq("RESOLVE_OVERDUE"), argThat(p -> m.no().equals(p.get("marketNo"))));
    desk.release(no(m), "已在 48 小时内补赛完赛", 1);
    ResolveDeskApi.ProposalInput e6 = new ResolveDeskApi.ProposalInput(m.options().get(0), null, "E6_COMPLETED", null, List.of("https://example.org/r"),
        List.of(), "延期后完赛，按实际结果");
    desk.decide(no(m), e6, 1, true);
    assertThat(status(m)).isEqualTo("PUBLICIZING");
    assertThat(records(m)).containsExactly("HOLD", "RELEASE", "PROPOSE", "CONFIRM");
    // E7：腰斩、48 小时内没有宣布 → 系统作废
    Setup s2 = pending(2);
    desk.hold(no(s2.m()), "abandoned", "比赛中途终止，等官方宣布", 1);
    advance(Duration.ofHours(48).minusSeconds(1));
    assertThat(publicity.holdsDue()).doesNotContainKey(s2.m().id());
    advance(Duration.ofSeconds(1));
    assertThat(publicity.holdsDue()).containsEntry(s2.m().id(), "voided");
    assertThat(status(s2.m())).isEqualTo("VOIDED");
    assertThat(available(s2.a().id())).isEqualByComparingTo("100");
    assertThat(jdbc.queryForObject("SELECT void_reason FROM market_question WHERE id = ?", String.class, s2.m().id())).isEqualTo("ABANDONED_48H");
    assertThat(notices(s2.a())).contains("48 小时内官方没有宣布有效比分");
    assertThat(records(s2.m())).containsExactly("HOLD", "VOID");
    assertThat(jdbc.queryForObject("SELECT actor FROM resolve_record WHERE market_id = ? AND kind = 'VOID'", String.class, s2.m().id())).isEqualTo("SYSTEM");
  }

  Instant eventStart(Market m) {
    return jdbc.queryForObject("SELECT event_start_at FROM market_question WHERE id = ?", java.sql.Timestamp.class, m.id()).toLocalDateTime()
        .toInstant(java.time.ZoneOffset.UTC);
  }

  @Test
  void E11作废提议_确认的人还要有作废权限_确认后全额退款_公开作废的依据() throws Exception {
    Setup s = pending(2);
    Market m = s.m();
    assertThatThrownBy(() -> desk.propose(no(m), new ResolveDeskApi.ProposalInput(null, true, null, null, List.of(), List.of(), ""), 1))
        .isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(((ValidationException) e).fields().toString()).contains("basis").contains("evidenceUrls").contains("note"));
    String p = desk.propose(no(m), voidProposal("E11_UNDETERMINABLE"), 7).summary().proposal().proposalId();
    assertThatThrownBy(() -> desk.confirm(no(m), p, 1, false)).extracting(e -> ((BizException) e).code()).isEqualTo("FORBIDDEN");
    assertThat(status(m)).isEqualTo("PENDING_RESOLUTION");
    desk.confirm(no(m), p, 1, true);
    assertThat(status(m)).isEqualTo("VOIDED");
    assertThat(available(s.a().id())).isEqualByComparingTo("100");
    assertThat(jdbc.queryForObject("SELECT void_reason FROM market_question WHERE id = ?", String.class, m.id())).isEqualTo("规则有歧义，无法判定");
    JsonNode r = getJson("/api/markets/" + m.no(), s.a().cookie()).get("resolution");
    assertThat(r.get("voided").asBoolean()).isTrue();
    assertThat(r.get("basis").asString()).isEqualTo("E11_UNDETERMINABLE");
    assertThat(r.get("resolvedBy").asString()).isEqualTo("1");
    assertThat(r.get("resultOptionNo").isNull()).isTrue();
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void E14_提议填实际开赛时间_结算时退回之后的下注_预览和实际结算逐分相等(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Market m = platformMarket(2, 2);
    User a = user("100");
    User b = user("100");
    User c = user("100");
    bet(a.id(), m, 0, "60");
    bet(b.id(), m, 1, "40");
    advance(Duration.ofSeconds(30));
    Instant start = Instant.now(clock);
    bet(c.id(), m, 0, "10"); // 实际开赛之后收到
    closeAndEnd(m);
    SettleAdminApi.Preview pv = settleAdmin.preview(no(m), m.options().get(0), start.toString());
    assertThat(pv.outcome()).isEqualTo("settle");
    assertThat(pv.lateBets()).isEqualTo(1);
    assertThat(pv.lateAmount()).isEqualTo("10.00");
    assertThat(pv.poolTotal()).isEqualTo("100.00");
    assertThat(pv.sumPayout()).isEqualTo("98.00");
    assertThat(pv.sumFee()).isEqualTo("2.00");
    assertThat(pv.conserved()).isTrue();
    // 不填实际开赛时间时 C 也参与
    assertThat(settleAdmin.preview(no(m), m.options().get(0), null).poolTotal()).isEqualTo("110.00");
    assertThat(settleAdmin.preview(no(m), m.options().get(1), null).winnerPositions()).isEqualTo(1);
    desk.decide(no(m), result(m, 0, start), 1, true);
    ResolveDeskApi.CaseDetail d = desk.detail(no(m)).orElseThrow();
    assertThat(d.actualStartAt()).isEqualTo(start.toString());
    expire(m, Duration.ZERO);
    assertThat(status(m)).isEqualTo("SETTLED");
    assertThat(available(c.id())).as("晚于开赛的下注全额退回").isEqualByComparingTo("100");
    assertThat(inbox(c.id(), "LATE_BET_REFUNDED")).isEqualTo(1);
    Map<String, Object> run = run(m, "PAYOUT");
    assertThat(new java.math.BigDecimal(String.valueOf(run.get("pool_total")))).isEqualByComparingTo(pv.poolTotal());
    assertThat(new java.math.BigDecimal(String.valueOf(run.get("sum_payout")))).isEqualByComparingTo(pv.sumPayout());
    assertThat(new java.math.BigDecimal(String.valueOf(run.get("sum_fee")))).isEqualByComparingTo(pv.sumFee());
    assertThat(new java.math.BigDecimal(String.valueOf(run.get("dust")))).isEqualByComparingTo(pv.dust());
    settledConserved(m, "100");
  }

  @Test
  void 预览_没人押赢方会作废_守恒数字和第3点5节例子逐分相等() throws Exception {
    Market m = platformMarket(3, 2);
    User alice = user("200");
    User bob = user("3000", true);
    User carol = user("4000", true);
    User dave = user("2000", true);
    User eve = user("2500", true);
    User frank = user("1000", true);
    bet(alice.id(), m, 0, "60");
    bet(alice.id(), m, 0, "40");
    bet(bob.id(), m, 0, "2500");
    bet(carol.id(), m, 0, "3400");
    bet(dave.id(), m, 1, "1500");
    bet(eve.id(), m, 2, "2000");
    bet(frank.id(), m, 2, "500");
    closeAndEnd(m);
    SettleAdminApi.Preview pv = settleAdmin.preview(no(m), m.options().get(0), null);
    assertThat(List.of(pv.poolTotal(), pv.winnerPool(), pv.loserPool(), pv.sumGross(), pv.sumFee(), pv.dust(), pv.sumPayout(), pv.platformIncome()))
        .containsExactly("10000.00", "6000.00", "4000.00", "3999.98", "199.99", "0.02", "9799.99", "200.01");
    assertThat(pv.conserved()).isTrue();
    // 一个没人押的选项（加一个第 4 选项的市场）
    Market m2 = platformMarket(3, 2);
    User x = user("100");
    User y = user("100");
    bet(x.id(), m2, 0, "10");
    bet(y.id(), m2, 1, "10");
    closeAndEnd(m2);
    assertThat(settleAdmin.preview(no(m2), m2.options().get(2), null).outcome()).isEqualTo("void_no_winner");
    assertThatThrownBy(() -> settleAdmin.preview(no(m2), "1", "昨天")).isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(((ValidationException) e).fields().toString()).contains("optionNo").contains("actualStartAt"));
  }

  @Test
  void 直接确认_不等提议_有待确认提议时不行() throws Exception {
    Setup s = pending(2);
    Market m = s.m();
    desk.propose(no(m), result(m, 1), 7);
    assertThatThrownBy(() -> desk.decide(no(m), result(m, 0), 1, true)).extracting(e -> ((BizException) e).code()).isEqualTo("PROPOSAL_PENDING");
    Setup s2 = pending(2);
    ResolveDeskApi.CaseDetail d = desk.decide(no(s2.m()), result(s2.m(), 1), 1, true);
    assertThat(d.summary().marketStatus()).isEqualTo("publicizing");
    assertThat(records(s2.m())).containsExactly("PROPOSE", "CONFIRM");
    assertThat(jdbc.queryForObject("SELECT actor FROM resolve_record WHERE market_id = ? AND kind = 'PROPOSE'", String.class, s2.m().id())).isEqualTo("1");
    // 作废没有 resolve:void 不行
    Setup s3 = pending(2);
    assertThatThrownBy(() -> desk.decide(no(s3.m()), voidProposal("E5_CANCELLED"), 1, false)).extracting(e -> ((BizException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThat(records(s3.m())).isEmpty();
  }

  @Test
  void 截图_只认PNG和JPG_存进对象存储带sha256_提议只能引用这个市场的截图() throws Exception {
    Setup s = pending(2);
    Market m = s.m();
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    ResolveDeskApi.Attachment a = desk.upload(no(m), png, 7);
    assertThat(a.contentType()).isEqualTo("image/png");
    assertThat(a.sha256()).hasSize(64);
    assertThat(desk.attachment(Long.parseLong(a.attachmentNo())).orElseThrow().content()).isEqualTo(png);
    assertThatThrownBy(() -> desk.upload(no(m), "<svg/>".getBytes(), 7)).isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(((ValidationException) e).fields().toString()).contains("invalidType"));
    assertThatThrownBy(() -> desk.upload(no(m), null, 7)).isInstanceOf(ValidationException.class);
    Setup other = pending(2);
    ResolveDeskApi.Attachment b = desk.upload(no(other.m()), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}, 7);
    assertThat(b.contentType()).isEqualTo("image/jpeg");
    ResolveDeskApi.ProposalInput withOther = new ResolveDeskApi.ProposalInput(m.options().get(0), null, null, null, List.of("https://example.org/a"),
        List.of(b.attachmentNo()), "x");
    assertThatThrownBy(() -> desk.propose(no(m), withOther, 7)).isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(((ValidationException) e).fields().toString()).contains("attachmentNos[0]"));
    ResolveDeskApi.ProposalInput ok = new ResolveDeskApi.ProposalInput(m.options().get(0), null, null, null,
        List.of("https://example.org/a", "ftp://example.org/b"), List.of(a.attachmentNo()), "x");
    assertThatThrownBy(() -> desk.propose(no(m), ok, 7)).isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(((ValidationException) e).fields().toString()).contains("evidenceUrls[1]"));
    ResolveDeskApi.CaseDetail d = desk.propose(no(m),
        new ResolveDeskApi.ProposalInput(m.options().get(0), null, null, null, List.of("https://example.org/a"), List.of(a.attachmentNo()), "x"), 7);
    assertThat(d.summary().proposal().attachmentNos()).containsExactly(a.attachmentNo());
    assertThat(d.attachments()).extracting(ResolveDeskApi.Attachment::attachmentNo).containsExactly(a.attachmentNo());
  }

  @Test
  void 人工作废_争议中的争议一起结束_结论是作废() throws Exception {
    Setup s = pending(2);
    Market m = s.m();
    publicize(m, 0, 7);
    assertThat(tryDispute(s.b().id(), m, m.options().get(1), "不对")).isEqualTo("OK");
    assertThatThrownBy(() -> desk.voidMarket(no(m), "", 1)).isInstanceOf(ValidationException.class);
    desk.voidMarket(no(m), "比赛取消（E5）", 1);
    assertThat(status(m)).isEqualTo("VOIDED");
    assertThat(jdbc.queryForObject("SELECT status FROM resolve_dispute WHERE market_id = ?", String.class, m.id())).isEqualTo("VOIDED");
    assertThat(inbox(s.b().id(), "DISPUTE_DECIDED")).isEqualTo(1);
    assertThat(available(s.b().id())).isEqualByComparingTo("100");
    assertThat(stage(m)).isEqualTo("CLOSED");
    assertThatThrownBy(() -> desk.voidMarket(no(m), "x", 1)).extracting(e -> ((BizException) e).code()).isEqualTo("MARKET_NOT_VOIDABLE");
  }

  @Test
  void 加密自动裁决参数不全转人工_进裁决台自动裁决失败_人工确认后公示2小时() throws Exception {
    Instant now = Instant.now(clock);
    Instant close = now.plus(Duration.ofMinutes(1));
    I18nText t = tx("BTC 涨跌");
    MarketAdminApi.Detail draft = admin.createDraft(new MarketAdminApi.DraftInput("CRYPTO_1H", t, t, "AUTO", "Binance", "OKX", null,
        now.minus(Duration.ofMinutes(1)).toString(), close.toString(), close.toString(), close.plus(Duration.ofHours(1)).toString(), null, null, null,
        List.of(new MarketAdminApi.OptionInput(tx("涨"), tx("涨")), new MarketAdminApi.OptionInput(tx("跌"), tx("跌")))), 1);
    admin.publish(Long.parseLong(draft.publicNo()), true, 1);
    long id = jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, Long.parseLong(draft.publicNo()));
    Market m = new Market(id, draft.publicNo(), draft.options().stream().map(MarketAdminApi.OptionView::optionNo).toList());
    User a = user("100");
    User b = user("100");
    bet(a.id(), m, 0, "10");
    bet(b.id(), m, 1, "10");
    closeAndEnd(m);
    assertThat(jdbc.queryForObject("SELECT status FROM resolve_auto_task WHERE market_id = ?", String.class, id)).isEqualTo("MANUAL");
    assertThat(desk.cases("auto_failed", 1, 100).items()).anyMatch(i -> i.marketNo().equals(m.no()) && i.origin().equals("auto_failed"));
    desk.decide(no(m), result(m, 1), 1, true);
    assertThat(status(m)).isEqualTo("PUBLICIZING");
    assertThat(Duration.between(Instant.now(clock), deadline(m))).as("加密转人工：争议窗口按参数 2 小时（D-75）").isEqualTo(Duration.ofHours(2));
  }

  @Test
  void 自动裁决还在处理的加密市场_不能人工提议() throws Exception {
    // 正常的加密自动裁决市场（参数齐全）：进度 PENDING、没有平台裁决的进度行
    List<String> codes = new ArrayList<>();
    Market m = platformMarket(2, 2);
    jdbc.update("DELETE FROM resolve_case WHERE market_id = ?", m.id());
    User a = user("100");
    bet(a.id(), m, 0, "10");
    User b = user("100");
    bet(b.id(), m, 1, "10");
    closeAndEnd(m);
    jdbc.update("DELETE FROM resolve_case WHERE market_id = ?", m.id()); // 模拟"自动裁决在处理、还没进裁决台"
    try {
      desk.propose(no(m), result(m, 0), 1);
    } catch (BizException e) {
      codes.add(e.code());
    }
    assertThat(codes).containsExactly("RESOLVE_AUTO_IN_PROGRESS");
    assertThat(desk.detail(no(m))).isEmpty();
  }

  /** 客户收到的站内信（按客户的语言生成的标题和正文） */
  String notices(User u) throws Exception {
    JsonNode items = getJson("/api/notifications", u.cookie()).get("items");
    StringBuilder sb = new StringBuilder();
    for (JsonNode i : items) sb.append(i.get("title").asString()).append('\n').append(i.get("body").asString()).append('\n');
    return sb.toString();
  }
}
