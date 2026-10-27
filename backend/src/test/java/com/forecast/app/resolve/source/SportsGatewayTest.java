package com.forecast.app.resolve.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.support.MutableClock;
import com.forecast.app.support.ScoreBook;
import com.forecast.app.support.StubHttp;
import com.forecast.app.support.StubHttp.Reply;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 体育数据源经网关调用的各种故障（forecast S8；agents C81、《测试工程师》C81；技术方案第 6.3 节）：用本机 HTTP 替身测<b>超时、断开、连不上、5xx、429 限频、
 * 格式错误、熔断</b>，以及<b>限频排队</b>（不超过每分钟 N 次）、<b>未开通</b>（没配 key 不调用）、<b>key 不进存档</b>、同一次调用给几个市场各存一行。
 * 超时等数值按测试改短（连接 300 毫秒、读取 500 毫秒、退避 10～40 毫秒），逻辑和生产相同。
 */
class SportsGatewayTest {

  final MutableClock clock = new MutableClock();
  final List<SourceArchive.Entry> archived = new CopyOnWriteArrayList<>();
  final ScoreBook book = new ScoreBook();
  final FootballData fd = new FootballData();
  final TheSportsDb ts = new TheSportsDb();
  final Balldontlie bdl = new Balldontlie();
  StubHttp stub;
  ResolveProperties props;
  SourceGateway gateway;

  @BeforeEach
  void setUp() {
    stub = new StubHttp();
    stub.handle(book.handler());
    props = new ResolveProperties();
    ResolveProperties.Gateway g = props.getGateway();
    g.setConnectTimeout(Duration.ofMillis(300));
    g.setReadTimeout(Duration.ofMillis(500));
    g.setAttempts(3);
    g.setBackoff(Duration.ofMillis(10));
    g.setMaxBackoff(Duration.ofMillis(40));
    g.setFailureThreshold(5);
    g.setOpenDuration(Duration.ofSeconds(60));
    g.setMaxBodyBytes(64 * 1024);
    props.getSources().put("football-data", src(stub.url() + "/fd", "fd-secret-token", "FC_SRC_FOOTBALL_DATA_TOKEN"));
    props.getSources().put("thesportsdb", src(stub.url() + "/tsdb", "tsdb-secret-key", "FC_SRC_THESPORTSDB_KEY"));
    props.getSources().put("balldontlie", src(stub.url() + "/bdl", null, "FC_SRC_BALLDONTLIE_KEY"));
    gateway = new SourceGateway(props, e -> {
      archived.add(e);
      return archived.size();
    }, clock);
    book.add("FOOTBALL_DATA", "537877", "FOOTBALL", "Arsenal FC", "Everton FC", Instant.parse("2026-10-24T14:00:00Z")).finished(2, 0);
    book.add("THESPORTSDB", "2494072", "FOOTBALL", "Arsenal", "Everton", Instant.parse("2026-10-24T14:00:00Z")).finished(2, 0);
  }

  static ResolveProperties.Source src(String base, String key, String env) {
    ResolveProperties.Source s = new ResolveProperties.Source(true, base);
    s.setKey(key);
    s.setKeyRequired(true);
    s.setKeyEnv(env);
    return s;
  }

  @AfterEach
  void tearDown() {
    stub.close();
  }

  SourceReply<ScoreFixture> fdOne(List<Long> markets) {
    return gateway.call(fd.fixture(gateway.config("football-data"), "FOOTBALL", "537877"), markets, null);
  }

  @Test
  void 成功_带上key_每个市场各存一行原文和sha256_存档里没有key() {
    SourceReply<ScoreFixture> r = fdOne(List.of(11L, 12L));
    assertThat(r.ok()).isTrue();
    assertThat(r.value().homeScore()).isEqualTo(2);
    assertThat(r.httpStatus()).isEqualTo(200);
    assertThat(stub.hits()).as("一次调用").isEqualTo(1);
    assertThat(stub.headers().get(0)).containsEntry("x-auth-token", "fd-secret-token").containsEntry("user-agent", "forecast-resolve/1");
    assertThat(archived).hasSize(2).extracting(SourceArchive.Entry::marketId).containsExactly(11L, 12L);
    assertThat(archived).allSatisfy(e -> {
      assertThat(e.source()).isEqualTo("FOOTBALL_DATA");
      assertThat(e.outcome()).isEqualTo("OK");
      assertThat(e.bodySha256()).isEqualTo(r.bodySha256()).hasSize(64);
      assertThat(e.parsed()).containsEntry("homeScore", 2).containsEntry("status", "FINISHED");
      assertThat(e.requestUrl()).doesNotContain("fd-secret-token");
    });
    // TheSportsDB 的 key 在路径里：请求带了，存档里换成 {key}
    SourceReply<ScoreFixture> t = gateway.call(ts.fixture(gateway.config("thesportsdb"), "FOOTBALL", "2494072"), List.of(), null);
    assertThat(t.ok()).isTrue();
    assertThat(book.auth()).containsExactly("THESPORTSDB:tsdb-secret-key");
    SourceArchive.Entry last = archived.get(archived.size() - 1);
    assertThat(last.marketId()).as("同步、找候选没有市场").isNull();
    assertThat(last.requestUrl()).contains("/api/v1/json/{key}/lookupevent.php?id=2494072").doesNotContain("tsdb-secret-key");
  }

  /** S12b：告警项里没配 key 的体育数据源是"未开通"（健康检查照样 DOWN，不发告警邮件）；配了 key 的正常时是 UP */
  @Test
  void S12b_告警项_没配key是未开通_配了是正常() {
    List<com.forecast.app.common.health.AlertCheck.Item> items = new SourceAlerts(gateway, List.of(), List.of(fd, ts, bdl), props).evaluate();
    java.util.Map<String, com.forecast.app.common.health.AlertCheck.Status> st = new java.util.TreeMap<>();
    for (var i : items) st.put(i.name(), i.status());
    assertThat(st).containsEntry("source.balldontlie", com.forecast.app.common.health.AlertCheck.Status.NOT_CONFIGURED)
        .containsEntry("source.football_data", com.forecast.app.common.health.AlertCheck.Status.UP)
        .containsEntry("source.thesportsdb", com.forecast.app.common.health.AlertCheck.Status.UP);
  }

  @Test
  void 没配key_未开通_不调用() {
    SourceReply<ScoreFixture> r = gateway.call(bdl.fixture(gateway.config("balldontlie"), "NBA", "1"), List.of(1L), null);
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.DISABLED);
    assertThat(r.error()).contains("未开通").contains("FC_SRC_BALLDONTLIE_KEY");
    assertThat(stub.hits()).isZero();
    assertThat(archived).isEmpty();
    assertThat(gateway.usable("balldontlie")).isFalse();
    assertThat(gateway.usable("football-data")).isTrue();
  }

  @Test
  void 慢响应_读取超时_重试3次_调用方不会被卡住() {
    book.fault("FOOTBALL_DATA", Reply.slow(3_000, "{}"));
    long t0 = System.nanoTime();
    SourceReply<ScoreFixture> r = fdOne(List.of(1L));
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.FAILED);
    assertThat(r.error()).startsWith("READ_TIMEOUT");
    assertThat(r.attempts()).isEqualTo(3);
    assertThat(stub.hits()).isEqualTo(3);
    assertThat(ms).as("用时 %d 毫秒", ms).isBetween(1_400L, 3_500L);
    assertThat(archived).hasSize(3).allSatisfy(e -> assertThat(e.outcome()).isEqualTo("FAILED"));
  }

  @Test
  void 单个来源的超时覆盖网关默认() {
    props.source("football-data").setReadTimeout(Duration.ofMillis(200));
    props.source("football-data").setAttempts(1);
    book.fault("FOOTBALL_DATA", Reply.slow(1_000, "{}"));
    long t0 = System.nanoTime();
    SourceReply<ScoreFixture> r = fdOne(List.of());
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(r.error()).startsWith("READ_TIMEOUT");
    assertThat(r.attempts()).isEqualTo(1);
    assertThat(ms).isLessThan(800L);
  }

  @Test
  void 连接中途断开_重试有上限() {
    book.fault("FOOTBALL_DATA", Reply.drop());
    SourceReply<ScoreFixture> r = fdOne(List.of());
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.FAILED);
    assertThat(r.error()).startsWith("IO");
    assertThat(r.attempts()).isEqualTo(3);
    // JDK 的 HttpClient 对 GET 在连接断开时自己再试 1 次：网关 3 次 × 最多 2 = 6，仍然有上限
    assertThat(stub.hits()).isBetween(3, 6);
  }

  @Test
  void 连接被拒绝() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    props.source("football-data").setBaseUrl("http://127.0.0.1:" + port);
    SourceReply<ScoreFixture> r = fdOne(List.of());
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.FAILED);
    assertThat(r.error()).startsWith("CONNECT_FAILED");
    assertThat(r.attempts()).isEqualTo(3);
  }

  @Test
  void 服务器5xx_重试后成功() {
    AtomicInteger n = new AtomicInteger();
    stub.handle(u -> n.incrementAndGet() <= 2 ? Reply.status(503, "Service Unavailable") : book.handler().apply(u));
    SourceReply<ScoreFixture> r = fdOne(List.of(1L));
    assertThat(r.ok()).isTrue();
    assertThat(r.attempts()).isEqualTo(3);
    assertThat(archived).extracting(SourceArchive.Entry::outcome).containsExactly("FAILED", "FAILED", "OK");
    assertThat(archived.get(0).httpStatus()).isEqualTo(503);
  }

  @Test
  void 其他4xx和格式不对_不重试() {
    book.fault("FOOTBALL_DATA", Reply.status(403, "{\"message\":\"The resource you are looking for is restricted.\",\"errorCode\":403}"));
    SourceReply<ScoreFixture> r = fdOne(List.of());
    assertThat(r.error()).isEqualTo("HTTP 403");
    assertThat(stub.hits()).isEqualTo(1);
    book.fault("FOOTBALL_DATA", Reply.ok("<html>maintenance</html>"));
    stub.clear();
    SourceReply<ScoreFixture> bad = fdOne(List.of(5L));
    assertThat(bad.outcome()).isEqualTo(SourceGateway.Outcome.FAILED);
    assertThat(bad.error()).startsWith("格式不对：");
    assertThat(stub.hits()).isEqualTo(1);
    assertThat(archived.get(archived.size() - 1).body()).as("格式不对的原文也存档").isEqualTo("<html>maintenance</html>");
  }

  @Test
  void 限频429_按RetryAfter暂停后重试成功() {
    props.source("football-data").setRateLimit(100);
    AtomicInteger n = new AtomicInteger();
    stub.handle(u -> n.incrementAndGet() == 1 ? new Reply(429, "{\"message\":\"You reached your request limit.\",\"errorCode\":429}", 0, false, 0)
        : book.handler().apply(u));
    // StubHttp 不回 Retry-After：按限频窗口暂停；测试把窗口改成 1 秒
    props.source("football-data").setRateWindow(Duration.ofSeconds(1));
    long t0 = System.nanoTime();
    SourceReply<ScoreFixture> r = fdOne(List.of(1L));
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(r.ok()).isTrue();
    assertThat(r.attempts()).isEqualTo(2);
    assertThat(ms).as("暂停了约 1 秒才重试").isGreaterThanOrEqualTo(900L);
    assertThat(archived.get(0).httpStatus()).isEqualTo(429);
    assertThat(archived.get(0).error()).contains("限频，暂停 1 秒");
  }

  @Test
  void 限频429_一直限频_排队超过上限就不再调用() {
    props.source("football-data").setRateLimit(100);
    props.source("football-data").setRateWindow(Duration.ofSeconds(30));
    props.source("football-data").setQueueWait(Duration.ofMillis(200));
    book.fault("FOOTBALL_DATA", new Reply(429, "{\"errorCode\":429}", 0, false, 0));
    long t0 = System.nanoTime();
    SourceReply<ScoreFixture> r = fdOne(List.of(1L));
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.THROTTLED);
    assertThat(r.error()).contains("限频排队").contains("HTTP 429");
    assertThat(stub.hits()).as("429 以后暂停 30 秒，不再打").isEqualTo(1);
    assertThat(ms).isLessThan(2_000L);
    assertThat(archived).extracting(SourceArchive.Entry::outcome).containsExactly("FAILED", "THROTTLED");
  }

  @Test
  void 按限频排队_不超过每个窗口N次_排队太久的这一轮不调用() {
    props.source("football-data").setRateLimit(3);
    props.source("football-data").setRateWindow(Duration.ofSeconds(1));
    long t0 = System.nanoTime();
    for (int i = 0; i < 4; i++) assertThat(fdOne(List.of()).ok()).isTrue();
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(stub.hits()).isEqualTo(4);
    assertThat(ms).as("第 4 次排队等到第 1 次之后 1 秒").isGreaterThanOrEqualTo(900L);
    // 排队上限 100 毫秒：窗口里已经 3 次，第 4 次排不上，不调用、不算故障
    props.source("football-data").setQueueWait(Duration.ofMillis(100));
    for (int i = 0; i < 3; i++) fdOne(List.of());
    int before = stub.hits();
    SourceReply<ScoreFixture> r = fdOne(List.of(9L));
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.THROTTLED);
    assertThat(r.attempts()).isZero();
    assertThat(stub.hits()).isEqualTo(before);
    assertThat(gateway.breaker("FOOTBALL_DATA").consecutiveFailures()).as("排队不算故障").isZero();
    // 同一个网站的不同接口共用限额（按配置的 key）
    assertThat(gateway.limiter("football-data")).isSameAs(gateway.limiter("football-data"));
  }

  @Test
  void 熔断_连续5次失败打开_打开期间不打接口但存档_60秒后半开放探测_恢复后关闭() {
    book.fault("FOOTBALL_DATA", Reply.status(500, "oops"));
    props.getGateway().setAttempts(1);
    for (int i = 0; i < 5; i++) assertThat(fdOne(List.of(1L)).outcome()).isEqualTo(SourceGateway.Outcome.FAILED);
    assertThat(gateway.breaker("FOOTBALL_DATA").state()).isEqualTo(CircuitBreaker.State.OPEN);
    int hits = stub.hits();
    SourceReply<ScoreFixture> r = fdOne(List.of(1L));
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.CIRCUIT_OPEN);
    assertThat(stub.hits()).isEqualTo(hits);
    assertThat(archived.get(archived.size() - 1).outcome()).isEqualTo("CIRCUIT_OPEN");
    // 别的来源不受影响
    assertThat(gateway.call(ts.fixture(gateway.config("thesportsdb"), "FOOTBALL", "2494072"), List.of(), null).ok()).isTrue();
    clock.advance(Duration.ofSeconds(60));
    book.fault("FOOTBALL_DATA", null);
    assertThat(fdOne(List.of(1L)).ok()).as("半开放的探测成功").isTrue();
    assertThat(gateway.breaker("FOOTBALL_DATA").state()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void 在数据库事务里调用直接报错() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> fdOne(List.of())).isInstanceOf(IllegalStateException.class).hasMessageContaining("C81");
      assertThat(stub.hits()).isZero();
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  @Test
  void 备源TheSportsDB找候选_按联赛和日期_按队名和日期() {
    book.add("THESPORTSDB", "2494070", "FOOTBALL", "Manchester United", "Bournemouth", Instant.parse("2026-10-25T14:00:00Z"));
    ResolveProperties.Source c = gateway.config("thesportsdb");
    SourceReply<List<ScoreFixture>> day = gateway.call(ts.day(c, "FOOTBALL", "4328", java.time.LocalDate.parse("2026-10-25")), List.of(), null);
    assertThat(day.ok()).isTrue();
    assertThat(day.value()).extracting(ScoreFixture::ref).containsExactly("2494070");
    SourceReply<List<ScoreFixture>> byName = gateway.call(ts.searchByNames(c, "FOOTBALL", "Manchester United", "Bournemouth", java.time.LocalDate.parse("2026-10-25")),
        List.of(), null);
    assertThat(byName.value()).extracting(ScoreFixture::homeName).containsExactly("Manchester United");
    assertThat(gateway.call(ts.searchByNames(c, "FOOTBALL", "Manchester United", "Bournemouth", java.time.LocalDate.parse("2026-12-01")), List.of(), null).value())
        .as("别的日期没有").isEmpty();
    assertThat(archived.get(archived.size() - 1).parsed()).containsEntry("count", 0);
  }
}
