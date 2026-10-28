package com.forecast.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.common.error.BizException;
import com.forecast.app.ledger.api.AccountKind;
import com.forecast.app.ledger.api.AccountSnapshot;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.FundsApi;
import com.forecast.app.ledger.api.LedgerAdminApi;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.ledger.api.LedgerQueryApi;
import com.forecast.app.ledger.api.LedgerResult;
import com.forecast.app.ledger.api.PlatformLedgerApi;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * S2 账本拆分：三项余额、流水类型规则、平台账户、幂等、市场合计，用真实的 MySQL（C37）。
 * 下注、结算、退款、补偿的业务逻辑在 S4、S5、S7；这里只用账本接口按技术方案第 3.2 节的表把钱走一遍，证明账本层的规则和守恒。
 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerBucketsTest {

  private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000 + 500_000_000L);
  /** 市场 id 只是账本记录上的标签（S3 才有市场表），用大数避免和别的测试重复 */
  private static final AtomicLong MARKETS = new AtomicLong(System.currentTimeMillis() * 10);

  @Autowired LedgerApi ledger;
  @Autowired PlatformLedgerApi platform;
  @Autowired LedgerQueryApi query;
  @Autowired FundsApi funds;
  @Autowired LedgerAdminApi admin;
  @Autowired JdbcTemplate jdbc;

  private long newUser(String deposit) {
    long id = IDS.incrementAndGet();
    ledger.openAccount(id);
    if (deposit != null) ledger.apply(new LedgerCommand(id, Bucket.AVAILABLE, new BigDecimal(deposit), LedgerEntryType.DEPOSIT, "TEST", "dep-" + id, "测试入金"));
    return id;
  }

  private static LedgerCommand cmd(long user, Bucket b, String amount, LedgerEntryType t, String bizType, String bizId, Long market) {
    return new LedgerCommand(user, b, new BigDecimal(amount), t, bizType, bizId, null, market);
  }

  /** 下注：可用 −b、下注锁定 +b（第 3.2 节，幂等键 BET : 下注 id） */
  private List<LedgerResult> bet(long user, String amount, String betId, long market) {
    return ledger.applyAll(List.of(
        cmd(user, Bucket.AVAILABLE, "-" + amount, LedgerEntryType.BET_LOCK, "BET", betId, market),
        cmd(user, Bucket.BET_LOCKED, amount, LedgerEntryType.BET_LOCK, "BET", betId, market)));
  }

  private int entryCount(long user) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry l JOIN ledger_account a ON a.id = l.account_id WHERE a.user_id = ?", Integer.class, user);
  }

  private void assertConsistent(long user) {
    assertThat(query.mismatches()).as("全库：账本合计 = 余额").isEmpty();
    for (Bucket b : Bucket.values()) {
      BigDecimal sum = jdbc.queryForObject("SELECT COALESCE(SUM(l.amount),0) FROM ledger_entry l JOIN ledger_account a ON a.id = l.account_id WHERE a.user_id = ? AND l.bucket = ?",
          BigDecimal.class, user, b.name());
      assertThat(sum).as("账本合计 = " + b).isEqualByComparingTo(ledger.snapshot(user).get(b));
    }
  }

  @Test
  void 三项余额_下注锁定_可提只算可用_余额是可用加下注锁定() {
    long u = newUser("100");
    long m = MARKETS.incrementAndGet();
    List<LedgerResult> r = bet(u, "60", "b-" + u, m);
    assertThat(r).extracting(LedgerResult::valueAfter).usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("40"), new BigDecimal("60"));
    AccountSnapshot s = ledger.snapshot(u);
    assertThat(s.available()).isEqualByComparingTo("40");
    assertThat(s.betLocked()).isEqualByComparingTo("60");
    assertThat(s.withdrawFrozen()).isEqualByComparingTo("0");
    assertThat(s.balance()).as("客户看到的余额").isEqualByComparingTo("100");
    assertThat(funds.withdrawable(u)).as("下注锁定的钱不能提").isEqualByComparingTo("40");
    assertThat(jdbc.queryForObject("SELECT ref_market_id FROM ledger_entry WHERE biz_type = 'BET' AND biz_id = ? AND bucket = 'BET_LOCKED'", Long.class, "b-" + u))
        .as("记下和哪个市场有关").isEqualTo(m);
    assertConsistent(u);
  }

  @Test
  void 一组变动全部记或全部不记_可用不够时整组回滚() {
    long u = newUser("50");
    assertThatThrownBy(() -> bet(u, "50.01", "b-" + u, MARKETS.incrementAndGet()))
        .isInstanceOf(BizException.class).extracting(e -> ((BizException) e).code()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(entryCount(u)).as("只有入金那一条").isEqualTo(1);
    assertThat(ledger.snapshot(u).betLocked()).isEqualByComparingTo("0");
    // 第二条（下注锁定）因为类型规则不对被拒，第一条（可用 −）也不能留下
    assertThatThrownBy(() -> ledger.applyAll(List.of(
        cmd(u, Bucket.AVAILABLE, "-10", LedgerEntryType.BET_LOCK, "BET", "x-" + u, null),
        cmd(u, Bucket.WITHDRAW_FROZEN, "10", LedgerEntryType.BET_LOCK, "BET", "x-" + u, null)))).isInstanceOf(IllegalArgumentException.class);
    assertThat(entryCount(u)).isEqualTo(1);
    assertThat(ledger.snapshot(u).available()).isEqualByComparingTo("50");
    assertConsistent(u);
  }

  @Test
  void 多个客户的一组变动_按用户id升序加锁_都记上() {
    long a = newUser("10");
    long b = newUser("20");
    long m = MARKETS.incrementAndGet();
    // 故意先给 id 大的，再给 id 小的
    List<LedgerResult> r = ledger.applyAll(List.of(
        cmd(b, Bucket.AVAILABLE, "-5", LedgerEntryType.BET_LOCK, "BET", "mb-" + b, m),
        cmd(b, Bucket.BET_LOCKED, "5", LedgerEntryType.BET_LOCK, "BET", "mb-" + b, m),
        cmd(a, Bucket.AVAILABLE, "-3", LedgerEntryType.BET_LOCK, "BET", "ma-" + a, m),
        cmd(a, Bucket.BET_LOCKED, "3", LedgerEntryType.BET_LOCK, "BET", "ma-" + a, m)));
    assertThat(r).hasSize(4).noneMatch(LedgerResult::duplicate);
    assertThat(ledger.snapshot(a).betLocked()).isEqualByComparingTo("3");
    assertThat(ledger.snapshot(b).betLocked()).isEqualByComparingTo("5");
    assertConsistent(a);
    assertConsistent(b);
  }

  @Test
  void 幂等键_重复只记一次_同一个键换了金额或账户是程序错误() {
    long u = newUser("100");
    long other = newUser("100");
    long m = MARKETS.incrementAndGet();
    bet(u, "30", "idem-" + u, m);
    List<LedgerResult> again = bet(u, "30", "idem-" + u, m);
    assertThat(again).allMatch(LedgerResult::duplicate);
    assertThat(ledger.snapshot(u).betLocked()).as("没有重复锁定").isEqualByComparingTo("30");
    assertThatThrownBy(() -> bet(u, "31", "idem-" + u, m)).isInstanceOf(IllegalStateException.class).hasMessageContaining("幂等键冲突");
    assertThatThrownBy(() -> bet(other, "30", "idem-" + u, m)).isInstanceOf(IllegalStateException.class).hasMessageContaining("幂等键冲突");
    assertThat(ledger.snapshot(other).available()).isEqualByComparingTo("100");
    assertThat(entryCount(u)).isEqualTo(3);
    assertConsistent(u);
    assertConsistent(other);
  }

  @Test
  void 流水类型规则_每种类型只能用在规定的账户_项和方向() {
    long u = newUser("1000");
    int checked = 0;
    for (LedgerEntryType t : LedgerEntryType.values()) {
      for (Bucket b : Bucket.values()) {
        for (int sign : new int[] {1, -1}) {
          boolean userOk = t.allows(AccountKind.USER, b, sign);
          String bizId = "rule-" + u + "-" + t + "-" + b + "-" + sign;
          if (!userOk) {
            checked++;
            assertThatThrownBy(() -> ledger.apply(cmd(u, b, sign > 0 ? "1" : "-1", t, "RULE", bizId, null)))
                .as(t + " " + b + " " + sign).isInstanceOf(IllegalArgumentException.class);
          }
          for (AccountKind k : List.of(AccountKind.PLATFORM_REVENUE, AccountKind.PLATFORM_COMPENSATION, AccountKind.PLATFORM_TEST_CREDIT, AccountKind.PLATFORM_MANUAL_ADJUST)) {
            if (t.allows(k, b, sign)) continue;
            checked++;
            if (b != Bucket.AVAILABLE) continue; // 平台记账只改可用余额，其他项在接口上就没法传
            assertThatThrownBy(() -> platform.apply(new PlatformLedgerApi.Command(k, new BigDecimal(sign > 0 ? "1" : "-1"), t, "RULE", bizId + "-" + k, null, null)))
                .as(k + " " + t + " " + sign).isInstanceOf(IllegalArgumentException.class);
          }
        }
      }
    }
    assertThat(checked).as("确实检查了不允许的组合").isGreaterThan(50);
    assertThat(entryCount(u)).as("被拒的一条都没写").isEqualTo(1);
    assertThatThrownBy(() -> platform.apply(new PlatformLedgerApi.Command(AccountKind.USER, BigDecimal.ONE, LedgerEntryType.DEPOSIT, "RULE", "u-" + u, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    // 金额为 0、超过 8 位小数、没有幂等键都拒绝
    assertThatThrownBy(() -> ledger.apply(cmd(u, Bucket.AVAILABLE, "0", LedgerEntryType.DEPOSIT, "RULE", "z-" + u, null))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ledger.apply(cmd(u, Bucket.AVAILABLE, "0.000000001", LedgerEntryType.DEPOSIT, "RULE", "s-" + u, null))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ledger.apply(cmd(u, Bucket.AVAILABLE, "1", LedgerEntryType.DEPOSIT, "RULE", " ", null))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 类型定义和架构方案第32节一致() {
    assertThat(LedgerEntryType.BET_LOCK.legs()).isEqualTo(Map.of(Bucket.AVAILABLE, -1, Bucket.BET_LOCKED, 1));
    assertThat(LedgerEntryType.BET_SETTLE.legs()).isEqualTo(Map.of(Bucket.BET_LOCKED, -1));
    assertThat(LedgerEntryType.BET_LOST.legs()).isEqualTo(Map.of(Bucket.BET_LOCKED, -1));
    assertThat(LedgerEntryType.PAYOUT.legs()).isEqualTo(Map.of(Bucket.AVAILABLE, 1));
    assertThat(LedgerEntryType.FEE.legs()).isEqualTo(Map.of(Bucket.AVAILABLE, -1));
    assertThat(LedgerEntryType.REFUND.legs()).isEqualTo(Map.of(Bucket.BET_LOCKED, -1, Bucket.AVAILABLE, 1));
    assertThat(LedgerEntryType.COMPENSATION.legs()).isEqualTo(Map.of(Bucket.AVAILABLE, 1));
    assertThat(LedgerEntryType.WITHDRAW_FREEZE.legs()).isEqualTo(Map.of(Bucket.AVAILABLE, -1, Bucket.WITHDRAW_FROZEN, 1));
    assertThat(LedgerEntryType.WITHDRAW_UNFREEZE.legs()).isEqualTo(Map.of(Bucket.WITHDRAW_FROZEN, -1, Bucket.AVAILABLE, 1));
    assertThat(LedgerEntryType.DEPOSIT_REVERSAL.legs()).as("冲正只扣可用余额").isEqualTo(Map.of(Bucket.AVAILABLE, -1));
    assertThat(LedgerEntryType.FEE_INCOME.accountKind).isEqualTo(AccountKind.PLATFORM_REVENUE);
    assertThat(LedgerEntryType.SETTLE_DUST.accountKind).isEqualTo(AccountKind.PLATFORM_REVENUE);
    assertThat(LedgerEntryType.COMPENSATION_EXPENSE.accountKind).isEqualTo(AccountKind.PLATFORM_COMPENSATION);
    // S17A 测试入金：客户可用 +，对手方是平台"测试入金"账户（−，可以为负）
    assertThat(LedgerEntryType.TEST_CREDIT.legs()).isEqualTo(Map.of(Bucket.AVAILABLE, 1));
    assertThat(LedgerEntryType.TEST_CREDIT.accountKind).isEqualTo(AccountKind.USER);
    assertThat(LedgerEntryType.TEST_CREDIT_EXPENSE.legs()).isEqualTo(Map.of(Bucket.AVAILABLE, -1));
    assertThat(LedgerEntryType.TEST_CREDIT_EXPENSE.accountKind).isEqualTo(AccountKind.PLATFORM_TEST_CREDIT);
    assertThat(AccountKind.PLATFORM_TEST_CREDIT.mayGoNegative()).isTrue();
    // S17B 人工调账：客户可用 + / −，对手方是平台"人工调账"账户（反方向，可正可负）
    assertThat(LedgerEntryType.MANUAL_CREDIT.legs()).isEqualTo(Map.of(Bucket.AVAILABLE, 1));
    assertThat(LedgerEntryType.MANUAL_DEBIT.legs()).isEqualTo(Map.of(Bucket.AVAILABLE, -1));
    assertThat(LedgerEntryType.MANUAL_ADJUST_OUT.legs()).isEqualTo(Map.of(Bucket.AVAILABLE, -1));
    assertThat(LedgerEntryType.MANUAL_ADJUST_IN.legs()).isEqualTo(Map.of(Bucket.AVAILABLE, 1));
    assertThat(LedgerEntryType.MANUAL_ADJUST_OUT.accountKind).isEqualTo(AccountKind.PLATFORM_MANUAL_ADJUST);
    assertThat(LedgerEntryType.MANUAL_ADJUST_IN.accountKind).isEqualTo(AccountKind.PLATFORM_MANUAL_ADJUST);
    assertThat(AccountKind.PLATFORM_MANUAL_ADJUST.mayGoNegative()).isTrue();
    assertThat(AccountKind.PLATFORM_REVENUE.mayGoNegative()).isFalse();
    // 客户的内部转移（不含充值、冲正、提现出账）在客户自己的三项之间是零和的
    for (LedgerEntryType t : List.of(LedgerEntryType.BET_LOCK, LedgerEntryType.REFUND, LedgerEntryType.WITHDRAW_FREEZE, LedgerEntryType.WITHDRAW_UNFREEZE)) {
      assertThat(t.legs().values().stream().mapToInt(Integer::intValue).sum()).as(t + " 两项一加一减").isZero();
    }
  }

  /**
   * 需求说明书第 3.5 节的例子（主胜 6,000、平 1,500、客胜 2,500，费率 5%，主胜赢）按第 3.2 节的流水类型在账本上走一遍：
   * 结算的数字（G、F、Pay、D）这里直接写需求里的值（计算在 S5 的 SettleMath），验证账本层的守恒：
   * Σ客户下注锁定的减少 = T；Σ(PAYOUT − FEE) + 平台(FEE_INCOME + SETTLE_DUST) = T；按 ref_market_id 合计能对上（第 4.4 节）。
   */
  @Test
  void 需求35节的例子在账本上走一遍_守恒_平台只多了抽成和尾差() {
    long m = MARKETS.incrementAndGet();
    long alice = newUser("1000"), bob = newUser("3000"), carol = newUser("4000"), dave = newUser("2000"), eve = newUser("2000"), frank = newUser("1000");
    BigDecimal revenueBefore = platform.balance(AccountKind.PLATFORM_REVENUE);
    long[] users = {alice, bob, carol, dave, eve, frank};
    BigDecimal before = BigDecimal.ZERO;
    for (long u : users) before = before.add(ledger.snapshot(u).balance());

    bet(alice, "60", "e-a1-" + m, m);
    bet(alice, "40", "e-a2-" + m, m);
    bet(bob, "2500", "e-b-" + m, m);
    bet(carol, "3400", "e-c-" + m, m);
    bet(dave, "1500", "e-d-" + m, m);
    bet(eve, "2000", "e-e-" + m, m);
    bet(frank, "500", "e-f-" + m, m);
    Map<LedgerQueryApi.TypeBucket, BigDecimal> locked = query.marketTotals(m);
    assertThat(locked.get(new LedgerQueryApi.TypeBucket(LedgerEntryType.BET_LOCK, Bucket.BET_LOCKED))).as("这个市场锁定的合计 = T").isEqualByComparingTo("10000");

    // 赢方（主胜）：Alice 100 → G 66.66 F 3.33；Bob 2500 → G 1666.66 F 83.33；Carol 3400 → G 2266.66 F 113.33
    String[][] winners = {{"alice", "100", "66.66", "3.33"}, {"bob", "2500", "1666.66", "83.33"}, {"carol", "3400", "2266.66", "113.33"}};
    long[] winnerIds = {alice, bob, carol};
    BigDecimal sumFee = BigDecimal.ZERO;
    for (int i = 0; i < winners.length; i++) {
      long u = winnerIds[i];
      BigDecimal b = new BigDecimal(winners[i][1]), g = new BigDecimal(winners[i][2]), f = new BigDecimal(winners[i][3]);
      String biz = "item-" + m + "-" + i;
      ledger.applyAll(List.of(
          cmd(u, Bucket.BET_LOCKED, b.negate().toPlainString(), LedgerEntryType.BET_SETTLE, "SETTLE", biz, m),
          cmd(u, Bucket.AVAILABLE, b.add(g).toPlainString(), LedgerEntryType.PAYOUT, "SETTLE", biz, m),
          cmd(u, Bucket.AVAILABLE, f.negate().toPlainString(), LedgerEntryType.FEE, "SETTLE", biz, m)));
      sumFee = sumFee.add(f);
    }
    // 输方：Dave 1500、Eve 2000、Frank 500
    long[] losers = {dave, eve, frank};
    String[] loserStake = {"1500", "2000", "500"};
    for (int i = 0; i < losers.length; i++) {
      ledger.apply(cmd(losers[i], Bucket.BET_LOCKED, "-" + loserStake[i], LedgerEntryType.BET_LOST, "SETTLE", "item-" + m + "-l" + i, m));
    }
    platform.apply(new PlatformLedgerApi.Command(AccountKind.PLATFORM_REVENUE, sumFee, LedgerEntryType.FEE_INCOME, "SETTLE_BATCH", m + ":1", "抽成", m));
    platform.apply(new PlatformLedgerApi.Command(AccountKind.PLATFORM_REVENUE, new BigDecimal("0.02"), LedgerEntryType.SETTLE_DUST, "SETTLE_RUN", String.valueOf(m), "尾差", m));

    assertThat(ledger.snapshot(alice).available()).as("Alice 派彩 163.33").isEqualByComparingTo(new BigDecimal("1000").subtract(new BigDecimal("100")).add(new BigDecimal("163.33")));
    assertThat(ledger.snapshot(bob).available()).isEqualByComparingTo(new BigDecimal("500").add(new BigDecimal("4083.33")));
    assertThat(ledger.snapshot(carol).available()).isEqualByComparingTo(new BigDecimal("600").add(new BigDecimal("5553.33")));
    for (long u : users) assertThat(ledger.snapshot(u).betLocked()).as("结算完没有锁定").isEqualByComparingTo("0");
    assertThat(sumFee).isEqualByComparingTo("199.99");
    assertThat(platform.balance(AccountKind.PLATFORM_REVENUE).subtract(revenueBefore)).as("平台收入 = 抽成 + 尾差").isEqualByComparingTo("200.01");

    // 守恒：客户合计少了的钱 = 平台多了的钱（平台不当对手方）
    BigDecimal after = BigDecimal.ZERO;
    for (long u : users) after = after.add(ledger.snapshot(u).balance());
    assertThat(before.subtract(after)).isEqualByComparingTo("200.01");

    Map<LedgerQueryApi.TypeBucket, BigDecimal> t = query.marketTotals(m);
    BigDecimal unlocked = t.get(new LedgerQueryApi.TypeBucket(LedgerEntryType.BET_SETTLE, Bucket.BET_LOCKED))
        .add(t.get(new LedgerQueryApi.TypeBucket(LedgerEntryType.BET_LOST, Bucket.BET_LOCKED)));
    assertThat(unlocked).as("Σ客户下注锁定的减少 = T").isEqualByComparingTo("-10000");
    BigDecimal paid = t.get(new LedgerQueryApi.TypeBucket(LedgerEntryType.PAYOUT, Bucket.AVAILABLE)).add(t.get(new LedgerQueryApi.TypeBucket(LedgerEntryType.FEE, Bucket.AVAILABLE)))
        .add(t.get(new LedgerQueryApi.TypeBucket(LedgerEntryType.FEE_INCOME, Bucket.AVAILABLE)))
        .add(t.get(new LedgerQueryApi.TypeBucket(LedgerEntryType.SETTLE_DUST, Bucket.AVAILABLE)));
    assertThat(paid).as("Σ(PAYOUT − FEE) + 平台(FEE_INCOME + SETTLE_DUST) = T").isEqualByComparingTo("10000");
    for (long u : users) assertConsistent(u);
  }

  @Test
  void 退款_下注锁定回到可用() {
    long u = newUser("100");
    long m = MARKETS.incrementAndGet();
    bet(u, "40", "r-" + u, m);
    ledger.applyAll(List.of(
        cmd(u, Bucket.BET_LOCKED, "-40", LedgerEntryType.REFUND, "REFUND", "ri-" + u, m),
        cmd(u, Bucket.AVAILABLE, "40", LedgerEntryType.REFUND, "REFUND", "ri-" + u, m)));
    AccountSnapshot s = ledger.snapshot(u);
    assertThat(s.available()).isEqualByComparingTo("100");
    assertThat(s.betLocked()).isEqualByComparingTo("0");
    // 退的比锁定的多：不能为负
    assertThatThrownBy(() -> ledger.apply(cmd(u, Bucket.BET_LOCKED, "-0.01", LedgerEntryType.REFUND, "REFUND", "ri2-" + u, m)))
        .isInstanceOf(BizException.class).extracting(e -> ((BizException) e).code()).isEqualTo("INSUFFICIENT_FUNDS");
    assertConsistent(u);
  }

  @Test
  void 补偿_补偿支出账户可以为负_平台收入不能为负_平台账户每种一行() {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_account WHERE kind = 'PLATFORM_REVENUE'", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_account WHERE kind = 'PLATFORM_COMPENSATION'", Integer.class)).isEqualTo(1);
    long u = newUser(null);
    long m = MARKETS.incrementAndGet();
    BigDecimal before = platform.balance(AccountKind.PLATFORM_COMPENSATION);
    ledger.apply(cmd(u, Bucket.AVAILABLE, "25.5", LedgerEntryType.COMPENSATION, "COMP", "ci-" + u, m));
    LedgerResult r = platform.apply(new PlatformLedgerApi.Command(AccountKind.PLATFORM_COMPENSATION, new BigDecimal("-25.5"), LedgerEntryType.COMPENSATION_EXPENSE, "COMP", "ci-" + u, "补偿", m));
    assertThat(r.valueAfter()).isEqualByComparingTo(before.subtract(new BigDecimal("25.5")));
    assertThat(platform.balance(AccountKind.PLATFORM_COMPENSATION)).as("补偿支出账户为负 = 累计补偿").isNegative();
    assertThat(platform.apply(new PlatformLedgerApi.Command(AccountKind.PLATFORM_COMPENSATION, new BigDecimal("-25.5"), LedgerEntryType.COMPENSATION_EXPENSE, "COMP", "ci-" + u, "补偿", m))
        .duplicate()).as("幂等").isTrue();
    assertThat(ledger.snapshot(u).available()).isEqualByComparingTo("25.5");
    // 数据库 CHECK 兜底：客户和平台收入不能为负，补偿支出账户只有可用余额可以为负（MySQL 报 3819，Spring 归为 UncategorizedSQLException）
    assertThatThrownBy(() -> jdbc.update("UPDATE ledger_account SET available = -1 WHERE user_id = ?", u)).isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_ledger_account_nonneg");
    assertThatThrownBy(() -> jdbc.update("UPDATE ledger_account SET available = -1 WHERE kind = 'PLATFORM_REVENUE'")).isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_ledger_account_nonneg");
    assertThatThrownBy(() -> jdbc.update("UPDATE ledger_account SET bet_locked = -1 WHERE kind = 'PLATFORM_COMPENSATION'")).isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_ledger_account_nonneg");
    // S17A（迁移 V29）：测试入金账户每种一行，可用余额可以为负，其他两项不行
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_account WHERE kind = 'PLATFORM_TEST_CREDIT'", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_account WHERE kind = 'PLATFORM_MANUAL_ADJUST'", Integer.class)).as("S17B（迁移 V30）").isEqualTo(1);
    assertThatThrownBy(() -> jdbc.update("UPDATE ledger_account SET withdraw_frozen = -1 WHERE kind = 'PLATFORM_TEST_CREDIT'")).isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_ledger_account_nonneg");
    assertThatThrownBy(() -> jdbc.update("INSERT INTO ledger_account (kind, user_id) VALUES ('PLATFORM_TEST_CREDIT', NULL)")).as("平台账户每种只有一行")
        .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_ledger_account_platform");
    assertThatThrownBy(() -> jdbc.update("INSERT INTO ledger_account (kind, user_id) VALUES ('NOPE', NULL)")).as("不认识的种类")
        .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_ledger_account_kind");
    assertThatThrownBy(() -> jdbc.update("INSERT INTO ledger_account (kind, user_id) VALUES ('PLATFORM_REVENUE', NULL)")).as("平台账户每种只有一行")
        .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_ledger_account_platform");
    assertThatThrownBy(() -> jdbc.update("INSERT INTO ledger_account (kind, user_id) VALUES ('USER', NULL)")).as("客户账户必须有用户 id")
        .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_ledger_account_kind");
    assertThatThrownBy(() -> jdbc.update("INSERT INTO ledger_account (kind, user_id) VALUES ('PLATFORM_REVENUE', ?)", u + 1_000_000)).as("平台账户不能有用户 id")
        .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_ledger_account_kind");
    assertConsistent(u);
  }

  @Test
  void 账本和余额对不上时_核对能找出来() {
    long u = newUser("10");
    assertThat(query.mismatches()).isEmpty();
    // 模拟绕过 LedgerApi 直接改了余额（应用里不会这样做）
    jdbc.update("UPDATE ledger_account SET bet_locked = bet_locked + 1 WHERE user_id = ?", u);
    try {
      List<LedgerQueryApi.Mismatch> bad = query.mismatches();
      assertThat(bad).singleElement().satisfies(x -> {
        assertThat(x.userId()).isEqualTo(u);
        assertThat(x.kind()).isEqualTo(AccountKind.USER);
        assertThat(x.bucket()).isEqualTo(Bucket.BET_LOCKED);
        assertThat(x.ledgerSum()).isEqualByComparingTo("0");
        assertThat(x.accountValue()).isEqualByComparingTo("1");
      });
    } finally {
      jdbc.update("UPDATE ledger_account SET bet_locked = bet_locked - 1 WHERE user_id = ?", u);
    }
    assertThat(query.mismatches()).isEmpty();
  }

  @Test
  void 后台的余额是可用加下注锁定() {
    long u = newUser("100");
    bet(u, "30", "v-" + u, MARKETS.incrementAndGet());
    assertThat(admin.balances(List.of(u)).get(u)).isEqualByComparingTo("100");
    assertThat(admin.balanceLedger(u, 10)).as("后台流水只列可用余额的变动").extracting(LedgerAdminApi.LedgerLine::type)
        .containsExactly(LedgerEntryType.BET_LOCK, LedgerEntryType.DEPOSIT);
  }
}
