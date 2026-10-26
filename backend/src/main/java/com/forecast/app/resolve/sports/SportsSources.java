package com.forecast.app.resolve.sports;

import com.forecast.app.resolve.source.Balldontlie;
import com.forecast.app.resolve.source.FootballData;
import com.forecast.app.resolve.source.ScoreFixture;
import com.forecast.app.resolve.source.ScoreSource;
import com.forecast.app.resolve.source.SourceGateway;
import com.forecast.app.resolve.source.SourceReply;
import com.forecast.app.resolve.source.TheSportsDb;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 每个品类用哪两个来源（AQ-17，Amos 已同意；两个来源是不同的网站，需求第 4.3 节）：
 * <ul>
 *   <li>足球：主源 football-data.org（免费档覆盖的 12 个赛事；覆盖不到的联赛没有第二个来源，只能平台裁决），备源 TheSportsDB；</li>
 *   <li>NBA：主源 balldontlie，备源 TheSportsDB。</li>
 * </ul>
 * 第一期不建独立抓取程序、不用 Gemini（AQ-18）：以后加来源时在这里加一个 {@link ScoreSource} 实现、按品类挂上即可（扩展点）。
 */
@Component
class SportsSources {

  private final FootballData footballData;
  private final Balldontlie balldontlie;
  private final TheSportsDb theSportsDb;
  private final SourceGateway gateway;

  SportsSources(FootballData footballData, Balldontlie balldontlie, TheSportsDb theSportsDb, SourceGateway gateway) {
    this.footballData = footballData;
    this.balldontlie = balldontlie;
    this.theSportsDb = theSportsDb;
    this.gateway = gateway;
  }

  static final List<String> SPORTS = List.of(ScoreSource.FOOTBALL, ScoreSource.NBA);

  ScoreSource primary(String sport) {
    return ScoreSource.NBA.equals(sport) ? balldontlie : footballData;
  }

  TheSportsDb backup() {
    return theSportsDb;
  }

  /** 按来源名找（进度里记的是来源名） */
  ScoreSource byName(String name) {
    for (ScoreSource s : List.<ScoreSource>of(footballData, balldontlie, theSportsDb)) if (s.name().equals(name)) return s;
    throw new IllegalArgumentException("不认识的数据源：" + name);
  }

  SourceGateway gateway() {
    return gateway;
  }

  boolean usable(ScoreSource s) {
    return gateway.usable(s.key());
  }

  String unusableReason(ScoreSource s) {
    String r = gateway.config(s.key()).unusableReason();
    return s.label() + (r == null ? "" : " " + r);
  }

  /** 取一场比赛（一轮，网关里有超时、重试、熔断、限频、存档；给 marketIds 里每个市场存一行） */
  SourceReply<ScoreFixture> fixture(ScoreSource s, String sport, String ref, Collection<Long> marketIds, Duration queueWait) {
    return gateway.call(s.fixture(gateway.config(s.key()), sport, ref), marketIds, queueWait);
  }
}
