package com.forecast.app.market.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.common.error.BizException;
import com.forecast.app.market.api.MarketStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 状态跳转在真实 MySQL 上（AC-10、技术方案第 5.1 节）：100 种组合逐个在库里试，只有 17 条边改得动状态并写状态历史，其余被拒、状态和历史都不变；
 * 带旧状态的条件更新：旧状态不对时什么都不做；同一个跳转 8 个线程同时触发只生效一次。
 */
class MarketTransitionDbTest extends MarketTestSupport {

  @Test
  void 穷举100种组合_库里只有17条边能改状态_其余状态和历史都不变() {
    List<String> passed = new ArrayList<>();
    for (MarketStatus from : MarketStatus.values()) {
      for (MarketStatus to : MarketStatus.values()) {
        long id = insert(from, NOW.plus(Duration.ofDays(1)));
        String edge = from + ">" + to;
        if (MarketStateMachineTest.EDGES.contains(edge)) {
          assertThat(markets.transition(id, from, to, "SYSTEM", "测试")).as(edge).isTrue();
          assertThat(status(id)).as(edge).isEqualTo(to.name());
          assertThat(log(id)).as(edge).containsExactly(edge + ":SYSTEM:测试");
          passed.add(edge);
        } else {
          assertThatThrownBy(() -> markets.transition(id, from, to, "SYSTEM", "测试")).as(edge).isInstanceOf(BizException.class)
              .extracting(e -> ((BizException) e).code()).isEqualTo("ILLEGAL_TRANSITION");
          assertThat(status(id)).as(edge).isEqualTo(from.name());
          assertThat(log(id)).as(edge).isEmpty();
        }
      }
    }
    assertThat(passed).containsExactlyInAnyOrderElementsOf(MarketStateMachineTest.EDGES);
  }

  @Test
  void 进入取消和作废时通知退款_其他跳转不通知_原因写进市场() {
    long a = insert(MarketStatus.LISTED, NOW.plus(Duration.ofDays(1)));
    long b = insert(MarketStatus.PENDING_RESOLUTION, NOW.plus(Duration.ofDays(1)));
    long c = insert(MarketStatus.LISTED, NOW.plus(Duration.ofDays(1)));
    markets.transition(a, MarketStatus.LISTED, MarketStatus.CANCELLED, "7", "比赛取消");
    markets.transition(b, MarketStatus.PENDING_RESOLUTION, MarketStatus.VOIDED, "SYSTEM", "E6");
    markets.transition(c, MarketStatus.LISTED, MarketStatus.PAUSED, "7", "突发");
    assertThat(events.refundsOf(a)).singleElement().extracting(r -> r.to() + ":" + r.reason()).isEqualTo("CANCELLED:比赛取消");
    assertThat(events.refundsOf(b)).singleElement().extracting(r -> r.to() + ":" + r.reason()).isEqualTo("VOIDED:E6");
    assertThat(events.refundsOf(c)).isEmpty();
    assertThat(jdbc.queryForObject("SELECT cancel_reason FROM market_question WHERE id = ?", String.class, a)).isEqualTo("比赛取消");
    assertThat(jdbc.queryForObject("SELECT void_reason FROM market_question WHERE id = ?", String.class, b)).isEqualTo("E6");
  }

  @Test
  void 条件更新_旧状态不对时什么都不做_返回false() {
    long id = insert(MarketStatus.PAUSED, NOW.plus(Duration.ofDays(1)));
    assertThat(markets.transition(id, MarketStatus.LISTED, MarketStatus.CLOSED, "SYSTEM", null)).as("市场实际是已暂停").isFalse();
    assertThat(status(id)).isEqualTo("PAUSED");
    assertThat(log(id)).isEmpty();
  }

  @Test
  void 同一个跳转8个线程同时触发_只生效一次_只有一条历史_只通知一次退款() throws Exception {
    long id = insert(MarketStatus.LISTED, NOW.plus(Duration.ofDays(1)));
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Boolean>> fs = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      fs.add(pool.submit(() -> {
        go.await();
        return markets.transition(id, MarketStatus.LISTED, MarketStatus.CANCELLED, "SYSTEM", "并发");
      }));
    }
    go.countDown();
    int ok = 0;
    for (Future<Boolean> f : fs) if (f.get(30, TimeUnit.SECONDS)) ok++;
    pool.shutdown();
    assertThat(ok).isEqualTo(1);
    assertThat(log(id)).hasSize(1);
    assertThat(events.refundsOf(id)).hasSize(1);
  }
}
