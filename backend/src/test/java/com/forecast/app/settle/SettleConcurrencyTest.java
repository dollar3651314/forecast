package com.forecast.app.settle;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.common.error.BizException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 死锁检查（技术方案/v1-架构方案.md 第 4.2、17 节"混合下注、结算、提现"；S4 的混合用例里还没有结算，在这里补上）：
 * 同一批客户同时下注（3 个一直开着的市场）、提现提交 + 撤销（锁同一个账户行）、在别的市场上被结算（派彩、抽成）和被取消（全额退款），
 * 跑 {@code settle.mixedSeconds} 秒（默认 15，本地验收跑 60），两种事务边界各跑一次。断言：InnoDB 死锁计数不变、没有锁等待超时、
 * 账本合计 = 余额、每个已结算市场守恒、每个开着的市场 Σ下注 = Σ奖池 = Σ持仓 = 账本锁定、提现冻结都回到 0。
 */
class SettleConcurrencyTest extends SettleTestSupport {

  long deadlocks() {
    Long n = jdbc.queryForObject("SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME = 'lock_deadlocks'", Long.class);
    return n == null ? 0 : n;
  }

  /** 把这个市场的时间挪到（测试时钟的）过去：截止、事件结束都已过，交给截止任务和事件结束任务（时钟不动，别的市场不受影响） */
  void endNow(Market m) {
    java.time.LocalDateTime now = java.time.LocalDateTime.ofInstant(java.time.Instant.now(clock), java.time.ZoneOffset.UTC);
    jdbc.update("UPDATE market_question SET open_at = ?, close_at = ?, event_start_at = ?, event_end_at = ? WHERE id = ?", now.minusMinutes(10), now.minusSeconds(2),
        now.minusSeconds(2), now.minusSeconds(1), m.id());
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 2})
  void 死锁检查_下注提现结算取消混合跑(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    int seconds = Integer.getInteger("settle.mixedSeconds", 15);
    long deadlocksBefore = deadlocks();
    List<Market> open = new ArrayList<>();
    for (int i = 0; i < 3; i++) open.add(market(3, java.time.Instant.now(clock).plus(Duration.ofDays(1)), null));
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
    List<Market> settled = new CopyOnWriteArrayList<>();
    List<Market> cancelled = new CopyOnWriteArrayList<>();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    ExecutorService pool = Executors.newFixedThreadPool(18);
    List<Future<?>> fs = new ArrayList<>();
    for (int t = 0; t < 18; t++) {
      int kind = t;
      fs.add(pool.submit(() -> {
        Random rnd = new Random(kind * 104729L);
        while (System.nanoTime() < end) {
          String key;
          try {
            if (kind < 9) { // 下注（开着的市场）
              bet(users.get(rnd.nextInt(users.size())).id(), open.get(rnd.nextInt(open.size())), rnd.nextInt(3), (1 + rnd.nextInt(20)) + "." + rnd.nextInt(10));
              key = "bet.OK";
            } else if (kind < 13) { // 提现：提交后立刻撤销
              int i = rnd.nextInt(users.size());
              MockHttpServletResponse s = call(MockMvcRequestBuilders.post("/api/wallet/withdrawals"), users.get(i).cookie(),
                  "{\"whitelistId\":\"" + whitelists.get(i) + "\",\"amount\":\"" + (100 + rnd.nextInt(50)) + "\"}");
              if (s.getStatus() == 200) {
                MockHttpServletResponse c = call(MockMvcRequestBuilders.post("/api/wallet/withdrawals/" + body(s).get("id").asString() + "/cancel"), users.get(i).cookie(), null);
                key = c.getStatus() == 200 ? "withdraw.OK" : "withdraw.cancel." + c.getStatus();
              } else {
                key = "withdraw." + s.getStatus();
                if (s.getStatus() >= 500) unexpected.add(new AssertionError(s.getContentAsString()));
              }
            } else { // 结算 / 取消：这批客户押一个新市场，然后结算（或取消）
              Market m = market(2, java.time.Instant.now(clock).plus(Duration.ofDays(1)), null);
              for (int k = 0; k < 8; k++) bet(users.get(rnd.nextInt(users.size())).id(), m, k % 2, (1 + rnd.nextInt(50)) + ".0" + rnd.nextInt(10));
              if (rnd.nextInt(4) == 0) {
                admin.cancel(Long.parseLong(m.no()), 1, "并发测试");
                if (batch > 0) settle.drainAll();
                cancelled.add(m);
                key = "cancel.OK";
              } else {
                endNow(m);
                marketService.closeDue();
                marketService.endDue();
                settle.settle(m.id(), optionId(m, rnd.nextInt(2)), null, "1");
                if (batch > 0) settle.drainAll();
                settled.add(m);
                key = "settle.OK";
              }
            }
          } catch (BizException e) {
            key = "biz." + e.code();
          } catch (PessimisticLockingFailureException e) {
            key = "LOCK_FAILURE";
            unexpected.add(e);
          } catch (Throwable e) {
            if (!(e instanceof com.forecast.app.common.error.ValidationException)) unexpected.add(e);
            key = "ERR." + e.getClass().getSimpleName();
          }
          outcomes.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        }
        return null;
      }));
    }
    for (Future<?> f : fs) f.get(seconds + 180L, TimeUnit.SECONDS);
    pool.shutdownNow();
    settle.drainAll();
    System.out.println("[死锁检查 batch-size=" + batch + "] " + seconds + " 秒：" + new java.util.TreeMap<>(outcomes));
    if (!unexpected.isEmpty()) unexpected.get(0).printStackTrace();
    assertThat(unexpected).as("锁等待超时、死锁或程序错误").isEmpty();
    assertThat(deadlocks() - deadlocksBefore).as("InnoDB 死锁计数（lock_deadlocks）没有增加").isZero();
    assertThat(outcomes.getOrDefault("bet.OK", new AtomicInteger()).get()).as("确实下了很多注").isGreaterThan(100);
    assertThat(outcomes.getOrDefault("withdraw.OK", new AtomicInteger()).get()).as("确实提交撤销了很多提现").isGreaterThan(10);
    assertThat(settled.size()).as("确实结算了一些市场").isGreaterThan(3);
    for (Market m : settled) {
      Map<String, Object> run = run(m, "PAYOUT");
      if (run == null) { // 结果选项没人押（E1）：作废退款
        assertThat(status(m)).isEqualTo("VOIDED");
        assertThat(stillLocked(m)).isEqualByComparingTo("0");
        continue;
      }
      assertThat(run.get("status")).isEqualTo("DONE");
      settledConserved(m, ((BigDecimal) run.get("pool_total")).toPlainString());
    }
    for (Market m : cancelled) {
      assertThat(run(m, "REFUND").get("status")).isEqualTo("DONE");
      assertThat(stillLocked(m)).isEqualByComparingTo("0");
    }
    for (Market m : open) {
      assertThat(sumBets(m)).isEqualByComparingTo(sumPools(m));
      assertThat(sumPositions(m)).isEqualByComparingTo(sumPools(m));
      assertThat(stillLocked(m)).isEqualByComparingTo(sumPools(m));
    }
    assertThat(ledgerQuery.mismatches()).as("账本合计 = 余额").isEmpty();
    for (User u : users) {
      assertThat(available(u.id()).signum()).isGreaterThanOrEqualTo(0);
      assertThat(ledger.snapshot(u.id()).withdrawFrozen()).as("提现都撤销了").isEqualByComparingTo("0");
    }
  }
}
