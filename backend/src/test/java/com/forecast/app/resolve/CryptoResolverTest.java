package com.forecast.app.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.resolve.api.ResolveAdminApi;
import com.forecast.app.support.StubHttp.Reply;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 加密市场的自动裁决（forecast S6；需求说明书第 4.3 节、E9、E10、AC-27、AC-29；技术方案第 5.2、6 节）。替身数据源（本机 HTTP，照真实格式），真实的网关、存档、结算。
 */
class CryptoResolverTest extends CryptoTestSupport {

  @Autowired ResolveAdminApi resolveAdmin;

  @ParameterizedTest(name = "batch-size = {0}")
  @MethodSource("modes")
  void AC27_两源一致_窗口结束1分钟后自动结算_详情公开开始价结束价和来源(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Instant ws = nextWindow();
    Market m = cryptoMarket(ws);
    User up = user("300");
    User down = user("300");
    bet(up.id(), m, 0, "100");
    bet(down.id(), m, 1, "50");
    closeAndEnd(m);
    assertThat(status(m)).isEqualTo("PENDING_RESOLUTION");
    assertThat(taskStatus(m)).isEqualTo("PENDING");
    BOOK.price("BINANCE", "BTCUSDT", ws, "67000.10", "67100.25");
    BOOK.price("OKX", "BTCUSDT", ws, "67000.3", "67099.9");
    // 窗口结束后不到 1 分钟：不取
    advance(Duration.ofSeconds(59));
    assertThat(resolver.resolveOne(m.id())).isEqualTo("notDue");
    assertThat(STUB.hits()).isZero();
    advance(Duration.ofSeconds(1));
    String r = resolver.resolveOne(m.id());
    if (batch > 0) {
      assertThat(r).isEqualTo("settling");
      settle.drainAll();
    } else {
      assertThat(r).isEqualTo("settled");
    }
    assertThat(status(m)).isEqualTo("SETTLED");
    assertThat(taskStatus(m)).isEqualTo("RESOLVED");
    // T = 150，赢方 100：G = 50，F = 2.5，派彩 147.5
    assertThat(available(up.id())).isEqualByComparingTo("347.5");
    assertThat(available(down.id())).isEqualByComparingTo("250");
    settledConserved(m, "150");
    assertThat(jdbc.queryForObject("SELECT resolved_by FROM market_question WHERE id = ?", String.class, m.id())).isEqualTo("AUTO");
    assertThat(jdbc.queryForObject("SELECT actor FROM market_status_log WHERE market_id = ? AND to_status = 'SETTLED'", String.class, m.id())).isEqualTo("AUTO");
    assertThat(recordKinds(m)).containsExactly("AUTO_RESULT");
    // 原始返回存档：两个来源各一行，sha256 和原文一致（数据库里重算）
    assertThat(fetchRows(m, "BINANCE")).isEqualTo(1);
    assertThat(fetchRows(m, "OKX")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resolve_source_fetch WHERE market_id = ? AND body_sha256 = SHA2(body, 256) AND outcome = 'OK'",
        Integer.class, m.id())).isEqualTo(2);
    // 客户端详情：开始价、结束价、来源（AC-27、需求第 4.3 节）
    JsonNode d = getJson("/api/markets/" + m.no(), up.cookie());
    assertThat(d.path("status").asString()).isEqualTo("settled");
    assertThat(d.path("winningOptionNo").asString()).isEqualTo(m.options().get(0));
    JsonNode res = d.path("resolution");
    assertThat(res.path("mode").asString()).isEqualTo("auto");
    assertThat(res.path("result").asString()).isEqualTo("up");
    assertThat(res.path("resolvedBy").asString()).isEqualTo("AUTO");
    assertThat(res.path("startPrice").asString()).isEqualTo("67000.10");
    assertThat(res.path("endPrice").asString()).isEqualTo("67100.25");
    assertThat(res.path("source").asString()).isEqualTo("Binance BTC/USDT");
    assertThat(res.path("windowStart").asString()).isEqualTo(ws.toString());
    assertThat(res.path("sources")).hasSize(2);
    assertThat(res.path("sources").get(1).path("source").asString()).isEqualTo("OKX");
    assertThat(res.path("sources").get(1).path("startPrice").asString()).isEqualTo("67000.3");
    assertThat(res.path("sources").get(1).path("sha256").asString()).hasSize(64);
    // 窗口结束后 3 分钟内（这里是 1 分钟）
    assertThat(Duration.between(ws.plus(Duration.ofHours(1)), Instant.now(clock))).isLessThanOrEqualTo(Duration.ofMinutes(3));
    // 再跑不会重复
    assertThat(resolver.resolveOne(m.id())).isEqualTo("resolved");
    assertThat(inbox(up.id(), "SETTLE_WON")).isEqualTo(1);
  }

  @Test
  void E9_收盘等于开盘判涨() throws Exception {
    Instant ws = nextWindow();
    Market m = cryptoMarket(ws);
    User up = user("100");
    User down = user("100");
    bet(up.id(), m, 0, "10");
    bet(down.id(), m, 1, "10");
    endAndWaitFirstFetch(m);
    prices(ws, "67000", "67000.00");
    assertThat(resolver.resolveOne(m.id())).isEqualTo("settled");
    assertThat(available(up.id())).isEqualByComparingTo("109.5");
    assertThat(available(down.id())).isEqualByComparingTo("90");
  }

  @Test
  void 正好相等导致两源方向不同_以首选为准() throws Exception {
    Instant ws = nextWindow();
    Market m = cryptoMarket(ws);
    User up = user("100");
    User down = user("100");
    bet(up.id(), m, 0, "10");
    bet(down.id(), m, 1, "10");
    endAndWaitFirstFetch(m);
    BOOK.price("BINANCE", "BTCUSDT", ws, "500", "500");
    BOOK.price("OKX", "BTCUSDT", ws, "500", "499.99");
    assertThat(resolver.resolveOne(m.id())).isEqualTo("settled");
    assertThat(available(up.id())).isEqualByComparingTo("109.5");
    String note = jdbc.queryForObject("SELECT note FROM resolve_record WHERE market_id = ?", String.class, m.id());
    assertThat(note).contains("以首选为准");
  }

  @Test
  void AC29_两源不一致_每分钟重试_30分钟后转人工_告警() throws Exception {
    Instant ws = nextWindow();
    Market m = cryptoMarket(ws);
    User up = user("100");
    bet(up.id(), m, 0, "10");
    User down = user("100");
    bet(down.id(), m, 1, "10");
    endAndWaitFirstFetch(m);
    BOOK.price("BINANCE", "BTCUSDT", ws, "100", "101");
    BOOK.price("OKX", "BTCUSDT", ws, "100", "99");
    int rounds = 0;
    String r;
    while (true) {
      r = resolver.resolveOne(m.id());
      rounds++;
      if (!r.equals("retry")) break;
      // 同一分钟里再跑：不取（每分钟一次）
      int hits = STUB.hits();
      assertThat(resolver.resolveOne(m.id())).isEqualTo("notDue");
      assertThat(STUB.hits()).isEqualTo(hits);
      advance(Duration.ofMinutes(1));
      assertThat(rounds).isLessThan(40);
    }
    assertThat(r).isEqualTo("manual");
    // 窗口结束 + 1 分钟起每分钟一次，到 + 31 分钟（第一次取数后 30 分钟）转人工
    assertThat(rounds).isEqualTo(31);
    assertThat(taskStatus(m)).isEqualTo("MANUAL");
    assertThat(status(m)).as("市场留在待裁决，等 S7 的裁决台").isEqualTo("PENDING_RESOLUTION");
    assertThat(phase(m)).isEqualTo("NONE");
    assertThat(available(up.id())).isEqualByComparingTo("90");
    assertThat(recordKinds(m)).containsExactly("AUTO_FAILED");
    assertThat(fetchRows(m, "BINANCE")).isEqualTo(31);
    // 告警邮件（裁决员 + 超管：告警邮箱）
    verify(staff).notifyStaff(eq("RESOLVE_AUTO_FAILED"), argThat(p -> m.no().equals(p.get("marketNo")) && p.get("reason").contains("方向不一致")
        && "30".equals(p.get("minutes"))));
    // 后台：转人工的列表、裁决依据（两个来源的原始数据）
    assertThat(resolveAdmin.manual(1, 100).items()).anySatisfy(t -> assertThat(t.marketNo()).isEqualTo(m.no()));
    ResolveAdminApi.Evidence ev = resolveAdmin.evidence(Long.parseLong(m.no())).orElseThrow();
    assertThat(ev.task().status()).isEqualTo("manual");
    assertThat(ev.task().attempts()).isEqualTo(31);
    assertThat(ev.records()).hasSize(1);
    assertThat(ev.records().get(0).kind()).isEqualTo("auto_failed");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> src = (List<Map<String, Object>>) ((Map<String, Object>) ev.records().get(0).detail()).get("sources");
    assertThat(src).extracting(s -> s.get("source") + " " + s.get("direction")).containsExactly("BINANCE UP", "OKX DOWN");
    assertThat(ev.fetches()).hasSize(62);
    assertThat(ev.fetches().get(0).body()).isNotBlank();
    // 转人工以后不再取
    advance(Duration.ofMinutes(5));
    int hits = STUB.hits();
    assertThat(resolver.resolveOne(m.id())).isEqualTo("manual");
    assertThat(STUB.hits()).isEqualTo(hits);
    // 客户端详情没有裁决依据（还没结算）
    JsonNode none = getJson("/api/markets/" + m.no(), up.cookie()).path("resolution");
    assertThat(none.isNull() || none.isMissingNode()).isTrue();
  }

  @Test
  void 备源出错_重试_恢复后结算() throws Exception {
    Instant ws = nextWindow();
    Market m = cryptoMarket(ws);
    User up = user("100");
    User down = user("100");
    bet(up.id(), m, 0, "10");
    bet(down.id(), m, 1, "10");
    endAndWaitFirstFetch(m);
    prices(ws, "100", "90");
    BOOK.fault("OKX", Reply.status(503, "<html>Service Unavailable</html>"));
    assertThat(resolver.resolveOne(m.id())).isEqualTo("retry");
    // 一轮里 OKX 试了 3 次（有上限的退避重试），存档 3 行
    assertThat(jdbc.queryForList("SELECT CONCAT(attempt, ' ', http_status, ' ', outcome) FROM resolve_source_fetch WHERE market_id = ? AND source = 'OKX' ORDER BY id",
        String.class, m.id())).containsExactly("1 503 FAILED", "2 503 FAILED", "3 503 FAILED");
    assertThat(jdbc.queryForObject("SELECT last_reason FROM resolve_auto_task WHERE market_id = ?", String.class, m.id())).contains("OKX failed").contains("HTTP 503");
    advance(Duration.ofMinutes(1));
    BOOK.fault("OKX", null);
    assertThat(resolver.resolveOne(m.id())).isEqualTo("settled");
    assertThat(available(down.id())).isEqualByComparingTo("109.5");
  }

  @Test
  void 两个来源都连不上_熔断以后不再打接口_30分钟后转人工() throws Exception {
    Instant ws = nextWindow();
    Market m = cryptoMarket(ws);
    bothSides(m);
    endAndWaitFirstFetch(m);
    BOOK.fault("BINANCE", Reply.drop());
    BOOK.fault("OKX", Reply.slow(1_500, "[]")); // 超过读取超时（测试配置 1 秒）
    String r = "retry";
    int rounds = 0;
    long t0 = System.nanoTime();
    while (r.equals("retry")) {
      r = resolver.resolveOne(m.id());
      rounds++;
      advance(Duration.ofMinutes(1));
    }
    long seconds = (System.nanoTime() - t0) / 1_000_000_000;
    assertThat(r).isEqualTo("manual");
    assertThat(rounds).isEqualTo(31);
    // 第 1 轮 3 次、第 2 轮 2 次后熔断打开；之后每 60 秒只放 1 次探测：两个来源各约 5 + 30 次，远少于 31 × 3
    int binanceCalls = jdbc.queryForObject("SELECT COUNT(*) FROM resolve_source_fetch WHERE market_id = ? AND source = 'BINANCE' AND outcome <> 'CIRCUIT_OPEN'",
        Integer.class, m.id());
    assertThat(binanceCalls).isLessThanOrEqualTo(36);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resolve_source_fetch WHERE market_id = ? AND outcome = 'CIRCUIT_OPEN'", Integer.class, m.id()))
        .isPositive();
    // 慢的接口也不会把裁决任务卡住：31 轮合计远小于 31 × 3 × 1.5 秒
    assertThat(seconds).as("用时 %d 秒", seconds).isLessThan(60);
    verify(staff).notifyStaff(eq("RESOLVE_AUTO_FAILED"), argThat(p -> m.no().equals(p.get("marketNo")) && p.get("reason").contains("拿不到数据")));
  }

  @Test
  void 第三源补位_备源拿不到时用Coinbase() throws Exception {
    resolveProps.getSources().get("coinbase").setEnabled(true);
    Instant ws = nextWindow();
    Market m = cryptoMarket(ws);
    User up = user("100");
    User down = user("100");
    bet(up.id(), m, 0, "10");
    bet(down.id(), m, 1, "10");
    endAndWaitFirstFetch(m);
    BOOK.price("BINANCE", "BTCUSDT", ws, "100", "101");
    BOOK.price("COINBASE", "BTCUSDT", ws, "99.5", "100.7");
    BOOK.fault("OKX", Reply.status(500, "{}"));
    assertThat(resolver.resolveOne(m.id())).isEqualTo("settled");
    assertThat(available(up.id())).isEqualByComparingTo("109.5");
    String detail = jdbc.queryForObject("SELECT detail FROM resolve_record WHERE market_id = ?", String.class, m.id());
    assertThat(detail).contains("第三源补位").contains("COINBASE");
    JsonNode res = getJson("/api/markets/" + m.no(), up.cookie()).path("resolution");
    assertThat(res.path("sources")).extracting(n -> n.path("source").asString()).containsExactly("BINANCE", "COINBASE");
  }

  @Test
  void 第三源默认关闭_主备都拿到时不调用() throws Exception {
    Instant ws = nextWindow();
    Market m = cryptoMarket(ws);
    bothSides(m);
    endAndWaitFirstFetch(m);
    BOOK.price("BINANCE", "BTCUSDT", ws, "100", "101");
    BOOK.fault("OKX", Reply.status(500, "{}"));
    BOOK.price("COINBASE", "BTCUSDT", ws, "100", "101");
    assertThat(resolver.resolveOne(m.id())).isEqualTo("retry");
    assertThat(fetchRows(m, "COINBASE")).isZero();
  }

  @Test
  void K线还不全或未收盘_下一分钟再取() throws Exception {
    Instant ws = nextWindow();
    Market m = cryptoMarket(ws);
    User[] u = bothSides(m);
    endAndWaitFirstFetch(m);
    BOOK.unconfirmed("BTCUSDT", ws, "100", "101");
    // 币安还没有数据（空数组）
    assertThat(resolver.resolveOne(m.id())).isEqualTo("retry");
    assertThat(jdbc.queryForList("SELECT outcome FROM resolve_source_fetch WHERE market_id = ? ORDER BY source", String.class, m.id()))
        .containsExactly("NOT_READY", "NOT_READY");
    // 不重试、不算故障
    assertThat(fetchRows(m, "BINANCE")).isEqualTo(1);
    advance(Duration.ofMinutes(1));
    prices(ws, "100", "101");
    assertThat(resolver.resolveOne(m.id())).isEqualTo("settled");
    assertThat(available(u[0].id())).isEqualByComparingTo("109.5");
    assertThat(taskStatus(m)).isEqualTo("RESOLVED");
  }

  @Test
  void 市场已被人工作废_不再取数() throws Exception {
    Instant ws = nextWindow();
    Market m = cryptoMarket(ws);
    bothSides(m);
    endAndWaitFirstFetch(m);
    assertThat(marketService.transition(m.id(), MarketStatus.PENDING_RESOLUTION, MarketStatus.VOIDED, "1", "人工作废")).isTrue();
    prices(ws, "100", "101");
    assertThat(resolver.resolveOne(m.id())).isEqualTo("closed");
    assertThat(STUB.hits()).isZero();
    assertThat(taskStatus(m)).isEqualTo("CLOSED");
  }

  @Test
  void 结果定了但还没结算时进程退出_下一轮接着结算() throws Exception {
    Instant ws = nextWindow();
    Market m = cryptoMarket(ws);
    User[] u = bothSides(m);
    endAndWaitFirstFetch(m);
    // 模拟：结果已经记下（RESOLVING + AUTO_RESULT），还没调结算进程就退出了
    long up = optionId(m, 0);
    jdbc.update("UPDATE resolve_auto_task SET status = 'RESOLVING', result_option_id = ?, next_attempt_at = ? WHERE market_id = ?", up,
        java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(Instant.now(clock).plusSeconds(60), java.time.ZoneOffset.UTC)), m.id());
    assertThat(resolver.resolveOne(m.id())).isEqualTo("notDue");
    advance(Duration.ofMinutes(1));
    assertThat(resolver.resolveOne(m.id())).isEqualTo("settled");
    assertThat(STUB.hits()).as("结果已定，不再取数").isZero();
    assertThat(available(u[0].id())).isEqualByComparingTo("109.5");
    assertThat(taskStatus(m)).isEqualTo("RESOLVED");
    // 再跑一次：已经结算过，什么都不做
    assertThat(resolver.resolveOne(m.id())).isEqualTo("resolved");
    settledConserved(m, "20");
  }

  @Test
  void 同时跑三次只结算一次() throws Exception {
    Instant ws = nextWindow();
    Market m = cryptoMarket(ws);
    User up = user("100");
    User down = user("100");
    bet(up.id(), m, 0, "10");
    bet(down.id(), m, 1, "10");
    endAndWaitFirstFetch(m);
    prices(ws, "100", "101");
    ExecutorService ex = Executors.newFixedThreadPool(3);
    List<Future<String>> fs = new ArrayList<>();
    for (int i = 0; i < 3; i++) fs.add(ex.submit((Callable<String>) () -> resolver.resolveOne(m.id())));
    List<String> results = new ArrayList<>();
    for (Future<String> f : fs) results.add(f.get());
    ex.shutdown();
    assertThat(results).contains("settled");
    assertThat(status(m)).isEqualTo("SETTLED");
    assertThat(available(up.id())).isEqualByComparingTo("109.5");
    assertThat(recordKinds(m)).containsExactly("AUTO_RESULT");
    assertThat(inbox(up.id(), "SETTLE_WON")).isEqualTo(1);
    settledConserved(m, "20");
  }

  @Test
  void 定时任务一轮处理到时间的市场() throws Exception {
    Instant ws = nextWindow();
    Market a = cryptoMarket(ws);
    Market b = cryptoMarket(ws.plus(Duration.ofHours(1)));
    bothSides(a);
    bothSides(b);
    closeAndEnd(a);
    advance(Duration.ofMinutes(1));
    prices(ws, "100", "101");
    Map<Long, String> r = resolver.resolveDue();
    assertThat(r).containsEntry(a.id(), "settled");
    assertThat(r).doesNotContainKey(b.id()); // 还没到窗口结束
    assertThat(status(b)).isEqualTo("LISTED");
  }

  @Test
  void 手工建的加密市场缺自动裁决参数_进入待裁决时直接转人工() throws Exception {
    Market m = cryptoMarket(nextWindow(), Duration.ofHours(1), null);
    bothSides(m);
    closeAndEnd(m);
    assertThat(taskStatus(m)).isEqualTo("MANUAL");
    assertThat(recordKinds(m)).containsExactly("AUTO_FAILED");
    verify(staff, atLeastOnce()).notifyStaff(eq("RESOLVE_AUTO_FAILED"), argThat(p -> m.no().equals(p.get("marketNo"))));
  }

  @Test
  void 不是加密自动裁决的市场不建进度() throws Exception {
    Market m = shortMarket(2);
    bothSides(m);
    closeAndEnd(m);
    assertThat(status(m)).isEqualTo("PENDING_RESOLUTION");
    assertThat(taskStatus(m)).isNull();
    verify(staff, never()).notifyStaff(eq("RESOLVE_AUTO_FAILED"), argThat(p -> m.no().equals(p.get("marketNo"))));
  }
}
