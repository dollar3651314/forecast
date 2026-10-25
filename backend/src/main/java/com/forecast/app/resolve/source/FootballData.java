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
 * football-data.org v4（足球主源，AQ-17：免费档 12 个赛事，每分钟 10 次，比分有延迟；技术方案第 6.1、6.4 节）。请求头 {@code X-Auth-Token: <FC_SRC_FOOTBALL_DATA_TOKEN>}。
 * <ul>
 *   <li>赛程：{@code GET /v4/matches?dateFrom=YYYY-MM-DD&dateTo=YYYY-MM-DD}（免费档一次最多 10 天；只返回 token 有权限的赛事）</li>
 *   <li>单场：{@code GET /v4/matches/{id}}</li>
 * </ul>
 * 状态：SCHEDULED / TIMED → 未开始；IN_PLAY / PAUSED / EXTRA_TIME / PENALTY_SHOOTOUT / LIVE → 进行中；FINISHED → 完赛
 * （{@code score.duration = REGULAR} 才是 90 分钟比分，取 {@code score.fullTime}；加时、点球转人工）；POSTPONED → 延期（E6）；SUSPENDED → 中断（E7）；
 * CANCELLED → 取消（E5）；AWARDED → 判定结果。
 * <p>实测（2026-10-25，开发环境出口，没有 token）：{@code /v4/matches?dateFrom&dateTo} 返回 200、{@code matches: []}、响应头 {@code X-Authenticated-Client: anonymous}；
 * {@code /v4/matches/{id}}、{@code /v4/competitions/PL/matches} 返回 403。带 token 的返回格式按官方文档写，<b>拿到 token 后要用冒烟测试实测</b>（C39）。
 */
@Component
public class FootballData implements ScoreSource {

  static final String NAME = "FOOTBALL_DATA";
  static final String KEY = "football-data";

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
    return "football-data.org";
  }

  @Override
  public Set<String> sports() {
    return Set.of(FOOTBALL);
  }

  private static Map<String, String> headers(ResolveProperties.Source cfg) {
    return Map.of("X-Auth-Token", cfg.getKey() == null ? "" : cfg.getKey());
  }

  @Override
  public SourceRequest<SchedulePage> schedule(ResolveProperties.Source cfg, String sport, LocalDate from, LocalDate to, String cursor) {
    String url = Scores.trim(cfg.getBaseUrl()) + "/v4/matches?dateFrom=" + from + "&dateTo=" + to;
    return new SourceRequest<>(NAME, KEY, URI.create(url), url, headers(cfg), FootballData::parseList, p -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("count", p.fixtures().size());
      m.put("from", from.toString());
      m.put("to", to.toString());
      return m;
    });
  }

  @Override
  public SourceRequest<ScoreFixture> fixture(ResolveProperties.Source cfg, String sport, String ref) {
    String url = Scores.trim(cfg.getBaseUrl()) + "/v4/matches/" + Scores.ref(ref);
    return new SourceRequest<>(NAME, KEY, URI.create(url), url, headers(cfg), body -> parseOne(body, ref), ScoreFixture::toMap);
  }

  static SchedulePage parseList(String body) throws SourceException {
    JsonNode root = Klines.read(body);
    JsonNode matches = root.get("matches");
    if (matches == null || !matches.isArray()) throw SourceException.badFormat(errorOf(root, "没有 matches"));
    List<ScoreFixture> out = new ArrayList<>();
    for (JsonNode m : matches) {
      ScoreFixture f = match(m);
      // 淘汰赛对阵还没定的（队名为空）不能生成市场，跳过
      if (f.homeName() != null && f.awayName() != null && f.kickoffAt() != null) out.add(f);
    }
    return new SchedulePage(out, null);
  }

  static ScoreFixture parseOne(String body, String ref) throws SourceException {
    JsonNode root = Klines.read(body);
    // v4 直接返回比赛；兼容外面包一层 {"match": …} 的写法
    JsonNode m = root.has("homeTeam") ? root : root.get("match");
    if (m == null || !m.isObject()) throw SourceException.badFormat(errorOf(root, "不是比赛"));
    ScoreFixture f = match(m);
    if (!ref.equals(f.ref())) throw SourceException.badFormat("返回的比赛编号 " + f.ref() + " 不是 " + ref);
    return f;
  }

  private static String errorOf(JsonNode root, String fallback) {
    String msg = Scores.text(root.get("message"));
    return msg == null ? fallback : "错误：" + (msg.length() > 120 ? msg.substring(0, 120) : msg);
  }

  static ScoreFixture match(JsonNode m) throws SourceException {
    String id = Scores.text(m.get("id"));
    if (id == null) throw SourceException.badFormat("比赛没有 id");
    String raw = Scores.text(m.get("status"));
    if (raw == null) throw SourceException.badFormat("比赛 " + id + " 没有状态");
    Instant kickoff = Scores.instant(Scores.text(m.get("utcDate")));
    if (kickoff == null && Scores.text(m.get("utcDate")) != null) throw SourceException.badFormat("比赛 " + id + " 的开赛时间不对");
    JsonNode comp = m.path("competition");
    JsonNode home = m.path("homeTeam");
    JsonNode away = m.path("awayTeam");
    JsonNode score = m.path("score");
    ScoreStatus st = status(raw);
    Boolean regular = null;
    Integer hs = null;
    Integer as = null;
    if (st == ScoreStatus.FINISHED) {
      String duration = Scores.text(score.get("duration"));
      regular = duration == null ? null : "REGULAR".equalsIgnoreCase(duration);
      hs = Scores.score(score.path("fullTime").get("home"), "主队比分");
      as = Scores.score(score.path("fullTime").get("away"), "客队比分");
      if (hs == null || as == null) throw SourceException.notReady("比赛 " + id + " 已完赛但还没有比分");
    }
    return new ScoreFixture(NAME, id, FOOTBALL, Scores.text(comp.get("code")), Scores.text(comp.get("name")), null, Scores.text(home.get("id")),
        Scores.text(home.get("name")), Scores.text(home.get("shortName")), Scores.text(away.get("id")), Scores.text(away.get("name")),
        Scores.text(away.get("shortName")), kickoff, st, raw, regular, hs, as);
  }

  static ScoreStatus status(String raw) {
    return switch (raw.toUpperCase(Locale.ROOT)) {
      case "SCHEDULED", "TIMED" -> ScoreStatus.SCHEDULED;
      case "IN_PLAY", "PAUSED", "EXTRA_TIME", "PENALTY_SHOOTOUT", "LIVE" -> ScoreStatus.LIVE;
      case "FINISHED" -> ScoreStatus.FINISHED;
      case "POSTPONED" -> ScoreStatus.POSTPONED;
      case "SUSPENDED" -> ScoreStatus.SUSPENDED;
      case "CANCELLED", "CANCELED" -> ScoreStatus.CANCELLED;
      case "AWARDED" -> ScoreStatus.AWARDED;
      default -> ScoreStatus.UNKNOWN;
    };
  }
}
