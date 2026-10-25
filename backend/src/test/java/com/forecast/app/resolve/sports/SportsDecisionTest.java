package com.forecast.app.resolve.sports;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.resolve.source.ScoreFixture;
import com.forecast.app.resolve.source.ScoreStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 体育自动裁决的判定（纯函数，forecast S8；需求说明书 v1.3 第 3.6 节 E5～E8、E14，第 4.3 节；RQ-15）。
 */
class SportsDecisionTest {

  static final Instant KICK = Instant.parse("2026-10-24T14:00:00Z");
  static final Instant CLOSE = KICK.minus(Duration.ofMinutes(5));
  static final Duration TOL = Duration.ofMinutes(15);

  static ScoreFixture fx(String source, String sport, ScoreStatus st, String raw, Boolean regular, Integer h, Integer a, Instant kickoff) {
    return new ScoreFixture(source, "1", sport, null, null, null, null, "Home", null, null, "Away", null, kickoff, st, raw, regular, h, a);
  }

  static ScoreFixture p(int h, int a) {
    return fx("FOOTBALL_DATA", "FOOTBALL", ScoreStatus.FINISHED, "FINISHED", true, h, a, KICK);
  }

  static ScoreFixture b(int h, int a) {
    return fx("THESPORTSDB", "FOOTBALL", ScoreStatus.FINISHED, "FT", true, h, a, KICK);
  }

  @Test
  void 主源状态_等_完赛_转人工_延期腰斩标记() {
    assertThat(SportsDecision.onPrimary("FOOTBALL", p(2, 1)).kind()).isEqualTo(SportsDecision.Kind.FINISHED);
    assertThat(SportsDecision.onPrimary("FOOTBALL", fx("FD", "FOOTBALL", ScoreStatus.LIVE, "IN_PLAY", null, null, null, KICK)).kind())
        .isEqualTo(SportsDecision.Kind.WAIT);
    assertThat(SportsDecision.onPrimary("FOOTBALL", fx("FD", "FOOTBALL", ScoreStatus.SCHEDULED, "TIMED", null, null, null, KICK)).kind())
        .isEqualTo(SportsDecision.Kind.WAIT);
    assertThat(SportsDecision.onPrimary("FOOTBALL", fx("FD", "FOOTBALL", ScoreStatus.UNKNOWN, "NEW", null, null, null, KICK)).kind())
        .isEqualTo(SportsDecision.Kind.WAIT);
    SportsDecision.Verdict pst = SportsDecision.onPrimary("FOOTBALL", fx("FD", "FOOTBALL", ScoreStatus.POSTPONED, "POSTPONED", null, null, null, KICK));
    assertThat(pst.kind()).isEqualTo(SportsDecision.Kind.HOLD_POSTPONED);
    assertThat(pst.reason()).contains("E6");
    assertThat(SportsDecision.onPrimary("NBA", fx("BDL", "NBA", ScoreStatus.SUSPENDED, "Suspended", null, null, null, KICK)).kind())
        .isEqualTo(SportsDecision.Kind.HOLD_ABANDONED);
    SportsDecision.Verdict canc = SportsDecision.onPrimary("FOOTBALL", fx("FD", "FOOTBALL", ScoreStatus.CANCELLED, "CANCELLED", null, null, null, KICK));
    assertThat(canc.kind()).isEqualTo(SportsDecision.Kind.MANUAL);
    assertThat(canc.reason()).contains("E5");
    assertThat(SportsDecision.onPrimary("FOOTBALL", fx("FD", "FOOTBALL", ScoreStatus.AWARDED, "AWARDED", null, 3, 0, KICK)).kind())
        .isEqualTo(SportsDecision.Kind.MANUAL);
    // 足球加时、点球结束：90 分钟比分要人工核对；不知道是不是常规时间的也转人工
    assertThat(SportsDecision.onPrimary("FOOTBALL", fx("FD", "FOOTBALL", ScoreStatus.FINISHED, "FINISHED", false, 3, 2, KICK)).kind())
        .isEqualTo(SportsDecision.Kind.MANUAL);
    assertThat(SportsDecision.onPrimary("FOOTBALL", fx("FD", "FOOTBALL", ScoreStatus.FINISHED, "FINISHED", null, 3, 2, KICK)).kind())
        .isEqualTo(SportsDecision.Kind.MANUAL);
    // NBA 含加时，不看 regularTime
    assertThat(SportsDecision.onPrimary("NBA", fx("BDL", "NBA", ScoreStatus.FINISHED, "Final", null, 120, 118, KICK)).kind())
        .isEqualTo(SportsDecision.Kind.FINISHED);
  }

  @Test
  void 两源比分一致_按玩法定结果() {
    SportsDecision.Verdict v = SportsDecision.compare(Play.FOOTBALL_1X2, null, p(2, 1), b(2, 1), null, false, CLOSE, TOL);
    assertThat(v.kind()).isEqualTo(SportsDecision.Kind.SETTLE);
    assertThat(v.outcome()).isEqualTo("HOME");
    assertThat(v.actualStartAt()).isEqualTo(KICK);
    assertThat(SportsDecision.compare(Play.FOOTBALL_1X2, null, p(1, 1), b(1, 1), null, false, CLOSE, TOL).outcome()).isEqualTo("DRAW");
    assertThat(SportsDecision.compare(Play.FOOTBALL_1X2, null, p(0, 3), b(0, 3), null, false, CLOSE, TOL).outcome()).isEqualTo("AWAY");
    BigDecimal l25 = new BigDecimal("2.5");
    assertThat(SportsDecision.compare(Play.FOOTBALL_OU, l25, p(2, 1), b(2, 1), null, false, CLOSE, TOL).outcome()).isEqualTo("OVER");
    assertThat(SportsDecision.compare(Play.FOOTBALL_OU, l25, p(1, 1), b(1, 1), null, false, CLOSE, TOL).outcome()).isEqualTo("UNDER");
    assertThat(SportsDecision.compare(Play.FOOTBALL_OU, l25, p(0, 0), b(0, 0), null, false, CLOSE, TOL).outcome()).isEqualTo("UNDER");
    ScoreFixture np = fx("BALLDONTLIE", "NBA", ScoreStatus.FINISHED, "Final", null, 118, 112, KICK);
    ScoreFixture nb = fx("THESPORTSDB", "NBA", ScoreStatus.FINISHED, "FT", null, 118, 112, KICK);
    assertThat(SportsDecision.compare(Play.NBA_ML, null, np, nb, null, false, CLOSE, TOL).outcome()).isEqualTo("HOME");
    assertThat(SportsDecision.compare(Play.NBA_TOTAL, new BigDecimal("229.5"), np, nb, null, false, CLOSE, TOL).outcome()).isEqualTo("OVER");
    assertThat(SportsDecision.compare(Play.NBA_TOTAL, new BigDecimal("230.5"), np, nb, null, false, CLOSE, TOL).outcome()).isEqualTo("UNDER");
  }

  @Test
  void 备源主客对调时比分也对调() {
    assertThat(SportsDecision.compare(Play.FOOTBALL_1X2, null, p(2, 1), b(1, 2), null, true, CLOSE, TOL).outcome()).isEqualTo("HOME");
    assertThat(SportsDecision.compare(Play.FOOTBALL_1X2, null, p(2, 1), b(2, 1), null, true, CLOSE, TOL).kind()).isEqualTo(SportsDecision.Kind.MANUAL);
  }

  @Test
  void 比分不一致_状态不一致_转人工() {
    SportsDecision.Verdict v = SportsDecision.compare(Play.FOOTBALL_OU, new BigDecimal("2.5"), p(2, 1), b(2, 0), null, false, CLOSE, TOL);
    assertThat(v.kind()).isEqualTo(SportsDecision.Kind.MANUAL);
    assertThat(v.reason()).contains("比分不一致").contains("2:1").contains("2:0");
    ScoreFixture pst = fx("THESPORTSDB", "FOOTBALL", ScoreStatus.POSTPONED, "PST", null, null, null, KICK);
    assertThat(SportsDecision.compare(Play.FOOTBALL_1X2, null, p(2, 1), pst, null, false, CLOSE, TOL).reason()).contains("状态不一致");
    ScoreFixture aet = fx("THESPORTSDB", "FOOTBALL", ScoreStatus.FINISHED, "AET", false, 2, 1, KICK);
    assertThat(SportsDecision.compare(Play.FOOTBALL_1X2, null, p(2, 1), aet, null, false, CLOSE, TOL).kind()).isEqualTo(SportsDecision.Kind.MANUAL);
    // NBA 不应平局
    ScoreFixture tie = fx("BALLDONTLIE", "NBA", ScoreStatus.FINISHED, "Final", null, 100, 100, KICK);
    assertThat(SportsDecision.compare(Play.NBA_ML, null, tie, fx("THESPORTSDB", "NBA", ScoreStatus.FINISHED, "FT", null, 100, 100, KICK), null, false, CLOSE, TOL)
        .kind()).isEqualTo(SportsDecision.Kind.MANUAL);
  }

  @Test
  void 备源拿不到或还没完赛_等() {
    SportsDecision.Verdict none = SportsDecision.compare(Play.FOOTBALL_1X2, null, p(2, 1), null, "HTTP 503", false, CLOSE, TOL);
    assertThat(none.kind()).isEqualTo(SportsDecision.Kind.WAIT);
    assertThat(none.reason()).contains("HTTP 503");
    ScoreFixture live = fx("THESPORTSDB", "FOOTBALL", ScoreStatus.LIVE, "2H", null, null, null, KICK);
    assertThat(SportsDecision.compare(Play.FOOTBALL_1X2, null, p(2, 1), live, null, false, CLOSE, TOL).kind()).isEqualTo(SportsDecision.Kind.WAIT);
  }

  @Test
  void E14_实际开赛早于截止_两源一致才交给结算_不一致转人工() {
    Instant early = CLOSE.minus(Duration.ofMinutes(30));
    ScoreFixture pe = fx("FOOTBALL_DATA", "FOOTBALL", ScoreStatus.FINISHED, "FINISHED", true, 2, 1, early);
    ScoreFixture be = fx("THESPORTSDB", "FOOTBALL", ScoreStatus.FINISHED, "FT", true, 2, 1, early.plus(Duration.ofMinutes(10)));
    SportsDecision.Verdict ok = SportsDecision.compare(Play.FOOTBALL_1X2, null, pe, be, null, false, CLOSE, TOL);
    assertThat(ok.kind()).isEqualTo(SportsDecision.Kind.SETTLE);
    assertThat(ok.actualStartAt()).as("按首选来源").isEqualTo(early);
    SportsDecision.Verdict bad = SportsDecision.compare(Play.FOOTBALL_1X2, null, pe, b(2, 1), null, false, CLOSE, TOL);
    assertThat(bad.kind()).isEqualTo(SportsDecision.Kind.MANUAL);
    assertThat(bad.reason()).contains("E14");
    // 开赛在截止之后：两源时间差不影响
    ScoreFixture late = fx("THESPORTSDB", "FOOTBALL", ScoreStatus.FINISHED, "FT", true, 2, 1, KICK.plus(Duration.ofHours(2)));
    assertThat(SportsDecision.compare(Play.FOOTBALL_1X2, null, p(2, 1), late, null, false, CLOSE, TOL).kind()).isEqualTo(SportsDecision.Kind.SETTLE);
  }

  @Test
  void 分界线必须是点5() {
    assertThat(Play.validLine(new BigDecimal("2.5"))).isTrue();
    assertThat(Play.validLine(new BigDecimal("229.5"))).isTrue();
    assertThat(Play.validLine(new BigDecimal("0.5"))).isTrue();
    assertThat(Play.validLine(new BigDecimal("3"))).isFalse();
    assertThat(Play.validLine(new BigDecimal("2.25"))).isFalse();
    assertThat(Play.validLine(new BigDecimal("-1.5"))).isFalse();
    assertThat(Play.validLine(new BigDecimal("1000.5"))).isFalse();
    assertThat(Play.of("football_ou")).isEqualTo(Play.FOOTBALL_OU);
    assertThat(Play.of("x")).isNull();
  }
}
