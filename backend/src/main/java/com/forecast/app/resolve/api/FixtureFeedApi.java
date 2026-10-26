package com.forecast.app.resolve.api;

import java.time.Instant;
import java.util.List;

/**
 * 赛程（只读，forecast S15）：自动化程序的"事件候选"和焦点战建议用（自动化需求第 3.1 节"体育赛程（已有，增强）"）。
 * 生成市场草稿仍是 S8 的后台"体育赛程 → 生成草稿"（运营确认两个来源的对应后操作），自动化程序不能生成、不能确认对应。
 */
public interface FixtureFeedApi {

  /**
   * 一场比赛。
   *
   * @param sport FOOTBALL / NBA
   * @param competition 联赛代码（football-data 的 PL、CL……；NBA 是 NBA）
   * @param mappingConfirmed 两个来源的对应已经由运营确认
   * @param markets 从这场比赛生成过的市场数（含草稿）
   */
  record Item(String fixtureNo, String sport, String competition, String competitionName, String home, String away, Instant kickoffAt, String status,
      boolean mappingConfirmed, int markets) {}

  /** 开赛时间在 [from, to) 的比赛，按开赛时间，最多 max 场 */
  List<Item> upcoming(Instant from, Instant to, int max);
}
