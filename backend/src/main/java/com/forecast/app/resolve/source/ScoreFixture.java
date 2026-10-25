package com.forecast.app.resolve.source;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个体育数据源里的一场比赛（forecast S8，三个适配器解析后的统一格式）。
 *
 * @param source 来源名（FOOTBALL_DATA / BALLDONTLIE / THESPORTSDB）
 * @param ref 这个来源里的比赛编号
 * @param sport FOOTBALL / NBA
 * @param competitionCode 赛事代码（football-data 的 PL、CL…；NBA 为 NBA；TheSportsDB 为空）
 * @param leagueRef TheSportsDB 的联赛编号（例如 4328 英超），别的来源为空
 * @param kickoffAt 开赛时间（UTC；数据源报告的，比赛改期后会变）
 * @param rawStatus 数据源的原始状态（存档、裁决台显示）
 * @param regularTime 足球：完赛时是不是在常规时间（90 分钟 + 补时）结束；加时、点球为 false；不知道为 null。NBA 不用（比分含加时）
 * @param homeScore 主队比分（完赛时一定有；没有为 null）
 */
public record ScoreFixture(String source, String ref, String sport, String competitionCode, String competitionName, String leagueRef, String homeRef,
    String homeName, String homeShort, String awayRef, String awayName, String awayShort, Instant kickoffAt, ScoreStatus status, String rawStatus,
    Boolean regularTime, Integer homeScore, Integer awayScore) {

  public boolean finished() {
    return status == ScoreStatus.FINISHED;
  }

  /** 存档里的 parsed、裁决记录里的依据 */
  public Map<String, Object> toMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("ref", ref);
    m.put("sport", sport);
    m.put("competition", competitionCode != null ? competitionCode : competitionName);
    m.put("home", homeName);
    m.put("away", awayName);
    m.put("kickoffAt", kickoffAt == null ? null : kickoffAt.toString());
    m.put("status", status.name());
    m.put("rawStatus", rawStatus);
    m.put("regularTime", regularTime);
    m.put("homeScore", homeScore);
    m.put("awayScore", awayScore);
    return m;
  }
}
