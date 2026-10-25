package com.forecast.app.wallet.sweep;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.address.Create2;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * SQ-8 的合约地址分支（以太坊体系配了合约地址参数，{@code AddressService.useForwarder}）：很多新客户同时分配不死锁；
 * 有旧 xpub 地址的客户几个请求同时打开充值页，旧地址只停用一次、新地址只有一个。xpub 分支见 {@code wallet/address/AddressConcurrencyTest}。
 */
class AddressForwarderConcurrencyTest extends SweepContextSupport {

  long bareUser() {
    GeneratedKeyHolder k = new GeneratedKeyHolder();
    String email = "fwd" + System.nanoTime() + "@example.com";
    jdbc.update(c -> {
      var ps = c.prepareStatement("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", java.sql.Statement.RETURN_GENERATED_KEYS);
      ps.setString(1, email);
      return ps;
    }, k);
    long id = k.getKey().longValue();
    ledger.openAccount(id);
    return id;
  }

  long deadlocks() {
    Long n = jdbc.queryForObject("SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME = 'lock_deadlocks'", Long.class);
    return n == null ? 0 : n;
  }

  void race(List<Runnable> tasks, List<Throwable> errors) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<?>> fs = new ArrayList<>();
      for (Runnable t : tasks) fs.add(pool.submit(() -> {
        try {
          go.await();
          t.run();
        } catch (Throwable e) {
          errors.add(e);
        }
      }));
      go.countDown();
      for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  String fwd(long uid) {
    return Create2.forwarderAddress(FACTORY, IMPLEMENTATION, PARENT, BATCHER, Create2.salt(uid));
  }

  @Test
  void 合约地址方案_很多新客户同时分配_不死锁_每人一个合约地址() throws Exception {
    long before = deadlocks();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    for (int round = 0; round < 5; round++) {
      List<Long> users = new ArrayList<>();
      for (int i = 0; i < 40; i++) users.add(bareUser());
      List<Runnable> tasks = new ArrayList<>();
      for (long uid : users) tasks.add(() -> addresses.allocateAddresses(uid));
      race(tasks, errors);
      for (long uid : users) {
        if (!errors.isEmpty()) break;
        assertThat(jdbc.queryForList("SELECT address FROM wallet_address WHERE user_id = ? AND family = 'ETH' AND status = 'ACTIVE'", String.class, uid))
            .containsExactly(fwd(uid));
      }
    }
    System.out.println("[SQ-8 合约地址] 5 轮 × 40 个客户：死锁 " + (deadlocks() - before) + " 次，异常 " + errors.size());
    assertThat(errors).isEmpty();
    assertThat(deadlocks() - before).as("InnoDB 死锁计数没有增加").isZero();
  }

  @Test
  void 合约地址方案_有旧xpub地址的客户几个请求同时打开充值页_旧地址停用一次_新地址一个() throws Exception {
    long before = deadlocks();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    List<Long> users = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      long uid = bareUser();
      String old = "0x00000000000000000000000000000002" + String.format("%08x", uid);
      jdbc.update("INSERT INTO wallet_address (user_id, family, scheme, scheme_ver, address_index, address, address_key, status, active_key) "
          + "VALUES (?, 'ETH', 'XPUB', 0, ?, ?, ?, 'ACTIVE', CONCAT(?, ':ETH'))", uid, (int) uid, old, old, uid);
      users.add(uid);
    }
    Map<Long, Set<String>> got = new ConcurrentHashMap<>();
    List<Runnable> tasks = new ArrayList<>();
    for (long uid : users) {
      for (int k = 0; k < 4; k++) {
        Chain c = k % 2 == 0 ? Chain.ERC20 : Chain.BSC;
        tasks.add(() -> got.computeIfAbsent(uid, x -> ConcurrentHashMap.newKeySet()).add(addresses.addressOf(uid, c).orElseThrow()));
      }
    }
    race(tasks, errors);
    assertThat(errors).isEmpty();
    assertThat(deadlocks() - before).as("InnoDB 死锁计数没有增加").isZero();
    for (long uid : users) {
      assertThat(got.get(uid)).as("几个请求拿到同一个合约地址").containsExactly(fwd(uid));
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_address WHERE user_id = ? AND family = 'ETH' AND status = 'RETIRED'", Integer.class, uid))
          .as("旧地址停用一次").isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_address WHERE user_id = ? AND family = 'ETH' AND status = 'ACTIVE'", Integer.class, uid))
          .isEqualTo(1);
    }
  }
}
