package com.forecast.app.resolve.sports;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.resolve.source.Balldontlie;
import com.forecast.app.resolve.source.FootballData;
import com.forecast.app.resolve.source.ResolveProperties;
import com.forecast.app.resolve.source.ScoreFixture;
import com.forecast.app.resolve.source.ScoreSource;
import com.forecast.app.resolve.source.SourceArchive;
import com.forecast.app.resolve.source.SourceGateway;
import com.forecast.app.resolve.source.SourceReply;
import com.forecast.app.resolve.source.TheSportsDb;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * <b>手动运行</b>的体育数据源冒烟测试（forecast S8；agents C39"第三方接口的行为要用真实网络确认"）：打真实的 football-data.org、balldontlie、TheSportsDB，
 * 用生产的超时、重试和限频，检查返回格式和适配器的解析一致；TheSportsDB 再按队名 + 日期找一场已知比赛、给出候选分数。
 * <b>默认跳过</b>（CI 和平时的 verify 都不跑，不依赖外网和 key）。key 从环境变量读（只在本机的终端里设，不进仓库、不打印）：
 * <pre>FC_SRC_FOOTBALL_DATA_TOKEN=… FC_SRC_BALLDONTLIE_KEY=… FC_SRC_THESPORTSDB_KEY=… mvn -o -B -ntp test -Dtest=RealScoreSourcesSmokeTest -Dresolve.sportsSmoke=true</pre>
 * 没有 key 的来源打印"未测（没有 key）"。结果写进 开发记录/S8-体育自动化.md。
 */
@EnabledIfSystemProperty(named = "resolve.sportsSmoke", matches = "true")
class RealScoreSourcesSmokeTest {

  static ResolveProperties.Source src(String base, String env) {
    ResolveProperties.Source s = new ResolveProperties.Source(true, base);
    s.setKey(System.getenv(env));
    s.setKeyRequired(true);
    s.setKeyEnv(env);
    s.setReadTimeout(Duration.ofSeconds(10));
    s.setBackoff(Duration.ofSeconds(2));
    s.setMaxBackoff(Duration.ofSeconds(8));
    return s;
  }

  @Test
  void 真实接口() {
    ResolveProperties props = new ResolveProperties();
    ResolveProperties.Source fdCfg = src("https://api.football-data.org", "FC_SRC_FOOTBALL_DATA_TOKEN");
    fdCfg.setRateLimit(8);
    ResolveProperties.Source bdCfg = src("https://api.balldontlie.io", "FC_SRC_BALLDONTLIE_KEY");
    bdCfg.setRateLimit(4);
    ResolveProperties.Source tsCfg = src("https://www.thesportsdb.com", "FC_SRC_THESPORTSDB_KEY");
    tsCfg.setRateLimit(25);
    props.getSources().put("football-data", fdCfg);
    props.getSources().put("balldontlie", bdCfg);
    props.getSources().put("thesportsdb", tsCfg);
    List<SourceArchive.Entry> archived = new CopyOnWriteArrayList<>();
    SourceGateway gw = new SourceGateway(props, e -> {
      archived.add(e);
      return archived.size();
    }, Clock.systemUTC());
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    System.out.println("\n===== 真实体育数据源冒烟测试 " + Instant.now() + " =====");
    List<String> lines = new ArrayList<>();

    // football-data：未来 7 天赛程 + 最近 3 天里一场完赛的单场
    FootballData fd = new FootballData();
    if (!fdCfg.usable()) {
      lines.add("FOOTBALL_DATA 未测（没有 key：" + fdCfg.unusableReason() + "）");
    } else {
      SourceReply<ScoreSource.SchedulePage> next = timed(lines, "FOOTBALL_DATA 赛程 " + today + "～" + today.plusDays(7),
          () -> gw.call(fd.schedule(fdCfg, "FOOTBALL", today, today.plusDays(7), null), List.of(), null));
      if (next.ok()) {
        lines.add("  拿到 " + next.value().fixtures().size() + " 场，赛事：" + next.value().fixtures().stream().map(ScoreFixture::competitionCode).distinct().toList());
        next.value().fixtures().stream().limit(3).forEach(f -> lines.add("  " + f.ref() + " " + f.kickoffAt() + " " + f.homeName() + " vs " + f.awayName() + " " + f.rawStatus()));
      }
      SourceReply<ScoreSource.SchedulePage> past = timed(lines, "FOOTBALL_DATA 赛程 " + today.minusDays(3) + "～" + today,
          () -> gw.call(fd.schedule(fdCfg, "FOOTBALL", today.minusDays(3), today, null), List.of(), null));
      if (past.ok()) {
        past.value().fixtures().stream().filter(ScoreFixture::finished).findFirst().ifPresent(f -> {
          SourceReply<ScoreFixture> one = timed(lines, "FOOTBALL_DATA 单场 " + f.ref(), () -> gw.call(fd.fixture(fdCfg, "FOOTBALL", f.ref()), List.of(), null));
          if (one.ok()) {
            ScoreFixture x = one.value();
            lines.add("  " + x.homeName() + " " + x.homeScore() + ":" + x.awayScore() + " " + x.awayName() + " " + x.rawStatus() + " 常规时间=" + x.regularTime());
            assertThat(x.status()).isEqualTo(com.forecast.app.resolve.source.ScoreStatus.FINISHED);
          }
        });
      }
    }

    // balldontlie：今天起 7 天的赛程 + 昨天一场完赛的单场
    Balldontlie bd = new Balldontlie();
    if (!bdCfg.usable()) {
      lines.add("BALLDONTLIE 未测（没有 key：" + bdCfg.unusableReason() + "）");
    } else {
      SourceReply<ScoreSource.SchedulePage> games = timed(lines, "BALLDONTLIE 赛程 " + today.minusDays(2) + "～" + today.plusDays(7),
          () -> gw.call(bd.schedule(bdCfg, "NBA", today.minusDays(2), today.plusDays(7), null), List.of(), null));
      if (games.ok()) {
        lines.add("  拿到 " + games.value().fixtures().size() + " 场，下一页 " + games.value().nextCursor());
        games.value().fixtures().stream().filter(ScoreFixture::finished).findFirst().ifPresent(f -> {
          SourceReply<ScoreFixture> one = timed(lines, "BALLDONTLIE 单场 " + f.ref(), () -> gw.call(bd.fixture(bdCfg, "NBA", f.ref()), List.of(), null));
          if (one.ok()) lines.add("  " + one.value().homeName() + " " + one.value().homeScore() + ":" + one.value().awayScore() + " " + one.value().awayName() + " "
              + one.value().rawStatus());
        });
      }
    }

    // TheSportsDB：单场（2026-10-24 英超 阿森纳 2:0 埃弗顿）、按联赛 + 日期、按队名 + 日期找候选并打分
    TheSportsDb ts = new TheSportsDb();
    if (!tsCfg.usable()) {
      lines.add("THESPORTSDB 未测（没有 key：" + tsCfg.unusableReason() + "）");
    } else {
      SourceReply<ScoreFixture> one = timed(lines, "THESPORTSDB 单场 2494072", () -> gw.call(ts.fixture(tsCfg, "FOOTBALL", "2494072"), List.of(), null));
      if (one.ok()) {
        ScoreFixture x = one.value();
        lines.add("  " + x.homeName() + " " + x.homeScore() + ":" + x.awayScore() + " " + x.awayName() + " " + x.rawStatus() + " " + x.kickoffAt());
        assertThat(x.homeScore()).isEqualTo(2);
        assertThat(x.awayScore()).isZero();
      }
      SourceReply<List<ScoreFixture>> day = timed(lines, "THESPORTSDB 英超 2026-10-25", () -> gw.call(ts.day(tsCfg, "FOOTBALL", "4328", LocalDate.parse("2026-10-25")),
          List.of(), null));
      if (day.ok()) day.value().forEach(f -> lines.add("  " + f.ref() + " " + f.kickoffAt() + " " + f.homeName() + " vs " + f.awayName() + " " + f.rawStatus()));
      Candidates.Target t = new Candidates.Target("Manchester United FC", "Man United", "AFC Bournemouth", "Bournemouth", Instant.parse("2026-10-25T14:00:00Z"));
      SourceReply<List<ScoreFixture>> byName = timed(lines, "THESPORTSDB 按队名找 Manchester United vs Bournemouth 2026-10-25",
          () -> gw.call(ts.searchByNames(tsCfg, "FOOTBALL", TeamNames.display("Manchester United FC"), TeamNames.display("AFC Bournemouth"),
              LocalDate.parse("2026-10-25")), List.of(), null));
      if (byName.ok()) {
        List<Candidates.Candidate> ranked = Candidates.rank(t, byName.value().stream().map(c -> Candidates.score(t, c)).toList());
        ranked.forEach(c -> lines.add("  候选 " + c.ref() + " " + c.home() + " vs " + c.away() + " " + c.kickoffAt() + " 分数 " + c.score() + (c.suggested() ? " 推荐" : "")));
      }
      SourceReply<ScoreFixture> nba = timed(lines, "THESPORTSDB NBA 单场 2601700", () -> gw.call(ts.fixture(tsCfg, "NBA", "2601700"), List.of(), null));
      if (nba.ok()) lines.add("  " + nba.value().homeName() + " " + nba.value().homeScore() + ":" + nba.value().awayScore() + " " + nba.value().awayName() + " "
          + nba.value().rawStatus());
    }
    // 存档里不能有 key
    for (SourceArchive.Entry e : archived) {
      for (String k : new String[] {fdCfg.getKey(), bdCfg.getKey(), tsCfg.getKey()}) {
        if (k != null && k.length() > 3) assertThat(e.requestUrl()).doesNotContain(k);
      }
    }
    lines.forEach(System.out::println);
    System.out.println("===== 结束（共 " + archived.size() + " 次调用存档）=====\n");
  }

  interface Call<T> {
    SourceReply<T> run();
  }

  static <T> SourceReply<T> timed(List<String> lines, String what, Call<T> c) {
    long t0 = System.nanoTime();
    SourceReply<T> r = c.run();
    long ms = (System.nanoTime() - t0) / 1_000_000;
    lines.add(what + "：" + r.outcome() + " HTTP " + r.httpStatus() + " " + ms + " 毫秒 尝试 " + r.attempts() + " 次" + (r.error() == null ? "" : " " + r.error()));
    return r;
  }
}
