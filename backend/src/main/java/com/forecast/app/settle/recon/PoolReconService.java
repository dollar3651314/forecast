package com.forecast.app.settle.recon;

import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.ledger.api.LedgerQueryApi;
import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.pool.api.PoolSettleApi;
import com.forecast.app.settle.run.SettleMapper;
import com.forecast.app.settle.run.SettleRows;
import com.forecast.app.settle.run.SettleService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 每日彩池对账（需求说明书第 3.9 节第 3 条、AC-38；技术方案/v1-架构方案.md 第 4.4 节；forecast S5）。只读，不改任何金额：
 * <ul>
 *   <li><b>未结算的市场</b>（已上架到争议中，且没有正在派的单）：Σ有效下注 = Σ选项奖池 = Σ持仓 = 账本里这个市场的下注锁定，截止后定格的奖池 = 现在的奖池；</li>
 *   <li><b>派彩中的结算单</b>：账本里这个市场还锁着的 = 还没派的本金；<b>派彩中的退款单</b>：= 还没退的金额；</li>
 *   <li><b>最近 3 天完成的结算单</b>：按账本复核守恒（Σ结算解除的下注锁定 = T、Σ(派彩 − 抽成) + 平台(抽成 + 尾差) = T、平台收入 = 抽成 + 尾差、下注锁定归零）；
 *       <b>完成的退款单</b>：下注锁定归零、没有有效下注；</li>
 *   <li><b>全部账户</b>：账本合计 = 余额（{@link LedgerQueryApi#mismatches()}）。</li>
 * </ul>
 * 每个市场在一个可重复读的只读事务里查（同一个快照，不会因为对账时正好有人下注而误报）。不一致的写进 {@code settle_pool_recon_issue}
 * （同一个对象同一种差异还没处理的不重复记），有新差异时邮件告警（模板 POOL_RECON_DIFF）。有待处理差异的市场：结算时停在"结算异常"，
 * 派彩中的单下一批停下（{@link SettleService}）；提现自动审批 R12 转人工。
 */
@Service
public class PoolReconService {

  private static final Logger log = LoggerFactory.getLogger(PoolReconService.class);
  /** 已完成的单，完成后几天内每天再复核一次 */
  static final int DONE_LOOKBACK_DAYS = 3;

  private final SettleMapper mapper;
  private final SettleService settle;
  private final PoolSettleApi pool;
  private final LedgerQueryApi ledger;
  private final StaffAlertApi staff;
  private final Clock clock;
  private final TransactionTemplate snapshot;

  PoolReconService(SettleMapper mapper, SettleService settle, PoolSettleApi pool, LedgerQueryApi ledger, StaffAlertApi staff, Clock clock,
      PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.settle = settle;
    this.pool = pool;
    this.ledger = ledger;
    this.staff = staff;
    this.clock = clock;
    this.snapshot = new TransactionTemplate(tm);
    this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.snapshot.setReadOnly(true);
  }

  /** 一次对账的结果 */
  public record Report(int markets, int runs, int fresh, Set<Long> marketsWithIssues) {}

  /** 跑一次（每日任务、e2e、测试调用）：返回检查了多少、新记了几条差异 */
  public Report run() {
    LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
    Map<Long, List<SettleService.Finding>> byMarket = new LinkedHashMap<>();
    List<SettleRows.Run> runs = mapper.runsForRecon(now.minusDays(DONE_LOOKBACK_DAYS));
    Set<Long> paying = new TreeSet<>();
    for (SettleRows.Run r : runs) {
      if (SettleService.PAYING.equals(r.status)) paying.add(r.marketId);
      List<SettleService.Finding> f = snapshot.execute(st -> checkRun(r));
      if (f != null && !f.isEmpty()) byMarket.computeIfAbsent(r.marketId, k -> new ArrayList<>()).addAll(f);
    }
    int markets = 0;
    for (long marketId : pool.openMarkets()) {
      if (paying.contains(marketId)) continue; // 派到一半：上面按"还锁着的 = 还没派的"查过了
      markets++;
      List<SettleService.Finding> f = snapshot.execute(st -> settle.check(marketId));
      if (f != null && !f.isEmpty()) byMarket.computeIfAbsent(marketId, k -> new ArrayList<>()).addAll(f);
    }
    int fresh = 0;
    Set<Long> withIssues = new TreeSet<>();
    for (Map.Entry<Long, List<SettleService.Finding>> e : byMarket.entrySet()) {
      int n = settle.recordIssuesForRecon(e.getKey(), e.getValue(), now);
      fresh += n;
      if (n > 0) withIssues.add(e.getKey());
    }
    for (LedgerQueryApi.Mismatch m : ledger.mismatches()) {
      String detail = "账户 " + m.accountId() + "（" + m.kind() + (m.userId() == null ? "" : "，客户 " + m.userId()) + "）的" + (m.bucket() == null ? "未知项" : m.bucket().name())
          + "：账本合计 ≠ 余额";
      fresh += settle.recordAccountIssue("A:" + m.accountId() + ":" + (m.bucket() == null ? "?" : m.bucket().name()), m.accountValue(), m.ledgerSum(), detail, now);
    }
    if (fresh > 0) {
      log.warn("彩池对账发现 {} 个新差异（{} 个市场）", fresh, withIssues.size());
      staff.notifyStaff("POOL_RECON_DIFF", Map.of("date", now.toLocalDate().toString(), "count", String.valueOf(fresh), "markets", String.valueOf(withIssues.size())));
    }
    return new Report(markets, runs.size(), fresh, withIssues);
  }

  /** 派彩中、最近完成的单：按账本核对 */
  private List<SettleService.Finding> checkRun(SettleRows.Run r) {
    List<SettleService.Finding> out = new ArrayList<>();
    Map<LedgerQueryApi.TypeBucket, BigDecimal> t = ledger.marketTotals(r.marketId);
    BigDecimal locked = BigDecimal.ZERO;
    for (Map.Entry<LedgerQueryApi.TypeBucket, BigDecimal> e : t.entrySet()) if (e.getKey().bucket() == Bucket.BET_LOCKED) locked = locked.add(e.getValue());
    boolean payout = SettleService.PAYOUT.equals(r.kind);
    if (SettleService.PAYING.equals(r.status)) {
      BigDecimal pending = payout ? mapper.pendingStake(r.id) : mapper.pendingRefundAmount(r.id);
      if (locked.compareTo(pending) != 0) {
        out.add(new SettleService.Finding(payout ? "PAYING_VS_LEDGER" : "REFUND_VS_LEDGER", pending, locked, "派彩中：账本里这个市场还锁着的 ≠ 还没派的金额"));
      }
      return out;
    }
    if (locked.signum() != 0) out.add(new SettleService.Finding("POOL_VS_LEDGER", BigDecimal.ZERO, locked, "已" + (payout ? "结算" : "退款") + "的市场账本里还有下注锁定"));
    if (payout) {
      BigDecimal released = get(t, LedgerEntryType.BET_SETTLE, Bucket.BET_LOCKED).add(get(t, LedgerEntryType.BET_LOST, Bucket.BET_LOCKED)).negate();
      BigDecimal paid = get(t, LedgerEntryType.PAYOUT, Bucket.AVAILABLE).add(get(t, LedgerEntryType.FEE, Bucket.AVAILABLE));
      BigDecimal income = get(t, LedgerEntryType.FEE_INCOME, Bucket.AVAILABLE).add(get(t, LedgerEntryType.SETTLE_DUST, Bucket.AVAILABLE));
      if (released.compareTo(r.poolTotal) != 0) out.add(new SettleService.Finding("CONSERVATION", r.poolTotal, released, "结算解除的下注锁定 ≠ T"));
      if (paid.add(income).compareTo(r.poolTotal) != 0) out.add(new SettleService.Finding("CONSERVATION", r.poolTotal, paid.add(income), "Σ(派彩 − 抽成) + 平台(抽成 + 尾差) ≠ T"));
      BigDecimal expectIncome = nz(r.sumFee).add(nz(r.dust));
      if (income.compareTo(expectIncome) != 0) out.add(new SettleService.Finding("CONSERVATION", expectIncome, income, "平台收入 ≠ 结算单的抽成 + 尾差"));
    } else {
      BigDecimal active = pool.sumActiveBets(r.marketId);
      if (active.signum() != 0) out.add(new SettleService.Finding("POOL_VS_BETS", BigDecimal.ZERO, active, "已退款的市场还有有效下注"));
    }
    return out;
  }

  private static BigDecimal get(Map<LedgerQueryApi.TypeBucket, BigDecimal> t, LedgerEntryType type, Bucket b) {
    return t.getOrDefault(new LedgerQueryApi.TypeBucket(type, b), BigDecimal.ZERO);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
