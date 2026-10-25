package com.forecast.app.settle.comp;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.id.Snowflake;
import com.forecast.app.ledger.api.AccountKind;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.ledger.api.PlatformLedgerApi;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.notification.api.NotifyApi;
import com.forecast.app.settle.api.SettleMath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 已结算后发现裁错的补偿（forecast S7；需求说明书第 3.6 节 E13、AC-35、RQ-8；技术方案/v1-架构方案.md 第 3.2、3.3 节）。
 * <ul>
 *   <li><b>不回滚、不追回已派的钱，只补不扣</b>：每个客户 补偿 = 按正确结果算的派彩 − 实际已得的派彩，只取 &gt; 0 的。</li>
 *   <li>"按正确结果算的派彩"用原结算单的持仓和费率（{@link SettleMath}，舍入同第 3.5 节）；正确结果是"作废"或正确的选项没人押（E1）时，应得 = 全额退款（本金）。
 *       E14、E15 当时退回的下注不在结算单里，不参与。同一客户几个持仓合在一起算。</li>
 *   <li>平台出钱：客户 {@code COMPENSATION}（可用 +，幂等键 COMP : 明细 id），平台补偿支出账户 {@code COMPENSATION_EXPENSE}（−合计，可以为负，
 *       幂等键 COMP_TOTAL : 补偿单 id）。不冲减平台收入，报表里单列。</li>
 *   <li>每个市场只有一张补偿单（唯一键）；有 {@code settle:compensate} 的一个人确认即入账（RQ-17；高危、原因必填、审计在 admin），通知 #17。</li>
 * </ul>
 * 一个事务：锁市场行 → 写补偿单和明细 → 客户账户（批量记账，按 user_id 升序锁）→ 平台账户（最后锁，架构方案第 4.2 节的顺序）。
 */
@Service
public class CompensationService {

  private static final Logger log = LoggerFactory.getLogger(CompensationService.class);
  static final String BIZ_ITEM = "COMP";
  static final String BIZ_TOTAL = "COMP_TOTAL";

  private final CompMapper mapper;
  private final MarketApi markets;
  private final LedgerApi ledger;
  private final PlatformLedgerApi platform;
  private final NotifyApi notify;
  private final Snowflake ids;
  private final Clock clock;
  private final TransactionTemplate tx;

  CompensationService(CompMapper mapper, MarketApi markets, LedgerApi ledger, PlatformLedgerApi platform, NotifyApi notify, Snowflake ids, Clock clock,
      PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.markets = markets;
    this.ledger = ledger;
    this.platform = platform;
    this.notify = notify;
    this.ids = ids;
    this.clock = clock;
    this.tx = new TransactionTemplate(tm);
    this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  /** 一个客户：应得、实际、补偿（&gt; 0） */
  public record Line(long userId, BigDecimal should, BigDecimal actual, BigDecimal amount) {}

  /**
   * 补偿的计算结果（预览和确认用同一个）。
   *
   * @param correctOptionId 正确结果；为空 = 本应作废
   * @param lines 只含补偿 &gt; 0 的客户，按 user_id 升序
   */
  public record Plan(long marketId, long runId, long originalOptionId, Long correctOptionId, BigDecimal feeRate, List<Line> lines, BigDecimal total) {}

  /** 补偿单 */
  public record Saved(long compensationNo, long marketId, long originalOptionId, Long correctOptionId, BigDecimal feeRate, BigDecimal total, int userCount,
      String reason, long createdBy, LocalDateTime createdAt, List<Line> lines) {}

  /**
   * 算出每个客户应补多少（只读）。市场必须已结算（结算单完成）；正确结果不能和原来的相同。
   *
   * @param correctOptionId 为空 = 本应作废
   */
  public Plan plan(long marketId, Long correctOptionId) {
    MarketApi.MarketRef m = markets.findById(marketId).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
    if (m.status() != MarketStatus.SETTLED) throw new BizException("MARKET_NOT_COMPENSABLE", "只有已结算的市场能补偿（" + m.status().api() + "）");
    CompRows.Run run = mapper.payoutRun(marketId);
    if (run == null || !"DONE".equals(run.status) || run.winningOptionId == null) {
      throw new BizException("MARKET_NOT_COMPENSABLE", "这个市场的结算还没完成，不能补偿");
    }
    if (correctOptionId != null && correctOptionId.equals(run.winningOptionId)) {
      throw new BizException("COMPENSATION_SAME_RESULT", "正确结果和原来结算的结果相同，不用补偿");
    }
    List<CompRows.Settled> items = mapper.settled(run.id);
    Map<Long, BigDecimal> actual = new TreeMap<>();
    Map<Long, BigDecimal> stake = new TreeMap<>();
    List<SettleMath.Stake> stakes = new ArrayList<>();
    for (CompRows.Settled i : items) {
      actual.merge(i.userId, i.payout, BigDecimal::add);
      stake.merge(i.userId, i.stake, BigDecimal::add);
      stakes.add(new SettleMath.Stake(i.positionId, i.userId, i.optionId, i.stake));
    }
    Map<Long, BigDecimal> should = new TreeMap<>();
    SettleMath.Result r = correctOptionId == null || stakes.isEmpty() ? null : SettleMath.settle(stakes, correctOptionId, run.feeRate);
    if (r == null || r.noWinner()) {
      should.putAll(stake); // 本应作废（或正确的选项没人押，E1）：应得 = 全额退款
    } else {
      for (SettleMath.Line l : r.lines()) should.merge(l.stake().userId(), l.payout(), BigDecimal::add);
    }
    List<Line> lines = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (Map.Entry<Long, BigDecimal> e : should.entrySet()) {
      BigDecimal a = actual.getOrDefault(e.getKey(), BigDecimal.ZERO);
      BigDecimal diff = e.getValue().subtract(a);
      if (diff.signum() > 0) {
        lines.add(new Line(e.getKey(), e.getValue(), a, diff));
        total = total.add(diff);
      }
    }
    return new Plan(marketId, run.id, run.winningOptionId, correctOptionId, run.feeRate, lines, total);
  }

  /**
   * 确认补偿并入账（一个事务）。同一个市场只能补偿一次（{@code COMPENSATION_EXISTS}）；没有人需要补偿时 {@code COMPENSATION_NOTHING}。
   */
  public Saved compensate(long marketId, Long correctOptionId, String reason, long adminId) {
    Saved saved = tx.execute(st -> {
      markets.lockForSettle(marketId); // 市场行排他锁：同一市场的补偿排队
      if (mapper.compByMarket(marketId) != null) throw exists();
      Plan p = plan(marketId, correctOptionId);
      if (p.lines().isEmpty()) throw new BizException("COMPENSATION_NOTHING", "按这个结果没有人少拿钱，不用补偿");
      LocalDateTime now = now();
      CompRows.Comp c = new CompRows.Comp();
      c.publicNo = ids.next();
      c.marketId = marketId;
      c.runId = p.runId();
      c.originalOptionId = p.originalOptionId();
      c.correctOptionId = correctOptionId;
      c.feeRate = p.feeRate();
      c.totalAmount = p.total();
      c.userCount = p.lines().size();
      c.reason = reason.length() <= 500 ? reason : reason.substring(0, 500);
      c.status = "PAID";
      c.createdBy = adminId;
      c.createdAt = now;
      try {
        mapper.insertComp(c);
      } catch (DuplicateKeyException e) {
        throw exists();
      }
      List<CompRows.Item> rows = new ArrayList<>();
      for (Line l : p.lines()) {
        CompRows.Item i = new CompRows.Item();
        i.compensationId = c.id;
        i.marketId = marketId;
        i.userId = l.userId();
        i.shouldPayout = l.should();
        i.actualPayout = l.actual();
        i.amount = l.amount();
        i.createdAt = now;
        rows.add(i);
      }
      for (int k = 0; k < rows.size(); k += 500) mapper.insertItems(rows.subList(k, Math.min(rows.size(), k + 500)));
      List<LedgerCommand> cmds = new ArrayList<>();
      for (CompRows.Item i : mapper.items(c.id)) {
        cmds.add(new LedgerCommand(i.userId, Bucket.AVAILABLE, i.amount, LedgerEntryType.COMPENSATION, BIZ_ITEM, String.valueOf(i.id), "补偿（E13）", marketId));
      }
      ledger.applyBatch(cmds);
      platform.apply(new PlatformLedgerApi.Command(AccountKind.PLATFORM_COMPENSATION, p.total().negate(), LedgerEntryType.COMPENSATION_EXPENSE, BIZ_TOTAL,
          String.valueOf(c.id), "补偿支出（E13）", marketId));
      // #17：每个被补偿的客户一条
      MarketApi.MarketRef m = markets.findById(marketId).orElseThrow();
      MarketApi.OptionRef correct = correctOptionId == null ? null : markets.optionsById(List.of(correctOptionId)).get(correctOptionId);
      List<NotifyApi.Notice> notices = new ArrayList<>();
      for (Line l : p.lines()) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("market", m.title().zhCN());
        v.put("marketEn", m.title().en());
        v.put("marketTw", m.title().zhTW());
        v.put("option", correct == null ? "VOID" : correct.label().zhCN());
        v.put("optionEn", correct == null ? "VOID" : correct.label().en());
        v.put("optionTw", correct == null ? "VOID" : correct.label().zhTW());
        v.put("amount", money(l.amount()));
        notices.add(new NotifyApi.Notice(l.userId(), v));
      }
      notify.toUsers("COMPENSATION_PAID", notices);
      return new Saved(c.publicNo, marketId, c.originalOptionId, correctOptionId, c.feeRate, c.totalAmount, c.userCount, c.reason, adminId, now, p.lines());
    });
    log.info("市场 {} 补偿 {} 个客户，合计 {}（平台补偿支出）", marketId, saved.userCount(), money(saved.total()));
    return saved;
  }

  private static BizException exists() {
    return new BizException("COMPENSATION_EXISTS", "这个市场已经补偿过了（每个市场只补偿一次）");
  }

  public Optional<Saved> byPublicNo(long compensationNo) {
    CompRows.Comp c = mapper.compByPublicNo(compensationNo);
    return c == null ? Optional.empty() : Optional.of(saved(c, true));
  }

  public Optional<Saved> byMarket(long marketId) {
    CompRows.Comp c = mapper.compByMarket(marketId);
    return c == null ? Optional.empty() : Optional.of(saved(c, false));
  }

  public List<Saved> list(int offset, int limit) {
    return mapper.listComps(offset, limit).stream().map(c -> saved(c, false)).toList();
  }

  public long count() {
    return mapper.countComps();
  }

  private Saved saved(CompRows.Comp c, boolean withLines) {
    List<Line> lines = withLines ? mapper.items(c.id).stream().map(i -> new Line(i.userId, i.shouldPayout, i.actualPayout, i.amount)).toList() : List.of();
    return new Saved(c.publicNo, c.marketId, c.originalOptionId, c.correctOptionId, c.feeRate, c.totalAmount, c.userCount, c.reason, c.createdBy, c.createdAt,
        lines);
  }

  /** 报表的一天：平台收入（抽成、尾差）和补偿支出分开列 */
  public record Day(LocalDate day, BigDecimal fee, BigDecimal dust, BigDecimal compensation, int compensations) {}

  /** [from, to] 每天的平台收入和补偿支出（按 UTC 日期；结算按完成时间，补偿按确认时间） */
  public List<Day> report(LocalDate from, LocalDate to) {
    LocalDateTime start = from.atStartOfDay();
    LocalDateTime end = to.plusDays(1).atStartOfDay();
    Map<LocalDate, Day> out = new TreeMap<>();
    for (CompRows.Day d : mapper.revenueByDay(start, end)) {
      out.put(d.day, new Day(d.day, nz(d.fee), nz(d.dust), BigDecimal.ZERO, 0));
    }
    for (CompRows.Day d : mapper.compensationByDay(start, end)) {
      Day prev = out.get(d.day);
      out.put(d.day, new Day(d.day, prev == null ? BigDecimal.ZERO : prev.fee(), prev == null ? BigDecimal.ZERO : prev.dust(), nz(d.amount), d.n));
    }
    return new ArrayList<>(out.values());
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  static String money(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
