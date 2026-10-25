package com.forecast.app.resolve.sports;

import com.forecast.app.resolve.source.ScoreFixture;
import com.forecast.app.resolve.source.ScoreSource;
import com.forecast.app.resolve.source.ScoreStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * 体育自动裁决的判定（纯函数，forecast S8；需求说明书 v1.3 第 3.6 节 E5～E7、E14，第 4.3 节"比赛状态为完赛后等 30 分钟，两个来源比分一致则自动结算；
 * 不一致、只有一个来源、状态异常（延期、腰斩）转人工"，RQ-15；技术方案第 6.2 节）。
 * <ol>
 *   <li>{@link #onPrimary}：主源（首选来源）报告的状态 → 等 / 完赛 / 转人工 / 转人工并标记延期（E6）或腰斩（E7）。</li>
 *   <li>{@link #compare}：完赛 30 分钟以后，主源仍是完赛时和备源比较 → 结算 / 等 / 转人工。</li>
 * </ol>
 */
final class SportsDecision {

  private SportsDecision() {}

  enum Kind {
    /** 还不能定：下一轮再看 */
    WAIT,
    /** 主源报告完赛（第一次看到时开始等 30 分钟） */
    FINISHED,
    /** 两个来源一致：按玩法定结果、结算 */
    SETTLE,
    /** 转人工 */
    MANUAL,
    /** 转人工，并标记 E6 延期等待（原定开赛后 48 小时） */
    HOLD_POSTPONED,
    /** 转人工，并标记 E7 腰斩 / 中断等待（48 小时） */
    HOLD_ABANDONED
  }

  /**
   * @param outcome 结算时的结果（HOME / DRAW / AWAY / OVER / UNDER）
   * @param actualStartAt 实际开赛时间（E14，结算时交给 settle：早于截止时间时，之后收到的下注退回）
   */
  record Verdict(Kind kind, String outcome, String reason, Integer homeScore, Integer awayScore, Instant actualStartAt) {

    static Verdict of(Kind k, String reason) {
      return new Verdict(k, null, reason, null, null, null);
    }
  }

  /** 主源报告的状态 */
  static Verdict onPrimary(String sport, ScoreFixture p) {
    return switch (p.status()) {
      case FINISHED -> ScoreSource.FOOTBALL.equals(sport) && !Boolean.TRUE.equals(p.regularTime())
          ? Verdict.of(Kind.MANUAL, "首选来源报告比赛在加时或点球后结束（" + p.rawStatus() + "），90 分钟比分要人工核对")
          : Verdict.of(Kind.FINISHED, "首选来源报告完赛 " + p.homeScore() + ":" + p.awayScore());
      case POSTPONED -> Verdict.of(Kind.HOLD_POSTPONED, "首选来源报告比赛延期（" + p.rawStatus() + "，E6）");
      case SUSPENDED -> Verdict.of(Kind.HOLD_ABANDONED, "首选来源报告比赛中断 / 腰斩（" + p.rawStatus() + "，E7）");
      case CANCELLED -> Verdict.of(Kind.MANUAL, "首选来源报告比赛取消（" + p.rawStatus() + "，E5），应作废退款");
      case AWARDED -> Verdict.of(Kind.MANUAL, "首选来源报告判定结果（" + p.rawStatus() + "），不按场上比分，要人工裁决");
      default -> Verdict.of(Kind.WAIT, "首选来源还没有报告完赛（" + p.rawStatus() + "）");
    };
  }

  /**
   * 完赛 30 分钟以后比较两个来源。
   *
   * @param p 主源（这一轮刚取的，已经是完赛、常规时间结束）
   * @param b 备源（拿不到为 null，原因在 backupError）
   * @param swapped 备源的主客队和主源相反
   * @param closeAt 市场的截止时间（E14）
   * @param tolerance 两个来源的开赛时间相差多少以内算一致
   */
  static Verdict compare(Play play, BigDecimal line, ScoreFixture p, ScoreFixture b, String backupError, boolean swapped, Instant closeAt,
      Duration tolerance) {
    if (b == null) return Verdict.of(Kind.WAIT, "备用来源拿不到：" + backupError);
    if (b.status() != ScoreStatus.FINISHED) {
      if (b.status() == ScoreStatus.POSTPONED || b.status() == ScoreStatus.SUSPENDED || b.status() == ScoreStatus.CANCELLED
          || b.status() == ScoreStatus.AWARDED) {
        return Verdict.of(Kind.MANUAL, "两个来源的状态不一致：首选来源完赛，备用来源 " + b.rawStatus());
      }
      return Verdict.of(Kind.WAIT, "备用来源还没有报告完赛（" + b.rawStatus() + "）");
    }
    if (ScoreSource.FOOTBALL.equals(play.sport) && Boolean.FALSE.equals(b.regularTime())) {
      return Verdict.of(Kind.MANUAL, "备用来源报告比赛在加时或点球后结束（" + b.rawStatus() + "），和首选来源不一致");
    }
    int bh = swapped ? b.awayScore() : b.homeScore();
    int ba = swapped ? b.homeScore() : b.awayScore();
    if (bh != p.homeScore() || ba != p.awayScore()) {
      return Verdict.of(Kind.MANUAL, "两个来源比分不一致：首选 " + p.homeScore() + ":" + p.awayScore() + "，备用 " + bh + ":" + ba);
    }
    // E14：实际开赛时间以数据源为准。任何一个来源的开赛时间早于截止时间，两个来源要一致才自动退回之后的下注，否则转人工确认
    Instant pk = p.kickoffAt();
    Instant bk = b.kickoffAt();
    boolean early = (pk != null && pk.isBefore(closeAt)) || (bk != null && bk.isBefore(closeAt));
    if (early && (pk == null || bk == null || Duration.between(pk, bk).abs().compareTo(tolerance) > 0)) {
      return Verdict.of(Kind.MANUAL, "实际开赛时间可能早于截止时间，两个来源不一致（首选 " + pk + "，备用 " + bk + "），要人工确认（E14）");
    }
    String outcome = outcome(play, line, p.homeScore(), p.awayScore());
    if (outcome == null) return Verdict.of(Kind.MANUAL, "比分 " + p.homeScore() + ":" + p.awayScore() + " 按 " + play.name() + " 定不了结果（NBA 不应平局）");
    return new Verdict(Kind.SETTLE, outcome, "两个来源比分一致 " + p.homeScore() + ":" + p.awayScore() + " → " + outcome, p.homeScore(), p.awayScore(), pk);
  }

  /** 按玩法定结果；定不了返回 null */
  static String outcome(Play play, BigDecimal line, int home, int away) {
    return switch (play) {
      case FOOTBALL_1X2 -> home > away ? "HOME" : home == away ? "DRAW" : "AWAY";
      case NBA_ML -> home > away ? "HOME" : home < away ? "AWAY" : null;
      case FOOTBALL_OU, NBA_TOTAL -> {
        if (line == null) yield null;
        int c = BigDecimal.valueOf(home + away).compareTo(line);
        yield c > 0 ? "OVER" : c < 0 ? "UNDER" : null;
      }
    };
  }
}
