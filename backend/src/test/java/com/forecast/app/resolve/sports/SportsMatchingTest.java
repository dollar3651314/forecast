package com.forecast.app.resolve.sports;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.resolve.source.ScoreFixture;
import com.forecast.app.resolve.source.ScoreStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 两个来源的比赛对应：队名比较和候选打分（forecast S8，X-12）。只是建议，最后由人确认。名字取自两家的真实叫法（football-data / TheSportsDB）。
 */
class SportsMatchingTest {

  @Test
  void 队名规范化和相似度() {
    assertThat(TeamNames.normalize("Arsenal FC")).isEqualTo("arsenal");
    assertThat(TeamNames.normalize("FC Bayern München")).isEqualTo("bayern munchen");
    assertThat(TeamNames.normalize("Bayern Munich")).isEqualTo("bayern munchen");
    assertThat(TeamNames.normalize("Brighton & Hove Albion FC")).isEqualTo("brighton");
    assertThat(TeamNames.normalize("TSG 1899 Hoffenheim")).isEqualTo("hoffenheim");
    assertThat(TeamNames.similarity("Manchester United FC", "Manchester United")).isEqualTo(1.0);
    assertThat(TeamNames.similarity("Man United", "Manchester United")).isEqualTo(1.0);
    assertThat(TeamNames.similarity("FC Internazionale Milano", "Inter Milan")).isEqualTo(1.0);
    assertThat(TeamNames.similarity("Club Atlético de Madrid", "Atletico Madrid")).isEqualTo(1.0);
    assertThat(TeamNames.similarity("Tottenham Hotspur FC", "Tottenham Hotspur")).isEqualTo(1.0);
    assertThat(TeamNames.similarity("Los Angeles Lakers", "Los Angeles Clippers")).isLessThan(0.75);
    assertThat(TeamNames.similarity("Manchester City FC", "Manchester United")).isLessThan(0.75);
    assertThat(TeamNames.similarity("AC Milan", "Inter Milan")).isLessThan(0.5);
    assertThat(TeamNames.display("Arsenal FC")).isEqualTo("Arsenal");
    assertThat(TeamNames.display("FC Bayern München")).isEqualTo("Bayern München");
  }

  static ScoreFixture ts(String ref, String home, String away, Instant at) {
    return new ScoreFixture("THESPORTSDB", ref, "FOOTBALL", null, "English Premier League", "4328", null, home, null, null, away, null, at, ScoreStatus.SCHEDULED,
        "NS", null, null, null);
  }

  @Test
  void 候选打分_同一场推荐_主客对调标出_别的日期和别的队分低() {
    Instant k = Instant.parse("2026-10-25T14:00:00Z");
    Candidates.Target t = new Candidates.Target("Manchester United FC", "Man United", "AFC Bournemouth", "Bournemouth", k);
    Candidates.Candidate same = Candidates.score(t, ts("2494070", "Manchester United", "Bournemouth", k));
    assertThat(same.score()).isEqualTo(1.0);
    assertThat(same.swapped()).isFalse();
    Candidates.Candidate swapped = Candidates.score(t, ts("9", "Bournemouth", "Manchester United", k.plus(Duration.ofHours(1))));
    assertThat(swapped.swapped()).isTrue();
    assertThat(swapped.score()).isEqualTo(0.85);
    Candidates.Candidate other = Candidates.score(t, ts("8", "Sunderland", "Leeds United", Instant.parse("2026-10-25T16:30:00Z")));
    assertThat(other.score()).isLessThan(0.5);
    Candidates.Candidate secondLeg = Candidates.score(t, ts("7", "Manchester United", "Bournemouth", Instant.parse("2027-04-10T14:00:00Z")));
    assertThat(secondLeg.score()).isLessThan(0.5);
    List<Candidates.Candidate> ranked = Candidates.rank(t, List.of(other, secondLeg, same, swapped, same));
    assertThat(ranked).extracting(Candidates.Candidate::ref).containsExactly("2494070", "9", "7", "8");
    assertThat(ranked.get(0).suggested()).isTrue();
    assertThat(ranked.stream().filter(Candidates.Candidate::suggested)).hasSize(1);
  }

  @Test
  void 开赛时间差太多或分数不够不推荐() {
    Instant k = Instant.parse("2026-10-25T14:00:00Z");
    Candidates.Target t = new Candidates.Target("Arsenal FC", "Arsenal", "Everton FC", "Everton", k);
    List<Candidates.Candidate> far = Candidates.rank(t, List.of(Candidates.score(t, ts("1", "Arsenal", "Everton", k.plus(Duration.ofHours(5))))));
    assertThat(far.get(0).suggested()).as("相差 5 小时").isFalse();
    List<Candidates.Candidate> near = Candidates.rank(t, List.of(Candidates.score(t, ts("1", "Arsenal", "Everton", k.plus(Duration.ofHours(1))))));
    assertThat(near.get(0).score()).isEqualTo(0.85);
    assertThat(near.get(0).suggested()).isTrue();
    List<Candidates.Candidate> weak = Candidates.rank(t, List.of(Candidates.score(t, ts("1", "Arsenal", "Chelsea", k))));
    assertThat(weak.get(0).suggested()).isFalse();
  }
}
