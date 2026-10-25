package com.forecast.app.wallet.address;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.wallet.Chain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SQ-8（S4 预压测发现）：很多新客户同时第一次取充值地址、或同时注册时，{@link AddressService#allocateAddresses} 不能死锁、不能报错（真实 MySQL 8.4，C37）。
 *
 * <p>修复前的根因：先 {@code SELECT … WHERE active_key = ? FOR UPDATE} 查一个还不存在的行。可重复读下这是<b>间隙锁</b>，
 * 新客户的 {@code active_key}（"用户ID:体系"）都落在唯一索引最后同一个间隙里；间隙锁互相兼容，两个事务都拿到，
 * 接着各自 INSERT 要的插入意向锁又都被对方的间隙锁挡住 → InnoDB 判死锁、回滚其中一个（接口 500）。
 *
 * <p>每种情况跑几轮（每轮一批新客户同时开始），断言 InnoDB 死锁计数（{@code lock_deadlocks}）不变、没有任何异常、
 * 每个客户每个体系恰好一个正在用的地址、地址是按序号派生的那个。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class AddressConcurrencyTest {

  static final int ROUNDS = 5;
  static final int USERS = 40;

  @Autowired AddressService addresses;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  @Autowired com.forecast.app.ledger.api.LedgerApi ledger;

  /** 新客户：建用户、开账户（和注册一样，账户先于充值地址） */
  long newUser() {
    String email = "addr" + System.nanoTime() + "-" + Thread.currentThread().threadId() + "@example.com";
    GeneratedKeyHolder k = new GeneratedKeyHolder();
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

  /** 每个任务等发令后同时开始；收集异常 */
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

  /** 每个客户每个体系恰好一个正在用的地址：以太坊体系是 CREATE2 合约地址（盐 = 用户 ID），TRON 是按序号（= 用户 ID）派生的 xpub 地址 */
  void exactlyOneDerived(List<Long> users) {
    for (long uid : users) {
      for (Chain.Family f : Chain.Family.values()) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT scheme, address_index, address FROM wallet_address WHERE user_id = ? AND family = ? AND status = 'ACTIVE'",
            uid, f.name());
        assertThat(rows).as("客户 %d %s 正在用的地址", uid, f).hasSize(1);
        assertThat(rows.get(0).get("scheme")).as("以太坊体系只用合约地址，TRON 用 xpub").isEqualTo(f == Chain.Family.ETH ? "FORWARDER" : "XPUB");
        assertThat(((Number) rows.get(0).get("address_index")).longValue()).isEqualTo(uid);
      }
    }
  }

  void summarize(String what, long before, List<Throwable> errors) {
    Map<String, Integer> kinds = new TreeMap<>();
    for (Throwable e : errors) kinds.merge(e.getClass().getSimpleName(), 1, Integer::sum);
    System.out.println("[SQ-8 " + what + "] " + ROUNDS + " 轮 × " + USERS + " 个客户：死锁 " + (deadlocks() - before) + " 次，异常 " + kinds);
    if (!errors.isEmpty()) errors.get(0).printStackTrace();
    assertThat(deadlocks() - before).as("InnoDB 死锁计数没有增加").isZero();
    assertThat(errors).as("没有异常（修复前：DeadlockLoserDataAccessException）").isEmpty();
  }

  @Test
  void 很多新客户同时第一次分配充值地址_不死锁() throws Exception {
    long before = deadlocks();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    for (int round = 0; round < ROUNDS; round++) {
      List<Long> users = new ArrayList<>();
      for (int i = 0; i < USERS; i++) users.add(newUser());
      List<Runnable> tasks = new ArrayList<>();
      for (long uid : users) tasks.add(() -> addresses.allocateAddresses(uid));
      race(tasks, errors);
      if (errors.isEmpty()) exactlyOneDerived(users);
    }
    summarize("同时分配", before, errors);
  }

  @Test
  void 很多人同时注册_建用户和分配地址在同一个事务里_不死锁() throws Exception {
    long before = deadlocks();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    for (int round = 0; round < ROUNDS; round++) {
      Set<Long> users = ConcurrentHashMap.newKeySet();
      List<Runnable> tasks = new ArrayList<>();
      for (int i = 0; i < USERS; i++) tasks.add(() -> tx.executeWithoutResult(st -> {
        long uid = newUser(); // 注册事务：先建用户（AuthService.register），再分配地址
        addresses.allocateAddresses(uid);
        users.add(uid);
      }));
      race(tasks, errors);
      if (errors.isEmpty()) exactlyOneDerived(new ArrayList<>(users));
    }
    summarize("同时注册", before, errors);
  }

  @Test
  void 很多新客户同时打开充值页_同一客户也有几个请求同时到_每人每个体系只有一个地址() throws Exception {
    long before = deadlocks();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    for (int round = 0; round < ROUNDS; round++) {
      List<Long> users = new ArrayList<>();
      for (int i = 0; i < USERS / 4; i++) users.add(newUser());
      Map<Long, Set<String>> seen = new ConcurrentHashMap<>();
      List<Runnable> tasks = new ArrayList<>();
      Chain[] chains = Chain.values();
      for (long uid : users) {
        for (int k = 0; k < 4; k++) {
          Chain c = chains[k % chains.length];
          tasks.add(() -> seen.computeIfAbsent(uid * 10 + c.family.ordinal(), x -> ConcurrentHashMap.newKeySet())
              .add(addresses.addressOf(uid, c).orElseThrow()));
        }
      }
      race(tasks, errors);
      if (errors.isEmpty()) {
        exactlyOneDerived(users);
        for (Set<String> s : seen.values()) assertThat(s).as("同一客户同一体系的几个请求拿到同一个地址").hasSize(1);
      }
    }
    summarize("同时取地址", before, errors);
  }
}
