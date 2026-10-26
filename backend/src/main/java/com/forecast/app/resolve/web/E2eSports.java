package com.forecast.app.resolve.web;

import com.forecast.app.common.e2e.E2eGuard;
import com.forecast.app.resolve.platform.PublicityService;
import com.forecast.app.resolve.sports.SportsResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试的替身体育数据源（只在 profile e2e 下存在，forecast S8）。e2e 配置里 football-data、balldontlie、TheSportsDB 的地址指向这里（application-e2e.yml），
 * 所以端到端走的是<b>真实的适配器、网关（超时、重试、熔断、限频、存档）、赛程同步、候选、生成草稿和体育裁决器</b>，只是比赛和比分是测试设的：
 * <ul>
 *   <li>{@code POST /api/e2e/sports/fixtures} {@code {"sport":"FOOTBALL","primaryRef":"9001","backupRef":"7001","competition":"PL","competitionName":"Premier League",
 *       "home":"Arsenal FC","away":"Everton FC","backupHome":"Arsenal","backupAway":"Everton","kickoffAt":"…Z"}}：加一场比赛（两个来源各一份）</li>
 *   <li>{@code POST /api/e2e/sports/state} {@code {"primaryRef":"9001","source":"PRIMARY"|"BACKUP"|"BOTH","status":"FINISHED","homeScore":2,"awayScore":1}}：
 *       设某个来源的状态和比分（status 用 football-data 的写法：SCHEDULED / IN_PLAY / FINISHED / POSTPONED / SUSPENDED / CANCELLED，按来源换成各家的写法）</li>
 *   <li>照真实接口的格式返回：{@code /api/e2e/sports/football-data/v4/matches}、{@code …/v4/matches/{id}}（要 X-Auth-Token）；
 *       {@code /api/e2e/sports/balldontlie/v1/games}、{@code …/v1/games/{id}}（要 Authorization）；
 *       {@code /api/e2e/sports/thesportsdb/api/v1/json/{key}/lookupevent.php}、{@code searchevents.php}、{@code eventsday.php}</li>
 *   <li>{@code POST /api/e2e/sports/tick}：立即跑一轮体育自动裁决（不用等 30 秒的任务），同时跑平台裁决的任务（E6 / E7 到期等）</li>
 * </ul>
 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eSports {

  record FixtureBody(String sport, String primaryRef, String backupRef, String competition, String competitionName, String home, String away,
      String backupHome, String backupAway, String kickoffAt, String backupKickoffAt) {}

  record StateBody(String primaryRef, String source, String status, Integer homeScore, Integer awayScore, String kickoffAt) {}

  /** 一个来源眼里的一场比赛 */
  private static final class Side {
    volatile String status = "SCHEDULED";
    volatile Integer home;
    volatile Integer away;
    volatile Instant kickoff;
  }

  private record Game(String sport, String primaryRef, String backupRef, String competition, String competitionName, String home, String away,
      String backupHome, String backupAway, Side primary, Side backup) {}

  private final Map<String, Game> byPrimary = new ConcurrentHashMap<>();
  private final Map<String, Game> byBackup = new ConcurrentHashMap<>();
  private final SportsResolver resolver;
  private final PublicityService publicity;

  E2eSports(SportsResolver resolver, PublicityService publicity) {
    this.resolver = resolver;
    this.publicity = publicity;
  }

  @PostMapping("/api/e2e/sports/fixtures")
  ResponseEntity<Map<String, Object>> fixture(@RequestBody FixtureBody b) {
    if (b == null || b.sport() == null || b.primaryRef() == null || b.backupRef() == null || b.home() == null || b.away() == null || b.kickoffAt() == null) {
      return ResponseEntity.badRequest().build();
    }
    Side p = new Side();
    p.kickoff = Instant.parse(b.kickoffAt());
    Side q = new Side();
    q.kickoff = b.backupKickoffAt() == null ? p.kickoff : Instant.parse(b.backupKickoffAt());
    Game g = new Game(b.sport(), b.primaryRef(), b.backupRef(), b.competition() == null ? (b.sport().equals("NBA") ? "NBA" : "PL") : b.competition(),
        b.competitionName() == null ? "Premier League" : b.competitionName(), b.home(), b.away(), b.backupHome() == null ? b.home() : b.backupHome(),
        b.backupAway() == null ? b.away() : b.backupAway(), p, q);
    byPrimary.put(g.primaryRef(), g);
    byBackup.put(g.backupRef(), g);
    return ResponseEntity.ok(Map.of("primaryRef", g.primaryRef(), "backupRef", g.backupRef()));
  }

  @PostMapping("/api/e2e/sports/state")
  ResponseEntity<Map<String, Object>> state(@RequestBody StateBody b) {
    Game g = b == null || b.primaryRef() == null ? null : byPrimary.get(b.primaryRef());
    if (g == null || b.status() == null) return ResponseEntity.badRequest().build();
    String src = b.source() == null ? "BOTH" : b.source();
    for (Side s : src.equals("PRIMARY") ? List.of(g.primary()) : src.equals("BACKUP") ? List.of(g.backup()) : List.of(g.primary(), g.backup())) {
      s.status = b.status();
      s.home = b.homeScore();
      s.away = b.awayScore();
      if (b.kickoffAt() != null) s.kickoff = Instant.parse(b.kickoffAt());
    }
    return ResponseEntity.ok(Map.of("primaryRef", g.primaryRef(), "source", src, "status", b.status()));
  }

  @PostMapping("/api/e2e/sports/tick")
  Map<String, Object> tick() {
    return Map.of("markets", resolver.resolveDue(), "holds", publicity.holdsDue());
  }

  // ---------------- football-data.org v4 ----------------

  @GetMapping("/api/e2e/sports/football-data/v4/matches")
  ResponseEntity<Object> fdList(@RequestHeader(value = "X-Auth-Token", required = false) String token, @RequestParam String dateFrom,
      @RequestParam String dateTo) {
    if (token == null || token.isBlank()) return ResponseEntity.status(403).body(Map.of("message", "e2e: no token", "errorCode", 403));
    LocalDate from = LocalDate.parse(dateFrom);
    LocalDate to = LocalDate.parse(dateTo);
    List<Object> out = new ArrayList<>();
    for (Game g : byPrimary.values()) {
      if (!g.sport().equals("FOOTBALL")) continue;
      LocalDate d = g.primary().kickoff.atZone(ZoneOffset.UTC).toLocalDate();
      if (!d.isBefore(from) && !d.isAfter(to)) out.add(fdMatch(g));
    }
    return ResponseEntity.ok(Map.of("filters", Map.of("dateFrom", dateFrom, "dateTo", dateTo), "resultSet", Map.of("count", out.size()), "matches", out));
  }

  @GetMapping("/api/e2e/sports/football-data/v4/matches/{id}")
  ResponseEntity<Object> fdOne(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable String id) {
    if (token == null || token.isBlank()) return ResponseEntity.status(403).body(Map.of("message", "e2e: no token", "errorCode", 403));
    Game g = byPrimary.get(id);
    if (g == null || !g.sport().equals("FOOTBALL")) return ResponseEntity.status(404).body(Map.of("message", "e2e: not found", "errorCode", 404));
    return ResponseEntity.ok(fdMatch(g));
  }

  private static Map<String, Object> fdMatch(Game g) {
    Side s = g.primary();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("area", Map.of("name", "England"));
    m.put("competition", Map.of("id", 2021, "name", g.competitionName(), "code", g.competition()));
    m.put("id", Long.parseLong(g.primaryRef()));
    m.put("utcDate", s.kickoff.toString());
    m.put("status", s.status);
    m.put("homeTeam", team(g.home()));
    m.put("awayTeam", team(g.away()));
    Map<String, Object> full = new LinkedHashMap<>();
    full.put("home", s.home);
    full.put("away", s.away);
    Map<String, Object> score = new LinkedHashMap<>();
    score.put("winner", null);
    score.put("duration", "REGULAR");
    score.put("fullTime", full);
    m.put("score", score);
    return m;
  }

  private static Map<String, Object> team(String name) {
    Map<String, Object> t = new LinkedHashMap<>();
    t.put("id", Math.abs(name.hashCode() % 10_000));
    t.put("name", name);
    t.put("shortName", name.replace(" FC", ""));
    t.put("tla", name.substring(0, Math.min(3, name.length())).toUpperCase());
    return t;
  }

  // ---------------- balldontlie v1 ----------------

  @GetMapping("/api/e2e/sports/balldontlie/v1/games")
  ResponseEntity<Object> bdList(@RequestHeader(value = "Authorization", required = false) String key, @RequestParam("start_date") String start,
      @RequestParam("end_date") String end) {
    if (key == null || key.isBlank()) return ResponseEntity.status(401).body("Unauthorized");
    LocalDate from = LocalDate.parse(start);
    LocalDate to = LocalDate.parse(end);
    List<Object> out = new ArrayList<>();
    for (Game g : byPrimary.values()) {
      if (!g.sport().equals("NBA")) continue;
      LocalDate d = g.primary().kickoff.atZone(ZoneOffset.UTC).toLocalDate();
      if (!d.isBefore(from) && !d.isAfter(to)) out.add(bdGame(g));
    }
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("next_cursor", null);
    meta.put("per_page", 100);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("data", out);
    body.put("meta", meta);
    return ResponseEntity.ok(body);
  }

  @GetMapping("/api/e2e/sports/balldontlie/v1/games/{id}")
  ResponseEntity<Object> bdOne(@RequestHeader(value = "Authorization", required = false) String key, @PathVariable String id) {
    if (key == null || key.isBlank()) return ResponseEntity.status(401).body("Unauthorized");
    Game g = byPrimary.get(id);
    if (g == null || !g.sport().equals("NBA")) return ResponseEntity.status(404).body(Map.of("error", "not found"));
    return ResponseEntity.ok(Map.of("data", bdGame(g)));
  }

  private static Map<String, Object> bdGame(Game g) {
    Side s = g.primary();
    boolean finished = "FINISHED".equals(s.status);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", Long.parseLong(g.primaryRef()));
    m.put("date", s.kickoff.atZone(ZoneOffset.UTC).toLocalDate().toString());
    m.put("season", 2026);
    m.put("status", switch (s.status) {
      case "FINISHED" -> "Final";
      case "IN_PLAY" -> "3rd Qtr";
      case "POSTPONED" -> "Postponed";
      case "SUSPENDED" -> "Suspended";
      case "CANCELLED" -> "Cancelled";
      default -> s.kickoff.toString();
    });
    m.put("period", finished ? 4 : "IN_PLAY".equals(s.status) ? 3 : 0);
    m.put("time", finished ? "Final" : "");
    m.put("postseason", false);
    m.put("home_team_score", s.home == null ? 0 : s.home);
    m.put("visitor_team_score", s.away == null ? 0 : s.away);
    m.put("datetime", s.kickoff.toString());
    m.put("home_team", Map.of("id", 1, "full_name", g.home(), "abbreviation", g.home().substring(0, 3).toUpperCase(), "name", g.home()));
    m.put("visitor_team", Map.of("id", 2, "full_name", g.away(), "abbreviation", g.away().substring(0, 3).toUpperCase(), "name", g.away()));
    return m;
  }

  // ---------------- TheSportsDB v1 ----------------

  @GetMapping("/api/e2e/sports/thesportsdb/api/v1/json/{key}/lookupevent.php")
  Map<String, Object> tsLookup(@PathVariable String key, @RequestParam String id) {
    Game g = byBackup.get(id);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("events", g == null ? null : List.of(tsEvent(g)));
    return out;
  }

  @GetMapping("/api/e2e/sports/thesportsdb/api/v1/json/{key}/searchevents.php")
  Map<String, Object> tsSearch(@PathVariable String key, @RequestParam String e, @RequestParam(required = false) String d) {
    List<Object> list = new ArrayList<>();
    for (Game g : byBackup.values()) {
      String name = (g.backupHome() + "_vs_" + g.backupAway()).replace(' ', '_');
      boolean sameDay = d == null || g.backup().kickoff.atZone(ZoneOffset.UTC).toLocalDate().toString().equals(d);
      if (name.equalsIgnoreCase(e) && sameDay) list.add(tsEvent(g));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("event", list.isEmpty() ? null : list);
    return out;
  }

  @GetMapping("/api/e2e/sports/thesportsdb/api/v1/json/{key}/eventsday.php")
  Map<String, Object> tsDay(@PathVariable String key, @RequestParam String d, @RequestParam(required = false) String l) {
    List<Object> list = new ArrayList<>();
    for (Game g : byBackup.values()) {
      if (g.backup().kickoff.atZone(ZoneOffset.UTC).toLocalDate().toString().equals(d) && leagueOf(g).equals(l)) list.add(tsEvent(g));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("events", list.isEmpty() ? null : list.subList(0, Math.min(3, list.size()))); // 免费 key 只给前 3 条
    return out;
  }

  private static String leagueOf(Game g) {
    return switch (g.competition()) {
      case "NBA" -> "4387";
      case "PL" -> "4328";
      default -> "0";
    };
  }

  private static Map<String, Object> tsEvent(Game g) {
    Side s = g.backup();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("idEvent", g.backupRef());
    m.put("strTimestamp", s.kickoff.toString().replace("Z", ""));
    m.put("strEvent", g.backupHome() + " vs " + g.backupAway());
    m.put("strSport", g.sport().equals("NBA") ? "Basketball" : "Soccer");
    m.put("idLeague", leagueOf(g));
    m.put("strLeague", g.sport().equals("NBA") ? "NBA" : "English Premier League");
    m.put("strHomeTeam", g.backupHome());
    m.put("strAwayTeam", g.backupAway());
    m.put("intHomeScore", "FINISHED".equals(s.status) && s.home != null ? String.valueOf(s.home) : null);
    m.put("intAwayScore", "FINISHED".equals(s.status) && s.away != null ? String.valueOf(s.away) : null);
    m.put("dateEvent", s.kickoff.atZone(ZoneOffset.UTC).toLocalDate().toString());
    m.put("strTime", s.kickoff.atZone(ZoneOffset.UTC).toLocalTime().toString());
    m.put("strStatus", switch (s.status) {
      case "FINISHED" -> "FT";
      case "IN_PLAY" -> "2H";
      case "POSTPONED" -> "PST";
      case "SUSPENDED" -> "ABD";
      case "CANCELLED" -> "CANC";
      default -> "NS";
    });
    m.put("strPostponed", "POSTPONED".equals(s.status) ? "yes" : "no");
    return m;
  }
}
