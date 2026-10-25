package com.forecast.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.common.error.BizException;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.ledger.api.LedgerQueryApi;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 批量记账 {@link LedgerApi#applyBatch}（forecast S5：结算派彩、全额退款用）和 {@link LedgerApi#lockAll}，真实 MySQL（C37）。
 * 要证明它和逐条的 {@link LedgerApi#applyAll} <b>结果完全一样</b>：同样的随机命令分别用两种方式记到两组初始余额相同的账户上，
 * 最后每个账户三项的金额、每条记录的 value_after 序列都相同；幂等、冲突、余额不足、类型不对时的行为也相同（整批回滚）。
 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerBatchTest {

  private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000 + 700_000_000L);
  private static final AtomicLong BIZ = new AtomicLong(System.nanoTime());

  @Autowired LedgerApi ledger;
  @Autowired LedgerQueryApi query;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  long newUser(String available, String locked) {
    long id = IDS.incrementAndGet();
    ledger.openAccount(id);
    ledger.apply(new LedgerCommand(id, Bucket.AVAILABLE, new BigDecimal(available), LedgerEntryType.DEPOSIT, "TEST", "dep-" + id, "测试入金"));
    if (new BigDecimal(locked).signum() > 0) {
      String bet = "b-" + id;
      ledger.applyAll(List.of(new LedgerCommand(id, Bucket.AVAILABLE, new BigDecimal(locked).negate(), LedgerEntryType.BET_LOCK, "BET", bet, null, 1L),
          new LedgerCommand(id, Bucket.BET_LOCKED, new BigDecimal(locked), LedgerEntryType.BET_LOCK, "BET", bet, null, 1L)));
    }
    return id;
  }

  /** 一个客户账户的三项和每一项的 value_after 序列（按记录先后） */
  String state(long user) {
    var s = ledger.snapshot(user);
    List<String> after = jdbc.queryForList("SELECT CONCAT(l.bucket, ':', l.entry_type, ':', TRIM(TRAILING '0' FROM l.amount), '→', TRIM(TRAILING '0' FROM l.value_after)) "
        + "FROM ledger_entry l JOIN ledger_account a ON a.id = l.account_id WHERE a.user_id = ? ORDER BY l.id", String.class, user);
    return s.available().stripTrailingZeros().toPlainString() + "/" + s.betLocked().stripTrailingZeros().toPlainString() + "/"
        + s.withdrawFrozen().stripTrailingZeros().toPlainString() + " " + after;
  }

  /** 结算、退款会用到的命令：随机挑客户，赢（解锁 + 派彩 + 抽成）、输（解锁）、退款（解锁 + 退回），同一个客户可能出现多次 */
  List<LedgerCommand> randomCommands(Random rnd, List<Long> users, String prefix) {
    List<LedgerCommand> out = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      long u = users.get(rnd.nextInt(users.size()));
      String biz = prefix + i;
      BigDecimal b = BigDecimal.valueOf(1 + rnd.nextInt(300), 2);
      switch (rnd.nextInt(3)) {
        case 0 -> {
          BigDecimal g = BigDecimal.valueOf(rnd.nextInt(500), 2);
          BigDecimal f = BigDecimal.valueOf(rnd.nextInt(Math.max(1, g.movePointRight(2).intValue() / 10 + 1)), 2);
          out.add(new LedgerCommand(u, Bucket.BET_LOCKED, b.negate(), LedgerEntryType.BET_SETTLE, "SETTLE", biz, null, 9L));
          out.add(new LedgerCommand(u, Bucket.AVAILABLE, b.add(g), LedgerEntryType.PAYOUT, "SETTLE", biz, null, 9L));
          if (f.signum() > 0) out.add(new LedgerCommand(u, Bucket.AVAILABLE, f.negate(), LedgerEntryType.FEE, "SETTLE", biz, null, 9L));
        }
        case 1 -> out.add(new LedgerCommand(u, Bucket.BET_LOCKED, b.negate(), LedgerEntryType.BET_LOST, "SETTLE", biz, null, 9L));
        default -> {
          out.add(new LedgerCommand(u, Bucket.BET_LOCKED, b.negate(), LedgerEntryType.REFUND, "REFUND", biz, null, 9L));
          out.add(new LedgerCommand(u, Bucket.AVAILABLE, b, LedgerEntryType.REFUND, "REFUND", biz, null, 9L));
        }
      }
    }
    return out;
  }

  /** 把命令里的客户换成另一组（对照组），单号加前缀（幂等键不同） */
  static List<LedgerCommand> remap(List<LedgerCommand> cmds, Map<Long, Long> users, String prefix) {
    return cmds.stream().map(c -> new LedgerCommand(users.get(c.userId()), c.bucket(), c.amount(), c.type(), c.bizType(), prefix + c.bizId(), c.remark(), c.refMarketId()))
        .toList();
  }

  @Test
  void 和逐条记账结果完全一样_金额和每条记录的value_after序列() {
    Random rnd = new Random(42);
    List<Long> a = new ArrayList<>();
    List<Long> b = new ArrayList<>();
    Map<Long, Long> ab = new java.util.HashMap<>();
    for (int i = 0; i < 40; i++) {
      long x = newUser("1000", "500");
      long y = newUser("1000", "500");
      a.add(x);
      b.add(y);
      ab.put(x, y);
    }
    String run = "p" + BIZ.incrementAndGet() + "-";
    List<LedgerCommand> cmds = randomCommands(rnd, a, run);
    ledger.applyAll(cmds);
    List<Boolean> fresh = ledger.applyBatch(remap(cmds, ab, "batch-"));
    assertThat(fresh).hasSize(cmds.size()).containsOnly(true);
    for (long x : a) assertThat(state(ab.get(x)).replace("batch-", "")).as("客户 " + x).isEqualTo(state(x));
    assertThat(query.mismatches()).as("账本合计 = 余额").isEmpty();
  }

  @Test
  void 幂等_同一批再来一次全部跳过_部分已记的只记新的_同一批里重复的键只记一次() {
    long u = newUser("100", "50");
    long v = newUser("100", "50");
    String k = "idem" + BIZ.incrementAndGet();
    List<LedgerCommand> first = List.of(
        new LedgerCommand(u, Bucket.BET_LOCKED, new BigDecimal("-10"), LedgerEntryType.REFUND, "REFUND", k + "a", null, 9L),
        new LedgerCommand(u, Bucket.AVAILABLE, new BigDecimal("10"), LedgerEntryType.REFUND, "REFUND", k + "a", null, 9L));
    assertThat(ledger.applyBatch(first)).containsExactly(true, true);
    assertThat(ledger.applyBatch(first)).as("再来一次：之前已经记过").containsExactly(false, false);
    List<LedgerCommand> mixed = new ArrayList<>(first);
    mixed.add(new LedgerCommand(v, Bucket.BET_LOCKED, new BigDecimal("-20"), LedgerEntryType.BET_LOST, "SETTLE", k + "b", null, 9L));
    mixed.add(new LedgerCommand(v, Bucket.BET_LOCKED, new BigDecimal("-20"), LedgerEntryType.BET_LOST, "SETTLE", k + "b", null, 9L)); // 同一批里重复
    assertThat(ledger.applyBatch(mixed)).containsExactly(false, false, true, false);
    assertThat(ledger.snapshot(u).available()).isEqualByComparingTo("60");
    assertThat(ledger.snapshot(u).betLocked()).isEqualByComparingTo("40");
    assertThat(ledger.snapshot(v).betLocked()).isEqualByComparingTo("30");
    assertThat(query.mismatches()).isEmpty();
  }

  @Test
  void 同一个键换了金额或账户_程序错误_整批回滚() {
    long u = newUser("100", "50");
    long v = newUser("100", "50");
    String k = "conflict" + BIZ.incrementAndGet();
    ledger.applyBatch(List.of(new LedgerCommand(u, Bucket.BET_LOCKED, new BigDecimal("-10"), LedgerEntryType.BET_LOST, "SETTLE", k, null, 9L)));
    String before = state(v);
    assertThatThrownBy(() -> ledger.applyBatch(List.of(
        new LedgerCommand(v, Bucket.BET_LOCKED, new BigDecimal("-5"), LedgerEntryType.BET_LOST, "SETTLE", k + "-ok", null, 9L),
        new LedgerCommand(u, Bucket.BET_LOCKED, new BigDecimal("-11"), LedgerEntryType.BET_LOST, "SETTLE", k, null, 9L)))).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("幂等键冲突");
    assertThatThrownBy(() -> ledger.applyBatch(List.of(new LedgerCommand(v, Bucket.BET_LOCKED, new BigDecimal("-10"), LedgerEntryType.BET_LOST, "SETTLE", k, null, 9L))))
        .as("换了账户").isInstanceOf(IllegalStateException.class);
    assertThat(state(v)).as("前面那条也没有记").isEqualTo(before);
  }

  @Test
  void 余额不足_类型不对_账户不存在_整批拒绝_什么都不写() {
    long u = newUser("100", "50");
    long v = newUser("100", "50");
    String su = state(u);
    String sv = state(v);
    String k = "reject" + BIZ.incrementAndGet();
    assertThatThrownBy(() -> ledger.applyBatch(List.of(
        new LedgerCommand(u, Bucket.BET_LOCKED, new BigDecimal("-50"), LedgerEntryType.BET_LOST, "SETTLE", k + "1", null, 9L),
        new LedgerCommand(v, Bucket.BET_LOCKED, new BigDecimal("-50.01"), LedgerEntryType.BET_LOST, "SETTLE", k + "2", null, 9L))))
        .isInstanceOf(BizException.class).extracting(e -> ((BizException) e).code()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThatThrownBy(() -> ledger.applyBatch(List.of(new LedgerCommand(u, Bucket.AVAILABLE, new BigDecimal("-1"), LedgerEntryType.PAYOUT, "SETTLE", k + "3", null, 9L))))
        .as("派彩只能加").isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ledger.applyBatch(List.of(new LedgerCommand(IDS.incrementAndGet(), Bucket.AVAILABLE, BigDecimal.ONE, LedgerEntryType.PAYOUT, "SETTLE",
        k + "4", null, 9L)))).isInstanceOf(BizException.class).extracting(e -> ((BizException) e).code()).isEqualTo("ACCOUNT_NOT_FOUND");
    assertThat(state(u)).isEqualTo(su);
    assertThat(state(v)).isEqualTo(sv);
  }

  @Test
  void 一批超过500个客户_分段加锁和写入_结果正确() {
    List<Long> users = new ArrayList<>();
    for (int i = 0; i < 1_201; i++) users.add(newUser("1.01", "1")); // 可用 0.01、下注锁定 1
    String k = "big" + BIZ.incrementAndGet() + "-";
    List<LedgerCommand> cmds = new ArrayList<>();
    for (long u : users) {
      cmds.add(new LedgerCommand(u, Bucket.BET_LOCKED, new BigDecimal("-1"), LedgerEntryType.REFUND, "REFUND", k + u, null, 9L));
      cmds.add(new LedgerCommand(u, Bucket.AVAILABLE, new BigDecimal("1"), LedgerEntryType.REFUND, "REFUND", k + u, null, 9L));
    }
    assertThat(ledger.applyBatch(cmds)).hasSize(2_402).containsOnly(true);
    for (long u : List.of(users.get(0), users.get(600), users.get(1_200))) {
      assertThat(ledger.snapshot(u).available()).isEqualByComparingTo("1.01");
      assertThat(ledger.snapshot(u).betLocked()).isEqualByComparingTo("0");
    }
    assertThat(query.mismatches()).isEmpty();
  }

  @Test
  void lockAll_必须在事务里_账户不存在报错() {
    long u = newUser("1", "0");
    assertThatThrownBy(() -> ledger.lockAll(List.of(u))).isInstanceOf(IllegalTransactionStateException.class);
    tx.executeWithoutResult(st -> ledger.lockAll(List.of(u, u)));
    assertThatThrownBy(() -> tx.executeWithoutResult(st -> ledger.lockAll(List.of(u, IDS.incrementAndGet())))).isInstanceOf(BizException.class);
  }
}
