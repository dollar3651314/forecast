package com.forecast.app.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.market.api.MarketCategory;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.resolve.platform.CaseBook;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 整合 2 的回归测试：市场跳到待裁决的事务（market 模块，默认隔离级别 = MySQL 的可重复读）里，resolve 的监听先 {@link CaseBook#sync}、再 {@link CaseBook#open}。
 * 原来 sync 对还没有进度行的市场直接 {@code UPDATE resolve_case … WHERE market_id = ?}：可重复读下这会在主键上加间隙锁；
 * 两个事务各自给自己的新市场加了同一段间隙锁、再各自插入，插入意向锁互相等待 → 死锁（GitHub CI 的 {@code SettleConcurrencyTest} 偶发，
 * 两个会话的 SQL 在 MySQL 8.4 上手工复现过）。修复：sync 先普通读（不加锁），没有进度行就什么都不做（项目地图约定 34 的同一类问题）。
 * 这里用两个线程按"都 sync 完、再都 open"的固定顺序跑，确定性地覆盖这个交错。
 */
class CaseBookGapLockTest extends PlatformTestSupport {

  @Autowired CaseBook cases;
  @Autowired PlatformTransactionManager tm;

  long deadlocks() {
    Long n = jdbc.queryForObject("SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME = 'lock_deadlocks'", Long.class);
    return n == null ? 0 : n;
  }

  @Test
  void 两个新市场同时进入待裁决_可重复读下不死锁() throws Exception {
    TransactionTemplate rr = new TransactionTemplate(tm);
    rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    // 比现有的进度行都大的两个市场编号：插入点都在主键最后那段间隙（原来的问题就出在这里）
    Long max = jdbc.queryForObject("SELECT COALESCE(MAX(market_id), 0) FROM resolve_case", Long.class);
    long base = Math.max(max == null ? 0 : max, 8_000_000_000_000_000_000L) + 1_000;
    long before = deadlocks();
    CyclicBarrier synced = new CyclicBarrier(2);
    ExecutorService ex = Executors.newFixedThreadPool(2);
    List<Future<Boolean>> fs = new ArrayList<>();
    try {
      for (int i = 0; i < 2; i++) {
        long marketId = base + i;
        fs.add(ex.submit(() -> rr.execute(st -> {
          cases.sync(marketId, MarketStatus.PENDING_RESOLUTION);
          try {
            synced.await(10, TimeUnit.SECONDS);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          return cases.open(marketId, CaseBook.PLATFORM, MarketCategory.MACRO);
        })));
      }
      for (Future<Boolean> f : fs) assertThat(f.get(30, TimeUnit.SECONDS)).as("两个进度行都建好了").isTrue();
    } finally {
      ex.shutdownNow();
      jdbc.update("DELETE FROM resolve_case WHERE market_id IN (?, ?)", base, base + 1);
    }
    assertThat(deadlocks() - before).as("InnoDB 死锁计数（lock_deadlocks）没有增加").isZero();
  }
}
