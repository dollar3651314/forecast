package com.forecast.app.resolve;

import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.resolve.api.ResolveDeskApi;
import com.forecast.app.resolve.api.SportsAdminApi;
import com.forecast.app.resolve.platform.PublicityService;
import com.forecast.app.resolve.source.ResolveProperties;
import com.forecast.app.resolve.source.SourceGateway;
import com.forecast.app.resolve.sports.SportsResolver;
import com.forecast.app.settle.SettleTestSupport;
import com.forecast.app.support.ScoreBook;
import com.forecast.app.support.StubHttp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 体育赛程、自动裁决测试的公共部分（forecast S8；真实 MySQL 8.4、Redis 7，C37）。沿用 settle 的客户、下注、截止、结算核对（{@link SettleTestSupport}），
 * 加上本机的 HTTP 替身数据源（{@link StubHttp} + {@link ScoreBook}，照 football-data、balldontlie、TheSportsDB 的格式回复，<b>不连外网</b>）：
 * 每个测试开始时把三个来源的地址指到替身、带上测试 key、清掉熔断和限频状态；结束时指回不监听的端口。
 * 比赛编号每个测试各自生成（C80：测试数据隔离，共享表里查询都带编号）。
 */
public abstract class SportsTestSupport extends SettleTestSupport {

  protected static final StubHttp STUB = new StubHttp();
  protected static final ScoreBook BOOK = new ScoreBook();
  private static final AtomicLong REF = new AtomicLong(System.currentTimeMillis() % 1_000_000_000L * 10);

  static {
    STUB.handle(BOOK.handler());
  }

  @Autowired protected SportsAdminApi sportsAdmin;
  @Autowired protected SportsResolver sports;
  @Autowired protected ResolveProperties resolveProps;
  @Autowired protected SourceGateway gateway;
  @Autowired protected PublicityService publicity;
  @Autowired protected ResolveDeskApi desk;

  @BeforeEach
  void pointSportsSourcesToStub() {
    BOOK.clear();
    STUB.clear();
    point("football-data", STUB.url() + "/fd", "test-token");
    point("balldontlie", STUB.url() + "/bdl", "test-key");
    point("thesportsdb", STUB.url() + "/tsdb", "test-key");
    gateway.resetBreakers();
  }

  private void point(String key, String url, String apiKey) {
    ResolveProperties.Source s = resolveProps.getSources().get(key);
    s.setBaseUrl(url);
    s.setKey(apiKey);
    s.setEnabled(true);
    s.setRateLimit(0);
  }

  @AfterEach
  void restoreSportsSources() {
    // 没跑完的进度（测试停在"等待中"）关掉，后面的测试跑 resolveDue 时不会去取别的测试的比赛
    jdbc.update("UPDATE resolve_sports_task SET status = 'CLOSED', last_reason = '测试结束' WHERE status IN ('PENDING', 'RESOLVING')");
    for (String k : List.of("football-data", "balldontlie", "thesportsdb")) {
      resolveProps.getSources().get(k).setBaseUrl("http://127.0.0.1:9");
      resolveProps.getSources().get(k).setKey("test");
    }
    gateway.resetBreakers();
  }

  /** 一个新的比赛编号（数字，两个来源各用一个） */
  protected static String ref() {
    return String.valueOf(REF.incrementAndGet());
  }

  /** 现在 + d，取整到分钟 */
  protected Instant in(Duration d) {
    return Instant.now(clock).plus(d).truncatedTo(ChronoUnit.MINUTES);
  }

  /** 一场比赛在替身里的两份数据（主源、备源）和它在赛程里的编号 */
  public record Fx(String sport, String primaryRef, String backupRef, String fixtureNo, ScoreBook.Game primary, ScoreBook.Game backup) {}

  /** 两个来源都有这场比赛（备源用另一种叫法），同步进赛程；不确认对应 */
  protected Fx fixture(String sport, String home, String away, String backupHome, String backupAway, Instant kickoff) {
    String pr = ref();
    String br = ref();
    ScoreBook.Game p = BOOK.add("NBA".equals(sport) ? "BALLDONTLIE" : "FOOTBALL_DATA", pr, sport, home, away, kickoff);
    ScoreBook.Game b = BOOK.add("THESPORTSDB", br, sport, backupHome, backupAway, kickoff);
    sportsAdmin.sync(sport);
    String no = fixtureNo("NBA".equals(sport) ? "BALLDONTLIE" : "FOOTBALL_DATA", pr);
    return new Fx(sport, pr, br, no, p, b);
  }

  protected Fx football(Instant kickoff) {
    return fixture("FOOTBALL", "Arsenal FC", "Everton FC", "Arsenal", "Everton", kickoff);
  }

  protected Fx nba(Instant kickoff) {
    return fixture("NBA", "Los Angeles Lakers", "Boston Celtics", "Los Angeles Lakers", "Boston Celtics", kickoff);
  }

  protected String fixtureNo(String source, String primaryRef) {
    return String.valueOf(jdbc.queryForObject("SELECT public_no FROM resolve_fixture WHERE primary_source = ? AND primary_ref = ?", Long.class, source, primaryRef));
  }

  /** 找候选（同步时已经顺便找过）并确认推荐的那个 */
  protected void confirm(Fx f) {
    SportsAdminApi.Fixture d = sportsAdmin.searchCandidates(Long.parseLong(f.fixtureNo()), null);
    String ref = d.candidates().stream().filter(SportsAdminApi.Candidate::suggested).findFirst().orElseThrow(() -> new AssertionError("没有推荐的候选：" + d))
        .ref();
    sportsAdmin.confirmMapping(Long.parseLong(f.fixtureNo()), ref, 1);
  }

  /** 生成草稿并上架；返回市场（选项按模板顺序） */
  protected Market listed(Fx f, String play, String line) {
    SportsAdminApi.Generated g = sportsAdmin.generate(Long.parseLong(f.fixtureNo()), List.of(new SportsAdminApi.PlayInput(play, line)), 1);
    String no = g.created().get(0).marketNo();
    admin.publish(Long.parseLong(no), true, 1);
    return marketOf(no);
  }

  /** 一次生成几个玩法、都上架 */
  protected List<Market> listedAll(Fx f, SportsAdminApi.PlayInput... plays) {
    SportsAdminApi.Generated g = sportsAdmin.generate(Long.parseLong(f.fixtureNo()), Arrays.asList(plays), 1);
    return g.created().stream().map(c -> {
      admin.publish(Long.parseLong(c.marketNo()), true, 1);
      return marketOf(c.marketNo());
    }).toList();
  }

  protected Market marketOf(String no) {
    long id = jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, Long.parseLong(no));
    MarketAdminApi.Detail d = admin.detail(Long.parseLong(no)).orElseThrow();
    return new Market(id, no, d.options().stream().map(MarketAdminApi.OptionView::optionNo).toList());
  }

  /** 两边各有一个客户各押 10（否则截止时只有一个选项有人押，按 E3 直接作废） */
  protected User[] twoSides(Market m, int a, int b) throws Exception {
    User x = user("100");
    User y = user("100");
    bet(x.id(), m, a, "10");
    bet(y.id(), m, b, "10");
    return new User[] {x, y};
  }

  /** 两个来源都报告完赛 */
  protected void finished(Fx f, int home, int away) {
    f.primary().finished(home, away);
    f.backup().finished(home, away);
  }

  protected String sportsTask(Market m) {
    List<String> s = jdbc.queryForList("SELECT status FROM resolve_sports_task WHERE market_id = ?", String.class, m.id());
    return s.isEmpty() ? null : s.get(0);
  }

  protected int fetchRows(Market m, String source) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM resolve_source_fetch WHERE market_id = ? AND source = ?", Integer.class, m.id(), source);
  }

  protected List<String> recordKinds(Market m) {
    return jdbc.queryForList("SELECT kind FROM resolve_record WHERE market_id = ? ORDER BY id", String.class, m.id());
  }

  protected String lastReason(Market m) {
    return jdbc.queryForObject("SELECT last_reason FROM resolve_sports_task WHERE market_id = ?", String.class, m.id());
  }

  /** 替身收到的、关于这场比赛的请求数（主源 + 备源） */
  protected long hitsFor(Fx f) {
    return STUB.requests().stream().filter(u -> u.getPath().endsWith("/" + f.primaryRef()) || ("id=" + f.backupRef()).equals(u.getQuery())).count();
  }

  /** 拨到 d 之后（相对现在） */
  protected void after(Duration d) {
    advance(d);
  }

  protected Duration until(Instant t) {
    Duration d = Duration.between(Instant.now(clock), t);
    return d.isNegative() ? Duration.ZERO : d;
  }
}
