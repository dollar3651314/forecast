package com.forecast.app.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.resolve.api.ResolveDeskApi;
import com.forecast.app.resolve.api.SportsAdminApi;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * 体育市场的完整流程（forecast S8，后端端到端，替身数据源，两种结算事务边界都跑）：
 * <ol>
 *   <li><b>赛程生成草稿 → 上架 → 下注 → 完赛 → 两个来源一致 → 自动结算</b>（足球大小球 2.5，MQ-10）；</li>
 *   <li><b>比分不一致 → 转人工</b> → 裁决台（自动裁决失败）→ 人按首选来源提议、确认 → 公示 → 期满结算。</li>
 * </ol>
 */
class SportsAutoFlowTest extends SportsTestSupport {

  @ParameterizedTest(name = "batch-size = {0}")
  @MethodSource("modes")
  void 赛程生成草稿_上架_下注_完赛_两源一致_自动结算(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Instant kickoff = in(Duration.ofDays(1));
    // 运营：同步赛程（主源 football-data）→ 系统在备源（TheSportsDB）里找候选 → 运营确认对应
    Fx f = fixture("FOOTBALL", "Liverpool FC", "Chelsea FC", "Liverpool", "Chelsea", kickoff);
    SportsAdminApi.Fixture fx = sportsAdmin.fixture(Long.parseLong(f.fixtureNo())).orElseThrow();
    assertThat(fx.candidates()).as("同步时已经顺便找了候选").isNotEmpty();
    assertThat(fx.candidates().get(0).suggested()).isTrue();
    sportsAdmin.confirmMapping(Long.parseLong(f.fixtureNo()), fx.candidates().get(0).ref(), 1);
    // 一键生成草稿（大小球不填分界线 = 2.5）→ 提交上架 → 有 market:publish 的人勾选检查后上架
    SportsAdminApi.Generated g = sportsAdmin.generate(Long.parseLong(f.fixtureNo()), List.of(new SportsAdminApi.PlayInput("FOOTBALL_OU", null)), 1);
    String no = g.created().get(0).marketNo();
    admin.submit(Long.parseLong(no));
    admin.publish(Long.parseLong(no), true, 1);
    Market m = marketOf(no);
    // 客户看得到、下注：大 60 + 40（A 两笔）、小 100（B）、大 50（C）
    User a = user("500");
    User b = user("500");
    User c = user("500");
    JsonNode card = getJson("/api/markets/" + m.no(), a.cookie());
    assertThat(card.path("status").asString()).isEqualTo("listed");
    assertThat(card.path("category").asString()).isEqualTo("FOOTBALL");
    assertThat(card.path("title").path("zhCN").asString()).isEqualTo("Liverpool FC vs Chelsea FC：总进球大于还是小于 2.5");
    assertThat(card.path("closeAt").asString()).isEqualTo(kickoff.minus(Duration.ofMinutes(5)).toString());
    bet(a.id(), m, 0, "60");
    bet(a.id(), m, 0, "40");
    bet(b.id(), m, 1, "100");
    bet(c.id(), m, 0, "50");
    // 开赛前 5 分钟截止：前 1 毫秒还能押，到点不能
    advance(until(kickoff.minus(Duration.ofMinutes(5)).minusMillis(1)));
    assertThat(tryBet(b.id(), m, 1, "1")).isEqualTo("OK");
    advance(Duration.ofMillis(1));
    assertThat(tryBet(b.id(), m, 1, "1")).isEqualTo("MARKET_CLOSED");
    marketService.closeDue();
    assertThat(status(m)).isEqualTo("CLOSED");
    // 开赛 + 2 小时：进入待裁决，开始看数据源
    advance(until(kickoff.plus(Duration.ofMinutes(120))));
    marketService.endDue();
    assertThat(status(m)).isEqualTo("PENDING_RESOLUTION");
    finished(f, 1, 1);
    assertThat(sports.resolveDue().get(m.id())).as("第一次看到完赛：等 30 分钟").isEqualTo("waiting");
    advance(Duration.ofMinutes(30));
    Map<Long, String> r = sports.resolveDue();
    assertThat(r.get(m.id())).isEqualTo(batch == 0 ? "settled" : "settling");
    if (batch > 0) settle.drainAll();
    assertThat(status(m)).isEqualTo("SETTLED");
    // 1:1 → 小。T = 251，赢方（小）只有 B 的 101：G = 150，F = 7.5，派彩 243.5
    assertThat(available(b.id())).isEqualByComparingTo("642.5");
    assertThat(available(a.id())).isEqualByComparingTo("400");
    assertThat(available(c.id())).isEqualByComparingTo("450");
    settledConserved(m, "251");
    JsonNode d = getJson("/api/markets/" + m.no(), b.cookie());
    assertThat(d.path("winningOptionNo").asString()).isEqualTo(m.options().get(1));
    assertThat(d.path("resolution").path("result").asString()).isEqualTo("under");
    assertThat(d.path("resolution").path("home").asString()).isEqualTo("Liverpool FC");
    assertThat(d.path("resolution").path("homeScore").asInt()).isEqualTo(1);
    assertThat(d.path("resolution").path("source").asString()).isEqualTo("football-data.org");
    assertThat(inbox(b.id(), "SETTLE_WON")).isEqualTo(1);
    assertThat(inbox(a.id(), "SETTLE_LOST")).isEqualTo(1);
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @MethodSource("modes")
  void 比分不一致_转人工_裁决台按首选来源提议确认_公示期满结算(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Instant kickoff = in(Duration.ofDays(1));
    Fx f = nba(kickoff);
    confirm(f);
    Market m = listed(f, "NBA_ML", null);
    User[] u = twoSides(m, 0, 1);
    closeAndEnd(m);
    f.primary().finished(110, 104);
    f.backup().finished(110, 106);
    sports.resolveDue();
    advance(Duration.ofMinutes(30));
    assertThat(sports.resolveDue().get(m.id())).isEqualTo("manual");
    assertThat(status(m)).isEqualTo("PENDING_RESOLUTION");
    // 裁决台：自动裁决失败 → 按首选来源（主队赢）提议 → 确认 → 公示
    ResolveDeskApi.CaseItem item = DeskCases.find(desk, "auto_failed", m.no()).orElseThrow();
    assertThat(item.origin()).isEqualTo("auto_failed");
    ResolveDeskApi.CaseDetail cd = desk.propose(Long.parseLong(m.no()), new ResolveDeskApi.ProposalInput(m.options().get(0), null, null, null,
        List.of("https://www.nba.com/game"), List.of(), "按首选来源 balldontlie 的终场比分 110:104"), 1);
    desk.confirm(Long.parseLong(m.no()), cd.summary().proposal().proposalId(), 1, true);
    assertThat(status(m)).isEqualTo("PUBLICIZING");
    // 公示期满（体育人工裁决 2 小时）没有争议 → 结算
    advance(Duration.ofMinutes(121));
    publicity.settleDue();
    if (batch > 0) settle.drainAll();
    assertThat(status(m)).isEqualTo("SETTLED");
    assertThat(available(u[0].id())).isEqualByComparingTo("109.5");
    assertThat(available(u[1].id())).isEqualByComparingTo("90");
    assertThat(recordKinds(m)).containsExactly("AUTO_FAILED", "PROPOSE", "CONFIRM", "PUBLICITY_END");
  }
}
