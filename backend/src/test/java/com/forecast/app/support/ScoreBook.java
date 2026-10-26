package com.forecast.app.support;

import com.forecast.app.support.StubHttp.Reply;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 替身体育数据源的"比分簿"（forecast S8 测试用）：按来源设比赛（编号、队名、开赛时间）、状态和比分，或者设成某种故障；{@link #handler()} 照
 * football-data.org v4、balldontlie v1、TheSportsDB v1 真实接口的格式回复（路径前缀 /fd、/bdl、/tsdb，配置里把三个来源的地址指到 StubHttp 的这三个前缀）。
 * 记下每个请求带的认证头，测试核对 key 有没有带、存档里有没有 key。
 */
public final class ScoreBook {

  /** 一个来源里的一场比赛 */
  public static final class Game {
    public final String source;
    public final String ref;
    public final String sport;
    public volatile String competition;
    public volatile String competitionName;
    public volatile String home;
    public volatile String away;
    public volatile Instant kickoff;
    /** football-data 的写法：SCHEDULED / TIMED / IN_PLAY / FINISHED / POSTPONED / SUSPENDED / CANCELLED / AWARDED（按来源换成各家的写法） */
    public volatile String status = "TIMED";
    /** football-data：REGULAR / EXTRA_TIME / PENALTY_SHOOTOUT */
    public volatile String duration = "REGULAR";
    public volatile Integer homeScore;
    public volatile Integer awayScore;

    Game(String source, String ref, String sport, String home, String away, Instant kickoff) {
      this.source = source;
      this.ref = ref;
      this.sport = sport;
      this.home = home;
      this.away = away;
      this.kickoff = kickoff;
      this.competition = "NBA".equals(sport) ? "NBA" : "PL";
      this.competitionName = "NBA".equals(sport) ? "NBA" : "Premier League";
    }

    public Game finished(int h, int a) {
      status = "FINISHED";
      homeScore = h;
      awayScore = a;
      return this;
    }

    public Game status(String s) {
      status = s;
      return this;
    }
  }

  private final Map<String, Game> games = new ConcurrentHashMap<>();
  private final Map<String, Reply> sourceFault = new ConcurrentHashMap<>();
  /** TheSportsDB 每个请求路径里带的 key（"THESPORTSDB:<key>"）；请求头里的 key 看 StubHttp.headers() */
  private final List<String> auth = new CopyOnWriteArrayList<>();

  public void clear() {
    games.clear();
    sourceFault.clear();
    auth.clear();
  }

  public Game add(String source, String ref, String sport, String home, String away, Instant kickoff) {
    Game g = new Game(source, ref, sport, home, away, kickoff);
    games.put(source + ":" + ref, g);
    return g;
  }

  public Game get(String source, String ref) {
    return games.get(source + ":" + ref);
  }

  /** 某个来源全部请求都这样回（优先于数据），例如一直 500；null 取消 */
  public void fault(String source, Reply r) {
    if (r == null) sourceFault.remove(source);
    else sourceFault.put(source, r);
  }

  public List<String> auth() {
    return List.copyOf(auth);
  }

  public Function<URI, Reply> handler() {
    return u -> {
      String path = u.getPath();
      Map<String, String> q = query(u);
      if (path.startsWith("/fd/")) {
        Reply f = sourceFault.get("FOOTBALL_DATA");
        if (f != null) return f;
        if (path.equals("/fd/v4/matches")) {
          String from = q.get("dateFrom");
          String to = q.get("dateTo");
          List<String> list = new ArrayList<>();
          for (Game g : games.values()) {
            if (!g.source.equals("FOOTBALL_DATA")) continue;
            String d = g.kickoff.atZone(ZoneOffset.UTC).toLocalDate().toString();
            if (d.compareTo(from) >= 0 && d.compareTo(to) <= 0) list.add(fd(g));
          }
          return Reply.ok("{\"filters\":{},\"resultSet\":{\"count\":" + list.size() + "},\"matches\":[" + String.join(",", list) + "]}");
        }
        Game g = games.get("FOOTBALL_DATA:" + path.substring("/fd/v4/matches/".length()));
        return g == null ? Reply.status(404, "{\"message\":\"The resource you are looking for does not exist.\",\"errorCode\":404}") : Reply.ok(fd(g));
      }
      if (path.startsWith("/bdl/")) {
        Reply f = sourceFault.get("BALLDONTLIE");
        if (f != null) return f;
        if (path.equals("/bdl/v1/games")) {
          String from = q.get("start_date");
          String to = q.get("end_date");
          List<String> list = new ArrayList<>();
          for (Game g : games.values()) {
            if (!g.source.equals("BALLDONTLIE")) continue;
            String d = g.kickoff.atZone(ZoneOffset.UTC).toLocalDate().toString();
            if (d.compareTo(from) >= 0 && d.compareTo(to) <= 0) list.add(bdl(g));
          }
          return Reply.ok("{\"data\":[" + String.join(",", list) + "],\"meta\":{\"per_page\":100}}");
        }
        Game g = games.get("BALLDONTLIE:" + path.substring("/bdl/v1/games/".length()));
        return g == null ? Reply.status(404, "{\"error\":\"Not Found\"}") : Reply.ok("{\"data\":" + bdl(g) + "}");
      }
      if (path.startsWith("/tsdb/")) {
        // /tsdb/api/v1/json/{key}/xxx.php
        String[] parts = path.split("/");
        auth.add("THESPORTSDB:" + parts[5]);
        Reply f = sourceFault.get("THESPORTSDB");
        if (f != null) return f;
        String file = parts[6];
        if (file.equals("lookupevent.php")) {
          Game g = games.get("THESPORTSDB:" + q.get("id"));
          return Reply.ok("{\"events\":" + (g == null ? "null" : "[" + ts(g) + "]") + "}");
        }
        if (file.equals("searchevents.php")) {
          List<String> list = new ArrayList<>();
          for (Game g : games.values()) {
            if (!g.source.equals("THESPORTSDB")) continue;
            String e = (g.home + "_vs_" + g.away).replace(' ', '_');
            String d = g.kickoff.atZone(ZoneOffset.UTC).toLocalDate().toString();
            if (e.equalsIgnoreCase(q.get("e")) && (q.get("d") == null || d.equals(q.get("d")))) list.add(ts(g));
          }
          return Reply.ok("{\"event\":" + (list.isEmpty() ? "null" : "[" + String.join(",", list) + "]") + "}");
        }
        if (file.equals("eventsday.php")) {
          List<String> list = new ArrayList<>();
          for (Game g : games.values()) {
            if (!g.source.equals("THESPORTSDB")) continue;
            String d = g.kickoff.atZone(ZoneOffset.UTC).toLocalDate().toString();
            if (d.equals(q.get("d")) && league(g).equals(q.get("l"))) list.add(ts(g));
          }
          // 免费 key 只返回前 3 条
          return Reply.ok("{\"events\":" + (list.isEmpty() ? "null" : "[" + String.join(",", list.subList(0, Math.min(3, list.size()))) + "]") + "}");
        }
      }
      return Reply.status(404, "{}");
    };
  }

  static String league(Game g) {
    return "NBA".equals(g.sport) ? "4387" : "PL".equals(g.competition) ? "4328" : "0";
  }

  static String str(String s) {
    return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\"";
  }

  static String num(Integer i) {
    return i == null ? "null" : String.valueOf(i);
  }

  public static String fd(Game g) {
    boolean done = "FINISHED".equals(g.status);
    return "{\"area\":{\"name\":\"England\"},\"competition\":{\"id\":2021,\"name\":" + str(g.competitionName) + ",\"code\":" + str(g.competition) + "},"
        + "\"season\":{\"id\":2400},\"id\":" + g.ref + ",\"utcDate\":\"" + g.kickoff + "\",\"status\":\"" + g.status + "\",\"matchday\":9,"
        + "\"homeTeam\":{\"id\":57,\"name\":" + str(g.home) + ",\"shortName\":" + str(g.home.replace(" FC", "")) + ",\"tla\":\"HOM\"},"
        + "\"awayTeam\":{\"id\":62,\"name\":" + str(g.away) + ",\"shortName\":" + str(g.away.replace(" FC", "")) + ",\"tla\":\"AWA\"},"
        + "\"score\":{\"winner\":null,\"duration\":\"" + g.duration + "\",\"fullTime\":{\"home\":" + (done ? num(g.homeScore) : "null") + ",\"away\":"
        + (done ? num(g.awayScore) : "null") + "},\"halfTime\":{\"home\":null,\"away\":null}},\"odds\":{\"msg\":\"Activate Odds-Package in User-Panel to retrieve odds.\"}}";
  }

  public static String bdl(Game g) {
    String status = switch (g.status) {
      case "FINISHED" -> "Final";
      case "IN_PLAY" -> "3rd Qtr";
      case "POSTPONED" -> "Postponed";
      case "SUSPENDED" -> "Suspended";
      case "CANCELLED" -> "Cancelled";
      default -> g.kickoff.toString();
    };
    int period = "FINISHED".equals(g.status) ? 4 : "IN_PLAY".equals(g.status) ? 3 : 0;
    return "{\"id\":" + g.ref + ",\"date\":\"" + g.kickoff.atZone(ZoneOffset.UTC).toLocalDate() + "\",\"season\":2026,\"status\":\"" + status + "\",\"period\":"
        + period + ",\"time\":\"" + ("FINISHED".equals(g.status) ? "Final" : "") + "\",\"postseason\":false,\"home_team_score\":"
        + (g.homeScore == null ? 0 : g.homeScore) + ",\"visitor_team_score\":" + (g.awayScore == null ? 0 : g.awayScore) + ",\"datetime\":\"" + g.kickoff
        + "\",\"home_team\":{\"id\":14,\"conference\":\"West\",\"division\":\"Pacific\",\"city\":\"x\",\"name\":\"x\",\"full_name\":" + str(g.home)
        + ",\"abbreviation\":\"HOM\"},\"visitor_team\":{\"id\":2,\"conference\":\"East\",\"division\":\"Atlantic\",\"city\":\"y\",\"name\":\"y\",\"full_name\":"
        + str(g.away) + ",\"abbreviation\":\"AWA\"}}";
  }

  public static String ts(Game g) {
    boolean done = "FINISHED".equals(g.status) || "AET".equals(g.status);
    String st = switch (g.status) {
      case "FINISHED" -> "EXTRA_TIME".equals(g.duration) ? "AET" : "PENALTY_SHOOTOUT".equals(g.duration) ? "PEN" : "FT";
      case "IN_PLAY" -> "2H";
      case "POSTPONED" -> "PST";
      case "SUSPENDED" -> "ABD";
      case "CANCELLED" -> "CANC";
      case "AWARDED" -> "AWD";
      default -> "NS";
    };
    return "{\"idEvent\":\"" + g.ref + "\",\"idAPIfootball\":\"1\",\"strTimestamp\":\"" + g.kickoff.toString().replace("Z", "") + "\",\"strEvent\":" + str(g.home
        + " vs " + g.away) + ",\"strSport\":\"" + ("NBA".equals(g.sport) ? "Basketball" : "Soccer") + "\",\"idLeague\":\"" + league(g) + "\",\"strLeague\":"
        + str("NBA".equals(g.sport) ? "NBA" : "English Premier League") + ",\"strSeason\":\"2026-2027\",\"strHomeTeam\":" + str(g.home) + ",\"strAwayTeam\":"
        + str(g.away) + ",\"intHomeScore\":" + (done && g.homeScore != null ? "\"" + g.homeScore + "\"" : "null") + ",\"intAwayScore\":"
        + (done && g.awayScore != null ? "\"" + g.awayScore + "\"" : "null") + ",\"dateEvent\":\"" + g.kickoff.atZone(ZoneOffset.UTC).toLocalDate()
        + "\",\"strTime\":\"" + g.kickoff.atZone(ZoneOffset.UTC).toLocalTime() + "\",\"strStatus\":\"" + st + "\",\"strPostponed\":\""
        + ("POSTPONED".equals(g.status) ? "yes" : "no") + "\",\"idHomeTeam\":\"133604\",\"idAwayTeam\":\"133615\"}";
  }

  private static Map<String, String> query(URI u) {
    Map<String, String> out = new HashMap<>();
    if (u.getRawQuery() == null) return out;
    for (String kv : u.getRawQuery().split("&")) {
      int i = kv.indexOf('=');
      if (i > 0) out.put(kv.substring(0, i), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
    }
    return out;
  }
}
