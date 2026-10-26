package com.forecast.app.resolve.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 三个体育适配器的请求和解析（forecast S8；技术方案第 6.1 节；AQ-17）。返回原文按各家真实接口的格式写：TheSportsDB 的样本是 2026-10-25 用公开免费 key 实测拿到的原文（节选），
 * football-data、balldontlie 按官方文档（没有 key，还没实测，见 RealScoreSourcesSmokeTest）。
 */
class ScoreSourceParseTest {

  static final ResolveProperties.Source FD = cfg("https://api.football-data.org/", "fd-token");
  static final ResolveProperties.Source BDL = cfg("https://api.balldontlie.io", "bdl-key");
  static final ResolveProperties.Source TS = cfg("https://www.thesportsdb.com", "ts-key");

  static ResolveProperties.Source cfg(String base, String key) {
    ResolveProperties.Source s = new ResolveProperties.Source(true, base);
    s.setKey(key);
    s.setKeyRequired(true);
    return s;
  }

  // ---------------- 请求 ----------------

  @Test
  void 请求地址和认证_存档用的地址不带key() {
    SourceRequest<ScoreSource.SchedulePage> fdList = new FootballData().schedule(FD, "FOOTBALL", LocalDate.parse("2026-10-25"), LocalDate.parse("2026-11-01"), null);
    assertThat(fdList.uri().toString()).isEqualTo("https://api.football-data.org/v4/matches?dateFrom=2026-10-25&dateTo=2026-11-01");
    assertThat(fdList.headers()).containsEntry("X-Auth-Token", "fd-token");
    assertThat(fdList.displayUrl()).doesNotContain("fd-token");
    assertThat(new FootballData().fixture(FD, "FOOTBALL", "537877").uri().toString()).isEqualTo("https://api.football-data.org/v4/matches/537877");

    SourceRequest<ScoreSource.SchedulePage> bdl = new Balldontlie().schedule(BDL, "NBA", LocalDate.parse("2026-10-25"), LocalDate.parse("2026-11-01"), "77");
    assertThat(bdl.uri().toString()).isEqualTo("https://api.balldontlie.io/v1/games?start_date=2026-10-25&end_date=2026-11-01&per_page=100&cursor=77");
    assertThat(bdl.headers()).containsEntry("Authorization", "bdl-key");
    assertThat(bdl.displayUrl()).doesNotContain("bdl-key");
    assertThat(new Balldontlie().fixture(BDL, "NBA", "15907925").uri().getPath()).isEqualTo("/v1/games/15907925");

    TheSportsDb ts = new TheSportsDb();
    SourceRequest<ScoreFixture> look = ts.fixture(TS, "FOOTBALL", "2494072");
    assertThat(look.uri().toString()).isEqualTo("https://www.thesportsdb.com/api/v1/json/ts-key/lookupevent.php?id=2494072");
    assertThat(look.displayUrl()).isEqualTo("https://www.thesportsdb.com/api/v1/json/{key}/lookupevent.php?id=2494072").doesNotContain("ts-key");
    assertThat(look.headers()).isEmpty();
    assertThat(ts.searchByNames(TS, "FOOTBALL", "Manchester United", "Bournemouth", LocalDate.parse("2026-10-25")).displayUrl())
        .endsWith("/{key}/searchevents.php?e=Manchester_United_vs_Bournemouth&d=2026-10-25");
    assertThat(ts.searchByNames(TS, "FOOTBALL", "Atlético Madrid", "Real Betis", LocalDate.parse("2026-10-25")).uri().toString())
        .contains("e=Atl%C3%A9tico_Madrid_vs_Real_Betis");
    assertThat(ts.day(TS, "FOOTBALL", "4328", LocalDate.parse("2026-10-24")).displayUrl()).endsWith("/{key}/eventsday.php?d=2026-10-24&l=4328");
    // 编号拼进地址：只能是字母、数字、减号
    assertThatThrownBy(() -> ts.fixture(TS, "FOOTBALL", "1&id=2")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FootballData().fixture(FD, "FOOTBALL", "../competitions")).isInstanceOf(IllegalArgumentException.class);
  }

  // ---------------- football-data ----------------

  static String fdMatch(String id, String status, String duration, String home, String away) {
    return "{\"area\":{\"id\":2072,\"name\":\"England\"},\"competition\":{\"id\":2021,\"name\":\"Premier League\",\"code\":\"PL\",\"type\":\"LEAGUE\"},"
        + "\"season\":{\"id\":2403},\"id\":" + id + ",\"utcDate\":\"2026-10-24T14:00:00Z\",\"status\":\"" + status + "\",\"matchday\":8,\"stage\":\"REGULAR_SEASON\","
        + "\"lastUpdated\":\"2026-10-24T16:00:12Z\",\"homeTeam\":{\"id\":57,\"name\":\"Arsenal FC\",\"shortName\":\"Arsenal\",\"tla\":\"ARS\"},"
        + "\"awayTeam\":{\"id\":62,\"name\":\"Everton FC\",\"shortName\":\"Everton\",\"tla\":\"EVE\"},"
        + "\"score\":{\"winner\":\"HOME_TEAM\",\"duration\":\"" + duration + "\",\"fullTime\":{\"home\":" + home + ",\"away\":" + away + "},"
        + "\"halfTime\":{\"home\":1,\"away\":0}},\"odds\":{\"msg\":\"Activate Odds-Package in User-Panel to retrieve odds.\"},\"referees\":[]}";
  }

  @Test
  void footballData_赛程和单场() throws Exception {
    String tbd = "{\"id\":3,\"utcDate\":\"2026-10-30T19:00:00Z\",\"status\":\"TIMED\",\"competition\":{\"code\":\"CL\",\"name\":\"UEFA Champions League\"},"
        + "\"homeTeam\":{\"id\":null,\"name\":null},\"awayTeam\":{\"id\":null,\"name\":null},\"score\":{\"duration\":\"REGULAR\",\"fullTime\":{\"home\":null,\"away\":null}}}";
    String list = "{\"filters\":{},\"resultSet\":{\"count\":3},\"matches\":[" + fdMatch("1", "FINISHED", "REGULAR", "2", "0") + ","
        + fdMatch("2", "TIMED", "REGULAR", "null", "null") + "," + tbd + "]}";
    ScoreSource.SchedulePage p = FootballData.parseList(list);
    assertThat(p.fixtures()).as("对阵还没定的跳过").hasSize(2);
    ScoreFixture f = p.fixtures().get(0);
    assertThat(f.ref()).isEqualTo("1");
    assertThat(f.sport()).isEqualTo("FOOTBALL");
    assertThat(f.competitionCode()).isEqualTo("PL");
    assertThat(f.homeName()).isEqualTo("Arsenal FC");
    assertThat(f.homeShort()).isEqualTo("Arsenal");
    assertThat(f.kickoffAt()).isEqualTo(Instant.parse("2026-10-24T14:00:00Z"));
    assertThat(f.status()).isEqualTo(ScoreStatus.FINISHED);
    assertThat(f.regularTime()).isTrue();
    assertThat(f.homeScore()).isEqualTo(2);
    assertThat(f.awayScore()).isZero();
    assertThat(p.fixtures().get(1).status()).isEqualTo(ScoreStatus.SCHEDULED);
    assertThat(p.fixtures().get(1).homeScore()).isNull();
    assertThat(p.nextCursor()).isNull();
    // 单场：v4 直接返回比赛；也兼容 {"match": …}
    assertThat(FootballData.parseOne(fdMatch("537877", "FINISHED", "EXTRA_TIME", "3", "2"), "537877").regularTime()).as("加时结束").isFalse();
    assertThat(FootballData.parseOne("{\"match\":" + fdMatch("9", "FINISHED", "REGULAR", "1", "1") + "}", "9").homeScore()).isEqualTo(1);
  }

  @Test
  void footballData_状态对应() {
    assertThat(FootballData.status("SCHEDULED")).isEqualTo(ScoreStatus.SCHEDULED);
    assertThat(FootballData.status("TIMED")).isEqualTo(ScoreStatus.SCHEDULED);
    for (String s : List.of("IN_PLAY", "PAUSED", "EXTRA_TIME", "PENALTY_SHOOTOUT", "LIVE")) assertThat(FootballData.status(s)).isEqualTo(ScoreStatus.LIVE);
    assertThat(FootballData.status("FINISHED")).isEqualTo(ScoreStatus.FINISHED);
    assertThat(FootballData.status("POSTPONED")).isEqualTo(ScoreStatus.POSTPONED);
    assertThat(FootballData.status("SUSPENDED")).isEqualTo(ScoreStatus.SUSPENDED);
    assertThat(FootballData.status("CANCELLED")).isEqualTo(ScoreStatus.CANCELLED);
    assertThat(FootballData.status("AWARDED")).isEqualTo(ScoreStatus.AWARDED);
    assertThat(FootballData.status("SOMETHING_NEW")).isEqualTo(ScoreStatus.UNKNOWN);
  }

  @Test
  void footballData_格式不对和还没好() {
    assertBad(() -> FootballData.parseList("<html>Bad gateway</html>"), "不是合法的 JSON");
    assertBad(() -> FootballData.parseList("{\"message\":\"The resource you are looking for is restricted.\",\"errorCode\":403}"), "错误");
    assertBad(() -> FootballData.parseOne(fdMatch("1", "FINISHED", "REGULAR", "-1", "0"), "1"), "负数");
    assertBad(() -> FootballData.parseOne(fdMatch("1", "FINISHED", "REGULAR", "\"two\"", "0"), "1"), "不是整数");
    assertBad(() -> FootballData.parseOne(fdMatch("1", "FINISHED", "REGULAR", "2", "0"), "2"), "不是 2");
    assertBad(() -> FootballData.parseOne("{\"homeTeam\":{},\"status\":\"FINISHED\"}", "1"), "没有 id");
    assertThatThrownBy(() -> FootballData.parseOne(fdMatch("1", "FINISHED", "REGULAR", "null", "null"), "1")).isInstanceOf(SourceException.class)
        .satisfies(e -> assertThat(((SourceException) e).kind()).as("完赛但还没比分：下一轮再看").isEqualTo(SourceException.Kind.NOT_READY));
  }

  // ---------------- balldontlie ----------------

  static String bdlGame(String id, String status, int period, int home, int away) {
    return "{\"id\":" + id + ",\"date\":\"2026-10-25\",\"season\":2026,\"status\":\"" + status + "\",\"period\":" + period + ",\"time\":\"\",\"postseason\":false,"
        + "\"home_team_score\":" + home + ",\"visitor_team_score\":" + away + ",\"datetime\":\"2026-10-25T23:30:00.000Z\","
        + "\"home_team\":{\"id\":14,\"conference\":\"West\",\"division\":\"Pacific\",\"city\":\"Los Angeles\",\"name\":\"Lakers\",\"full_name\":\"Los Angeles Lakers\",\"abbreviation\":\"LAL\"},"
        + "\"visitor_team\":{\"id\":2,\"conference\":\"East\",\"division\":\"Atlantic\",\"city\":\"Boston\",\"name\":\"Celtics\",\"full_name\":\"Boston Celtics\",\"abbreviation\":\"BOS\"}}";
  }

  @Test
  void balldontlie_赛程单场和状态() throws Exception {
    String list = "{\"data\":[" + bdlGame("1", "Final", 4, 112, 108) + "," + bdlGame("2", "2026-10-25T23:30:00Z", 0, 0, 0) + "," + bdlGame("3", "3rd Qtr", 3, 70, 66)
        + "],\"meta\":{\"next_cursor\":4521,\"per_page\":100}}";
    ScoreSource.SchedulePage p = Balldontlie.parseList(list);
    assertThat(p.fixtures()).hasSize(3);
    assertThat(p.nextCursor()).isEqualTo("4521");
    ScoreFixture f = p.fixtures().get(0);
    assertThat(f.sport()).isEqualTo("NBA");
    assertThat(f.homeName()).isEqualTo("Los Angeles Lakers");
    assertThat(f.awayName()).isEqualTo("Boston Celtics");
    assertThat(f.kickoffAt()).isEqualTo(Instant.parse("2026-10-25T23:30:00Z"));
    assertThat(f.status()).isEqualTo(ScoreStatus.FINISHED);
    assertThat(f.homeScore()).isEqualTo(112);
    assertThat(p.fixtures().get(1).status()).isEqualTo(ScoreStatus.SCHEDULED);
    assertThat(p.fixtures().get(1).homeScore()).as("没完赛不给比分").isNull();
    assertThat(p.fixtures().get(2).status()).isEqualTo(ScoreStatus.LIVE);
    assertThat(Balldontlie.parseOne("{\"data\":" + bdlGame("15907925", "Final/OT", 5, 120, 118) + "}", "15907925").status()).isEqualTo(ScoreStatus.FINISHED);
    assertThat(Balldontlie.status("Postponed", 0)).isEqualTo(ScoreStatus.POSTPONED);
    assertThat(Balldontlie.status("Cancelled", 0)).isEqualTo(ScoreStatus.CANCELLED);
    assertThat(Balldontlie.status("Suspended", 2)).isEqualTo(ScoreStatus.SUSPENDED);
    assertBad(() -> Balldontlie.parseOne("{\"data\":" + bdlGame("1", "Final", 4, -3, 100) + "}", "1"), "负数");
    assertBad(() -> Balldontlie.parseOne("{\"error\":\"Unauthorized\"}", "1"), "没有 data");
    assertBad(() -> Balldontlie.parseList("Unauthorized"), "不是合法的 JSON");
  }

  // ---------------- TheSportsDB ----------------

  /** 2026-10-25 实测 lookupevent.php?id=2494072 的返回（节选，去掉了图片地址等字段） */
  static final String TS_REAL = "{\"events\":[{\"idEvent\":\"2494072\",\"idAPIfootball\":\"1557437\",\"strTimestamp\":\"2026-10-24T14:00:00\","
      + "\"strEvent\":\"Arsenal vs Everton\",\"strEventAlternate\":\"Everton @ Arsenal\",\"strFilename\":\"English Premier League 2026-10-24 Arsenal vs Everton\","
      + "\"strSport\":\"Soccer\",\"idLeague\":\"4328\",\"strLeague\":\"English Premier League\",\"strSeason\":\"2026-2027\",\"strDescriptionEN\":\"\","
      + "\"strHomeTeam\":\"Arsenal\",\"strAwayTeam\":\"Everton\",\"intHomeScore\":\"2\",\"intHomeScoreExtra\":null,\"intAwayScoreExtra\":null,\"intRound\":\"8\","
      + "\"intAwayScore\":\"0\",\"intSpectators\":null,\"dateEvent\":\"2026-10-24\",\"dateEventLocal\":\"2026-10-24\",\"strTime\":\"14:00:00\",\"strTimeLocal\":\"15:00:00\","
      + "\"idHomeTeam\":\"133604\",\"idAwayTeam\":\"133615\",\"strVenue\":\"Emirates Stadium\",\"strCountry\":\"England\",\"strStatus\":\"FT\",\"strPostponed\":\"no\","
      + "\"strLocked\":\"unlocked\"}]}";

  @Test
  void theSportsDb_实测原文_单场() throws Exception {
    ScoreFixture f = TheSportsDb.parseOne(TS_REAL, "2494072", "FOOTBALL");
    assertThat(f.source()).isEqualTo("THESPORTSDB");
    assertThat(f.homeName()).isEqualTo("Arsenal");
    assertThat(f.awayName()).isEqualTo("Everton");
    assertThat(f.leagueRef()).isEqualTo("4328");
    assertThat(f.kickoffAt()).as("strTimestamp 是 UTC（strTime 14:00、当地 15:00）").isEqualTo(Instant.parse("2026-10-24T14:00:00Z"));
    assertThat(f.status()).isEqualTo(ScoreStatus.FINISHED);
    assertThat(f.regularTime()).isTrue();
    assertThat(f.homeScore()).isEqualTo(2);
    assertThat(f.awayScore()).isZero();
    assertBad(() -> TheSportsDb.parseOne("{\"events\":null}", "999999999", "FOOTBALL"), "没有这场比赛");
    assertBad(() -> TheSportsDb.parseOne(TS_REAL, "2494072", "NBA"), "不是 NBA");
    assertBad(() -> TheSportsDb.parseOne(TS_REAL, "1", "FOOTBALL"), "不是 1");
    assertBad(() -> TheSportsDb.parseOne(TS_REAL.replace("\"intHomeScore\":\"2\"", "\"intHomeScore\":\"two\""), "2494072", "FOOTBALL"), "不是整数");
    assertBad(() -> TheSportsDb.parseOne("{\"message\":\"limit\"}", "1", "FOOTBALL"), "没有 events");
  }

  @Test
  void theSportsDb_搜索和状态对应() throws Exception {
    String other = TS_REAL.replace("\"strSport\":\"Soccer\"", "\"strSport\":\"Basketball\"").replace("2494072", "1");
    String body = "{\"event\":[" + TS_REAL.substring(11, TS_REAL.length() - 2) + "," + other.substring(11, other.length() - 2) + "]}";
    List<ScoreFixture> found = TheSportsDb.parseList(body, "event", "FOOTBALL");
    assertThat(found).as("别的运动项目不要").extracting(ScoreFixture::ref).containsExactly("2494072");
    assertThat(TheSportsDb.parseList("{\"event\":null}", "event", "FOOTBALL")).isEmpty();
    // 状态
    assertThat(TheSportsDb.status("FT")).isEqualTo(ScoreStatus.FINISHED);
    assertThat(TheSportsDb.status("Match Finished")).isEqualTo(ScoreStatus.FINISHED);
    assertThat(TheSportsDb.status("AOT")).isEqualTo(ScoreStatus.FINISHED);
    assertThat(TheSportsDb.regular("AET")).as("加时").isFalse();
    assertThat(TheSportsDb.regular("PEN")).as("点球").isFalse();
    assertThat(TheSportsDb.regular("FT")).isTrue();
    assertThat(TheSportsDb.status("NS")).isEqualTo(ScoreStatus.SCHEDULED);
    assertThat(TheSportsDb.status(null)).isEqualTo(ScoreStatus.SCHEDULED);
    assertThat(TheSportsDb.status("PST")).isEqualTo(ScoreStatus.POSTPONED);
    assertThat(TheSportsDb.status("CANC")).isEqualTo(ScoreStatus.CANCELLED);
    assertThat(TheSportsDb.status("ABD")).isEqualTo(ScoreStatus.SUSPENDED);
    assertThat(TheSportsDb.status("INT")).isEqualTo(ScoreStatus.SUSPENDED);
    assertThat(TheSportsDb.status("AWD")).isEqualTo(ScoreStatus.AWARDED);
    assertThat(TheSportsDb.status("2H")).isEqualTo(ScoreStatus.LIVE);
    assertThat(TheSportsDb.status("Q3")).isEqualTo(ScoreStatus.LIVE);
    assertThat(TheSportsDb.status("whatever")).isEqualTo(ScoreStatus.UNKNOWN);
    // strPostponed = yes 优先
    ScoreFixture pst = TheSportsDb.parseOne(TS_REAL.replace("\"strPostponed\":\"no\"", "\"strPostponed\":\"yes\"").replace("\"strStatus\":\"FT\"", "\"strStatus\":\"NS\""),
        "2494072", "FOOTBALL");
    assertThat(pst.status()).isEqualTo(ScoreStatus.POSTPONED);
    assertThat(pst.homeScore()).as("没完赛不给比分").isNull();
    // 没有 strTimestamp 时用日期 + 时间
    ScoreFixture noTs = TheSportsDb.parseOne(TS_REAL.replace("\"strTimestamp\":\"2026-10-24T14:00:00\",", ""), "2494072", "FOOTBALL");
    assertThat(noTs.kickoffAt()).isEqualTo(Instant.parse("2026-10-24T14:00:00Z"));
  }

  @Test
  void 联赛编号对照表_英超和NBA() {
    assertThat(TheSportsDb.LEAGUES).containsEntry("PL", "4328").containsEntry("NBA", "4387").hasSize(13);
  }

  interface Thrower {
    void run() throws Exception;
  }

  static void assertBad(Thrower t, String contains) {
    assertThatThrownBy(t::run).isInstanceOf(SourceException.class).hasMessageContaining(contains)
        .satisfies(e -> assertThat(((SourceException) e).kind()).isEqualTo(SourceException.Kind.BAD_FORMAT));
  }
}
