package com.forecast.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.common.error.BizException;
import com.forecast.app.ledger.api.AccountKind;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.ledger.api.LedgerQueryApi;
import com.forecast.app.ledger.api.PlatformLedgerApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 不变量"任何时刻账本合计 = 余额"（项目地图约定 4，S2）。用固定种子的随机操作在真实 MySQL 上并发跑（C37）：
 * 8 个线程、12 个客户、每个线程 150 次随机业务（充值、下注、结算赢 / 输、退款、补偿、提现冻结 / 退回 / 出账、平台抽成和尾差、重复提交），
 * 其中有故意的余额不足和重复幂等键。跑完后：
 * <ol>
 *   <li>全库每个账户每一项：账本合计 = 账户金额（{@link LedgerQueryApi#mismatches()}）；</li>
 *   <li>每个账户每一项的最后一条记录的 value_after = 账户金额，并且按 id 顺序每条 value_after = 上一条 + amount（同一账户的记账是排队的，没有交错）；</li>
 *   <li>守恒：本测试的客户三项合计 + 两个平台账户的变化 = 充值 − 提现出账（内部转移净额为 0）；</li>
 *   <li>同一个幂等键只有一条记录。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerInvariantTest {

  private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000 + 700_000_000L);
  private static final long SEED = 20261025L;
  private static final int THREADS = 8;
  private static final int USERS = 12;
  private static final int OPS = 150;

  @Autowired LedgerApi ledger;
  @Autowired PlatformLedgerApi platform;
  @Autowired LedgerQueryApi query;
  @Autowired JdbcTemplate jdbc;

  private static BigDecimal money(Random r, int maxCents) {
    return BigDecimal.valueOf(1 + r.nextInt(maxCents)).movePointLeft(2);
  }

  private static LedgerCommand c(long u, Bucket b, BigDecimal amt, LedgerEntryType t, String biz, String id, long market) {
    return new LedgerCommand(u, b, amt, t, biz, id, null, market);
  }

  /** 和市场无关的变动（充值、提现） */
  private static LedgerCommand c0(long u, Bucket b, BigDecimal amt, LedgerEntryType t, String biz, String id) {
    return new LedgerCommand(u, b, amt, t, biz, id, null);
  }

  @Test
  void 随机并发业务以后_账本合计等于余额_value_after连续_内部转移净额为0() throws Exception {
    long[] users = new long[USERS];
    for (int i = 0; i < USERS; i++) {
      users[i] = IDS.incrementAndGet();
      ledger.openAccount(users[i]);
    }
    String run = "inv-" + users[0];
    BigDecimal revenue0 = platform.balance(AccountKind.PLATFORM_REVENUE);
    BigDecimal comp0 = platform.balance(AccountKind.PLATFORM_COMPENSATION);
    AtomicInteger insufficient = new AtomicInteger();
    AtomicInteger duplicates = new AtomicInteger();
    AtomicLong seq = new AtomicLong();

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<?>> fs = new ArrayList<>();
    for (int t = 0; t < THREADS; t++) {
      Random r = new Random(SEED + t);
      fs.add(pool.submit(() -> {
        for (int i = 0; i < OPS; i++) {
          long u = users[r.nextInt(USERS)];
          long market = 1 + r.nextInt(5);
          String id = run + "-" + seq.incrementAndGet();
          try {
            switch (r.nextInt(10)) {
              case 0, 1 -> ledger.apply(c0(u, Bucket.AVAILABLE, money(r, 50_000), LedgerEntryType.DEPOSIT, "DEPOSIT", id));
              case 2, 3 -> { // 下注
                BigDecimal b = money(r, 20_000);
                ledger.applyAll(List.of(c(u, Bucket.AVAILABLE, b.negate(), LedgerEntryType.BET_LOCK, "BET", id, market),
                    c(u, Bucket.BET_LOCKED, b, LedgerEntryType.BET_LOCK, "BET", id, market)));
              }
              case 4 -> { // 结算·赢：解锁 b、派彩 b + G、抽成 F、平台收入 F（G 从空气里来不行：这里让 G 来自另一个客户的输，保持守恒）
                long loser = users[r.nextInt(USERS)];
                BigDecimal b = money(r, 5_000);
                BigDecimal g = money(r, 5_000);
                BigDecimal f = g.multiply(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP);
                List<LedgerCommand> legs = new ArrayList<>(List.of(
                    c(u, Bucket.BET_LOCKED, b.negate(), LedgerEntryType.BET_SETTLE, "SETTLE", id, market),
                    c(loser, Bucket.BET_LOCKED, g.negate(), LedgerEntryType.BET_LOST, "SETTLE", id + "-l", market),
                    c(u, Bucket.AVAILABLE, b.add(g), LedgerEntryType.PAYOUT, "SETTLE", id, market)));
                // 抽成四舍五入后可能是 0：金额为 0 的记录不能记（S5 结算时同样跳过）
                if (f.signum() > 0) legs.add(c(u, Bucket.AVAILABLE, f.negate(), LedgerEntryType.FEE, "SETTLE", id, market));
                ledger.applyAll(legs);
                if (f.signum() > 0) {
                  platform.apply(new PlatformLedgerApi.Command(AccountKind.PLATFORM_REVENUE, f, LedgerEntryType.FEE_INCOME, "SETTLE_BATCH", id, null, market));
                }
              }
              case 5 -> { // 退款
                BigDecimal b = money(r, 5_000);
                ledger.applyAll(List.of(c(u, Bucket.BET_LOCKED, b.negate(), LedgerEntryType.REFUND, "REFUND", id, market),
                    c(u, Bucket.AVAILABLE, b, LedgerEntryType.REFUND, "REFUND", id, market)));
              }
              case 6 -> { // 补偿：客户 +x、补偿支出账户 −x（可以为负）
                BigDecimal x = money(r, 3_000);
                ledger.apply(c(u, Bucket.AVAILABLE, x, LedgerEntryType.COMPENSATION, "COMP", id, market));
                platform.apply(new PlatformLedgerApi.Command(AccountKind.PLATFORM_COMPENSATION, x.negate(), LedgerEntryType.COMPENSATION_EXPENSE, "COMP", id, null, market));
              }
              case 7 -> { // 提现冻结，然后一半退回、一半出账
                BigDecimal w = money(r, 10_000);
                ledger.applyAll(List.of(c0(u, Bucket.AVAILABLE, w.negate(), LedgerEntryType.WITHDRAW_FREEZE, "WITHDRAW", id),
                    c0(u, Bucket.WITHDRAW_FROZEN, w, LedgerEntryType.WITHDRAW_FREEZE, "WITHDRAW", id)));
                if (r.nextBoolean()) {
                  ledger.apply(c0(u, Bucket.WITHDRAW_FROZEN, w.negate(), LedgerEntryType.WITHDRAW_PAYOUT, "WITHDRAW", id));
                } else {
                  ledger.applyAll(List.of(c0(u, Bucket.WITHDRAW_FROZEN, w.negate(), LedgerEntryType.WITHDRAW_UNFREEZE, "WITHDRAW", id),
                      c0(u, Bucket.AVAILABLE, w, LedgerEntryType.WITHDRAW_UNFREEZE, "WITHDRAW", id)));
                }
              }
              case 8 -> platform.apply(new PlatformLedgerApi.Command(AccountKind.PLATFORM_REVENUE, BigDecimal.valueOf(r.nextInt(2) + 1).movePointLeft(2),
                  LedgerEntryType.SETTLE_DUST, "SETTLE_RUN", id, null, market)); // 尾差：本测试里没有对应的客户输钱，守恒时单独扣掉
              default -> { // 重复提交一笔已有的充值：只记一次
                String dup = run + "-dup-" + u;
                ledger.apply(c0(u, Bucket.AVAILABLE, new BigDecimal("1.00"), LedgerEntryType.DEPOSIT, "DEPOSIT", dup));
                if (ledger.apply(c0(u, Bucket.AVAILABLE, new BigDecimal("1.00"), LedgerEntryType.DEPOSIT, "DEPOSIT", dup)).duplicate()) {
                  duplicates.incrementAndGet();
                }
              }
            }
          } catch (BizException e) {
            assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS");
            insufficient.incrementAndGet();
          }
        }
        return null;
      }));
    }
    for (Future<?> f : fs) f.get();
    pool.shutdown();

    assertThat(insufficient.get()).as("随机操作里确实有余额不足被整组拒绝的").isPositive();
    assertThat(duplicates.get()).as("确实有重复提交").isPositive();

    // ① 全库：账本合计 = 余额
    assertThat(query.mismatches()).isEmpty();

    // ② value_after 连续：同一账户同一项按 id 顺序，每条 = 上一条 + amount，最后一条 = 账户金额
    for (long u : users) {
      for (Bucket b : Bucket.values()) {
        List<BigDecimal[]> rows = jdbc.query(
            "SELECT l.amount, l.value_after FROM ledger_entry l JOIN ledger_account a ON a.id = l.account_id WHERE a.user_id = ? AND l.bucket = ? ORDER BY l.id",
            (rs, n) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}, u, b.name());
        BigDecimal v = BigDecimal.ZERO;
        for (BigDecimal[] row : rows) {
          v = v.add(row[0]);
          assertThat(row[1]).as("value_after 连续 user=" + u + " " + b).isEqualByComparingTo(v);
          assertThat(v.signum()).as("任何时候都不为负").isGreaterThanOrEqualTo(0);
        }
        assertThat(ledger.snapshot(u).get(b)).isEqualByComparingTo(v);
      }
    }

    // ③ 守恒：客户三项合计 + 平台变化 − 尾差 = 充值 − 提现出账
    BigDecimal customers = BigDecimal.ZERO;
    BigDecimal external = BigDecimal.ZERO;
    for (long u : users) {
      customers = customers.add(ledger.snapshot(u).balance()).add(ledger.snapshot(u).withdrawFrozen());
      external = external.add(jdbc.queryForObject("SELECT COALESCE(SUM(l.amount), 0) FROM ledger_entry l JOIN ledger_account a ON a.id = l.account_id "
          + "WHERE a.user_id = ? AND l.entry_type IN ('DEPOSIT', 'DEPOSIT_REVERSAL', 'WITHDRAW_PAYOUT')", BigDecimal.class, u));
    }
    BigDecimal dust = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_entry WHERE entry_type = 'SETTLE_DUST' AND biz_id LIKE ?", BigDecimal.class, run + "-%");
    BigDecimal platformDelta = platform.balance(AccountKind.PLATFORM_REVENUE).subtract(revenue0).add(platform.balance(AccountKind.PLATFORM_COMPENSATION).subtract(comp0));
    assertThat(customers.add(platformDelta).subtract(dust)).as("内部转移净额为 0").isEqualByComparingTo(external);

    // ④ 幂等键唯一
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT biz_type, biz_id, entry_type, bucket FROM ledger_entry WHERE biz_id LIKE ? "
        + "GROUP BY biz_type, biz_id, entry_type, bucket HAVING COUNT(*) > 1) t", Integer.class, run + "-%")).isZero();
  }

  /** 技术方案第 3.1 节：ledger_entry 只增不改。应用里任何 Mapper XML 都不能有它的 UPDATE、DELETE 语句 */
  @Test
  void 账本只增不改_Mapper里没有ledger_entry的UPDATE和DELETE() throws Exception {
    Path root = Path.of("src/main/resources/mapper");
    if (!Files.exists(root)) root = Path.of("backend/src/main/resources/mapper");
    List<String> bad = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path p : files.filter(f -> f.toString().endsWith(".xml")).toList()) {
        String sql = Files.readString(p).replaceAll("\\s+", " ").toLowerCase();
        if (sql.matches(".*update (`)?ledger_entry.*") || sql.matches(".*delete from (`)?ledger_entry.*") || sql.matches(".*truncate (table )?(`)?ledger_entry.*")) {
          bad.add(p.toString());
        }
      }
    }
    assertThat(bad).isEmpty();
  }
}
