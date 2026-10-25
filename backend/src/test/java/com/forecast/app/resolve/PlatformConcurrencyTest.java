package com.forecast.app.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.forecast.app.common.error.BizException;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.resolve.api.ResolveDeskApi;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 平台裁决的并发（forecast S7；技术方案/v1-架构方案.md 第 5.3 节、第 17 节；真实 MySQL、Redis）。每个用例都是多个线程用同一个起跑线（CountDownLatch）同时触发：
 * <ul>
 *   <li>公示期满的结算 vs 提交争议（收到时间在截止前 1 毫秒）：只有一个生效——要么争议成立、不结算；要么结算、争议被拒（市场行锁上排队）。</li>
 *   <li>到期结算多个实例同时跑 + 直接触发：只结算一次（结算的三道防重复）。</li>
 *   <li>争议处理（维持）和到期结算任务、重复交给结算同时：只结算一次，结果是维持的结果。</li>
 *   <li>同一个人同时提交两次争议：只算一次（唯一键 + 市场行锁）。</li>
 *   <li>确认同一条提议多次：只公示一次，#10 每人一条。</li>
 * </ul>
 */
class PlatformConcurrencyTest extends PlatformTestSupport {

  record Setup(Market m, User a, User b) {}

  Setup publicized(int window) throws Exception {
    Market m = platformMarket(2, window);
    User a = user("100");
    User b = user("100");
    bet(a.id(), m, 0, "60");
    bet(b.id(), m, 1, "40");
    closeAndEnd(m);
    publicize(m, 0, 7);
    return new Setup(m, a, b);
  }

  /** 同时跑这些任务，返回每个的结果（正常返回 OK，业务拒绝返回错误码，其他异常原样抛出） */
  static List<String> race(List<Callable<Object>> tasks) throws Exception {
    ExecutorService ex = Executors.newFixedThreadPool(tasks.size());
    CountDownLatch go = new CountDownLatch(1);
    List<Future<String>> fs = new ArrayList<>();
    for (Callable<Object> t : tasks) {
      fs.add(ex.submit(() -> {
        go.await();
        try {
          t.call();
          return "OK";
        } catch (BizException e) {
          return e.code();
        }
      }));
    }
    go.countDown();
    List<String> out = new ArrayList<>();
    for (Future<String> f : fs) out.add(f.get(60, TimeUnit.SECONDS));
    ex.shutdown();
    return out;
  }

  int ledgerCount(long uid, String type) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry e JOIN ledger_account a ON a.id = e.account_id WHERE a.user_id = ? AND e.entry_type = ?",
        Integer.class, uid, type);
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void 公示期满的结算和截止前一刻的争议同时到_只有一个生效(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    int disputed = 0;
    int settled = 0;
    for (int round = 0; round < 8; round++) {
      Setup s = publicized(2);
      Market m = s.m();
      Instant deadline = deadline(m);
      advance(Duration.between(Instant.now(clock), deadline));
      List<Callable<Object>> tasks = new ArrayList<>();
      for (int i = 0; i < 3; i++) tasks.add(() -> publicity.settleDue());
      tasks.add(() -> desk.dispute(no(m), s.b().id(), new ResolveDeskApi.DisputeInput(m.options().get(1), "最后一刻提交", null), deadline.minusMillis(1)));
      List<String> r = race(tasks);
      String disputeResult = r.get(3);
      if (batch > 0) settle.drainAll();
      int disputes = jdbc.queryForObject("SELECT COUNT(*) FROM resolve_dispute WHERE market_id = ?", Integer.class, m.id());
      if ("OK".equals(disputeResult)) {
        disputed++;
        assertThat(status(m)).isEqualTo("DISPUTED");
        assertThat(settleRuns(m)).as("争议先到：不结算").isZero();
        assertThat(disputes).isEqualTo(1);
        assertThat(available(s.a().id())).isEqualByComparingTo("40");
      } else {
        settled++;
        assertThat(disputeResult).isEqualTo("DISPUTE_CLOSED");
        assertThat(status(m)).isEqualTo("SETTLED");
        assertThat(settleRuns(m)).isEqualTo(1);
        assertThat(disputes).as("结算先开始：争议不记").isZero();
        assertThat(available(s.a().id())).isEqualByComparingTo("138");
        assertThat(ledgerCount(s.a().id(), "PAYOUT")).isEqualTo(1);
        settledConserved(m, "100");
      }
    }
    assertThat(disputed + settled).isEqualTo(8);
    System.out.println("公示期满 vs 截止前一刻的争议（batch-size " + batch + "）：争议先到 " + disputed + " 次，结算先开始 " + settled + " 次");
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void 到期结算多个实例同时跑加直接触发_只结算一次(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Setup s = publicized(2);
    Market m = s.m();
    advance(Duration.between(Instant.now(clock), deadline(m)));
    long win = optionId(m, 0);
    List<Callable<Object>> tasks = new ArrayList<>();
    for (int i = 0; i < 4; i++) tasks.add(() -> publicity.settleDue());
    for (int i = 0; i < 2; i++) tasks.add(() -> {
      settle.settle(m.id(), win, null, "7", MarketStatus.PUBLICIZING);
      return null;
    });
    for (int i = 0; i < 2; i++) tasks.add(() -> publicity.requestSettle(m.id(), MarketStatus.PUBLICIZING));
    assertThat(race(tasks)).containsOnly("OK");
    if (batch > 0) settle.drainAll();
    assertThat(status(m)).isEqualTo("SETTLED");
    assertThat(settleRuns(m)).isEqualTo(1);
    assertThat(available(s.a().id())).isEqualByComparingTo("138");
    assertThat(ledgerCount(s.a().id(), "PAYOUT")).isEqualTo(1);
    assertThat(inbox(s.a().id(), "SETTLE_WON")).isEqualTo(1);
    assertThat(records(m).stream().filter("PUBLICITY_END"::equals).count()).isEqualTo(1);
    settledConserved(m, "100");
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void 争议处理和到期结算任务同时_只结算一次_按处理的结论(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Setup s = publicized(2);
    Market m = s.m();
    assertThat(tryDispute(s.b().id(), m, m.options().get(1), "不对")).isEqualTo("OK");
    advance(Duration.between(Instant.now(clock), deadline(m)).plusMinutes(1)); // 争议窗口也过了
    List<Callable<Object>> tasks = new ArrayList<>();
    for (int i = 0; i < 2; i++) tasks.add(() -> desk.decideDisputes(no(m), new ResolveDeskApi.DecisionInput("uphold", null, "维持"), 9));
    for (int i = 0; i < 3; i++) tasks.add(() -> publicity.settleDue());
    for (int i = 0; i < 2; i++) tasks.add(() -> publicity.requestSettle(m.id(), MarketStatus.DISPUTED));
    List<String> r = race(tasks);
    assertThat(r.subList(0, 2)).containsExactlyInAnyOrder("OK", "DISPUTE_NOT_OPEN");
    publicity.settleDue(); // 处理的那个线程交给结算时，别的线程可能已经交过：再跑一轮也不会多结算
    if (batch > 0) settle.drainAll();
    assertThat(status(m)).isEqualTo("SETTLED");
    assertThat(settleRuns(m)).isEqualTo(1);
    assertThat(available(s.a().id())).isEqualByComparingTo("138");
    assertThat(ledgerCount(s.a().id(), "PAYOUT")).isEqualTo(1);
    assertThat(records(m).stream().filter(k -> k.startsWith("DISPUTE_")).toList()).containsExactly("DISPUTE_UPHOLD");
    settledConserved(m, "100");
  }

  @Test
  void 同一个人同时提交8次争议_只算一次() throws Exception {
    Setup s = publicized(2);
    Market m = s.m();
    List<Callable<Object>> tasks = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      int k = i;
      tasks.add(() -> desk.dispute(no(m), s.b().id(), new ResolveDeskApi.DisputeInput(m.options().get(1), "第 " + k + " 次", null), Instant.now(clock)));
    }
    List<String> r = race(tasks);
    assertThat(Collections.frequency(r, "OK")).isEqualTo(1);
    assertThat(Collections.frequency(r, "DISPUTE_EXISTS")).isEqualTo(7);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resolve_dispute WHERE market_id = ?", Integer.class, m.id())).isEqualTo(1);
    assertThat(status(m)).isEqualTo("DISPUTED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_status_log WHERE market_id = ? AND to_status = 'DISPUTED'", Integer.class, m.id())).isEqualTo(1);
    verify(staff, times(1)).notifyStaff(eq("DISPUTE_NEW"), argThat(p -> m.no().equals(p.get("marketNo"))));
  }

  @Test
  void 同一条提议同时确认4次_只公示一次() throws Exception {
    Market m = platformMarket(2, 2);
    User a = user("100");
    User b = user("100");
    bet(a.id(), m, 0, "60");
    bet(b.id(), m, 1, "40");
    closeAndEnd(m);
    String p = desk.propose(no(m), result(m, 0), 7).summary().proposal().proposalId();
    List<Callable<Object>> tasks = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      long admin = 20 + i;
      tasks.add(() -> desk.confirm(no(m), p, admin, true));
    }
    List<String> r = race(tasks);
    assertThat(Collections.frequency(r, "OK")).isEqualTo(1);
    assertThat(r.stream().filter(x -> !x.equals("OK"))).allMatch(x -> x.equals("PROPOSAL_CHANGED") || x.equals("MARKET_NOT_RESOLVABLE"));
    assertThat(status(m)).isEqualTo("PUBLICIZING");
    assertThat(records(m)).containsExactly("PROPOSE", "CONFIRM");
    assertThat(inbox(a.id(), "RESULT_PUBLICIZED")).isEqualTo(1);
    assertThat(inbox(b.id(), "RESULT_PUBLICIZED")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_status_log WHERE market_id = ? AND to_status = 'PUBLICIZING'", Integer.class, m.id())).isEqualTo(1);
  }
}
