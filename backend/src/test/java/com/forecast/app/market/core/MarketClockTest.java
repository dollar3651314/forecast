package com.forecast.app.market.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.job.JobLease;
import com.forecast.app.common.job.TestLeases;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * 截止屏障、截止任务、事件结束任务、多实例租约（技术方案第 4.2、5.2、5.3 节；AC-14、AC-15、AC-24 的状态部分）。
 * 下注本身在 S4：这里用 {@link MarketApi#lockForBet} 代表"下注事务里的那一步"，它和截止任务之间的先后由市场行的共享锁 / 排他锁决定。
 */
class MarketClockTest extends MarketTestSupport {

  @Autowired ApplicationContext ctx;

  MarketApi.BetTarget bet(long id, Instant receivedAt) {
    return tx().execute(st -> markets.lockForBet(publicNo(id), optionNo(id, 1), receivedAt));
  }

  String betError(long id, Instant receivedAt) {
    try {
      bet(id, receivedAt);
      return "OK";
    } catch (BizException e) {
      return e.code();
    }
  }

  @Test
  void AC15_截止前1毫秒收到的通过_正好等于截止时间的拒绝() {
    Instant close = NOW.plus(Duration.ofMinutes(10));
    long id = insert(MarketStatus.LISTED, close);
    MarketApi.BetTarget t = bet(id, close.minusMillis(1));
    assertThat(t.marketId()).isEqualTo(id);
    assertThat(t.closeAt()).isEqualTo(close);
    assertThat(betError(id, close)).isEqualTo("MARKET_CLOSED");
    assertThat(betError(id, close.plusMillis(1))).isEqualTo("MARKET_CLOSED");
  }

  @Test
  void 截止任务_时钟到截止前1毫秒不截止_到截止时间截止() {
    long id = insert(MarketStatus.LISTED, NOW.plus(Duration.ofMinutes(10)));
    advance(Duration.ofMinutes(10).minusMillis(1));
    markets.closeDue();
    assertThat(status(id)).isEqualTo("LISTED");
    advance(Duration.ofMillis(1));
    assertThat(markets.closeDue()).isGreaterThanOrEqualTo(1);
    assertThat(status(id)).isEqualTo("CLOSED");
    assertThat(log(id)).containsExactly("LISTED>CLOSED:SYSTEM:-");
    assertThat(pool.frozen).as("截止时定格奖池").contains(id);
    assertThat(betError(id, NOW.plus(Duration.ofMinutes(5)))).as("截止任务跑过以后，哪怕收到时间早于截止也拒绝（状态已变）").isEqualTo("MARKET_CLOSED");
  }

  @Test
  void 截止屏障_下注事务持有共享锁时截止任务等它提交_之后的下注看到已截止() throws Exception {
    Instant close = NOW.plus(Duration.ofMinutes(1));
    long id = insert(MarketStatus.LISTED, close);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> betTx = CompletableFuture.runAsync(() -> tx().executeWithoutResult(st -> {
      markets.lockForBet(publicNo(id), optionNo(id, 1), close.minusMillis(1));
      locked.countDown();
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }));
    assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
    advance(Duration.ofMinutes(1));
    CompletableFuture<Integer> closer = CompletableFuture.supplyAsync(markets::closeDue);
    Thread.sleep(500);
    assertThat(closer).as("截止任务在等下注事务的共享锁").isNotDone();
    assertThat(status(id)).isEqualTo("LISTED");
    release.countDown();
    betTx.get(30, TimeUnit.SECONDS);
    assertThat(closer.get(30, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(1);
    assertThat(status(id)).isEqualTo("CLOSED");
    assertThat(betError(id, close.minusMillis(1))).isEqualTo("MARKET_CLOSED");
  }

  @Test
  void AC14_暂停中拒绝下注_恢复后能下注_暂停中到了截止时间也截止() {
    long id = insert(MarketStatus.LISTED, NOW.plus(Duration.ofMinutes(10)));
    markets.transition(id, MarketStatus.LISTED, MarketStatus.PAUSED, "1", "突发");
    assertThat(betError(id, NOW)).isEqualTo("MARKET_PAUSED");
    markets.transition(id, MarketStatus.PAUSED, MarketStatus.LISTED, "1", "恢复");
    assertThat(betError(id, NOW)).isEqualTo("OK");
    markets.transition(id, MarketStatus.LISTED, MarketStatus.PAUSED, "1", "又暂停");
    advance(Duration.ofMinutes(10));
    markets.closeDue();
    assertThat(status(id)).isEqualTo("CLOSED");
    assertThat(log(id)).last().isEqualTo("PAUSED>CLOSED:SYSTEM:-");
  }

  @Test
  void 拒单原因_没到开放时间_已取消_草稿_选项不属于这个市场() {
    long notOpen = insert(MarketStatus.LISTED, NOW.plus(Duration.ofMinutes(10)), NOW.plus(Duration.ofMinutes(20)), NOW.plus(Duration.ofHours(2)));
    assertThat(betError(notOpen, NOW)).isEqualTo("MARKET_NOT_OPEN");
    assertThat(betError(notOpen, NOW.plus(Duration.ofMinutes(10)))).as("正好到开放时间可以下注").isEqualTo("OK");
    long cancelled = insert(MarketStatus.CANCELLED, NOW.plus(Duration.ofMinutes(10)));
    assertThat(betError(cancelled, NOW)).isEqualTo("MARKET_CANCELLED");
    long draft = insert(MarketStatus.DRAFT, NOW.plus(Duration.ofMinutes(10)));
    assertThat(betError(draft, NOW)).isEqualTo("NOT_FOUND");
    long other = insert(MarketStatus.LISTED, NOW.plus(Duration.ofMinutes(10)));
    assertThatThrownBy(() -> tx().execute(st -> markets.lockForBet(publicNo(other), optionNo(notOpen, 1), NOW)))
        .isInstanceOf(BizException.class).extracting(e -> ((BizException) e).code()).isEqualTo("OPTION_NOT_FOUND");
    for (MarketStatus s : List.of(MarketStatus.CLOSED, MarketStatus.PENDING_RESOLUTION, MarketStatus.SETTLED, MarketStatus.VOIDED)) {
      assertThat(betError(insert(s, NOW.plus(Duration.ofMinutes(10))), NOW)).as(s.name()).isEqualTo("MARKET_CLOSED");
    }
  }

  @Test
  void 截止屏障必须在下注事务里调用() {
    long id = insert(MarketStatus.LISTED, NOW.plus(Duration.ofMinutes(10)));
    assertThatThrownBy(() -> markets.lockForBet(publicNo(id), optionNo(id, 1), NOW)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
  }

  @Test
  void E3_E4_截止时有效选项不足2个_同一事务作废并通知退款_足够的正常进入截止下注() {
    long none = insert(MarketStatus.LISTED, NOW.plus(Duration.ofMinutes(1)));
    long one = insert(MarketStatus.PAUSED, NOW.plus(Duration.ofMinutes(1)));
    long two = insert(MarketStatus.LISTED, NOW.plus(Duration.ofMinutes(1)));
    pool.activeOptions.put(none, 0);
    pool.activeOptions.put(one, 1);
    pool.activeOptions.put(two, 2);
    advance(Duration.ofMinutes(1));
    markets.closeDue();
    assertThat(status(none)).isEqualTo("VOIDED");
    assertThat(log(none)).containsExactly("LISTED>CLOSED:SYSTEM:-", "CLOSED>VOIDED:SYSTEM:NOT_ENOUGH_OPTIONS");
    assertThat(status(one)).isEqualTo("VOIDED");
    assertThat(log(one)).containsExactly("PAUSED>CLOSED:SYSTEM:-", "CLOSED>VOIDED:SYSTEM:NOT_ENOUGH_OPTIONS");
    assertThat(events.refundsOf(one)).singleElement().extracting(r -> r.to() + ":" + r.reason()).isEqualTo("VOIDED:NOT_ENOUGH_OPTIONS");
    assertThat(jdbc.queryForObject("SELECT void_reason FROM market_question WHERE id = ?", String.class, one)).isEqualTo("NOT_ENOUGH_OPTIONS");
    assertThat(status(two)).isEqualTo("CLOSED");
    assertThat(events.refundsOf(two)).isEmpty();
  }

  @Test
  void 事件结束任务_截止下注且事件结束_进入待裁决_没结束的不动() {
    Instant close = NOW.plus(Duration.ofMinutes(1));
    long id = insert(MarketStatus.LISTED, NOW.minus(Duration.ofHours(1)), close, close.plus(Duration.ofHours(2)));
    advance(Duration.ofMinutes(1));
    markets.closeDue();
    markets.endDue();
    assertThat(status(id)).isEqualTo("CLOSED");
    advance(Duration.ofHours(2).minusMillis(1));
    markets.endDue();
    assertThat(status(id)).isEqualTo("CLOSED");
    advance(Duration.ofMillis(1));
    markets.endDue();
    assertThat(status(id)).isEqualTo("PENDING_RESOLUTION");
    assertThat(log(id)).containsExactly("LISTED>CLOSED:SYSTEM:-", "CLOSED>PENDING_RESOLUTION:SYSTEM:-");
  }

  @Test
  void 停机期间到期的市场_恢复后第一轮一起处理() {
    long a = insert(MarketStatus.LISTED, NOW.plus(Duration.ofMinutes(1)));
    long b = insert(MarketStatus.PAUSED, NOW.plus(Duration.ofMinutes(30)));
    advance(Duration.ofHours(3));
    markets.closeDue();
    markets.endDue();
    assertThat(status(a)).isEqualTo("PENDING_RESOLUTION");
    assertThat(status(b)).isEqualTo("PENDING_RESOLUTION");
  }

  @Test
  void 两个实例同时跑截止任务_只有一个实例执行_每个市场只截止一次() throws Exception {
    List<Long> due = new ArrayList<>();
    for (int i = 0; i < 20; i++) due.add(insert(MarketStatus.LISTED, NOW.plus(Duration.ofMinutes(1))));
    advance(Duration.ofMinutes(1));
    JobLease a = TestLeases.instance(ctx, "instance-a");
    JobLease b = TestLeases.instance(ctx, "instance-b");
    String job = "test.market.close." + System.nanoTime();
    CountDownLatch aRunning = new CountDownLatch(1);
    CountDownLatch bTried = new CountDownLatch(1);
    AtomicInteger ran = new AtomicInteger();
    CompletableFuture<Boolean> fa = CompletableFuture.supplyAsync(() -> a.runExclusive(job, Duration.ofSeconds(30), () -> {
      ran.incrementAndGet();
      aRunning.countDown();
      try {
        bTried.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      markets.closeDue();
    }));
    assertThat(aRunning.await(30, TimeUnit.SECONDS)).isTrue();
    // 实例 A 正在跑的时候，实例 B 的同一个任务拿不到租约，不执行
    boolean bRan = b.runExclusive(job, Duration.ofSeconds(30), () -> {
      ran.incrementAndGet();
      markets.closeDue();
    });
    bTried.countDown();
    assertThat(fa.get(60, TimeUnit.SECONDS)).isTrue();
    assertThat(bRan).isFalse();
    assertThat(ran.get()).isEqualTo(1);
    for (long id : due) {
      assertThat(status(id)).isEqualTo("CLOSED");
      assertThat(log(id)).as("每个市场只有一条截止记录").hasSize(1);
    }
    // A 跑完释放以后，B 下一轮可以拿到（不会因为 A 的租约卡住）
    assertThat(b.runExclusive(job, Duration.ofSeconds(30), () -> {})).isTrue();
  }

  @Test
  void 没有租约保护时两个实例同时截止_条件更新保证只截止一次() throws Exception {
    List<Long> due = new ArrayList<>();
    for (int i = 0; i < 20; i++) due.add(insert(MarketStatus.LISTED, NOW.plus(Duration.ofMinutes(1))));
    advance(Duration.ofMinutes(1));
    CyclicBarrier start = new CyclicBarrier(2);
    CompletableFuture<Integer> fa = CompletableFuture.supplyAsync(() -> await(start) ? markets.closeDue() : 0);
    CompletableFuture<Integer> fb = CompletableFuture.supplyAsync(() -> await(start) ? markets.closeDue() : 0);
    int total = fa.get(60, TimeUnit.SECONDS) + fb.get(60, TimeUnit.SECONDS);
    for (long id : due) assertThat(log(id)).hasSize(1);
    assertThat(total).as("两个实例合计只截止了这 20 个（加上别的测试留下的到期市场）").isGreaterThanOrEqualTo(20);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_status_log WHERE to_status = 'CLOSED' AND market_id IN (" + join(due) + ")", Long.class)).isEqualTo(20);
  }

  static boolean await(CyclicBarrier b) {
    try {
      b.await(30, TimeUnit.SECONDS);
      return true;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String join(List<Long> ids) {
    return String.join(",", ids.stream().map(String::valueOf).toList());
  }
}
