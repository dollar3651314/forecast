package com.forecast.app.resolve.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.support.MutableClock;
import com.forecast.app.support.StubHttp;
import com.forecast.app.support.StubHttp.Reply;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
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
 * 数据源网关（forecast S6；agents C81、《测试工程师》C81；技术方案第 6.3 节）：用本机 HTTP 替身测<b>超时、断开、5xx、格式错误、熔断、存档</b>，
 * 以及有上限的重试、调用方不会被卡住、不能在事务里调用。超时等数值按测试改短（连接 300 毫秒、读取 500 毫秒、退避 10～40 毫秒），逻辑和生产相同。
 */
class SourceGatewayTest {

  static final CandleQuery Q = new CandleQuery("BTCUSDT", Instant.parse("2026-11-01T13:00:00Z"), Instant.parse("2026-11-01T14:00:00Z"));
  static final String OK_BODY = CandleSourceParseTest.binanceBody(Q.windowStart(), 60, "100", "101");

  final MutableClock clock = new MutableClock();
  final List<SourceArchive.Entry> archived = new CopyOnWriteArrayList<>();
  final AtomicInteger archiveFailures = new AtomicInteger();
  final BinanceSpot binance = new BinanceSpot();
  StubHttp stub;
  ResolveProperties props;
  SourceGateway gateway;

  @BeforeEach
  void setUp() {
    stub = new StubHttp();
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
    props.getSources().put("binance", new ResolveProperties.Source(true, stub.url()));
    SourceArchive archive = e -> {
      if (archiveFailures.get() > 0) {
        archiveFailures.decrementAndGet();
        throw new IllegalStateException("数据库写不进");
      }
      archived.add(e);
      return archived.size();
    };
    gateway = new SourceGateway(props, archive, clock);
  }

  @AfterEach
  void tearDown() {
    stub.close();
  }

  @Test
  void 成功_存档原文和sha256() {
    stub.handle(u -> Reply.ok(OK_BODY));
    SourceGateway.Result r = gateway.fetch(binance, Q, 42L);
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.OK);
    assertThat(r.candles().open()).isEqualByComparingTo("100");
    assertThat(r.candles().close()).isEqualByComparingTo("101");
    assertThat(r.attempts()).isEqualTo(1);
    assertThat(archived).hasSize(1);
    SourceArchive.Entry e = archived.get(0);
    assertThat(e.marketId()).isEqualTo(42L);
    assertThat(e.source()).isEqualTo("BINANCE");
    assertThat(e.outcome()).isEqualTo("OK");
    assertThat(e.httpStatus()).isEqualTo(200);
    assertThat(e.body()).isEqualTo(OK_BODY);
    assertThat(e.bodySha256()).isEqualTo(SourceGateway.sha256(OK_BODY.getBytes(StandardCharsets.UTF_8))).hasSize(64).isEqualTo(r.bodySha256());
    assertThat(e.requestUrl()).startsWith(stub.url() + "/api/v3/klines?symbol=BTCUSDT&interval=1m&startTime=");
    assertThat(e.parsed()).containsEntry("startPrice", "100").containsEntry("endPrice", "101").containsEntry("direction", "UP").containsEntry("count", 60);
    assertThat(stub.requests().get(0).getQuery()).contains("startTime=" + Q.windowStart().toEpochMilli()).contains("endTime=" + (Q.windowEnd().toEpochMilli() - 1));
  }

  @Test
  void 慢响应_读取超时_有上限地重试_调用方不会被卡住() {
    stub.handle(u -> Reply.slow(3_000, OK_BODY));
    long t0 = System.nanoTime();
    SourceGateway.Result r = gateway.fetch(binance, Q, 1L);
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.FAILED);
    assertThat(r.error()).startsWith("READ_TIMEOUT");
    assertThat(r.attempts()).isEqualTo(3);
    assertThat(stub.hits()).isEqualTo(3);
    // 3 次 × 读取超时 500 毫秒 + 退避 10 + 20，留余量也远小于替身的 3 秒 × 3
    assertThat(ms).as("用时 %d 毫秒", ms).isBetween(1_400L, 3_500L);
    assertThat(archived).hasSize(3).allSatisfy(e -> {
      assertThat(e.outcome()).isEqualTo("FAILED");
      assertThat(e.httpStatus()).isNull();
      assertThat(e.error()).startsWith("READ_TIMEOUT");
    });
    assertThat(archived.stream().map(SourceArchive.Entry::attempt).toList()).containsExactly(1, 2, 3);
  }

  @Test
  void 慢慢吐字节也算读取超时() {
    stub.handle(u -> Reply.trickle(100, OK_BODY));
    long t0 = System.nanoTime();
    SourceGateway.Result r = gateway.fetch(binance, Q, 1L);
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.FAILED);
    assertThat(r.error()).startsWith("READ_TIMEOUT");
    assertThat(ms).isLessThan(4_000L);
  }

  @Test
  void 连不上_连接被拒绝() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    props.getSources().put("binance", new ResolveProperties.Source(true, "http://127.0.0.1:" + port));
    SourceGateway.Result r = gateway.fetch(binance, Q, 1L);
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.FAILED);
    assertThat(r.error()).startsWith("CONNECT_FAILED");
    assertThat(r.attempts()).isEqualTo(3);
    assertThat(archived).hasSize(3);
  }

  @Test
  void 连接中途断开() {
    stub.handle(u -> Reply.drop());
    SourceGateway.Result r = gateway.fetch(binance, Q, 1L);
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.FAILED);
    assertThat(r.error()).startsWith("IO");
    assertThat(r.attempts()).isEqualTo(3);
  }

  @Test
  void HTTP_5xx_重试后成功() {
    AtomicInteger n = new AtomicInteger();
    stub.handle(u -> n.incrementAndGet() == 1 ? Reply.status(502, "<html>bad gateway</html>") : Reply.ok(OK_BODY));
    SourceGateway.Result r = gateway.fetch(binance, Q, 1L);
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.OK);
    assertThat(r.attempts()).isEqualTo(2);
    assertThat(archived).hasSize(2);
    assertThat(archived.get(0).outcome()).isEqualTo("FAILED");
    assertThat(archived.get(0).httpStatus()).isEqualTo(502);
    assertThat(archived.get(0).body()).isEqualTo("<html>bad gateway</html>");
    assertThat(archived.get(0).bodySha256()).isEqualTo(SourceGateway.sha256("<html>bad gateway</html>".getBytes(StandardCharsets.UTF_8)));
    assertThat(archived.get(1).outcome()).isEqualTo("OK");
  }

  @Test
  void HTTP_5xx_一直失败_次数有上限() {
    stub.handle(u -> Reply.status(500, "{}"));
    SourceGateway.Result r = gateway.fetch(binance, Q, 1L);
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.FAILED);
    assertThat(r.error()).isEqualTo("HTTP 500");
    assertThat(stub.hits()).isEqualTo(3);
  }

  @Test
  void 限频429重试_其他4xx不重试() {
    stub.handle(u -> Reply.status(429, "{\"code\":-1003}"));
    assertThat(gateway.fetch(binance, Q, 1L).attempts()).isEqualTo(3);
    stub.clear();
    gateway.resetBreakers();
    stub.handle(u -> Reply.status(451, "{\"code\":0,\"msg\":\"restricted location\"}"));
    SourceGateway.Result r = gateway.fetch(binance, Q, 1L);
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.FAILED);
    assertThat(r.error()).isEqualTo("HTTP 451");
    assertThat(stub.hits()).isEqualTo(1);
  }

  @Test
  void 返回格式不对_不重试_算失败() {
    stub.handle(u -> Reply.ok("{\"unexpected\": true}"));
    SourceGateway.Result r = gateway.fetch(binance, Q, 1L);
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.FAILED);
    assertThat(r.error()).startsWith("格式不对");
    assertThat(stub.hits()).isEqualTo(1);
    assertThat(archived.get(0).body()).isEqualTo("{\"unexpected\": true}");
    assertThat(gateway.breaker("BINANCE").consecutiveFailures()).isEqualTo(1);
  }

  @Test
  void K线还不全_不重试_不算故障() {
    stub.handle(u -> Reply.ok(CandleSourceParseTest.binanceBody(Q.windowStart(), 30, "1", "2")));
    SourceGateway.Result r = gateway.fetch(binance, Q, 1L);
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.NOT_READY);
    assertThat(stub.hits()).isEqualTo(1);
    assertThat(archived.get(0).outcome()).isEqualTo("NOT_READY");
    assertThat(gateway.breaker("BINANCE").consecutiveFailures()).isZero();
  }

  @Test
  void 响应太大_断开_不重试() {
    props.getGateway().setMaxBodyBytes(1_000);
    stub.handle(u -> Reply.ok(OK_BODY));
    SourceGateway.Result r = gateway.fetch(binance, Q, 1L);
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.FAILED);
    assertThat(r.error()).startsWith("TOO_LARGE");
    assertThat(stub.hits()).isEqualTo(1);
  }

  @Test
  void 熔断_连续5次失败打开_不再调用_到时间放1次探测() {
    stub.handle(u -> Reply.status(500, "{}"));
    gateway.fetch(binance, Q, 1L); // 3 次失败
    assertThat(gateway.breaker("BINANCE").state()).isEqualTo(CircuitBreaker.State.CLOSED);
    SourceGateway.Result r = gateway.fetch(binance, Q, 1L); // 第 4、5 次失败 → 打开；第 6 次不调用
    assertThat(stub.hits()).isEqualTo(5);
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.CIRCUIT_OPEN);
    assertThat(gateway.breaker("BINANCE").state()).isEqualTo(CircuitBreaker.State.OPEN);
    // 打开期间：直接返回，不打接口，照样存档一行（裁决台能看到为什么没数据）
    archived.clear();
    SourceGateway.Result again = gateway.fetch(binance, Q, 1L);
    assertThat(again.outcome()).isEqualTo(SourceGateway.Outcome.CIRCUIT_OPEN);
    assertThat(again.attempts()).isZero();
    assertThat(stub.hits()).isEqualTo(5);
    assertThat(archived).hasSize(1);
    assertThat(archived.get(0).outcome()).isEqualTo("CIRCUIT_OPEN");
    assertThat(archived.get(0).httpStatus()).isNull();
    // 59 秒还开着
    clock.advance(Duration.ofSeconds(59));
    assertThat(gateway.fetch(binance, Q, 1L).outcome()).isEqualTo(SourceGateway.Outcome.CIRCUIT_OPEN);
    assertThat(stub.hits()).isEqualTo(5);
    // 60 秒：半开放，放 1 次探测；探测失败 → 再打开 60 秒（这一轮不再重试）
    clock.advance(Duration.ofSeconds(1));
    assertThat(gateway.breaker("BINANCE").state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    SourceGateway.Result probe = gateway.fetch(binance, Q, 1L);
    assertThat(stub.hits()).isEqualTo(6);
    assertThat(probe.outcome()).isEqualTo(SourceGateway.Outcome.CIRCUIT_OPEN);
    assertThat(gateway.breaker("BINANCE").state()).isEqualTo(CircuitBreaker.State.OPEN);
    // 再过 60 秒，接口恢复：探测成功 → 关闭
    clock.advance(Duration.ofSeconds(60));
    stub.handle(u -> Reply.ok(OK_BODY));
    SourceGateway.Result ok = gateway.fetch(binance, Q, 1L);
    assertThat(ok.outcome()).isEqualTo(SourceGateway.Outcome.OK);
    assertThat(stub.hits()).isEqualTo(7);
    assertThat(gateway.breaker("BINANCE").state()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(gateway.breaker("BINANCE").consecutiveFailures()).isZero();
  }

  @Test
  void 半开放时只放一个探测() {
    CircuitBreaker b = new CircuitBreaker(1, Duration.ofSeconds(60), clock);
    b.failure();
    assertThat(b.allow()).isFalse();
    clock.advance(Duration.ofSeconds(60));
    assertThat(b.allow()).isTrue();
    assertThat(b.allow()).as("探测还没回来时，别的调用不放").isFalse();
    b.success();
    assertThat(b.allow()).isTrue();
  }

  @Test
  void 存档失败_不用这份数据() {
    stub.handle(u -> Reply.ok(OK_BODY));
    archiveFailures.set(1);
    SourceGateway.Result r = gateway.fetch(binance, Q, 1L);
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.FAILED);
    assertThat(r.error()).isEqualTo("存档失败");
  }

  @Test
  void 没打开的数据源不调用() {
    props.getSources().put("binance", new ResolveProperties.Source(false, stub.url()));
    SourceGateway.Result r = gateway.fetch(binance, Q, 1L);
    assertThat(r.outcome()).isEqualTo(SourceGateway.Outcome.DISABLED);
    assertThat(stub.hits()).isZero();
    assertThat(archived).isEmpty();
  }

  @Test
  void 不能在数据库事务里调用() {
    stub.handle(u -> Reply.ok(OK_BODY));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> gateway.fetch(binance, Q, 1L)).isInstanceOf(IllegalStateException.class).hasMessageContaining("事务");
      assertThat(stub.hits()).isZero();
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  @Test
  void 退避间隔逐次翻倍_有上限() {
    ResolveProperties.Gateway g = new ResolveProperties.Gateway();
    assertThat(SourceGateway.backoff(g, 1)).isEqualTo(Duration.ofSeconds(1));
    assertThat(SourceGateway.backoff(g, 2)).isEqualTo(Duration.ofSeconds(2));
    assertThat(SourceGateway.backoff(g, 3)).isEqualTo(Duration.ofSeconds(4));
    assertThat(SourceGateway.backoff(g, 4)).isEqualTo(Duration.ofSeconds(4));
    // 默认值（架构方案第 6.3 节）
    assertThat(g.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(g.getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(g.getAttempts()).isEqualTo(3);
    assertThat(g.getFailureThreshold()).isEqualTo(5);
    assertThat(g.getOpenDuration()).isEqualTo(Duration.ofSeconds(60));
  }
}
