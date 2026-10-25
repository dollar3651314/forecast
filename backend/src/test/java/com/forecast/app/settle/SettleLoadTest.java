package com.forecast.app.settle;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.id.Snowflake;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 大市场结算压测（AC-71、NF-03：10,000 个持仓 60 秒内全部派彩完、守恒成立；AQ-3 两种事务边界的对比数据）。默认跳过，
 * {@code mvn -o -B -ntp test -Dtest=SettleLoadTest -Dsettle.load=true} 运行（本机，不在 CI；建议 FC_DB_URL 指向单独的空库，压测会留下 1 万个客户）。
 * <ol>
 *   <li>准备：10,000 个客户、每人充值 1,000；一个 3 选项的市场，每人一笔下注（1～100，两位小数，选项 1 约 40%）。为了快，下注、持仓、奖池用批量 SQL 写、
 *       账本的"下注锁定"用批量记账写，口径和真实下注完全一样（结算前对账会核对 Σ下注 = Σ奖池 = Σ持仓 = 账本锁定，对不上就不结算）。</li>
 *   <li>结算期间的影响：8 个线程让<b>同一批客户</b>在另一个市场下注、4 个线程让其中 40 个客户提现（提交 + 撤销，锁同一个账户行），
 *       记录每个请求的耗时；分"结算之前 3 秒"和"结算期间"统计 p50 / p99 / 最大；另外记录 InnoDB 行锁等待的次数和时间（全库）。</li>
 *   <li>batch-size = 0（一个事务）和 500（每批一个事务）各结算一个市场，记录耗时；之后核对守恒、账本合计 = 余额。</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "settle.load", matches = "true")
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=40") // 测试配置只有 10 个连接，12 个并发线程会在拿连接上排队，测不出锁等待
class SettleLoadTest extends SettleTestSupport {

  static final int USERS = Integer.getInteger("settle.loadUsers", 10_000);
  @Autowired Snowflake ids;

  record Op(String kind, long start, long end, String result) {}

  List<Long> bulkUsers(int n) {
    List<Long> out = new ArrayList<>(n);
    String run = Long.toString(System.nanoTime(), 36);
    for (int i = 0; i < n; i += 1000) {
      int k = Math.min(n, i + 1000);
      StringBuilder sql = new StringBuilder("INSERT INTO account_user (email, status) VALUES ");
      List<Object> args = new ArrayList<>();
      for (int j = i; j < k; j++) {
        sql.append(j > i ? ",(?, 'ACTIVE')" : "(?, 'ACTIVE')");
        args.add("load" + run + "-" + j + "@example.com");
      }
      jdbc.update(sql.toString(), args.toArray());
    }
    out.addAll(jdbc.queryForList("SELECT id FROM account_user WHERE email LIKE ? ORDER BY id", Long.class, "load" + run + "-%"));
    for (int i = 0; i < out.size(); i += 1000) {
      List<Long> chunk = out.subList(i, Math.min(out.size(), i + 1000));
      StringBuilder sql = new StringBuilder("INSERT INTO ledger_account (kind, user_id) VALUES ");
      for (int j = 0; j < chunk.size(); j++) sql.append(j > 0 ? ",('USER', ?)" : "('USER', ?)");
      jdbc.update(sql.toString(), chunk.toArray());
      List<LedgerCommand> dep = new ArrayList<>();
      for (long u : chunk) dep.add(new LedgerCommand(u, Bucket.AVAILABLE, new BigDecimal("1000"), LedgerEntryType.DEPOSIT, "TEST", "load-dep-" + u, "压测入金"));
      ledger.applyBatch(dep);
    }
    return out;
  }

  /** 每人一笔下注（批量写，和 BetService 的结果一致）；返回 T */
  BigDecimal bulkBets(Market m, List<Long> users, Random rnd) {
    long[] optionIds = {optionId(m, 0), optionId(m, 1), optionId(m, 2)};
    LocalDateTime now = LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    BigDecimal[] perOption = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    int[] bettors = new int[3];
    BigDecimal total = BigDecimal.ZERO;
    for (int i = 0; i < users.size(); i += 1000) {
      List<Long> chunk = users.subList(i, Math.min(users.size(), i + 1000));
      StringBuilder bets = new StringBuilder("INSERT INTO pool_bet (public_no, user_id, market_id, option_id, amount, client_req_id, received_at, status, snapshot, created_at) VALUES ");
      StringBuilder pos = new StringBuilder("INSERT INTO pool_position (user_id, market_id, option_id, amount, bet_count, created_at, updated_at) VALUES ");
      List<Object> ba = new ArrayList<>();
      List<Object> pa = new ArrayList<>();
      List<LedgerCommand> lock = new ArrayList<>();
      for (int j = 0; j < chunk.size(); j++) {
        long u = chunk.get(j);
        int r = rnd.nextInt(100);
        int o = r < 40 ? 0 : r < 75 ? 1 : 2;
        BigDecimal amt = BigDecimal.valueOf(100 + rnd.nextInt(9_901), 2);
        long no = ids.next();
        bets.append(j > 0 ? "," : "").append("(?, ?, ?, ?, ?, ?, ?, 'ACTIVE', '{}', ?)");
        ba.addAll(List.of(no, u, m.id(), optionIds[o], amt, "load-" + UUID.randomUUID(), Timestamp.valueOf(now), Timestamp.valueOf(now)));
        pos.append(j > 0 ? "," : "").append("(?, ?, ?, ?, 1, ?, ?)");
        pa.addAll(List.of(u, m.id(), optionIds[o], amt, Timestamp.valueOf(now), Timestamp.valueOf(now)));
        lock.add(new LedgerCommand(u, Bucket.AVAILABLE, amt.negate(), LedgerEntryType.BET_LOCK, "BET", String.valueOf(no), null, m.id()));
        lock.add(new LedgerCommand(u, Bucket.BET_LOCKED, amt, LedgerEntryType.BET_LOCK, "BET", String.valueOf(no), null, m.id()));
        perOption[o] = perOption[o].add(amt);
        bettors[o]++;
        total = total.add(amt);
      }
      jdbc.update(bets.toString(), ba.toArray());
      jdbc.update(pos.toString(), pa.toArray());
      ledger.applyBatch(lock);
    }
    for (int o = 0; o < 3; o++) {
      jdbc.update("UPDATE pool_option_total SET amount = amount + ?, bettors = bettors + ? WHERE option_id = ?", perOption[o], bettors[o], optionIds[o]);
    }
    return total;
  }

  Cookie session(long uid) throws Exception {
    String token = UUID.randomUUID().toString();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    jdbc.update("INSERT INTO account_session (user_id, token_hash, created_at, last_seen_at, expires_at) VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), "
        + "UTC_TIMESTAMP(3) + INTERVAL 1 DAY)", uid, hash);
    return new Cookie("fc_session", token);
  }

  long metric(String name) {
    Long n = jdbc.queryForObject("SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME = ?", Long.class, name);
    return n == null ? 0 : n;
  }

  static String stats(List<Op> ops, String kind, long from, long to) {
    List<Long> ms = new ArrayList<>();
    int fail = 0;
    for (Op o : ops) {
      if (!o.kind().equals(kind) || o.end() < from || o.start() > to) continue;
      ms.add((o.end() - o.start()) / 1_000_000);
      if (!o.result().equals("OK")) fail++;
    }
    if (ms.isEmpty()) return kind + "：0 次";
    Collections.sort(ms);
    return String.format("%s：%d 次（失败 %d），p50 %d 毫秒、p99 %d 毫秒、最大 %d 毫秒", kind, ms.size(), fail, ms.get(ms.size() / 2), ms.get(Math.min(ms.size() - 1,
        (int) Math.ceil(ms.size() * 0.99) - 1)), ms.get(ms.size() - 1));
  }

  @Test
  void 一万个持仓的市场_两种事务边界_耗时和对下注提现的影响() throws Exception {
    Random rnd = new Random(71);
    long prep = System.nanoTime();
    List<Long> users = bulkUsers(USERS);
    // 40 个客户可以提现：KYC 已通过、登录会话、白名单已生效
    List<Cookie> wdCookies = new ArrayList<>();
    List<String> wdWhitelist = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      long u = users.get(i * (USERS / 40));
      jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', ?, 'f', 'b', 's', 'APPROVED')",
          u, "P" + u);
      Cookie c = session(u);
      MockHttpServletResponse w = call(MockMvcRequestBuilders.post("/api/wallet/whitelist"), c,
          "{\"network\":\"ERC20\",\"address\":\"0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed\",\"label\":\"load\"}");
      assertThat(w.getStatus()).as(w.getContentAsString()).isEqualTo(200);
      String wid = body(w).get("id").asString();
      jdbc.update("UPDATE wallet_whitelist SET active_at = created_at - INTERVAL 1 HOUR WHERE id = ?", Long.parseLong(wid));
      wdCookies.add(c);
      wdWhitelist.add(wid);
    }
    System.out.printf("[结算压测] 准备 %d 个客户用了 %.1f 秒%n", users.size(), (System.nanoTime() - prep) / 1e9);
    Map<String, String> report = new LinkedHashMap<>();
    // 对照实验：-Dsettle.loadNoNotify=true 时停用 #11、#12（看通知占了多少时间；正式数据不用这个）
    boolean noNotify = Boolean.getBoolean("settle.loadNoNotify");
    if (noNotify) jdbc.update("UPDATE notify_template SET enabled = 0 WHERE code IN ('SETTLE_WON', 'SETTLE_LOST')");
    for (int batch : List.of(0, 500)) {
      settleProps.setBatchSize(batch);
      Market big = shortMarket(3);
      Market other = market(2, Instant.now(clock).plus(java.time.Duration.ofDays(1)), null);
      long t = System.nanoTime();
      BigDecimal total = bulkBets(big, users, rnd);
      System.out.printf("[结算压测 batch-size=%d] 写入 %d 笔下注（T = %s）用了 %.1f 秒%n", batch, users.size(), total.toPlainString(), (System.nanoTime() - t) / 1e9);
      closeAndEnd(big);
      assertThat(status(big)).isEqualTo("PENDING_RESOLUTION");
      assertThat(settle.check(big.id())).as("准备的数据和真实下注一样，结算前对账对得上").isEmpty();
      // 同时：同一批客户在另一个市场下注、其中 40 个提现（提交 + 撤销）
      List<Op> ops = new CopyOnWriteArrayList<>();
      AtomicBoolean stop = new AtomicBoolean();
      ExecutorService pool = Executors.newFixedThreadPool(12);
      List<Future<?>> fs = new ArrayList<>();
      for (int k = 0; k < 12; k++) {
        int kind = k;
        fs.add(pool.submit(() -> {
          Random r = new Random(kind * 31L + batch);
          while (!stop.get()) {
            long s = System.nanoTime();
            String res;
            String what;
            try {
              if (kind < 8) {
                what = "下注";
                bet(users.get(r.nextInt(users.size())), other, r.nextInt(2), (1 + r.nextInt(5)) + ".00");
                res = "OK";
              } else {
                what = "提现（提交 + 撤销）";
                int i = r.nextInt(wdCookies.size());
                MockHttpServletResponse sub = call(MockMvcRequestBuilders.post("/api/wallet/withdrawals"), wdCookies.get(i),
                    "{\"whitelistId\":\"" + wdWhitelist.get(i) + "\",\"amount\":\"100\"}");
                if (sub.getStatus() == 200) {
                  MockHttpServletResponse c = call(MockMvcRequestBuilders.post("/api/wallet/withdrawals/" + body(sub).get("id").asString() + "/cancel"), wdCookies.get(i), null);
                  res = c.getStatus() == 200 ? "OK" : "cancel." + c.getStatus();
                } else {
                  res = "submit." + sub.getStatus() + " " + body(sub).path("code").asString();
                }
              }
            } catch (BizException e) {
              what = kind < 8 ? "下注" : "提现（提交 + 撤销）";
              res = e.code();
            } catch (Exception e) {
              what = kind < 8 ? "下注" : "提现（提交 + 撤销）";
              res = e.getClass().getSimpleName();
            }
            ops.add(new Op(what, s, System.nanoTime(), res));
          }
          return null;
        }));
      }
      Thread.sleep(3_000);
      long waits0 = metric("lock_row_lock_waits");
      long waitMs0 = metric("lock_row_lock_time");
      long t0 = System.nanoTime();
      settle.settle(big.id(), optionId(big, 0), null, "1");
      long t1 = System.nanoTime();
      if (batch > 0) settle.drainAll();
      long t2 = System.nanoTime();
      long waits = metric("lock_row_lock_waits") - waits0;
      long waitMs = metric("lock_row_lock_time") - waitMs0;
      Thread.sleep(1_000);
      stop.set(true);
      for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
      pool.shutdownNow();
      Map<String, Object> run = run(big, "PAYOUT");
      assertThat(run.get("status")).isEqualTo("DONE");
      settledConserved(big, total.toPlainString());
      int positions = ((Number) run.get("item_count")).intValue();
      String head = String.format("batch-size=%d：%d 个持仓（赢方 %s），结算 %.2f 秒（计算 %.2f 秒%s）", batch, positions, run.get("winner_positions"), (t2 - t0) / 1e9,
          (t1 - t0) / 1e9, batch > 0 ? String.format("、派彩 %.2f 秒", (t2 - t1) / 1e9) : "，计算和派彩同一个事务");
      String before = stats(ops, "下注", t0 - 3_000_000_000L, t0) + "；" + stats(ops, "提现（提交 + 撤销）", t0 - 3_000_000_000L, t0);
      String during = stats(ops, "下注", t0, t2) + "；" + stats(ops, "提现（提交 + 撤销）", t0, t2);
      String innodb = String.format("结算期间 InnoDB 行锁等待 %d 次、合计 %d 毫秒（全库，含压测线程之间的）", waits, waitMs);
      report.put("batch-size=" + batch, head + "\n    结算之前 3 秒：" + before + "\n    结算期间：" + during + "\n    " + innodb);
      assertThat((t2 - t0) / 1e9).as("AC-71：10,000 个持仓 60 秒内全部派彩完").isLessThan(60.0);
      assertThat(ops.stream().filter(o -> !o.result().equals("OK") && !o.result().startsWith("INSUFFICIENT") && !o.result().startsWith("submit.400")))
          .as("结算期间别的下注、提现没有出错（余额不足等业务拒绝除外）").isEmpty();
    }
    if (noNotify) jdbc.update("UPDATE notify_template SET enabled = 1 WHERE code IN ('SETTLE_WON', 'SETTLE_LOST')");
    System.out.println("[结算压测] 结果" + (noNotify ? "（对照：停用了 #11、#12）" : "") + "：");
    report.values().forEach(v -> System.out.println("  " + v));
  }
}
