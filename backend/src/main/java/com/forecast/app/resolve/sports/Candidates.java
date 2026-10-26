package com.forecast.app.resolve.sports;

import com.forecast.app.resolve.source.ScoreFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 备源候选的打分（forecast S8，X-12"按队名 + 开赛时间建议候选，由人确认"）。纯函数，方便测试。
 * <p>分数 = 队名相似度（主队、客队的平均；主客对调时也算一次，取高的并标记 {@code swapped}）× 开赛时间系数（相差 ≤ 15 分钟 1、≤ 3 小时 0.85、≤ 36 小时 0.6、更远 0.3）。
 * 最高分 ≥ 0.75 且开赛时间相差不超过 3 小时的标为"推荐"（{@code suggested}）——只是推荐，不会自动确认。
 */
final class Candidates {

  static final double SUGGEST_MIN = 0.75;
  static final Duration SUGGEST_MAX_DIFF = Duration.ofHours(3);
  static final int KEEP = 5;

  private Candidates() {}

  /** 一个候选（存进 resolve_fixture.candidates，JSON） */
  record Candidate(String source, String ref, String home, String away, String kickoffAt, String league, double score, boolean swapped, boolean suggested) {}

  /** 赛程那一场（主源） */
  record Target(String home, String homeShort, String away, String awayShort, Instant kickoffAt) {}

  static Candidate score(Target t, ScoreFixture c) {
    double hh = TeamNames.best(c.homeName(), t.home(), t.homeShort());
    double aa = TeamNames.best(c.awayName(), t.away(), t.awayShort());
    double ha = TeamNames.best(c.awayName(), t.home(), t.homeShort());
    double ah = TeamNames.best(c.homeName(), t.away(), t.awayShort());
    double straight = (hh + aa) / 2;
    double swapped = (ha + ah) / 2;
    boolean isSwapped = swapped > straight + 0.05;
    double names = Math.max(straight, swapped);
    double s = Math.round(names * timeFactor(t.kickoffAt(), c.kickoffAt()) * 1000) / 1000.0;
    return new Candidate(c.source(), c.ref(), c.homeName(), c.awayName(), c.kickoffAt() == null ? null : c.kickoffAt().toString(), c.competitionName(), s,
        isSwapped, false);
  }

  static double timeFactor(Instant a, Instant b) {
    if (a == null || b == null) return 0.3;
    Duration d = Duration.between(a, b).abs();
    if (d.compareTo(Duration.ofMinutes(15)) <= 0) return 1;
    if (d.compareTo(Duration.ofHours(3)) <= 0) return 0.85;
    if (d.compareTo(Duration.ofHours(36)) <= 0) return 0.6;
    return 0.3;
  }

  /** 去重（同一个编号留分高的）、按分数排序、留前 5 个、标出推荐 */
  static List<Candidate> rank(Target t, List<Candidate> all) {
    List<Candidate> uniq = new ArrayList<>();
    for (Candidate c : all.stream().sorted(Comparator.comparingDouble(Candidate::score).reversed()).toList()) {
      if (uniq.stream().noneMatch(u -> u.ref().equals(c.ref()))) uniq.add(c);
    }
    List<Candidate> top = uniq.subList(0, Math.min(KEEP, uniq.size()));
    List<Candidate> out = new ArrayList<>();
    for (int i = 0; i < top.size(); i++) {
      Candidate c = top.get(i);
      boolean near = c.kickoffAt() != null && t.kickoffAt() != null
          && Duration.between(t.kickoffAt(), Instant.parse(c.kickoffAt())).abs().compareTo(SUGGEST_MAX_DIFF) <= 0;
      boolean suggested = i == 0 && c.score() >= SUGGEST_MIN && near && (top.size() == 1 || top.get(1).score() < c.score());
      out.add(new Candidate(c.source(), c.ref(), c.home(), c.away(), c.kickoffAt(), c.league(), c.score(), c.swapped(), suggested));
    }
    return out;
  }
}
