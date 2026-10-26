package com.forecast.app.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.forecast.app.common.error.BizException;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.resolve.api.ResolveAdminApi;
import com.forecast.app.resolve.api.ResolveDeskApi;
import com.forecast.app.resolve.api.SportsAdminApi;
import com.forecast.app.support.StubHttp.Reply;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 体育市场的自动裁决（forecast S8；需求说明书 v1.3 第 3.6 节 E5～E7、E14，第 4.3 节；RQ-15；技术方案第 5.2、6.2 节）。
 * 替身数据源（本机 HTTP，照 football-data、balldontlie、TheSportsDB 的真实格式），真实的网关、存档、裁决台、结算。
 */
class SportsResolverTest extends SportsTestSupport {

  @Autowired ResolveAdminApi resolveAdmin;

  @ParameterizedTest(name = "batch-size = {0}")
  @MethodSource("modes")
  void 足球大小球_完赛后等30分钟_两源比分一致_自动结算_详情公开比分和来源(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Fx f = football(in(Duration.ofHours(3)));
    confirm(f);
    Market m = listed(f, "FOOTBALL_OU", "2.5");
    User over = user("300");
    User under = user("300");
    bet(over.id(), m, 0, "100");
    bet(under.id(), m, 1, "50");
    closeAndEnd(m);
    assertThat(status(m)).isEqualTo("PENDING_RESOLUTION");
    assertThat(sportsTask(m)).isEqualTo("PENDING");
    // 事件结束了，主源还在比赛中：5 分钟后再看
    f.primary().status("IN_PLAY");
    assertThat(sports.resolveOne(m.id())).isEqualTo("retry");
    assertThat(sports.resolveOne(m.id())).isEqualTo("notDue");
    advance(Duration.ofMinutes(5));
    finished(f, 2, 1);
    STUB.clear();
    // 第一次看到完赛：开始等 30 分钟，这一轮不看备源
    assertThat(sports.resolveOne(m.id())).isEqualTo("waiting");
    assertThat(STUB.requests()).extracting(u -> u.getPath()).allMatch(p -> p.startsWith("/fd/"));
    advance(Duration.ofMinutes(29));
    assertThat(sports.resolveOne(m.id())).isEqualTo("notDue");
    advance(Duration.ofMinutes(1));
    String r = sports.resolveOne(m.id());
    if (batch > 0) {
      assertThat(r).isEqualTo("settling");
      settle.drainAll();
    } else {
      assertThat(r).isEqualTo("settled");
    }
    assertThat(status(m)).isEqualTo("SETTLED");
    assertThat(sportsTask(m)).isEqualTo("RESOLVED");
    // T = 150，赢方（大）100：G = 50，F = 2.5，派彩 147.5
    assertThat(available(over.id())).isEqualByComparingTo("347.5");
    assertThat(available(under.id())).isEqualByComparingTo("250");
    settledConserved(m, "150");
    assertThat(jdbc.queryForObject("SELECT resolved_by FROM market_question WHERE id = ?", String.class, m.id())).isEqualTo("AUTO");
    assertThat(jdbc.queryForObject("SELECT actual_start_at FROM market_question WHERE id = ?", java.sql.Timestamp.class, m.id())).isNotNull();
    assertThat(recordKinds(m)).containsExactly("AUTO_RESULT");
    // 主源：进行中、第一次完赛、等完各一次；备源：等完一次
    assertThat(fetchRows(m, "FOOTBALL_DATA")).isEqualTo(3);
    assertThat(fetchRows(m, "THESPORTSDB")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resolve_source_fetch WHERE market_id = ? AND body_sha256 = SHA2(body, 256) AND outcome = 'OK'",
        Integer.class, m.id())).isEqualTo(4);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resolve_source_fetch WHERE market_id = ? AND request_url LIKE '%test-key%'", Integer.class, m.id()))
        .as("存档里没有 key").isZero();
    // 客户端详情：结果、比分、来源
    JsonNode d = getJson("/api/markets/" + m.no(), over.cookie());
    assertThat(d.path("status").asString()).isEqualTo("settled");
    JsonNode res = d.path("resolution");
    assertThat(res.path("mode").asString()).isEqualTo("auto");
    assertThat(res.path("result").asString()).isEqualTo("over");
    assertThat(res.path("resolvedBy").asString()).isEqualTo("AUTO");
    assertThat(res.path("homeScore").asInt()).isEqualTo(2);
    assertThat(res.path("awayScore").asInt()).isEqualTo(1);
    assertThat(res.path("line").asString()).isEqualTo("2.5");
    assertThat(res.path("source").asString()).isEqualTo("football-data.org");
    assertThat(res.path("sources")).hasSize(2);
    assertThat(res.path("sources").get(1).path("source").asString()).isEqualTo("THESPORTSDB");
    assertThat(res.path("sources").get(1).path("sha256").asString()).hasSize(64);
    // 后台裁决依据：体育进度
    ResolveAdminApi.Evidence ev = resolveAdmin.evidence(Long.parseLong(m.no())).orElseThrow();
    assertThat(ev.task()).isNull();
    assertThat(ev.sports().status()).isEqualTo("resolved");
    assertThat(ev.sports().play()).isEqualTo("FOOTBALL_OU");
    assertThat(ev.sports().backupRef()).isEqualTo(f.backupRef());
    assertThat(ev.sports().resultOptionNo()).isEqualTo(m.options().get(0));
    assertThat(sports.resolveOne(m.id())).isEqualTo("resolved");
    assertThat(inbox(over.id(), "SETTLE_WON")).isEqualTo(1);
  }

  @Test
  void 同一场比赛的几个玩法一起处理_每轮每个来源只调一次_每个市场各存一行() throws Exception {
    Fx f = football(in(Duration.ofHours(3)));
    confirm(f);
    List<Market> ms = listedAll(f, new SportsAdminApi.PlayInput("FOOTBALL_1X2", null), new SportsAdminApi.PlayInput("FOOTBALL_OU", "2.5"),
        new SportsAdminApi.PlayInput("FOOTBALL_OU", "3.5"));
    for (Market m : ms) twoSides(m, 0, 1);
    for (Market m : ms) closeAndEnd(m);
    finished(f, 1, 1);
    STUB.clear();
    Map<Long, String> r1 = sports.resolveDue();
    for (Market m : ms) assertThat(r1.get(m.id())).isEqualTo("waiting");
    assertThat(hitsFor(f)).as("三个市场一次调用").isEqualTo(1);
    advance(Duration.ofMinutes(30));
    STUB.clear();
    Map<Long, String> r2 = sports.resolveDue();
    for (Market m : ms) assertThat(r2.get(m.id())).isEqualTo("settled");
    assertThat(hitsFor(f)).as("主源 1 次、备源 1 次").isEqualTo(2);
    for (Market m : ms) {
      assertThat(fetchRows(m, "FOOTBALL_DATA")).isEqualTo(2);
      assertThat(fetchRows(m, "THESPORTSDB")).isEqualTo(1);
    }
    // 1:1 → 胜平负 平；大小 2.5 小；大小 3.5 小
    assertThat(winner(ms.get(0))).isEqualTo(ms.get(0).options().get(1));
    assertThat(winner(ms.get(1))).isEqualTo(ms.get(1).options().get(1));
    assertThat(winner(ms.get(2))).isEqualTo(ms.get(2).options().get(1));
  }

  java.sql.Timestamp nowTs() {
    return java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(Instant.now(clock), java.time.ZoneOffset.UTC));
  }

  String winner(Market m) {
    return jdbc.queryForObject("SELECT CAST(o.public_no AS CHAR) FROM market_question q JOIN market_option o ON o.id = q.winning_option_id WHERE q.id = ?",
        String.class, m.id());
  }

  @Test
  void NBA胜负和大小分_含加时_自动结算() throws Exception {
    Fx f = nba(in(Duration.ofHours(5)));
    confirm(f);
    List<Market> ms = listedAll(f, new SportsAdminApi.PlayInput("NBA_ML", null), new SportsAdminApi.PlayInput("NBA_TOTAL", "229.5"));
    for (Market m : ms) twoSides(m, 0, 1);
    for (Market m : ms) closeAndEnd(m);
    finished(f, 118, 120);
    sports.resolveDue();
    advance(Duration.ofMinutes(30));
    Map<Long, String> done = sports.resolveDue();
    for (Market m : ms) assertThat(done.get(m.id())).isEqualTo("settled");
    assertThat(winner(ms.get(0))).as("客队赢").isEqualTo(ms.get(0).options().get(1));
    assertThat(winner(ms.get(1))).as("238 > 229.5 大").isEqualTo(ms.get(1).options().get(0));
    assertThat(fetchRows(ms.get(0), "BALLDONTLIE")).isEqualTo(2);
  }

  @Test
  void 两源比分不一致_转人工_进裁决台自动裁决失败_告警_不再取数() throws Exception {
    Fx f = football(in(Duration.ofHours(3)));
    confirm(f);
    Market m = listed(f, "FOOTBALL_1X2", null);
    twoSides(m, 0, 2);
    closeAndEnd(m);
    f.primary().finished(2, 1);
    f.backup().finished(2, 2);
    assertThat(sports.resolveOne(m.id())).isEqualTo("waiting");
    advance(Duration.ofMinutes(30));
    assertThat(sports.resolveOne(m.id())).isEqualTo("manual");
    assertThat(status(m)).isEqualTo("PENDING_RESOLUTION");
    assertThat(sportsTask(m)).isEqualTo("MANUAL");
    assertThat(lastReason(m)).contains("比分不一致").contains("2:1").contains("2:2");
    assertThat(recordKinds(m)).containsExactly("AUTO_FAILED");
    String detail = jdbc.queryForObject("SELECT detail FROM resolve_record WHERE market_id = ?", String.class, m.id());
    assertThat(detail).contains("\"source\": \"FOOTBALL_DATA\"").contains("\"source\": \"THESPORTSDB\"").contains("\"homeScore\": 2");
    assertThat(jdbc.queryForObject("SELECT origin FROM resolve_case WHERE market_id = ?", String.class, m.id())).isEqualTo("AUTO_FAILED");
    verify(staff).notifyStaff(eq("SPORTS_AUTO_FAILED"), argThat(p -> m.no().equals(p.get("marketNo")) && p.get("reason").contains("比分不一致")));
    // 裁决台：人按首选来源提议 → 确认 → 公示
    ResolveDeskApi.CaseItem item = DeskCases.find(desk, "auto_failed", m.no()).orElseThrow();
    assertThat(item.origin()).isEqualTo("auto_failed");
    STUB.clear();
    advance(Duration.ofMinutes(10));
    assertThat(sports.resolveDue()).doesNotContainKey(m.id());
    assertThat(hitsFor(f)).isZero();
  }

  @Test
  void 备源拿不到_5分钟重试_等完以后30分钟仍不行转人工() throws Exception {
    Fx f = football(in(Duration.ofHours(3)));
    confirm(f);
    Market m = listed(f, "FOOTBALL_1X2", null);
    twoSides(m, 0, 2);
    closeAndEnd(m);
    finished(f, 0, 1);
    BOOK.fault("THESPORTSDB", Reply.status(503, "Service Unavailable"));
    assertThat(sports.resolveOne(m.id())).isEqualTo("waiting");
    advance(Duration.ofMinutes(30));
    int rounds = 0;
    String r;
    do {
      r = sports.resolveOne(m.id());
      rounds++;
      advance(Duration.ofMinutes(5));
    } while ("retry".equals(r) && rounds < 20);
    assertThat(r).isEqualTo("manual");
    assertThat(rounds).as("等完以后第 0、5 … 30 分钟各一轮").isEqualTo(7);
    assertThat(lastReason(m)).contains("备用来源拿不到").contains("仍不行");
    // 每轮最多 3 次；连续失败 5 次以后熔断，之后每轮只有半开放的 1 次探测（熔断跳过的也存档）
    assertThat(fetchRows(m, "THESPORTSDB")).isBetween(7, 21);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resolve_source_fetch WHERE market_id = ? AND source = 'THESPORTSDB' AND http_status = 503", Integer.class,
        m.id())).isGreaterThanOrEqualTo(5);
  }

  @Test
  void 备源连不上_熔断以后不再打接口_仍按时间转人工() throws Exception {
    Fx f = football(in(Duration.ofHours(3)));
    confirm(f);
    Market m = listed(f, "FOOTBALL_1X2", null);
    twoSides(m, 0, 2);
    closeAndEnd(m);
    finished(f, 3, 0);
    BOOK.fault("THESPORTSDB", Reply.drop());
    sports.resolveOne(m.id());
    advance(Duration.ofMinutes(30));
    sports.resolveOne(m.id()); // 3 次失败
    advance(Duration.ofMinutes(5));
    sports.resolveOne(m.id()); // 第 4、5 次失败 → 熔断打开
    long hits = STUB.requests().stream().filter(u -> u.getPath().startsWith("/tsdb/")).count();
    advance(Duration.ofSeconds(30));
    jdbc.update("UPDATE resolve_sports_task SET next_attempt_at = ? WHERE market_id = ?", nowTs(), m.id());
    sports.resolveOne(m.id());
    assertThat(STUB.requests().stream().filter(u -> u.getPath().startsWith("/tsdb/")).count()).as("熔断中不打接口").isEqualTo(hits);
    assertThat(lastReason(m)).contains("熔断");
  }

  @Test
  void 主源报告延期_转人工_自动标记E6等待_不能提议_48小时没解除系统作废退款() throws Exception {
    Instant kickoff = in(Duration.ofHours(3));
    Fx f = football(kickoff);
    confirm(f);
    Market m = listed(f, "FOOTBALL_1X2", null);
    User[] u = twoSides(m, 0, 2);
    closeAndEnd(m);
    f.primary().status("POSTPONED");
    assertThat(sports.resolveOne(m.id())).isEqualTo("manual_postponed");
    assertThat(jdbc.queryForObject("SELECT hold_kind FROM resolve_case WHERE market_id = ?", String.class, m.id())).isEqualTo("POSTPONED");
    assertThat(jdbc.queryForObject("SELECT hold_until FROM resolve_case WHERE market_id = ?", java.sql.Timestamp.class, m.id()).toLocalDateTime())
        .as("原定开赛后 48 小时").isEqualTo(java.time.LocalDateTime.ofInstant(kickoff.plus(Duration.ofHours(48)), java.time.ZoneOffset.UTC));
    assertThat(jdbc.queryForList("SELECT CONCAT(kind, ' ', actor) FROM resolve_record WHERE market_id = ? ORDER BY id", String.class, m.id()))
        .containsExactly("AUTO_FAILED AUTO", "HOLD SYSTEM");
    verify(staff).notifyStaff(eq("SPORTS_AUTO_FAILED"), argThat(p -> m.no().equals(p.get("marketNo")) && p.get("hold").contains("E6")));
    // 等待期间不能提议
    try {
      desk.propose(Long.parseLong(m.no()), new ResolveDeskApi.ProposalInput(m.options().get(0), null, null, null, List.of("https://example.org/r"), List.of(),
          "x"), 1);
      throw new AssertionError("应该被拒绝");
    } catch (BizException e) {
      assertThat(e.code()).isEqualTo("RESOLVE_ON_HOLD");
    }
    // 到原定开赛后 48 小时还没解除：系统作废、全额退款
    advance(until(kickoff.plus(Duration.ofHours(48))));
    assertThat(publicity.holdsDue()).containsEntry(m.id(), "voided");
    assertThat(status(m)).isEqualTo("VOIDED");
    assertThat(available(u[0].id())).isEqualByComparingTo("100");
    assertThat(available(u[1].id())).isEqualByComparingTo("100");
  }

  @Test
  void 主源报告中断腰斩_E7等待_人工解除后按官方比分提议确认() throws Exception {
    Fx f = football(in(Duration.ofHours(3)));
    confirm(f);
    Market m = listed(f, "FOOTBALL_1X2", null);
    twoSides(m, 0, 2);
    closeAndEnd(m);
    f.primary().status("SUSPENDED");
    assertThat(sports.resolveOne(m.id())).isEqualTo("manual_abandoned");
    assertThat(jdbc.queryForObject("SELECT hold_kind FROM resolve_case WHERE market_id = ?", String.class, m.id())).isEqualTo("ABANDONED");
    desk.release(Long.parseLong(m.no()), "联赛宣布比分有效", 1);
    ResolveDeskApi.CaseDetail d = desk.propose(Long.parseLong(m.no()), new ResolveDeskApi.ProposalInput(m.options().get(0), null, "E7_OFFICIAL", null,
        List.of("https://www.premierleague.com/"), List.of(), "官方宣布比分有效"), 1);
    desk.confirm(Long.parseLong(m.no()), d.summary().proposal().proposalId(), 1, true);
    assertThat(status(m)).isEqualTo("PUBLICIZING");
  }

  @Test
  void 比赛取消_判定结果_加时点球_转人工不标记等待() throws Exception {
    for (String kind : List.of("CANCELLED", "AWARDED", "EXTRA_TIME")) {
      Fx f = football(in(Duration.ofHours(3)));
      confirm(f);
      Market m = listed(f, "FOOTBALL_1X2", null);
      twoSides(m, 0, 2);
      closeAndEnd(m);
      if (kind.equals("EXTRA_TIME")) {
        f.primary().finished(2, 2);
        f.primary().duration = "EXTRA_TIME";
      } else {
        f.primary().status(kind);
      }
      assertThat(sports.resolveOne(m.id())).as(kind).isEqualTo("manual");
      assertThat(jdbc.queryForObject("SELECT hold_kind FROM resolve_case WHERE market_id = ?", String.class, m.id())).isNull();
      assertThat(lastReason(m)).contains(kind.equals("CANCELLED") ? "E5" : kind.equals("AWARDED") ? "判定结果" : "加时或点球");
    }
  }

  @Test
  void E14_数据源报告实际开赛早于截止_开赛后收到的下注全额退回() throws Exception {
    Instant kickoff = in(Duration.ofHours(3));
    Fx f = football(kickoff);
    confirm(f);
    Market m = listed(f, "FOOTBALL_OU", "2.5");
    User over = user("300");
    User under = user("300");
    User late = user("300");
    bet(over.id(), m, 0, "100");
    bet(under.id(), m, 1, "100");
    // 比赛提前到 1 小时后开赛（市场还是原来的截止时间），开赛 10 分钟后还有人下注
    Instant actual = in(Duration.ofHours(1));
    advance(until(actual.plus(Duration.ofMinutes(10))));
    bet(late.id(), m, 0, "50");
    closeAndEnd(m);
    f.primary().kickoff = actual;
    f.backup().kickoff = actual.plus(Duration.ofMinutes(2));
    finished(f, 3, 0);
    sports.resolveOne(m.id());
    advance(Duration.ofMinutes(30));
    assertThat(sports.resolveOne(m.id())).isEqualTo("settled");
    assertThat(jdbc.queryForObject("SELECT actual_start_at FROM market_question WHERE id = ?", java.sql.Timestamp.class, m.id()).toLocalDateTime())
        .isEqualTo(java.time.LocalDateTime.ofInstant(actual, java.time.ZoneOffset.UTC));
    // 开赛后的 50 全额退回；T = 200，赢方（大）100：派彩 100 + 100 − 5 = 195
    assertThat(available(late.id())).isEqualByComparingTo("300");
    assertThat(jdbc.queryForObject("SELECT refund_reason FROM pool_bet WHERE user_id = ? AND market_id = ?", String.class, late.id(), m.id())).isEqualTo("LATE_BET");
    assertThat(available(over.id())).isEqualByComparingTo("395");
    assertThat(available(under.id())).isEqualByComparingTo("200");
  }

  @Test
  void E14_两个来源的实际开赛时间不一致且早于截止_转人工() throws Exception {
    Instant kickoff = in(Duration.ofHours(3));
    Fx f = football(kickoff);
    confirm(f);
    Market m = listed(f, "FOOTBALL_1X2", null);
    twoSides(m, 0, 2);
    closeAndEnd(m);
    f.primary().kickoff = kickoff.minus(Duration.ofHours(1));
    finished(f, 1, 0);
    sports.resolveOne(m.id());
    advance(Duration.ofMinutes(30));
    assertThat(sports.resolveOne(m.id())).isEqualTo("manual");
    assertThat(lastReason(m)).contains("E14");
  }

  @Test
  void 只有一个来源_没确认对应_进入待裁决直接转人工() throws Exception {
    Fx f = football(in(Duration.ofHours(3)));
    Market m = listed(f, "FOOTBALL_1X2", null);
    twoSides(m, 0, 2);
    closeAndEnd(m);
    assertThat(sportsTask(m)).isEqualTo("MANUAL");
    assertThat(lastReason(m)).contains("只有一个来源");
    assertThat(recordKinds(m)).containsExactly("AUTO_FAILED");
    assertThat(jdbc.queryForObject("SELECT origin FROM resolve_case WHERE market_id = ?", String.class, m.id())).isEqualTo("AUTO_FAILED");
    verify(staff, atLeastOnce()).notifyStaff(eq("SPORTS_AUTO_FAILED"), argThat(p -> m.no().equals(p.get("marketNo"))));
  }

  @Test
  void 手工建的足球自动裁决市场_参数不全_直接转人工() throws Exception {
    Market m = shortMarket(3);
    bet(user("100").id(), m, 0, "10");
    bet(user("100").id(), m, 1, "10");
    closeAndEnd(m);
    assertThat(status(m)).isEqualTo("PENDING_RESOLUTION");
    assertThat(sportsTask(m)).isEqualTo("MANUAL");
    assertThat(lastReason(m)).contains("参数").contains("不全");
    assertThat(jdbc.queryForObject("SELECT origin FROM resolve_case WHERE market_id = ?", String.class, m.id())).isEqualTo("AUTO_FAILED");
    verify(staff, atLeastOnce()).notifyStaff(eq("SPORTS_AUTO_FAILED"), argThat(p -> m.no().equals(p.get("marketNo"))));
  }

  @Test
  void 主源未开通_没有key_转人工() throws Exception {
    Fx f = football(in(Duration.ofHours(3)));
    confirm(f);
    Market m = listed(f, "FOOTBALL_1X2", null);
    twoSides(m, 0, 2);
    closeAndEnd(m);
    resolveProps.getSources().get("football-data").setKey("");
    STUB.clear();
    assertThat(sports.resolveOne(m.id())).isEqualTo("manual");
    assertThat(lastReason(m)).contains("未开通").contains("FC_SRC_FOOTBALL_DATA_TOKEN");
    assertThat(STUB.hits()).isZero();
  }

  @Test
  void 主源一直没报告完赛_事件结束6小时后转人工() throws Exception {
    Fx f = football(in(Duration.ofHours(3)));
    confirm(f);
    Market m = listed(f, "FOOTBALL_1X2", null);
    twoSides(m, 0, 2);
    closeAndEnd(m);
    f.primary().status("IN_PLAY");
    assertThat(sports.resolveOne(m.id())).isEqualTo("retry");
    advance(Duration.ofHours(6));
    assertThat(sports.resolveOne(m.id())).isEqualTo("manual");
    assertThat(lastReason(m)).contains("还没有报告完赛").contains("仍不行");
  }

  @Test
  void 市场被人工作废_进度关闭_不再取数() throws Exception {
    Fx f = football(in(Duration.ofHours(3)));
    confirm(f);
    Market m = listed(f, "FOOTBALL_1X2", null);
    twoSides(m, 0, 2);
    closeAndEnd(m);
    desk.voidMarket(Long.parseLong(m.no()), "比赛取消", 1);
    STUB.clear();
    assertThat(sports.resolveOne(m.id())).isEqualTo("closed");
    assertThat(sportsTask(m)).isEqualTo("CLOSED");
    assertThat(STUB.hits()).isZero();
  }

  @Test
  void 结果定了还没结算时进程退出_下一轮接着结算() throws Exception {
    Fx f = football(in(Duration.ofHours(3)));
    confirm(f);
    Market m = listed(f, "FOOTBALL_1X2", null);
    User[] u = twoSides(m, 0, 2);
    closeAndEnd(m);
    jdbc.update("UPDATE resolve_sports_task SET status = 'RESOLVING', result_option_id = ?, next_attempt_at = ? WHERE market_id = ?", optionId(m, 0), nowTs(),
        m.id());
    STUB.clear();
    assertThat(sports.resolveOne(m.id())).isEqualTo("settled");
    assertThat(STUB.hits()).as("结果已定，不再取数").isZero();
    assertThat(available(u[0].id())).isEqualByComparingTo("109.5");
    assertThat(sports.resolveOne(m.id())).isEqualTo("resolved");
  }

  @Test
  void 三个线程同时跑只结算一次() throws Exception {
    Fx f = football(in(Duration.ofHours(3)));
    confirm(f);
    Market m = listed(f, "FOOTBALL_1X2", null);
    User[] u = twoSides(m, 0, 2);
    closeAndEnd(m);
    finished(f, 1, 0);
    sports.resolveOne(m.id());
    advance(Duration.ofMinutes(30));
    ExecutorService ex = Executors.newFixedThreadPool(3);
    try {
      List<Callable<Map<Long, String>>> jobs = new ArrayList<>();
      for (int i = 0; i < 3; i++) jobs.add(() -> sports.resolveDue());
      for (Future<Map<Long, String>> x : ex.invokeAll(jobs)) x.get();
    } finally {
      ex.shutdown();
    }
    assertThat(status(m)).isEqualTo("SETTLED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settle_run WHERE market_id = ? AND kind = 'PAYOUT'", Integer.class, m.id())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resolve_record WHERE market_id = ? AND kind = 'AUTO_RESULT'", Integer.class, m.id())).isEqualTo(1);
    assertThat(available(u[0].id())).isEqualByComparingTo("109.5");
    verify(staff, never()).notifyStaff(eq("SPORTS_AUTO_FAILED"), argThat(p -> m.no().equals(p.get("marketNo"))));
  }

  @Test
  void 平台裁决的足球市场不走自动裁决() throws Exception {
    Fx f = football(in(Duration.ofHours(3)));
    SportsAdminApi.Generated g = sportsAdmin.generate(Long.parseLong(f.fixtureNo()), List.of(new SportsAdminApi.PlayInput("FOOTBALL_1X2", null)), 1);
    long no = Long.parseLong(g.created().get(0).marketNo());
    MarketAdminApi.Detail d = admin.detail(no).orElseThrow();
    admin.updateDraft(no, new MarketAdminApi.DraftInput(d.category(), d.title(), d.rules(), "PLATFORM", d.sourcePrimary(), d.sourceBackup(), d.sourceRef(),
        d.openAt(), d.closeAt(), d.eventStartAt(), d.eventEndAt(), null, 120, null,
        d.options().stream().map(o -> new MarketAdminApi.OptionInput(o.label(), o.winCondition())).toList()), 1);
    admin.publish(no, true, 1);
    Market m = marketOf(String.valueOf(no));
    twoSides(m, 0, 2);
    closeAndEnd(m);
    assertThat(sportsTask(m)).isNull();
    assertThat(jdbc.queryForObject("SELECT origin FROM resolve_case WHERE market_id = ?", String.class, m.id())).isEqualTo("PLATFORM");
    verify(staff, times(0)).notifyStaff(eq("SPORTS_AUTO_FAILED"), argThat(p -> m.no().equals(p.get("marketNo"))));
  }
}
