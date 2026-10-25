package com.forecast.app.resolve.source;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 一个体育数据源（技术方案/v1-架构方案.md 第 6.1 节 ScoreSource；forecast S8，AQ-17）：只负责拼请求（地址、请求头、不带 key 的显示地址）和解析，
 * 超时、重试、熔断、限频、存档在 {@link SourceGateway#call} 统一做，判定规则（两个来源比分一致）在 {@code resolve/sports/SportsDecision}。
 * <ul>
 *   <li>足球：主源 {@link FootballData}（免费档 12 个赛事），备源 {@link TheSportsDb}；</li>
 *   <li>NBA：主源 {@link Balldontlie}（免费档），备源 {@link TheSportsDb}。</li>
 * </ul>
 * 主源提供赛程（{@link #schedule}）和单场结果（{@link #fixture}）；备源提供单场结果和"找对应比赛"（{@link TheSportsDb} 自己的方法）。
 */
public interface ScoreSource {

  String FOOTBALL = "FOOTBALL";
  String NBA = "NBA";

  /** 存档、熔断里的名字：FOOTBALL_DATA / BALLDONTLIE / THESPORTSDB */
  String name();

  /** 配置 {@code forecast.resolve.sources.<key>} 的 key，也是限频的单位 */
  String key();

  /** 规则区、详情页显示的来源（网站名） */
  String label();

  Set<String> sports();

  /** 一页赛程 */
  record SchedulePage(List<ScoreFixture> fixtures, String nextCursor) {}

  /**
   * 一段日期（UTC，含首尾）的赛程（主源才有）。
   *
   * @param cursor 翻页（balldontlie），第一页为空
   */
  default SourceRequest<SchedulePage> schedule(ResolveProperties.Source cfg, String sport, LocalDate from, LocalDate to, String cursor) {
    throw new UnsupportedOperationException(name() + " 不提供赛程");
  }

  /** 一场比赛的现状（状态、比分、开赛时间） */
  SourceRequest<ScoreFixture> fixture(ResolveProperties.Source cfg, String sport, String ref);
}
