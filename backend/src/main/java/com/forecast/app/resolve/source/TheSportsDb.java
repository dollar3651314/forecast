package com.forecast.app.resolve.source;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * TheSportsDB v1（足球备源、NBA 备源，AQ-17：测试环境用免费 key，上线时买 $9/月的档拿独立 key；技术方案第 6.1、6.4 节）。key 在路径里：
 * {@code /api/v1/json/<FC_SRC_THESPORTSDB_KEY>/…}；存档和日志里的地址把 key 换成 {@code {key}}。
 * <ul>
 *   <li>单场：{@code lookupevent.php?id=} → {@code {"events": [ … ] | null}}</li>
 *   <li>找对应比赛（"两个来源的比赛编号由运营确认"时给候选）：{@code searchevents.php?e=<主队>_vs_<客队>&d=<日期>}（{@code {"event": …}}），
 *       {@code eventsday.php?d=<日期>&l=<联赛编号>}（免费 key 只返回前 3 条）</li>
 * </ul>
 * 状态（{@code strStatus}，参考官方的状态表）：FT / Match Finished / AOT → 完赛；AET / PEN（加时、点球结束）→ 完赛但不是 90 分钟比分；NS / TBD / 空 → 未开始；
 * PST / Postponed 或 {@code strPostponed = yes} → 延期；CANC → 取消；ABD / SUSP / INT → 中断；AWD / WO → 判定；1H / HT / 2H / ET / P / Q1～Q4 / OT 等 → 进行中。
 * 开赛时间 {@code strTimestamp}（UTC，不带时区）。
 * <p>实测（2026-10-25，开发环境出口，公开的免费 key）：{@code lookupevent}、{@code searchevents?e=&d=}、{@code searchfilename}、{@code eventsday?l=} 都可用；
 * 英超 10-24 阿森纳 2:0 埃弗顿状态 FT；NBA 也是 FT；不存在的编号返回 {@code {"events": null}}；{@code searchevents} 不带日期时只返回 1 场（可能是别的赛季）。
 */
@Component
public class TheSportsDb implements ScoreSource {

  static final String NAME = "THESPORTSDB";
  static final String KEY = "thesportsdb";

  /**
   * football-data 的赛事代码 → TheSportsDB 的联赛编号（2026-10-25 用 {@code lookupleague.php?id=} 逐个实测核对过名字）。NBA 是 4387。
   * 不在表里的赛事只能按队名找。
   */
  public static final Map<String, String> LEAGUES = Map.ofEntries(Map.entry("PL", "4328"), Map.entry("ELC", "4329"), Map.entry("PD", "4335"),
      Map.entry("BL1", "4331"), Map.entry("SA", "4332"), Map.entry("FL1", "4334"), Map.entry("DED", "4337"), Map.entry("PPL", "4344"),
      Map.entry("BSA", "4351"), Map.entry("CL", "4480"), Map.entry("WC", "4429"), Map.entry("EC", "4502"), Map.entry("NBA", "4387"));

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public String label() {
    return "TheSportsDB";
  }

  @Override
  public Set<String> sports() {
    return Set.of(FOOTBALL, NBA);
  }

  private static String base(ResolveProperties.Source cfg) {
    return Scores.trim(cfg.getBaseUrl()) + "/api/v1/json/";
  }

  private static <T> SourceRequest<T> request(ResolveProperties.Source cfg, String path, SourceRequest.Parser<T> parser,
      java.util.function.Function<T, Map<String, Object>> summary) {
    String key = cfg.getKey() == null ? "" : cfg.getKey();
    URI uri = URI.create(base(cfg) + Scores.enc(key) + "/" + path);
    return new SourceRequest<>(NAME, KEY, uri, base(cfg) + "{key}/" + path, Map.of(), parser, summary);
  }

  @Override
  public SourceRequest<ScoreFixture> fixture(ResolveProperties.Source cfg, String sport, String ref) {
    return request(cfg, "lookupevent.php?id=" + Scores.ref(ref), body -> parseOne(body, ref, sport), ScoreFixture::toMap);
  }

  /** 按"主队 vs 客队"和日期找（日期是开赛时间的 UTC 日期） */
  public SourceRequest<List<ScoreFixture>> searchByNames(ResolveProperties.Source cfg, String sport, String home, String away, LocalDate date) {
    String e = Scores.enc(home.strip().replace(' ', '_')) + "_vs_" + Scores.enc(away.strip().replace(' ', '_'));
    return request(cfg, "searchevents.php?e=" + e + "&d=" + date, body -> parseList(body, "event", sport), TheSportsDb::summary);
  }

  /** 某个联赛某一天的比赛（免费 key 只返回前 3 条，付费 key 全部） */
  public SourceRequest<List<ScoreFixture>> day(ResolveProperties.Source cfg, String sport, String leagueRef, LocalDate date) {
    return request(cfg, "eventsday.php?d=" + date + "&l=" + Scores.ref(leagueRef), body -> parseList(body, "events", sport), TheSportsDb::summary);
  }

  private static Map<String, Object> summary(List<ScoreFixture> list) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("count", list.size());
    m.put("refs", list.stream().map(ScoreFixture::ref).toList());
    return m;
  }

  static ScoreFixture parseOne(String body, String ref, String sport) throws SourceException {
    JsonNode root = Klines.read(body);
    JsonNode events = root.get("events");
    if (events == null) throw SourceException.badFormat("没有 events");
    if (events.isNull() || (events.isArray() && events.isEmpty())) throw SourceException.badFormat("没有这场比赛（编号 " + ref + "）");
    if (!events.isArray()) throw SourceException.badFormat("events 不是数组");
    ScoreFixture f = event(events.get(0), sport);
    if (!ref.equals(f.ref())) throw SourceException.badFormat("返回的比赛编号 " + f.ref() + " 不是 " + ref);
    return f;
  }

  static List<ScoreFixture> parseList(String body, String field, String sport) throws SourceException {
    JsonNode root = Klines.read(body);
    if (!root.has(field)) throw SourceException.badFormat("没有 " + field);
    JsonNode list = root.get(field);
    List<ScoreFixture> out = new ArrayList<>();
    if (list == null || list.isNull()) return out;
    if (!list.isArray()) throw SourceException.badFormat(field + " 不是数组");
    for (JsonNode e : list) {
      ScoreFixture f = event(e, null);
      // 别的运动项目（同名的篮球队、足球队）不要
      if (f.sport() != null && f.sport().equals(sport)) out.add(f);
    }
    return out;
  }

  /**
   * @param expectSport 不为空时检查运动项目（足球 Soccer、NBA Basketball），不对算格式错误（编号对应错了）
   */
  static ScoreFixture event(JsonNode e, String expectSport) throws SourceException {
    String id = Scores.text(e.get("idEvent"));
    if (id == null) throw SourceException.badFormat("比赛没有 idEvent");
    String sportRaw = Scores.text(e.get("strSport"));
    String sport = sportRaw == null ? null : sportRaw.equalsIgnoreCase("Soccer") ? FOOTBALL : sportRaw.equalsIgnoreCase("Basketball") ? NBA : sportRaw;
    if (expectSport != null && sport != null && !expectSport.equals(sport)) throw SourceException.badFormat("比赛 " + id + " 是 " + sportRaw + "，不是 " + expectSport);
    String raw = Scores.text(e.get("strStatus"));
    boolean postponed = "yes".equalsIgnoreCase(Scores.text(e.get("strPostponed")));
    ScoreStatus st = postponed ? ScoreStatus.POSTPONED : status(raw);
    Boolean regular = st == ScoreStatus.FINISHED ? regular(raw) : null;
    Integer hs = Scores.score(e.get("intHomeScore"), "主队比分");
    Integer as = Scores.score(e.get("intAwayScore"), "客队比分");
    if (st == ScoreStatus.FINISHED && (hs == null || as == null)) throw SourceException.notReady("比赛 " + id + " 已完赛但还没有比分");
    if (st != ScoreStatus.FINISHED) {
      hs = null;
      as = null;
    }
    Instant kickoff = Scores.instant(Scores.text(e.get("strTimestamp")));
    if (kickoff == null && Scores.text(e.get("dateEvent")) != null) {
      String t = Scores.text(e.get("strTime"));
      kickoff = Scores.instant(Scores.text(e.get("dateEvent")) + "T" + (t == null ? "00:00:00" : t.length() == 5 ? t + ":00" : t.substring(0, Math.min(8, t.length()))));
    }
    return new ScoreFixture(NAME, id, sport, null, Scores.text(e.get("strLeague")), Scores.text(e.get("idLeague")), Scores.text(e.get("idHomeTeam")),
        Scores.text(e.get("strHomeTeam")), null, Scores.text(e.get("idAwayTeam")), Scores.text(e.get("strAwayTeam")), null, kickoff, st,
        raw == null ? (postponed ? "Postponed" : "") : raw, regular, hs, as);
  }

  static ScoreStatus status(String raw) {
    if (raw == null || raw.isBlank()) return ScoreStatus.SCHEDULED;
    String s = raw.strip().toUpperCase(Locale.ROOT);
    return switch (s) {
      case "FT", "MATCH FINISHED", "AOT", "AET", "PEN", "AP", "FINISHED", "AFTER OVER TIME", "AFTER EXTRA TIME", "AFTER PENALTIES", "FT_PEN", "FINAL" -> ScoreStatus.FINISHED;
      case "NS", "NOT STARTED", "TBD", "TIME TO BE DEFINED" -> ScoreStatus.SCHEDULED;
      case "PST", "POSTPONED" -> ScoreStatus.POSTPONED;
      case "CANC", "CANCELLED", "CANCELED" -> ScoreStatus.CANCELLED;
      case "ABD", "ABANDONED", "SUSP", "SUSPENDED", "INT", "INTERRUPTED" -> ScoreStatus.SUSPENDED;
      case "AWD", "WO", "AWARDED", "WALKOVER" -> ScoreStatus.AWARDED;
      case "1H", "HT", "2H", "ET", "BT", "P", "LIVE", "Q1", "Q2", "Q3", "Q4", "OT", "HALFTIME", "IN PLAY", "1ST HALF", "2ND HALF" -> ScoreStatus.LIVE;
      default -> ScoreStatus.UNKNOWN;
    };
  }

  /** 足球：加时、点球结束的不是 90 分钟比分（NBA 不用这个） */
  static Boolean regular(String raw) {
    if (raw == null) return null;
    String s = raw.strip().toUpperCase(Locale.ROOT);
    if (s.equals("FT") || s.equals("MATCH FINISHED") || s.equals("FINISHED") || s.equals("FINAL")) return true;
    if (s.equals("AET") || s.equals("PEN") || s.equals("AP") || s.equals("AFTER EXTRA TIME") || s.equals("AFTER PENALTIES") || s.equals("FT_PEN")) return false;
    return null; // AOT（篮球加时）等
  }
}
