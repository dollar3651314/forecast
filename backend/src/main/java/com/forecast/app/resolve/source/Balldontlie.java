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
 * balldontlie（NBA 主源，AQ-17：免费档每分钟 5 次，包含 Games 接口；条款允许商用，但不能把它当作结算的唯一来源——本项目两个来源一致才结算，符合）。
 * 请求头 {@code Authorization: <FC_SRC_BALLDONTLIE_KEY>}。
 * <ul>
 *   <li>赛程：{@code GET /v1/games?start_date=&end_date=&per_page=100&cursor=}（按 {@code meta.next_cursor} 翻页）</li>
 *   <li>单场：{@code GET /v1/games/{id}} → {@code {"data": {…}}}</li>
 * </ul>
 * 状态：{@code status = "Final"} → 完赛（比分 {@code home_team_score}、{@code visitor_team_score}，含加时，NBA 规则按含加时写）；含 postpon → 延期；
 * 含 cancel → 取消；含 suspend → 中断；{@code period = 0} → 未开始（这时 status 是开赛时间）；其他 → 进行中。开赛时间用 {@code datetime}。
 * <p>实测（2026-10-25）：不带 key 返回 401。带 key 的格式按官方文档写，<b>拿到 key 后要用冒烟测试实测</b>（C39）。
 */
@Component
public class Balldontlie implements ScoreSource {

  static final String NAME = "BALLDONTLIE";
  static final String KEY = "balldontlie";

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
    return "balldontlie.io";
  }

  @Override
  public Set<String> sports() {
    return Set.of(NBA);
  }

  private static Map<String, String> headers(ResolveProperties.Source cfg) {
    return Map.of("Authorization", cfg.getKey() == null ? "" : cfg.getKey());
  }

  @Override
  public SourceRequest<SchedulePage> schedule(ResolveProperties.Source cfg, String sport, LocalDate from, LocalDate to, String cursor) {
    String url = Scores.trim(cfg.getBaseUrl()) + "/v1/games?start_date=" + from + "&end_date=" + to + "&per_page=100"
        + (cursor == null ? "" : "&cursor=" + Scores.ref(cursor));
    return new SourceRequest<>(NAME, KEY, URI.create(url), url, headers(cfg), Balldontlie::parseList, p -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("count", p.fixtures().size());
      m.put("from", from.toString());
      m.put("to", to.toString());
      m.put("nextCursor", p.nextCursor());
      return m;
    });
  }

  @Override
  public SourceRequest<ScoreFixture> fixture(ResolveProperties.Source cfg, String sport, String ref) {
    String url = Scores.trim(cfg.getBaseUrl()) + "/v1/games/" + Scores.ref(ref);
    return new SourceRequest<>(NAME, KEY, URI.create(url), url, headers(cfg), body -> parseOne(body, ref), ScoreFixture::toMap);
  }

  static SchedulePage parseList(String body) throws SourceException {
    JsonNode root = Klines.read(body);
    JsonNode data = root.get("data");
    if (data == null || !data.isArray()) throw SourceException.badFormat("没有 data");
    List<ScoreFixture> out = new ArrayList<>();
    for (JsonNode g : data) {
      ScoreFixture f = game(g);
      if (f.kickoffAt() != null && f.homeName() != null && f.awayName() != null) out.add(f);
    }
    return new SchedulePage(out, Scores.text(root.path("meta").get("next_cursor")));
  }

  static ScoreFixture parseOne(String body, String ref) throws SourceException {
    JsonNode root = Klines.read(body);
    JsonNode g = root.get("data");
    if (g == null || !g.isObject()) throw SourceException.badFormat("没有 data");
    ScoreFixture f = game(g);
    if (!ref.equals(f.ref())) throw SourceException.badFormat("返回的比赛编号 " + f.ref() + " 不是 " + ref);
    return f;
  }

  static ScoreFixture game(JsonNode g) throws SourceException {
    String id = Scores.text(g.get("id"));
    if (id == null) throw SourceException.badFormat("比赛没有 id");
    String raw = Scores.text(g.get("status"));
    if (raw == null) throw SourceException.badFormat("比赛 " + id + " 没有状态");
    Instant kickoff = Scores.instant(Scores.text(g.get("datetime")));
    if (kickoff == null) kickoff = Scores.instant(raw); // 还没开始时 status 就是开赛时间
    int period = g.path("period").isNumber() ? g.path("period").asInt() : -1;
    ScoreStatus st = status(raw, period);
    Integer hs = null;
    Integer as = null;
    if (st == ScoreStatus.FINISHED) {
      hs = Scores.score(g.get("home_team_score"), "主队比分");
      as = Scores.score(g.get("visitor_team_score"), "客队比分");
      if (hs == null || as == null) throw SourceException.notReady("比赛 " + id + " 已完赛但还没有比分");
    }
    JsonNode home = g.path("home_team");
    JsonNode away = g.path("visitor_team");
    return new ScoreFixture(NAME, id, NBA, "NBA", "NBA", null, Scores.text(home.get("id")), Scores.text(home.get("full_name")),
        Scores.text(home.get("abbreviation")), Scores.text(away.get("id")), Scores.text(away.get("full_name")), Scores.text(away.get("abbreviation")), kickoff,
        st, raw, null, hs, as);
  }

  static ScoreStatus status(String raw, int period) {
    String s = raw.toLowerCase(Locale.ROOT);
    if (s.equals("final") || s.startsWith("final")) return ScoreStatus.FINISHED;
    if (s.contains("postpon")) return ScoreStatus.POSTPONED;
    if (s.contains("cancel")) return ScoreStatus.CANCELLED;
    if (s.contains("suspend")) return ScoreStatus.SUSPENDED;
    if (period == 0) return ScoreStatus.SCHEDULED;
    if (period > 0) return ScoreStatus.LIVE;
    return ScoreStatus.UNKNOWN;
  }
}
