package com.forecast.app.settle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;

/**
 * 结算只派一次、中途崩溃后续跑（AC-23；技术方案/v1-架构方案.md 第 4.3 节"三道防重复"、第 17 节"结算中途崩溃后续跑，结果和一次跑完相同"）。
 * 两种事务边界都测：batch-size = 0（一个事务，崩溃 = 整个回滚，重跑）和分批（崩溃 = 那一批回滚，续跑）。
 */
class SettleIdempotencyTest extends SettleTestSupport {

  /** 一个固定的市场：7 个客户、8 个持仓（客户 0 押了两个选项），3 个选项；结果选项 1 */
  record Scenario(Market m, List<User> users) {}

  static final String[][] PLAN = {{"0", "10.07"}, {"0", "33.33"}, {"1", "21.50"}, {"2", "7.00"}, {"0", "50.00"}, {"1", "19.99"}, {"2", "3.01"}};

  Scenario scenario() throws Exception {
    Market m = shortMarket(3);
    List<User> users = new ArrayList<>();
    for (String[] p : PLAN) {
      User u = user("100");
      users.add(u);
      bet(u.id(), m, Integer.parseInt(p[0]), p[1]);
    }
    bet(users.get(0).id(), m, 1, "5.55");
    return new Scenario(m, users);
  }

  /** 结算后的结果：每个客户的可用余额 + 结算单的数字 + 平台在这个市场的收入 */
  Map<String, String> result(Scenario s) {
    Map<String, String> out = new LinkedHashMap<>();
    for (int i = 0; i < s.users().size(); i++) out.put("user" + i, available(s.users().get(i).id()).stripTrailingZeros().toPlainString());
    Map<String, Object> r = run(s.m(), "PAYOUT");
    for (String k : List.of("pool_total", "sum_gross", "sum_fee", "dust", "sum_payout")) out.put(k, ((BigDecimal) r.get(k)).stripTrailingZeros().toPlainString());
    out.put("status", (String) r.get("status"));
    out.put("income", ledgerSum(s.m(), "FEE_INCOME", "AVAILABLE").add(ledgerSum(s.m(), "SETTLE_DUST", "AVAILABLE")).stripTrailingZeros().toPlainString());
    out.put("payoutEntries", String.valueOf(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE ref_market_id = ? AND entry_type = 'PAYOUT'", Integer.class, s.m().id())));
    out.put("wonNotices", String.valueOf(s.users().stream().mapToInt(u -> inbox(u.id(), "SETTLE_WON")).sum()));
    out.put("lostNotices", String.valueOf(s.users().stream().mapToInt(u -> inbox(u.id(), "SETTLE_LOST")).sum()));
    return out;
  }

  /** 一次跑完的结果（对照组） */
  Map<String, String> clean(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Scenario s = scenario();
    closeAndEnd(s.m());
    settle(s.m(), 0);
    settledConserved(s.m(), "150.45");
    return result(s);
  }

  void failAt(int nth) {
    AtomicInteger calls = new AtomicInteger();
    doAnswer(inv -> {
      if (calls.incrementAndGet() == nth) throw new IllegalStateException("模拟崩溃：第 " + nth + " 批提交前");
      return null;
    }).when(faults).afterBatch(anyLong(), anyString(), anyInt());
  }

  void noFault() {
    doAnswer(inv -> null).when(faults).afterBatch(anyLong(), anyString(), anyInt());
  }

  @Test
  void 一个事务模式_中途崩溃整个回滚_重跑和一次跑完相同() throws Exception {
    Map<String, String> expected = clean(0);
    Scenario s = scenario();
    closeAndEnd(s.m());
    BigDecimal locked = stillLocked(s.m());
    failAt(1);
    assertThatThrownBy(() -> settle(s.m(), 0)).hasMessageContaining("模拟崩溃");
    // 什么都没留下：没有结算单、结算阶段还是 NONE、钱都还锁着、没有通知
    assertThat(run(s.m(), "PAYOUT")).isNull();
    assertThat(phase(s.m())).isEqualTo("NONE");
    assertThat(status(s.m())).isEqualTo("PENDING_RESOLUTION");
    assertThat(stillLocked(s.m())).isEqualByComparingTo(locked);
    assertThat(jdbc.queryForObject("SELECT winning_option_id FROM market_question WHERE id = ?", Long.class, s.m().id())).as("结果也回滚了").isNull();
    for (User u : s.users()) assertThat(inbox(u.id(), "SETTLE_WON") + inbox(u.id(), "SETTLE_LOST")).isZero();
    noFault();
    settle(s.m(), 0);
    assertThat(result(s)).isEqualTo(expected);
    settledConserved(s.m(), "150.45");
  }

  @Test
  void 分批模式_第3批崩溃_前两批已提交_续跑和一次跑完相同() throws Exception {
    Map<String, String> expected = clean(2);
    settleProps.setBatchSize(2);
    Scenario s = scenario();
    closeAndEnd(s.m());
    failAt(3);
    settle.settle(s.m().id(), optionId(s.m(), 0), null, "1"); // 计算一个事务（已提交）
    settle.drainAll(); // 第 1、2 批提交，第 3 批崩溃（回滚），派彩任务记日志、下一轮接着派
    Map<String, Object> r = run(s.m(), "PAYOUT");
    assertThat(r.get("status")).isEqualTo("PAYING");
    assertThat(phase(s.m())).isEqualTo("PAYING");
    int doneItems = jdbc.queryForObject("SELECT COUNT(*) FROM settle_item WHERE run_id = ? AND status = 'DONE'", Integer.class, r.get("id"));
    assertThat(doneItems).as("前两批（每批 2 个客户的持仓）已提交").isBetween(4, 5);
    assertThat(stillLocked(s.m()).signum()).as("还有没派的").isPositive();
    assertThat(getJson("/api/markets/" + s.m().no(), s.users().get(0).cookie()).get("settling").asBoolean()).as("派彩中客户端显示\"结算中\"（X-2）").isTrue();
    noFault();
    settle.drainAll();
    assertThat(result(s)).isEqualTo(expected);
    settledConserved(s.m(), "150.45");
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 2})
  void AC23_同一市场三次并发触发结算_派彩任务三个同时跑_只派一次(int batch) throws Exception {
    Map<String, String> expected = clean(batch);
    settleProps.setBatchSize(batch);
    Scenario s = scenario();
    closeAndEnd(s.m());
    long option = optionId(s.m(), 0);
    race(3, () -> settle.settle(s.m().id(), option, null, "1"));
    if (batch > 0) race(3, () -> settle.drainAll());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settle_run WHERE market_id = ?", Integer.class, s.m().id())).isEqualTo(1);
    assertThat(result(s)).isEqualTo(expected);
    settledConserved(s.m(), "150.45");
  }

  @Test
  void 三道防重复_每一道单独都挡得住() throws Exception {
    settleProps.setBatchSize(2);
    Map<String, String> expected = clean(2);
    settleProps.setBatchSize(2);
    Scenario s = scenario();
    closeAndEnd(s.m());
    failAt(2);
    settle.settle(s.m().id(), optionId(s.m(), 0), null, "1");
    settle.drainAll(); // 第 1 批提交，第 2 批崩溃
    long runId = ((Number) run(s.m(), "PAYOUT").get("id")).longValue();
    // ① 同一市场同一种单只能有一张（唯一键）
    assertThatThrownBy(() -> jdbc.update("INSERT INTO settle_run (public_no, market_id, kind, pool_total, status, batch_size, calculated_at) "
        + "VALUES (?, ?, 'PAYOUT', 1, 'PAYING', 0, UTC_TIMESTAMP(3))", System.nanoTime(), s.m().id())).isInstanceOf(DuplicateKeyException.class);
    // ② 明细的条件更新：已经 DONE 的再改一次改不到
    assertThat(jdbc.update("UPDATE settle_item SET status = 'DONE' WHERE run_id = ? AND status = 'PENDING' AND id < 0", runId)).isZero();
    // ③ 假设前两道都失效：把已派的明细改回 PENDING 再派一次——账本幂等键挡住，余额不会多，通知不重发
    int reset = jdbc.update("UPDATE settle_item SET status = 'PENDING', done_at = NULL WHERE run_id = ? AND status = 'DONE'", runId);
    assertThat(reset).isPositive();
    noFault();
    settle.drainAll();
    assertThat(result(s)).isEqualTo(expected);
    settledConserved(s.m(), "150.45");
  }

  @Test
  void 已结算的市场再触发结算_什么都不做() throws Exception {
    Map<String, String> expected = clean(0);
    Scenario s = scenario();
    closeAndEnd(s.m());
    settle(s.m(), 0);
    Map<String, String> once = result(s);
    assertThat(once).isEqualTo(expected);
    settle.settle(s.m().id(), optionId(s.m(), 1), null, "1"); // 换一个结果再来：已经结算，什么都不做
    assertThat(result(s)).isEqualTo(once);
    assertThat(jdbc.queryForObject("SELECT winning_option_id FROM market_question WHERE id = ?", Long.class, s.m().id())).isEqualTo(optionId(s.m(), 0));
  }

  void race(int threads, Runnable task) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<?>> fs = new ArrayList<>();
      for (int i = 0; i < threads; i++) fs.add(pool.submit(() -> {
        go.await();
        task.run();
        return null;
      }));
      go.countDown();
      for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }
}
