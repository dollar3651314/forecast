package com.forecast.app.pool;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.common.error.BizException;
import com.forecast.app.ledger.api.LedgerQueryApi;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.market.core.MarketService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 下注的并发（真实 MySQL 8.4，C37；技术方案第 4.2 节的加锁顺序、第 17 节的集成测试）：
 * <ul>
 *   <li>AC-18：同一客户余额 100，20 个线程各押 10 → 恰好 10 笔成功，余额 0、奖池 + 100；单客户单市场上限同理（跨选项合计）。</li>
 *   <li>同一个请求号并发提交只下一次注；很多客户同时押同一个选项，奖池和人数都对。</li>
 *   <li>截止竞争：下注和截止任务同时跑，截止时定格的奖池正好等于截止前已提交的下注，之后的全部按"已截止"拒绝（截止屏障）。</li>
 *   <li>死锁检查：下注、提现（提交 + 撤销）、改费率、暂停 / 恢复混合跑（默认 15 秒，{@code -Dpool.mixedSeconds=60} 跑 1 分钟），
 *       InnoDB 死锁计数不变、没有锁等待超时，账本合计 = 余额，每个市场 Σ下注 = Σ奖池 = Σ持仓 = 账本锁定。</li>
 * </ul>
 */
class BetConcurrencyTest extends PoolTestSupport {

  @Autowired LedgerQueryApi ledgerQuery;
  @Autowired MarketService marketService;

  /** 线程同时开始：每个任务先等发令，再执行；返回每个任务的结果（OK 或错误码） */
  List<String> race(int threads, List<Supplier<String>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<String>> fs = new ArrayList<>();
      for (Supplier<String> t : tasks) fs.add(pool.submit(() -> {
        go.await();
        return t.get();
      }));
      go.countDown();
      List<String> out = new ArrayList<>();
      for (Future<String> f : fs) out.add(f.get(60, TimeUnit.SECONDS));
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  void conserved(Market m) {
    assertThat(sumBets(m)).as("Σ有效下注 = Σ选项奖池").isEqualByComparingTo(sumPools(m));
    assertThat(sumPositions(m)).as("= Σ持仓").isEqualByComparingTo(sumPools(m));
    assertThat(ledgerLocked(m)).as("= 账本里这个市场的下注锁定").isEqualByComparingTo(sumPools(m));
  }

  @Test
  void AC18_余额100_20个线程各押10_恰好10笔成功_余额0_奖池加100() throws Exception {
    User u = user("100");
    Market m = market(2);
    List<Supplier<String>> tasks = new ArrayList<>();
    for (int i = 0; i < 20; i++) tasks.add(() -> tryBet(u.id(), m, 0, "10"));
    List<String> r = race(20, tasks);
    assertThat(Collections.frequency(r, "OK")).isEqualTo(10);
    assertThat(Collections.frequency(r, "INSUFFICIENT_FUNDS")).isEqualTo(10);
    assertThat(available(u.id())).isEqualByComparingTo("0");
    assertThat(betLocked(u.id())).isEqualByComparingTo("100");
    assertThat(optionPool(m, 0)).isEqualByComparingTo("100");
    assertThat(jdbc.queryForObject("SELECT CONCAT(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM amount)), '/', bet_count) FROM pool_position WHERE market_id = ?",
        String.class, m.id())).isEqualTo("100/10");
    conserved(m);
    assertThat(ledgerQuery.mismatches()).isEmpty();
  }

  @Test
  void AC18_单客户单市场上限100_20个线程各押10_跨两个选项_恰好10笔成功() throws Exception {
    User u = user("1000");
    Market m = market(2, Instant.now(clock).plus(Duration.ofDays(1)), "100");
    List<Supplier<String>> tasks = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      int option = i % 2;
      tasks.add(() -> tryBet(u.id(), m, option, "10"));
    }
    List<String> r = race(20, tasks);
    assertThat(Collections.frequency(r, "OK")).isEqualTo(10);
    assertThat(Collections.frequency(r, "BET_OVER_USER_CAP")).isEqualTo(10);
    assertThat(sumPositions(m)).isEqualByComparingTo("100");
    assertThat(available(u.id())).isEqualByComparingTo("900");
    conserved(m);
  }

  @Test
  void 同一个请求号并发提交只下一次注() throws Exception {
    User u = user("100");
    Market m = market(2);
    String req = UUID.randomUUID().toString();
    Set<String> betNos = ConcurrentHashMap.newKeySet();
    List<Supplier<String>> tasks = new ArrayList<>();
    for (int i = 0; i < 10; i++) tasks.add(() -> {
      betNos.add(bet(u.id(), m, 0, "10", req, Instant.now(clock)).betNo());
      return "OK";
    });
    assertThat(race(10, tasks)).containsOnly("OK");
    assertThat(betNos).hasSize(1);
    assertThat(available(u.id())).isEqualByComparingTo("90");
    assertThat(sumBets(m)).isEqualByComparingTo("10");
  }

  @Test
  void 很多客户同时押同一个选项_奖池和人数都对() throws Exception {
    Market m = market(3);
    List<User> users = new ArrayList<>();
    for (int i = 0; i < 30; i++) users.add(user("100"));
    List<Supplier<String>> tasks = new ArrayList<>();
    for (User u : users) for (int k = 0; k < 4; k++) tasks.add(() -> tryBet(u.id(), m, 1, "2.5"));
    List<String> r = race(24, tasks);
    assertThat(r).containsOnly("OK");
    assertThat(optionPool(m, 1)).isEqualByComparingTo("300");
    assertThat(jdbc.queryForObject("SELECT bettors FROM pool_option_total WHERE option_no = ?", Integer.class, Long.parseLong(m.options().get(1)))).isEqualTo(30);
    conserved(m);
  }

  @Test
  void 截止竞争_下注和截止同时发生_定格的奖池正好是截止前提交的下注() throws Exception {
    int accepted = 0;
    int rejected = 0;
    for (int round = 0; round < 5; round++) {
      Instant close = NOW.plus(Duration.ofMinutes(10 + round));
      Market m = market(2, close, null);
      List<User> users = new ArrayList<>();
      for (int i = 0; i < 8; i++) users.add(user("1000"));
      // 时钟到截止时间：截止任务会处理这个市场；下注的收到时间都在截止前 1 毫秒（按时间可以接受，能不能成功只看谁先拿到市场行的锁）
      ((com.forecast.app.support.MutableClock) clock).reset();
      advance(Duration.between(NOW, close));
      List<Supplier<String>> tasks = new ArrayList<>();
      AtomicInteger closedBy = new AtomicInteger();
      for (int i = 0; i < 80; i++) {
        User u = users.get(i % users.size());
        int option = i % 2;
        tasks.add(() -> {
          try {
            bet(u.id(), m, option, "1", UUID.randomUUID().toString(), close.minusMillis(1));
            return "OK";
          } catch (BizException e) {
            return e.code();
          }
        });
        if (i == 30) tasks.add(() -> {
          closedBy.addAndGet(marketService.closeDue());
          return "CLOSE";
        });
      }
      List<String> r = race(16, tasks);
      marketService.closeDue();
      assertThat(status(m)).isEqualTo("CLOSED");
      assertThat(r.stream().filter(x -> !x.equals("OK") && !x.equals("CLOSE") && !x.equals("MARKET_CLOSED")).toList()).as("只有成功和已截止").isEmpty();
      BigDecimal finals = jdbc.queryForObject("SELECT SUM(final_amount) FROM pool_option_total WHERE market_id = ?", BigDecimal.class, m.id());
      assertThat(finals).as("定格的奖池 = 成功的下注").isEqualByComparingTo(sumBets(m)).isEqualByComparingTo(BigDecimal.valueOf(Collections.frequency(r, "OK")));
      assertThat(sumPools(m)).as("定格以后没有再加").isEqualByComparingTo(finals);
      conserved(m);
      // 截止以后再下注：拒绝
      assertThat(tryBet(users.get(0).id(), m, 0, "1")).isEqualTo("MARKET_CLOSED");
      accepted += Collections.frequency(r, "OK");
      rejected += Collections.frequency(r, "MARKET_CLOSED");
    }
    System.out.println("[截止竞争] 5 轮：截止前提交 " + accepted + " 笔，截止后拒绝 " + rejected + " 笔");
    assertThat(accepted).as("确实有下注在截止任务之前提交").isPositive();
    assertThat(rejected).as("确实有下注排在截止任务之后、被拒绝").isPositive();
  }

  /**
   * 死锁检查：下注（多个市场、多个客户、两个选项）、提现提交和撤销（同一批客户，锁同一个账户行）、改费率和暂停 / 恢复（市场行排他锁），
   * 一起跑 {@code pool.mixedSeconds} 秒（默认 15，本地验收跑 60）。
   */
  @Test
  void 死锁检查_下注提现改费率暂停混合跑() throws Exception {
    int seconds = Integer.getInteger("pool.mixedSeconds", 15);
    long deadlocksBefore = deadlocks();
    List<Market> markets = new ArrayList<>();
    for (int i = 0; i < 3; i++) markets.add(market(3, NOW.plus(Duration.ofDays(1)), null));
    List<User> users = new ArrayList<>();
    List<String> whitelists = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      User u = user("100000", true);
      users.add(u);
      MockHttpServletResponse w = call(MockMvcRequestBuilders.post("/api/wallet/whitelist"), u.cookie(),
          "{\"network\":\"ERC20\",\"address\":\"0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed\",\"label\":\"mix\"}");
      assertThat(w.getStatus()).as(w.getContentAsString()).isEqualTo(200);
      String wid = body(w).get("id").asString();
      jdbc.update("UPDATE wallet_whitelist SET active_at = created_at - INTERVAL 1 HOUR WHERE id = ?", Long.parseLong(wid));
      whitelists.add(wid);
    }
    Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
    List<Throwable> unexpected = new CopyOnWriteArrayList<>();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    ExecutorService pool = Executors.newFixedThreadPool(20);
    List<Future<?>> fs = new ArrayList<>();
    for (int t = 0; t < 20; t++) {
      int kind = t;
      fs.add(pool.submit(() -> {
        Random rnd = new Random(kind * 7919L);
        while (System.nanoTime() < end) {
          String key;
          try {
            if (kind < 13) { // 下注
              User u = users.get(rnd.nextInt(users.size()));
              Market m = markets.get(rnd.nextInt(markets.size()));
              bet(u.id(), m, rnd.nextInt(3), (1 + rnd.nextInt(30)) + "." + rnd.nextInt(10));
              key = "bet.OK";
            } else if (kind < 18) { // 提现：提交后立刻撤销（冷静期内），同一个账户行
              int i = rnd.nextInt(users.size());
              MockHttpServletResponse s = call(MockMvcRequestBuilders.post("/api/wallet/withdrawals"), users.get(i).cookie(),
                  "{\"whitelistId\":\"" + whitelists.get(i) + "\",\"amount\":\"" + (100 + rnd.nextInt(50)) + "\"}");
              if (s.getStatus() == 200) {
                String id = body(s).get("id").asString();
                MockHttpServletResponse c = call(MockMvcRequestBuilders.post("/api/wallet/withdrawals/" + id + "/cancel"), users.get(i).cookie(), null);
                key = c.getStatus() == 200 ? "withdraw.OK" : "withdraw.cancel." + c.getStatus() + body(c).path("code").asString();
              } else {
                key = "withdraw." + body(s).path("code").asString(String.valueOf(s.getStatus()));
                if (s.getStatus() >= 500) unexpected.add(new AssertionError(s.getContentAsString()));
              }
            } else if (kind == 18) { // 改费率（市场行排他锁）
              Market m = markets.get(rnd.nextInt(markets.size()));
              admin.changeFee(Long.parseLong(m.no()), String.valueOf(3 + rnd.nextInt(5)) + "." + rnd.nextInt(10), 1, "并发测试");
              key = "fee.OK";
            } else { // 暂停 / 恢复
              Market m = markets.get(rnd.nextInt(markets.size()));
              long no = Long.parseLong(m.no());
              MarketAdminApi.Detail d = admin.pause(no, 1, "并发测试");
              Thread.sleep(5);
              admin.resume(no, 1, "并发测试");
              key = "pause.OK" + (d == null ? "" : "");
            }
          } catch (BizException e) {
            key = "biz." + e.code();
          } catch (PessimisticLockingFailureException e) {
            key = "LOCK_FAILURE";
            unexpected.add(e);
          } catch (Throwable e) {
            if (e instanceof org.springframework.transaction.TransactionException || e instanceof org.springframework.dao.DataAccessException) unexpected.add(e);
            else if (!(e instanceof com.forecast.app.common.error.ValidationException)) unexpected.add(e);
            key = "ERR." + e.getClass().getSimpleName();
          }
          outcomes.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        }
        return null;
      }));
    }
    for (Future<?> f : fs) f.get(seconds + 120L, TimeUnit.SECONDS);
    pool.shutdownNow();
    System.out.println("[死锁检查] " + seconds + " 秒：" + new java.util.TreeMap<>(outcomes));
    assertThat(unexpected).as("锁等待超时、死锁或程序错误").isEmpty();
    assertThat(deadlocks() - deadlocksBefore).as("InnoDB 死锁计数（lock_deadlocks）没有增加").isZero();
    assertThat(outcomes.getOrDefault("bet.OK", new AtomicInteger()).get()).as("确实下了很多注").isGreaterThan(100);
    assertThat(outcomes.getOrDefault("withdraw.OK", new AtomicInteger()).get()).as("确实提交撤销了很多提现").isGreaterThan(20);
    for (Market m : markets) conserved(m);
    assertThat(ledgerQuery.mismatches()).as("账本合计 = 余额").isEmpty();
    for (User u : users) {
      assertThat(available(u.id()).signum()).isGreaterThanOrEqualTo(0);
      assertThat(ledger.snapshot(u.id()).withdrawFrozen()).as("提现都撤销了").isEqualByComparingTo("0");
    }
  }

  /** InnoDB 从启动以来检测到的死锁次数（lock_deadlocks 默认开启） */
  long deadlocks() {
    Long n = jdbc.queryForObject("SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME = 'lock_deadlocks'", Long.class);
    return n == null ? 0 : n;
  }
}
