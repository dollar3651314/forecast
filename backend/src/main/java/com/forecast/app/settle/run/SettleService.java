package com.forecast.app.settle.run;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.id.Snowflake;
import com.forecast.app.ledger.api.AccountKind;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.ledger.api.LedgerQueryApi;
import com.forecast.app.ledger.api.PlatformLedgerApi;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.market.api.SettlePhase;
import com.forecast.app.notification.api.NotifyApi;
import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.pool.api.PoolSettleApi;
import com.forecast.app.settle.api.SettleMath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 结算和退款（forecast S5；需求说明书第 3.5、3.6、3.9 节，技术方案/v1-架构方案.md 第 4.3、4.4 节）。
 *
 * <h2>结算三段</h2>
 * <ol>
 *   <li><b>计算</b>（一个事务）：锁市场行 → 结算阶段必须是 NONE（否则已经开始，直接返回：幂等）→ 记下结果 → <b>结算前对账</b>
 *       （Σ有效下注 = Σ选项奖池 = Σ持仓 = 账本里这个市场的下注锁定，截止后定格的奖池也相等；这个市场没有待处理的对账差异）→
 *       E14 退回实际开赛之后收到的下注、E15 退回员工的下注 → 按持仓算 G、F、Pay、D（{@link SettleMath}）→ 没人押赢方作废（E1）→
 *       <b>守恒校验</b>，不通过的写一张"结算异常"的结算单、不动钱、告警 → 通过的写结算单和全部明细，结算阶段 NONE → PAYING。</li>
 *   <li><b>派彩</b>：明细按 user_id 升序分批，每批 PENDING → DONE（条件更新）、记账（批量，幂等键 SETTLE : 明细 id）、通知 #11 / #12、
 *       平台抽成按批入账（幂等键 SETTLE_FEE : 结算单 : 这批第一个明细）。</li>
 *   <li><b>收尾</b>（一个事务）：明细全部 DONE → 尾差入平台（SETTLE_RUN : 结算单）→ <b>按账本复核守恒</b> → 结算阶段 DONE、市场进入已结算。</li>
 * </ol>
 * 事务边界由 {@code forecast.settle.batch-size} 决定（{@link SettleProperties}，AQ-3 已定：B）：<b>大于 0（默认 500）= 计算一个事务，
 * 派彩每批一个事务（{@link PayoutWorker} 推进，可以续跑），收尾一个事务</b>（架构方案第 4.3 节方案 B）；0 = 三段在同一个事务里一次完成（可配置项）。
 *
 * <h2>三道防重复派彩</h2>
 * ① {@code settle_run} 唯一 (market_id, kind)；② 明细 PENDING → DONE 的条件更新（改的行数不对整批回滚）；③ 账本幂等键。外加市场行、结算单行的锁让并发的触发排队。
 *
 * <h2>退款</h2>
 * 取消、作废（含 E1、E3、E4、E5）：market 进入 CANCELLED / VOIDED 的同一个事务里通知 {@link SettleMarketListener}，这里建退款单（每笔有效下注一个明细），
 * 派法和派彩一样（同一个 batch-size）。E14、E15、提前截止后的退回是单笔退款，在当时的事务里当场退。退款同步扣持仓和选项奖池（{@link PoolSettleApi#markRefunded}）。
 *
 * <h2>加锁顺序</h2>
 * 市场行 → 结算单行 → 客户账户行（按 user_id 升序）→ pool 的行 → 平台账户行（架构方案第 4.2 节）。batch-size = 0 时一个事务里有几次记账，
 * 开头先按升序锁齐这个市场全部下注客户的账户（{@link LedgerApi#lockAll}）。事务用 READ COMMITTED（和下注一样：锁之后读到的是最新提交的数据）。
 */
@Service
public class SettleService {

  private static final Logger log = LoggerFactory.getLogger(SettleService.class);

  public static final String PAYOUT = "PAYOUT";
  public static final String REFUND = "REFUND";
  public static final String PAYING = "PAYING";
  public static final String DONE = "DONE";
  public static final String ABNORMAL = "ABNORMAL";
  /** 账本幂等键的业务类型 */
  static final String BIZ_SETTLE = "SETTLE";
  static final String BIZ_REFUND = "REFUND";
  static final String BIZ_FEE = "SETTLE_FEE";
  static final String BIZ_DUST = "SETTLE_RUN";
  /** 作废原因代码：E1 没人押赢方、退回之后奖池为 0 */
  public static final String VOID_NO_WINNER = "NO_WINNER";
  public static final String VOID_NO_POOL = "NO_POOL";
  /** 批次大小为 0 时派彩任务（续跑已经在派的单）每批用多少 */
  static final int WORKER_DEFAULT_BATCH = 500;
  /** 能结算的市场状态：待裁决（S5 超管填结果、S6 自动裁决）、公示中（S7 公示期满）、争议中（S7 处理完） */
  static final Set<MarketStatus> SETTLEABLE = EnumSet.of(MarketStatus.PENDING_RESOLUTION, MarketStatus.PUBLICIZING, MarketStatus.DISPUTED);
  private static final DateTimeFormatter NOTICE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

  private final SettleMapper mapper;
  private final MarketApi markets;
  private final PoolSettleApi pool;
  private final LedgerApi ledger;
  private final PlatformLedgerApi platform;
  private final LedgerQueryApi ledgerQuery;
  private final AccountApi accounts;
  private final NotifyApi notify;
  private final StaffAlertApi staff;
  private final Snowflake ids;
  private final Clock clock;
  private final SettleProperties props;
  private final SettleFaults faults;
  private final PayoutKick kick;
  private final TransactionTemplate tx;

  SettleService(SettleMapper mapper, MarketApi markets, PoolSettleApi pool, LedgerApi ledger, PlatformLedgerApi platform, LedgerQueryApi ledgerQuery,
      AccountApi accounts, NotifyApi notify, StaffAlertApi staff, Snowflake ids, Clock clock, SettleProperties props, SettleFaults faults,
      PayoutKick kick, PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.markets = markets;
    this.pool = pool;
    this.ledger = ledger;
    this.platform = platform;
    this.ledgerQuery = ledgerQuery;
    this.accounts = accounts;
    this.notify = notify;
    this.staff = staff;
    this.ids = ids;
    this.clock = clock;
    this.props = props;
    this.faults = faults;
    this.kick = kick;
    this.tx = new TransactionTemplate(tm);
    this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  // =====================================================================
  // ① 计算（+ batch-size = 0 时派彩、收尾）
  // =====================================================================

  /**
   * 按结果结算一个市场（超管手工填结果、以后的自动裁决、公示期满、争议处理都从这里进）。同一个市场重复调用（含并发）只结算一次。
   * batch-size = 0 时返回时已经结算完；大于 0 时返回时已经算好、进入派彩中，由 {@link PayoutWorker} 派完。
   *
   * @param actualStartAt 实际开赛时间（E14），可空
   * @param resolvedBy 谁给的结果（管理员 id / AUTO）
   */
  public void settle(long marketId, long winningOptionId, Instant actualStartAt, String resolvedBy) {
    settle(marketId, winningOptionId, actualStartAt, resolvedBy, null);
  }

  /**
   * 同上，但只在市场<b>正好是 expect 状态</b>时开始（forecast S7）：锁住市场行以后检查，不是就什么都不做（不报错）。
   * 公示期满的结算用 {@code PUBLICIZING}：和"客户提交争议（公示中 → 争议中）"在市场行锁上排队，只有一个生效。
   *
   * @param expect 为空 = 待裁决、公示中、争议中都可以
   */
  public void settle(long marketId, long winningOptionId, Instant actualStartAt, String resolvedBy, MarketStatus expect) {
    tx.executeWithoutResult(st -> calculate(marketId, winningOptionId, actualStartAt, resolvedBy, false, expect));
    if (props.getBatchSize() > 0) kick.now();
  }

  private void calculate(long marketId, long winningOptionId, Instant actualStartAt, String resolvedBy, boolean retry) {
    calculate(marketId, winningOptionId, actualStartAt, resolvedBy, retry, null);
  }

  private void calculate(long marketId, long winningOptionId, Instant actualStartAt, String resolvedBy, boolean retry, MarketStatus expect) {
    MarketApi.SettleTarget t = markets.lockForSettle(marketId);
    if (t.settlePhase() != SettlePhase.NONE) return; // 已经开始（派彩中、完成、异常）：幂等，什么都不做
    if (expect != null && t.status() != expect) return; // S7：状态已经变了（例如公示期满的同时有人提交了争议）
    if (!SETTLEABLE.contains(t.status())) throw new BizException("MARKET_NOT_SETTLEABLE", "市场现在不能结算（" + t.status().api() + "）");
    if (!markets.recordResult(marketId, winningOptionId, actualStartAt, resolvedBy)) return;
    int batch = props.getBatchSize();
    LocalDateTime now = now();
    List<PoolSettleApi.Bet> active = pool.activeBets(marketId);
    if (batch == 0) ledger.lockAll(active.stream().map(PoolSettleApi.Bet::userId).toList());
    // 结算前对账（需求第 3.9 节第 2 条）：对不上就停在"结算异常"，不动钱
    List<Finding> found = check(marketId);
    if (mapper.openIssuesOfMarket(marketId) > 0) found.add(new Finding("RECON_OPEN", BigDecimal.ZERO, BigDecimal.ZERO, "这个市场有待处理的彩池对账差异"));
    if (!found.isEmpty()) {
      SettleRows.Run run = newRun(t, PAYOUT, null, now, batch, resolvedBy);
      run.winningOptionId = winningOptionId;
      run.feeRate = t.feeRate();
      run.poolTotal = pool.sumActiveBets(marketId);
      abnormalAtCalc(t, run, "SETTLE", found, now);
      return;
    }
    // E14：实际开赛时间早于截止时间时，之后收到的下注全额退回（不参与结算）
    if (actualStartAt != null && actualStartAt.isBefore(t.closeAt())) {
      refundNow(t, pool.activeBetsReceivedFrom(marketId, ldt(actualStartAt)), "LATE_BET", actualStartAt, now);
    }
    // E15：员工账户的下注退回（漏登记后补登的）
    Set<Long> staffUsers = accounts.staffAmong(active.stream().map(PoolSettleApi.Bet::userId).distinct().toList());
    if (!staffUsers.isEmpty()) refundNow(t, pool.activeBetsOfUsers(marketId, staffUsers), "STAFF", null, now);
    List<PoolSettleApi.Position> positions = pool.positions(marketId);
    if (positions.isEmpty()) { // 退回之后奖池为 0：作废（没有要退的钱了）
      markets.transition(marketId, t.status(), MarketStatus.VOIDED, actor(resolvedBy), VOID_NO_POOL);
      return;
    }
    List<SettleMath.Stake> stakes = positions.stream().map(p -> new SettleMath.Stake(p.id(), p.userId(), p.optionId(), p.amount())).toList();
    SettleMath.Result r = SettleMath.settle(stakes, winningOptionId, t.feeRate());
    if (r.noWinner()) { // E1：没人押赢方 → 作废、全额退款、不收费（退款单在 market 的作废事务里由 SettleMarketListener 建）
      markets.transition(marketId, t.status(), MarketStatus.VOIDED, actor(resolvedBy), VOID_NO_WINNER);
      return;
    }
    SettleRows.Run run = newRun(t, PAYOUT, null, now, batch, resolvedBy);
    run.winningOptionId = winningOptionId;
    run.feeRate = t.feeRate();
    run.poolTotal = r.total();
    run.winnerPool = r.winnerPool();
    run.loserPool = r.loserPool();
    run.sumGross = r.sumGross();
    run.sumFee = r.sumFee();
    run.dust = r.dust();
    run.sumPayout = r.sumPayout();
    run.winnerPositions = r.winners();
    run.loserPositions = r.losers();
    run.conservationOk = r.conserved();
    if (!r.conserved()) {
      abnormalAtCalc(t, run, "SETTLE", List.of(new Finding("CONSERVATION", r.total(), r.sumPayout().add(r.sumFee()).add(r.dust()),
          "ΣPay + ΣF + D ≠ T 或尾差超出范围：D = " + money(r.dust()) + "，赢方持仓 " + r.winners())), now);
      return;
    }
    run.itemCount = r.lines().size();
    run.status = PAYING;
    saveRun(run);
    List<SettleRows.Item> items = new ArrayList<>(r.lines().size());
    for (SettleMath.Line l : r.lines()) {
      SettleRows.Item i = new SettleRows.Item();
      i.runId = run.id;
      i.marketId = marketId;
      i.positionId = l.stake().key();
      i.userId = l.stake().userId();
      i.optionId = l.stake().optionId();
      i.win = l.win();
      i.stake = l.stake().amount();
      i.gross = l.gross();
      i.fee = l.fee();
      i.payout = l.payout();
      items.add(i);
    }
    for (int k = 0; k < items.size(); k += WORKER_DEFAULT_BATCH) mapper.insertItems(items.subList(k, Math.min(items.size(), k + WORKER_DEFAULT_BATCH)));
    if (!markets.casSettlePhase(marketId, SettlePhase.NONE, SettlePhase.PAYING)) throw new IllegalStateException("结算阶段被别人改了：" + marketId);
    log.info("市场 {} 结算算好：T {}，赢方持仓 {}，抽成 {}，尾差 {}，每批 {}{}", t.publicNo(), money(r.total()), r.winners(), money(r.sumFee()),
        money(r.dust()), batch, retry ? "（重试）" : "");
    if (batch == 0) {
      payPayout(run, t.title(), Integer.MAX_VALUE);
      finishPayout(run, t);
    }
  }

  /**
   * 结算预览（forecast S7，裁决台"按提议结果的结算预览"，需求第 6.2 节、W4）：按这个结果<b>现在</b>结算会怎样——不加锁、不写任何数据。
   * 和 {@link #calculate} 同样的步骤：E14 退回实际开赛之后收到的下注、E15 退回员工的下注，按持仓算 G、F、Pay、D，守恒校验；没人押赢方或退回后奖池为 0 时是作废。
   *
   * @param outcome settle 结算 / void_no_winner 没人押赢方，作废（E1）/ void_no_pool 退回之后奖池为 0，作废
   * @param lateBets E14 要退回的下注笔数和金额（实际开赛时间早于截止时间时）
   * @param staffBets E15 要退回的员工下注笔数和金额
   */
  public record Preview(String outcome, BigDecimal feeRate, BigDecimal total, BigDecimal winnerPool, BigDecimal loserPool, BigDecimal sumGross,
      BigDecimal sumFee, BigDecimal dust, BigDecimal sumPayout, BigDecimal platformIncome, int winners, int losers, boolean conserved, int lateBets,
      BigDecimal lateAmount, int staffBets, BigDecimal staffAmount) {}

  public Preview preview(long marketId, long winningOptionId, Instant actualStartAt) {
    return tx.execute(st -> {
      MarketApi.MarketRef m = markets.findById(marketId).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
      List<PoolSettleApi.Bet> active = pool.activeBets(marketId);
      Map<Long, PoolSettleApi.Bet> excluded = new LinkedHashMap<>();
      List<PoolSettleApi.Bet> late = actualStartAt != null && actualStartAt.isBefore(m.closeAt()) ? pool.activeBetsReceivedFrom(marketId, ldt(actualStartAt)) : List.of();
      for (PoolSettleApi.Bet b : late) excluded.put(b.id(), b);
      Set<Long> staffUsers = accounts.staffAmong(active.stream().map(PoolSettleApi.Bet::userId).distinct().toList());
      List<PoolSettleApi.Bet> staffBets = staffUsers.isEmpty() ? List.of() : pool.activeBetsOfUsers(marketId, staffUsers);
      List<PoolSettleApi.Bet> staffOnly = staffBets.stream().filter(b -> !excluded.containsKey(b.id())).toList();
      for (PoolSettleApi.Bet b : staffOnly) excluded.put(b.id(), b);
      // 持仓减去要退回的下注（同一客户同一选项）
      Map<String, BigDecimal> minus = new LinkedHashMap<>();
      for (PoolSettleApi.Bet b : excluded.values()) minus.merge(b.userId() + ":" + b.optionId(), b.amount(), BigDecimal::add);
      List<SettleMath.Stake> stakes = new ArrayList<>();
      for (PoolSettleApi.Position p : pool.positions(marketId)) {
        BigDecimal left = p.amount().subtract(minus.getOrDefault(p.userId() + ":" + p.optionId(), BigDecimal.ZERO));
        if (left.signum() > 0) stakes.add(new SettleMath.Stake(p.id(), p.userId(), p.optionId(), left));
      }
      BigDecimal lateAmount = late.stream().map(PoolSettleApi.Bet::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal staffAmount = staffOnly.stream().map(PoolSettleApi.Bet::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
      if (stakes.isEmpty()) {
        return new Preview("void_no_pool", m.feeRate(), BigDecimal.ZERO, null, null, null, null, null, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, false, late.size(),
            lateAmount, staffOnly.size(), staffAmount);
      }
      SettleMath.Result r = SettleMath.settle(stakes, winningOptionId, m.feeRate());
      if (r.noWinner()) {
        return new Preview("void_no_winner", m.feeRate(), r.total(), BigDecimal.ZERO, r.total(), null, null, null, BigDecimal.ZERO, BigDecimal.ZERO, 0,
            r.losers(), false, late.size(), lateAmount, staffOnly.size(), staffAmount);
      }
      return new Preview("settle", m.feeRate(), r.total(), r.winnerPool(), r.loserPool(), r.sumGross(), r.sumFee(), r.dust(), r.sumPayout(), r.platformIncome(),
          r.winners(), r.losers(), r.conserved(), late.size(), lateAmount, staffOnly.size(), staffAmount);
    });
  }

  /** 计算时就没通过：写一张"结算异常"的结算单（没有明细，不动钱），结算阶段 → ABNORMAL，记对账差异、告警 */
  private void abnormalAtCalc(MarketApi.SettleTarget t, SettleRows.Run run, String source, List<Finding> found, LocalDateTime now) {
    run.status = ABNORMAL;
    run.conservationOk = false;
    run.itemCount = 0;
    run.abnormalReason = cut(String.join("；", found.stream().map(Finding::describe).toList()), 500);
    saveRun(run);
    markets.casSettlePhase(t.marketId(), SettlePhase.NONE, SettlePhase.ABNORMAL);
    recordIssues(t.marketId(), source, found, now);
    alert(t.publicNo(), t.title(), "结算计算", "settlement calculation", run.abnormalReason);
    log.warn("市场 {} 结算异常，不派彩：{}", t.publicNo(), run.abnormalReason);
  }

  /** 新写，或者把计算时没通过的那张单（还没有明细）改成这次重新计算的结果（同一市场同一种单只有一张） */
  private void saveRun(SettleRows.Run run) {
    SettleRows.Run old = mapper.runByMarket(run.marketId, run.kind);
    if (old == null) {
      mapper.insertRun(run);
      return;
    }
    if (!ABNORMAL.equals(old.status) || old.itemCount != 0) throw new IllegalStateException("市场 " + run.marketId + " 已经有一张 " + run.kind + " 单");
    run.id = old.id;
    run.publicNo = old.publicNo;
    if (mapper.recalculated(run) != 1) throw new IllegalStateException("重新计算时结算单被别人改了：" + old.id);
  }

  private SettleRows.Run newRun(MarketApi.SettleTarget t, String kind, String reason, LocalDateTime now, int batch, String resolvedBy) {
    SettleRows.Run r = new SettleRows.Run();
    r.publicNo = ids.next();
    r.marketId = t.marketId();
    r.kind = kind;
    r.reason = reason == null ? null : cut(reason, 500);
    r.sumPayout = BigDecimal.ZERO;
    r.calculatedAt = now;
    r.batchSize = batch;
    r.resolvedBy = resolvedBy == null ? null : cut(resolvedBy, 32);
    return r;
  }

  // =====================================================================
  // ② 派彩
  // =====================================================================

  /**
   * 派一批（或 limit = MAX 时全部）待派的明细：按 user_id 升序取 limit 个，补齐到客户边界（同一客户的明细在同一批，通知只发一次）；
   * 每一小段 PENDING → DONE、批量记账、通知；最后平台抽成一笔入账。调用方已经锁了结算单（或在计算的同一个事务里）。
   *
   * @return 这次派了几个明细
   */
  int payPayout(SettleRows.Run run, I18nText title, int limit) {
    String winOption = run.winningOptionId == null ? null : String.valueOf(run.winningOptionId);
    Map<Long, MarketApi.OptionRef> opts = run.winningOptionId == null ? Map.of() : markets.optionsById(List.of(run.winningOptionId));
    MarketApi.OptionRef win = opts.get(run.winningOptionId);
    int done = 0;
    int batchIndex = 0;
    long firstItem = 0;
    BigDecimal fee = BigDecimal.ZERO;
    while (done < limit) {
      List<SettleRows.Item> chunk = nextChunk(mapper.pendingItems(run.id, Math.min(WORKER_DEFAULT_BATCH, limit - done)),
          users -> mapper.pendingItemsOfUsers(run.id, users), i -> i.userId);
      if (chunk.isEmpty()) break;
      List<Long> idsDone = chunk.stream().map(i -> i.id).toList();
      if (mapper.markItemsDone(idsDone, now()) != idsDone.size()) throw new IllegalStateException("结算明细被别的任务派过了（结算单 " + run.id + "）");
      if (firstItem == 0) firstItem = idsDone.get(0);
      List<LedgerCommand> cmds = new ArrayList<>(chunk.size() * 3);
      for (SettleRows.Item i : chunk) {
        String biz = String.valueOf(i.id);
        cmds.add(new LedgerCommand(i.userId, Bucket.BET_LOCKED, i.stake.negate(), i.win ? LedgerEntryType.BET_SETTLE : LedgerEntryType.BET_LOST, BIZ_SETTLE, biz,
            i.win ? "结算：赢" : "结算：输", run.marketId));
        if (i.win) {
          cmds.add(new LedgerCommand(i.userId, Bucket.AVAILABLE, i.stake.add(i.gross), LedgerEntryType.PAYOUT, BIZ_SETTLE, biz, "派彩（本金 + 盈利）", run.marketId));
          if (i.fee.signum() > 0) cmds.add(new LedgerCommand(i.userId, Bucket.AVAILABLE, i.fee.negate(), LedgerEntryType.FEE, BIZ_SETTLE, biz, "抽成", run.marketId));
        }
      }
      List<Boolean> fresh = ledger.applyBatch(cmds);
      // 同一客户的明细合并成一条通知（赢了：#11，派彩、抽成合计；只输：#12）；之前已经记过账的（续跑时不应该有）不再通知
      Map<Long, List<SettleRows.Item>> byUser = new LinkedHashMap<>();
      for (SettleRows.Item i : chunk) byUser.computeIfAbsent(i.userId, k -> new ArrayList<>()).add(i);
      Set<Long> repeated = new TreeSet<>();
      int k = 0;
      for (SettleRows.Item i : chunk) {
        int legs = i.win ? (i.fee.signum() > 0 ? 3 : 2) : 1;
        for (int x = 0; x < legs; x++) if (!fresh.get(k++)) repeated.add(i.userId);
        if (i.win && fresh.get(k - 1)) fee = fee.add(i.fee);
      }
      if (!repeated.isEmpty()) log.warn("结算单 {} 有 {} 个客户的派彩之前已经记过账（幂等键挡住，没有重复记）", run.id, repeated.size());
      List<NotifyApi.Notice> won = new ArrayList<>();
      List<NotifyApi.Notice> lost = new ArrayList<>();
      for (Map.Entry<Long, List<SettleRows.Item>> e : byUser.entrySet()) {
        if (repeated.contains(e.getKey())) continue;
        boolean w = e.getValue().stream().anyMatch(i -> i.win);
        (w ? won : lost).add(new NotifyApi.Notice(e.getKey(), settledParams(e.getValue(), title, win, run)));
      }
      notify.toUsers("SETTLE_WON", won);
      notify.toUsers("SETTLE_LOST", lost);
      done += chunk.size();
      faults.afterBatch(run.id, PAYOUT, batchIndex++);
    }
    if (fee.signum() > 0) {
      platform.apply(new PlatformLedgerApi.Command(AccountKind.PLATFORM_REVENUE, fee, LedgerEntryType.FEE_INCOME, BIZ_FEE, run.id + ":" + firstItem,
          "结算抽成", run.marketId));
    }
    if (winOption == null && done > 0) log.warn("结算单 {} 没有赢的选项", run.id);
    return done;
  }

  /** 结算通知的变量：赢了（#11）写派彩、抽成合计；只输（#12）写输掉的本金 */
  private Map<String, String> settledParams(List<SettleRows.Item> items, I18nText title, MarketApi.OptionRef win, SettleRows.Run run) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("market", title.zhCN());
    p.put("marketEn", title.en());
    p.put("option", win == null ? "" : win.label().zhCN());
    p.put("optionEn", win == null ? "" : win.label().en());
    List<SettleRows.Item> wins = items.stream().filter(i -> i.win).toList();
    if (!wins.isEmpty()) {
      p.put("stake", money(wins.stream().map(i -> i.stake).reduce(BigDecimal.ZERO, BigDecimal::add)));
      p.put("gross", money(wins.stream().map(i -> i.gross).reduce(BigDecimal.ZERO, BigDecimal::add)));
      p.put("fee", money(wins.stream().map(i -> i.fee).reduce(BigDecimal.ZERO, BigDecimal::add)));
      p.put("feeRate", percent(run.feeRate));
      p.put("payout", money(wins.stream().map(i -> i.payout).reduce(BigDecimal.ZERO, BigDecimal::add)));
    } else {
      p.put("stake", money(items.stream().map(i -> i.stake).reduce(BigDecimal.ZERO, BigDecimal::add)));
    }
    return p;
  }

  // =====================================================================
  // ③ 收尾
  // =====================================================================

  /**
   * 明细全部派完以后：尾差入平台 → 按账本复核守恒（Σ下注锁定减少 = T、Σ(派彩 − 抽成) + 平台(抽成 + 尾差) = T、这个市场的下注锁定归零）→
   * 结算阶段 DONE、市场进入已结算、结算单 DONE。复核不通过：结算单和市场停在"结算异常"（已派的不回滚），告警。
   *
   * @return 是否收尾成功
   */
  boolean finishPayout(SettleRows.Run run, MarketApi.SettleTarget t) {
    if (mapper.countPendingItems(run.id) > 0) return false;
    if (run.dust != null && run.dust.signum() > 0) {
      platform.apply(new PlatformLedgerApi.Command(AccountKind.PLATFORM_REVENUE, run.dust, LedgerEntryType.SETTLE_DUST, BIZ_DUST, String.valueOf(run.id),
          "结算尾差", run.marketId));
    }
    Map<LedgerQueryApi.TypeBucket, BigDecimal> totals = ledgerQuery.marketTotals(run.marketId);
    BigDecimal released = sum(totals, LedgerEntryType.BET_SETTLE, Bucket.BET_LOCKED).add(sum(totals, LedgerEntryType.BET_LOST, Bucket.BET_LOCKED)).negate();
    BigDecimal paid = sum(totals, LedgerEntryType.PAYOUT, Bucket.AVAILABLE).add(sum(totals, LedgerEntryType.FEE, Bucket.AVAILABLE));
    BigDecimal income = sum(totals, LedgerEntryType.FEE_INCOME, Bucket.AVAILABLE).add(sum(totals, LedgerEntryType.SETTLE_DUST, Bucket.AVAILABLE));
    BigDecimal stillLocked = lockedOf(totals);
    List<Finding> found = new ArrayList<>();
    if (released.compareTo(run.poolTotal) != 0) found.add(new Finding("CONSERVATION", run.poolTotal, released, "账本里结算解除的下注锁定 ≠ T"));
    if (paid.add(income).compareTo(run.poolTotal) != 0) found.add(new Finding("CONSERVATION", run.poolTotal, paid.add(income), "账本里 Σ(派彩 − 抽成) + 平台(抽成 + 尾差) ≠ T"));
    if (income.compareTo(run.sumFee.add(run.dust)) != 0) found.add(new Finding("CONSERVATION", run.sumFee.add(run.dust), income, "平台收入 ≠ 抽成 + 尾差"));
    if (stillLocked.signum() != 0) found.add(new Finding("POOL_VS_LEDGER", BigDecimal.ZERO, stillLocked, "结算后账本里这个市场还有下注锁定"));
    if (!found.isEmpty()) {
      abnormalRun(run, t.publicNo(), t.title(), "FINISH", found, "结算收尾复核", "settlement final check");
      return false;
    }
    if (!markets.casSettlePhase(run.marketId, SettlePhase.PAYING, SettlePhase.DONE)) throw new IllegalStateException("结算阶段不是派彩中：" + run.marketId);
    if (!markets.transition(run.marketId, t.status(), MarketStatus.SETTLED, actor(run.resolvedBy), null)) {
      throw new IllegalStateException("市场状态变了，不能进入已结算：" + run.marketId);
    }
    if (mapper.casRunStatus(run.id, PAYING, DONE, null, now()) != 1) throw new IllegalStateException("结算单状态变了：" + run.id);
    log.info("市场 {} 已结算：派彩 {}，抽成 {}，尾差 {}", t.publicNo(), money(run.sumPayout), money(run.sumFee), money(run.dust));
    return true;
  }

  /** 派到一半或收尾时发现对不上：结算单 PAYING → ABNORMAL、结算阶段 → ABNORMAL（已派的不回滚），记差异、告警 */
  private void abnormalRun(SettleRows.Run run, long marketNo, I18nText title, String source, List<Finding> found, String what, String whatEn) {
    String reason = cut(String.join("；", found.stream().map(Finding::describe).toList()), 500);
    if (mapper.casRunStatus(run.id, PAYING, ABNORMAL, reason, null) != 1) return;
    if (PAYOUT.equals(run.kind)) markets.casSettlePhase(run.marketId, SettlePhase.PAYING, SettlePhase.ABNORMAL);
    recordIssues(run.marketId, source, found, now());
    alert(marketNo, title, what, whatEn, reason);
    log.warn("市场 {} {}异常，停止派彩：{}", marketNo, what, reason);
  }

  /** 派彩中的单因为这个市场有待处理的对账差异而暂停：PAYING → ABNORMAL（已派的不回滚），处理差异后重试接着派 */
  private void pause(SettleRows.Run run, long marketNo, I18nText title) {
    String reason = "这个市场有待处理的彩池对账差异，已停止派彩（处理后重试）";
    if (mapper.casRunStatus(run.id, PAYING, ABNORMAL, reason, null) != 1) return;
    if (PAYOUT.equals(run.kind)) markets.casSettlePhase(run.marketId, SettlePhase.PAYING, SettlePhase.ABNORMAL);
    alert(marketNo, title, PAYOUT.equals(run.kind) ? "结算派彩" : "全额退款", PAYOUT.equals(run.kind) ? "payout" : "refund", reason);
    log.warn("市场 {} 有待处理的彩池对账差异，停止派彩", marketNo);
  }

  // =====================================================================
  // 退款
  // =====================================================================

  /**
   * 市场进入 CANCELLED / VOIDED（{@link SettleMarketListener}，在 market 的事务里，市场行已加排他锁）：建退款单，每笔有效下注一个明细；
   * 退款前同样对账（对不上就停在"结算异常"，不动钱）。batch-size = 0 时当场退完。同一市场重复调用只建一张。
   */
  void createRefundRun(long marketId, MarketStatus to, String reason) {
    if (mapper.runByMarket(marketId, REFUND) != null) return;
    MarketApi.MarketRef m = markets.findById(marketId).orElseThrow();
    LocalDateTime now = now();
    int batch = props.getBatchSize();
    MarketApi.SettleTarget t = new MarketApi.SettleTarget(marketId, m.publicNo(), to, SettlePhase.NONE, m.category(), m.feeRate(), m.closeAt(), null, null,
        m.title(), List.of());
    SettleRows.Run run = newRun(t, REFUND, reason == null ? to.name() : reason, now, batch, "SYSTEM");
    List<PoolSettleApi.Bet> bets = pool.activeBets(marketId);
    BigDecimal total = bets.stream().map(PoolSettleApi.Bet::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    run.poolTotal = total;
    run.sumPayout = total;
    List<Finding> found = check(marketId);
    if (!found.isEmpty()) {
      run.status = ABNORMAL;
      run.abnormalReason = cut(String.join("；", found.stream().map(Finding::describe).toList()), 500);
      mapper.insertRun(run);
      recordIssues(marketId, "SETTLE", found, now);
      alert(m.publicNo(), m.title(), "全额退款", "full refund", run.abnormalReason);
      log.warn("市场 {} 退款前对账不平，不退款：{}", m.publicNo(), run.abnormalReason);
      return;
    }
    run.itemCount = bets.size();
    run.conservationOk = true;
    run.status = bets.isEmpty() ? DONE : PAYING;
    mapper.insertRun(run);
    if (bets.isEmpty()) { // E4：奖池为 0，没有资金变动
      mapper.casRunStatus(run.id, DONE, DONE, null, now);
      return;
    }
    insertRefunds(run.id, marketId, bets, to == MarketStatus.CANCELLED ? "CANCELLED" : "VOIDED", now);
    if (batch == 0) {
      payRefunds(run, m.title(), Integer.MAX_VALUE);
      finishRefund(run, m.publicNo(), m.title());
    }
  }

  private void insertRefunds(Long runId, long marketId, List<PoolSettleApi.Bet> bets, String reason, LocalDateTime now) {
    List<SettleRows.Refund> rows = new ArrayList<>(bets.size());
    for (PoolSettleApi.Bet b : bets) {
      SettleRows.Refund r = new SettleRows.Refund();
      r.runId = runId;
      r.marketId = marketId;
      r.betId = b.id();
      r.userId = b.userId();
      r.amount = b.amount();
      r.reason = reason;
      r.createdAt = now;
      rows.add(r);
    }
    for (int k = 0; k < rows.size(); k += WORKER_DEFAULT_BATCH) mapper.insertRefunds(rows.subList(k, Math.min(rows.size(), k + WORKER_DEFAULT_BATCH)));
  }

  /** 退款单：派一批（或全部），和派彩同样的分批、条件更新、批量记账（幂等键 REFUND : 退款明细 id），再把下注标成已退回 */
  int payRefunds(SettleRows.Run run, I18nText title, int limit) {
    int done = 0;
    int batchIndex = 0;
    while (done < limit) {
      List<SettleRows.Refund> chunk = nextChunk(mapper.pendingRefunds(run.id, Math.min(WORKER_DEFAULT_BATCH, limit - done)),
          users -> mapper.pendingRefundsOfUsers(run.id, users), r -> r.userId);
      if (chunk.isEmpty()) break;
      executeRefunds(run.marketId, chunk, title, null);
      done += chunk.size();
      faults.afterBatch(run.id, REFUND, batchIndex++);
    }
    return done;
  }

  /** 退款单收尾：明细全部退完、账本里这个市场的下注锁定归零、没有有效下注了 → DONE；对不上 → 结算异常 */
  boolean finishRefund(SettleRows.Run run, long marketNo, I18nText title) {
    if (mapper.countPendingRefunds(run.id) > 0) return false;
    Map<LedgerQueryApi.TypeBucket, BigDecimal> totals = ledgerQuery.marketTotals(run.marketId);
    BigDecimal locked = lockedOf(totals);
    BigDecimal refunded = sum(totals, LedgerEntryType.REFUND, Bucket.AVAILABLE);
    BigDecimal recorded = mapper.refundedAmount(run.marketId);
    BigDecimal active = pool.sumActiveBets(run.marketId);
    List<Finding> found = new ArrayList<>();
    if (locked.signum() != 0) found.add(new Finding("REFUND_VS_LEDGER", BigDecimal.ZERO, locked, "退完以后账本里这个市场还有下注锁定"));
    if (refunded.compareTo(recorded) != 0) found.add(new Finding("REFUND_VS_LEDGER", recorded, refunded, "账本里的退款 ≠ 退款明细合计"));
    if (active.signum() != 0) found.add(new Finding("POOL_VS_BETS", BigDecimal.ZERO, active, "退完以后还有有效下注"));
    if (!found.isEmpty()) {
      abnormalRun(run, marketNo, title, "FINISH", found, "全额退款复核", "refund final check");
      return false;
    }
    if (mapper.casRunStatus(run.id, PAYING, DONE, null, now()) != 1) throw new IllegalStateException("退款单状态变了：" + run.id);
    log.info("市场 {} 全额退款完成：{}", marketNo, money(run.poolTotal));
    return true;
  }

  /**
   * 当场退回一批下注（E14 晚于实际开赛、E15 员工、提前截止后在新截止时间之后收到的）：调用方已经锁了市场行。
   *
   * @param at E14 / 提前截止：实际开赛时间或新的截止时间（通知 #14 里写）；E15 为空（不发通知，需求第 7 节没有这一项）
   */
  void refundNow(MarketApi.SettleTarget t, List<PoolSettleApi.Bet> bets, String reason, Instant at, LocalDateTime now) {
    if (bets.isEmpty()) return;
    insertRefunds(null, t.marketId(), bets, reason, now);
    List<SettleRows.Refund> rows = new ArrayList<>();
    List<Long> betIds = bets.stream().map(PoolSettleApi.Bet::id).toList();
    for (int k = 0; k < betIds.size(); k += WORKER_DEFAULT_BATCH) rows.addAll(mapper.refundsByBets(betIds.subList(k, Math.min(betIds.size(), k + WORKER_DEFAULT_BATCH))));
    for (int k = 0; k < rows.size(); k += WORKER_DEFAULT_BATCH) {
      executeRefunds(t.marketId(), rows.subList(k, Math.min(rows.size(), k + WORKER_DEFAULT_BATCH)), t.title(), at);
    }
    log.info("市场 {} 退回 {} 笔下注（{}），合计 {}", t.publicNo(), bets.size(), reason, money(bets.stream().map(PoolSettleApi.Bet::amount).reduce(BigDecimal.ZERO, BigDecimal::add)));
  }

  /** 一段退款明细：PENDING → DONE → 批量记账 → 下注标成已退回（扣持仓、奖池）→ 按客户合并通知 */
  private void executeRefunds(long marketId, List<SettleRows.Refund> chunk, I18nText title, Instant at) {
    List<Long> idsDone = chunk.stream().map(r -> r.id).toList();
    if (mapper.markRefundsDone(idsDone, now()) != idsDone.size()) throw new IllegalStateException("退款明细被别的任务退过了（市场 " + marketId + "）");
    List<LedgerCommand> cmds = new ArrayList<>(chunk.size() * 2);
    for (SettleRows.Refund r : chunk) {
      String biz = String.valueOf(r.id);
      cmds.add(new LedgerCommand(r.userId, Bucket.BET_LOCKED, r.amount.negate(), LedgerEntryType.REFUND, BIZ_REFUND, biz, "下注退回：" + r.reason, marketId));
      cmds.add(new LedgerCommand(r.userId, Bucket.AVAILABLE, r.amount, LedgerEntryType.REFUND, BIZ_REFUND, biz, "下注退回：" + r.reason, marketId));
    }
    List<Boolean> fresh = ledger.applyBatch(cmds);
    Map<String, List<SettleRows.Refund>> byReason = new LinkedHashMap<>();
    for (SettleRows.Refund r : chunk) byReason.computeIfAbsent(r.reason, k -> new ArrayList<>()).add(r);
    for (Map.Entry<String, List<SettleRows.Refund>> e : byReason.entrySet()) {
      List<Long> bets = e.getValue().stream().map(r -> r.betId).toList();
      List<Long> changed = pool.markRefunded(marketId, bets, e.getKey(), now());
      if (changed.size() != bets.size()) throw new IllegalStateException("有下注已经退回过了（市场 " + marketId + "，要退 " + bets.size() + " 笔，改了 " + changed.size() + " 笔）");
    }
    Set<Long> repeated = new TreeSet<>();
    for (int k = 0; k < chunk.size(); k++) if (!fresh.get(2 * k) || !fresh.get(2 * k + 1)) repeated.add(chunk.get(k).userId);
    Map<Long, List<SettleRows.Refund>> byUser = new LinkedHashMap<>();
    for (SettleRows.Refund r : chunk) byUser.computeIfAbsent(r.userId, k -> new ArrayList<>()).add(r);
    Map<String, List<NotifyApi.Notice>> notices = new LinkedHashMap<>();
    Long runId = chunk.get(0).runId;
    SettleRows.Run run = runId == null ? null : mapper.runById(runId); // 取消、作废时通知里写退款单上的原因
    String runReason = run == null ? null : run.reason;
    for (Map.Entry<Long, List<SettleRows.Refund>> e : byUser.entrySet()) {
      if (repeated.contains(e.getKey())) continue;
      String template = refundTemplate(e.getValue().get(0).reason);
      if (template != null) {
        notices.computeIfAbsent(template, k -> new ArrayList<>()).add(new NotifyApi.Notice(e.getKey(), refundParams(e.getValue(), title, at, runReason)));
      }
    }
    notices.forEach(notify::toUsers);
  }

  /** 退款通知的模板：取消 / 作废 #13，晚于开赛（含提前截止）#14；员工的下注退回没有通知（需求第 7 节没有这一项） */
  private static String refundTemplate(String reason) {
    return switch (reason) {
      case "LATE_BET" -> "LATE_BET_REFUNDED";
      case "CANCELLED", "VOIDED" -> "MARKET_REFUNDED";
      default -> null;
    };
  }

  private static Map<String, String> refundParams(List<SettleRows.Refund> rows, I18nText title, Instant at, String runReason) {
    String reason = rows.get(0).reason;
    Map<String, String> p = new LinkedHashMap<>();
    p.put("market", title.zhCN());
    p.put("marketEn", title.en());
    p.put("amount", money(rows.stream().map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add)));
    if ("LATE_BET".equals(reason)) {
      p.put("at", at == null ? "" : NOTICE_TIME.format(at.atZone(ZoneOffset.UTC)));
    } else {
      p.put("refundKind", reason);
      p.put("refundReason", runReason == null ? reason : runReason);
    }
    return p;
  }

  /**
   * 截止时间被提前（{@link SettleMarketListener}，市场行已加排他锁）：新截止时间之后收到的下注全额退回（需求第 3.2 节第 4 条，和 E14 同样的逻辑，通知 #14）。
   */
  void refundAfterAdvancedClose(long marketId, Instant newCloseAt) {
    MarketApi.SettleTarget t = markets.lockForSettle(marketId);
    refundNow(t, pool.activeBetsReceivedFrom(marketId, ldt(newCloseAt)), "LATE_BET", newCloseAt, now());
  }

  // =====================================================================
  // 派彩任务（batch-size > 0）和重试
  // =====================================================================

  /**
   * 推进一张派彩中的单的一批（一个事务）：锁市场行 → 锁结算单 → 这个市场有待处理的对账差异时停在"结算异常"（停止派彩）→ 派一批。
   *
   * @return 这批派了几个（0 = 没有待派的了，或者已经不是派彩中）
   */
  public int payOneBatch(long runId) {
    SettleRows.Run peek = mapper.runById(runId);
    if (peek == null || !PAYING.equals(peek.status)) return 0;
    Integer n = tx.execute(st -> {
      MarketApi.SettleTarget t = markets.lockForSettle(peek.marketId);
      SettleRows.Run run = mapper.lockRun(runId);
      if (!PAYING.equals(run.status)) return 0;
      if (mapper.openIssuesOfMarket(run.marketId) > 0) { // 对账发现差异：停止派彩，停在"结算异常"（差异已经由对账记下、告警过）
        pause(run, t.publicNo(), t.title());
        return 0;
      }
      int batch = props.getBatchSize() > 0 ? props.getBatchSize() : WORKER_DEFAULT_BATCH;
      return PAYOUT.equals(run.kind) ? payPayout(run, t.title(), batch) : payRefunds(run, t.title(), batch);
    });
    return n == null ? 0 : n;
  }

  /** 收尾（一个事务）：明细都派完了才做 */
  boolean finish(long runId) {
    SettleRows.Run peek = mapper.runById(runId);
    if (peek == null || !PAYING.equals(peek.status)) return false;
    return Boolean.TRUE.equals(tx.execute(st -> {
      MarketApi.SettleTarget t = markets.lockForSettle(peek.marketId);
      SettleRows.Run run = mapper.lockRun(runId);
      if (!PAYING.equals(run.status)) return false;
      return PAYOUT.equals(run.kind) ? finishPayout(run, t) : finishRefund(run, t.publicNo(), t.title());
    }));
  }

  /** 一张单派到底：一批一批派，再收尾（派彩任务、e2e、测试用） */
  public boolean drain(long runId) {
    while (payOneBatch(runId) > 0) {
      // 每批一个事务
    }
    return finish(runId);
  }

  /** 全部派彩中的单各派到底；返回收尾完成的张数 */
  public int drainAll() {
    int n = 0;
    for (long id : mapper.payingRuns(50)) {
      try {
        if (drain(id)) n++;
      } catch (RuntimeException e) {
        log.error("结算单 {} 派彩失败，下一轮接着派", id, e);
      }
    }
    return n;
  }

  /**
   * 结算异常处理后重试（{@code SettleAdminApi.retry}）：这个市场还有待处理的对账差异时拒绝；计算时就没通过的（没有明细）按记下的结果重新计算；
   * 派到一半停下的、收尾复核没通过的，回到派彩中接着派。batch-size = 0 时在这个事务里派完。
   */
  public void retry(long runNo) {
    retryInTx(runNo);
    if (props.getBatchSize() > 0) kick.now();
  }

  private void retryInTx(long runNo) {
    tx.executeWithoutResult(st -> {
      SettleRows.Run peek = mapper.runByPublicNo(runNo);
      if (peek == null) throw new BizException("NOT_FOUND", "结算单不存在");
      MarketApi.SettleTarget t = markets.lockForSettle(peek.marketId);
      SettleRows.Run run = mapper.lockRun(peek.id);
      if (!ABNORMAL.equals(run.status)) throw new BizException("SETTLE_NOT_ABNORMAL", "结算单不是结算异常，不用重试");
      if (mapper.openIssuesOfMarket(run.marketId) > 0) throw new BizException("POOL_RECON_OPEN", "这个市场还有待处理的彩池对账差异，先处理再重试");
      int batch = props.getBatchSize();
      if (PAYOUT.equals(run.kind) && run.itemCount == 0) {
        if (!markets.casSettlePhase(run.marketId, SettlePhase.ABNORMAL, SettlePhase.NONE)) throw new BizException("SETTLE_STATE_CHANGED", "结算阶段变了");
        if (t.winningOptionId() == null) throw new IllegalStateException("市场没有记下结果：" + run.marketId);
        calculate(run.marketId, t.winningOptionId(), t.actualStartAt(), run.resolvedBy, true);
        return;
      }
      if (REFUND.equals(run.kind) && run.itemCount == 0) {
        retryRefundCreation(run, t);
        return;
      }
      if (mapper.casRunStatus(run.id, ABNORMAL, PAYING, null, null) != 1) throw new BizException("SETTLE_STATE_CHANGED", "结算单状态变了");
      run.status = PAYING;
      if (PAYOUT.equals(run.kind)) markets.casSettlePhase(run.marketId, SettlePhase.ABNORMAL, SettlePhase.PAYING);
      if (batch == 0) {
        if (PAYOUT.equals(run.kind)) {
          ledger.lockAll(pendingUsers(run));
          payPayout(run, t.title(), Integer.MAX_VALUE);
          finishPayout(run, t);
        } else {
          payRefunds(run, t.title(), Integer.MAX_VALUE);
          finishRefund(run, t.publicNo(), t.title());
        }
      }
    });
  }

  private List<Long> pendingUsers(SettleRows.Run run) {
    List<Long> users = new ArrayList<>();
    for (SettleRows.Item i : mapper.pendingItems(run.id, Integer.MAX_VALUE)) users.add(i.userId);
    return users;
  }

  /** 退款单在建的时候对账没通过（没有明细）：现在重新对账，通过了就建明细、进入派彩中 */
  private void retryRefundCreation(SettleRows.Run run, MarketApi.SettleTarget t) {
    List<Finding> found = check(run.marketId);
    LocalDateTime now = now();
    if (!found.isEmpty()) {
      recordIssues(run.marketId, "SETTLE", found, now);
      throw new BizException("POOL_RECON_OPEN", "对账仍然不平：" + found.get(0).describe());
    }
    List<PoolSettleApi.Bet> bets = pool.activeBets(run.marketId);
    BigDecimal total = bets.stream().map(PoolSettleApi.Bet::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    run.poolTotal = total;
    run.sumPayout = total;
    run.itemCount = bets.size();
    run.conservationOk = true;
    run.status = bets.isEmpty() ? DONE : PAYING;
    run.abnormalReason = null;
    run.calculatedAt = now;
    run.batchSize = props.getBatchSize();
    if (mapper.recalculated(run) != 1) throw new BizException("SETTLE_STATE_CHANGED", "退款单状态变了");
    if (bets.isEmpty()) return;
    insertRefunds(run.id, run.marketId, bets, t.status() == MarketStatus.CANCELLED ? "CANCELLED" : "VOIDED", now);
    if (props.getBatchSize() == 0) {
      payRefunds(run, t.title(), Integer.MAX_VALUE);
      finishRefund(run, t.publicNo(), t.title());
    }
  }

  // =====================================================================
  // 对账（结算前、每日、收尾共用）
  // =====================================================================

  /** 一条对不上的地方 */
  public record Finding(String kind, BigDecimal expected, BigDecimal actual, String detail) {
    String describe() {
      return detail + "（应为 " + money(expected) + "，实际 " + money(actual) + "）";
    }
  }

  /**
   * 未结算市场的彩池对账（需求第 3.9 节第 2 条）：Σ有效下注 = Σ选项奖池 = Σ持仓 = 账本里这个市场的下注锁定；截止后定格的奖池 = 现在的奖池。
   * 读的是最新提交的数据（调用方是 READ COMMITTED 事务，或没有事务）。
   */
  public List<Finding> check(long marketId) {
    List<Finding> out = new ArrayList<>();
    BigDecimal bets = pool.sumActiveBets(marketId);
    BigDecimal positions = pool.sumPositions(marketId);
    List<PoolSettleApi.OptionPool> pools = pool.pools(marketId);
    BigDecimal sumPool = pools.stream().map(PoolSettleApi.OptionPool::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal locked = lockedOf(ledgerQuery.marketTotals(marketId));
    if (sumPool.compareTo(bets) != 0) out.add(new Finding("POOL_VS_BETS", bets, sumPool, "Σ选项奖池 ≠ Σ有效下注"));
    if (positions.compareTo(bets) != 0) out.add(new Finding("POOL_VS_POSITIONS", bets, positions, "Σ持仓 ≠ Σ有效下注"));
    if (locked.compareTo(bets) != 0) out.add(new Finding("POOL_VS_LEDGER", bets, locked, "账本里这个市场的下注锁定 ≠ Σ有效下注"));
    boolean frozen = !pools.isEmpty() && pools.stream().allMatch(p -> p.finalAmount() != null);
    if (frozen) {
      BigDecimal sumFinal = pools.stream().map(PoolSettleApi.OptionPool::finalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
      if (sumFinal.compareTo(sumPool) != 0) out.add(new Finding("POOL_FINAL", sumPool, sumFinal, "截止时定格的奖池（扣掉退回的）≠ 现在的奖池"));
    }
    return out;
  }

  /** 记对账差异：同一个对象同一种差异还有没处理的，不重复记；返回新记的条数 */
  int recordIssues(Long marketId, String source, List<Finding> found, LocalDateTime now) {
    int fresh = 0;
    for (Finding f : found) {
      String ref = marketId == null ? f.detail() : "M:" + marketId;
      if (mapper.openIssueCount(ref, f.kind()) > 0) continue;
      SettleRows.Issue i = new SettleRows.Issue();
      i.refKey = cut(ref, 64);
      i.marketId = marketId;
      i.checkDate = now.toLocalDate();
      i.kind = f.kind();
      i.source = source;
      i.expected = f.expected();
      i.actual = f.actual();
      i.diff = f.actual().subtract(f.expected());
      i.detail = cut(f.detail(), 500);
      i.createdAt = now;
      fresh += mapper.insertIssue(i);
    }
    return fresh;
  }

  /** 每日对账发现的市场差异（来源 DAILY） */
  public int recordIssuesForRecon(long marketId, List<Finding> found, LocalDateTime now) {
    return recordIssues(marketId, "DAILY", found, now);
  }

  /** 每日对账发现的账户差异（账本合计 ≠ 余额，和市场无关） */
  public int recordAccountIssue(String refKey, BigDecimal expected, BigDecimal actual, String detail, LocalDateTime now) {
    if (mapper.openIssueCount(refKey, "LEDGER_ACCOUNT") > 0) return 0;
    SettleRows.Issue i = new SettleRows.Issue();
    i.refKey = cut(refKey, 64);
    i.checkDate = now.toLocalDate();
    i.kind = "LEDGER_ACCOUNT";
    i.source = "DAILY";
    i.expected = expected;
    i.actual = actual;
    i.diff = actual.subtract(expected);
    i.detail = cut(detail, 500);
    i.createdAt = now;
    return mapper.insertIssue(i);
  }

  private void alert(long marketNo, I18nText title, String what, String whatEn, String reason) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("marketNo", String.valueOf(marketNo));
    p.put("market", title.zhCN());
    p.put("marketEn", title.en());
    p.put("what", what);
    p.put("whatEn", whatEn);
    p.put("reason", reason == null || reason.isBlank() ? "-" : reason);
    staff.notifyStaff("SETTLE_ABNORMAL", p);
  }

  /** 账本里这个市场还锁着的下注金额（下注锁定这一项的合计：下注 +，结算、退款 −） */
  static BigDecimal lockedOf(Map<LedgerQueryApi.TypeBucket, BigDecimal> totals) {
    BigDecimal s = BigDecimal.ZERO;
    for (Map.Entry<LedgerQueryApi.TypeBucket, BigDecimal> e : totals.entrySet()) if (e.getKey().bucket() == Bucket.BET_LOCKED) s = s.add(e.getValue());
    return s;
  }

  static BigDecimal sum(Map<LedgerQueryApi.TypeBucket, BigDecimal> totals, LedgerEntryType type, Bucket bucket) {
    return totals.getOrDefault(new LedgerQueryApi.TypeBucket(type, bucket), BigDecimal.ZERO);
  }

  /**
   * 从按 user_id 排好的一段里取下一批：最后一个客户可能还有明细没取到，补齐（同一客户的明细不跨批）。
   */
  private static <T> List<T> nextChunk(List<T> first, Function<List<Long>, List<T>> ofUsers, Function<T, Long> userOf) {
    if (first.isEmpty()) return first;
    long last = userOf.apply(first.get(first.size() - 1));
    List<T> out = new ArrayList<>();
    for (T x : first) if (userOf.apply(x) != last) out.add(x);
    out.addAll(ofUsers.apply(List.of(last)));
    return out;
  }

  static String actor(String resolvedBy) {
    return resolvedBy == null || resolvedBy.isBlank() ? "SYSTEM" : resolvedBy;
  }

  static LocalDateTime ldt(Instant i) {
    return LocalDateTime.ofInstant(i.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  static String money(BigDecimal v) {
    return v == null ? "-" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /** 小数费率 → 百分比字符串（0.0525 → "5.25"，0.05 → "5"） */
  static String percent(BigDecimal rate) {
    if (rate == null) return "-";
    BigDecimal p = rate.movePointRight(2).stripTrailingZeros();
    return p.scale() < 0 ? p.setScale(0).toPlainString() : p.toPlainString();
  }

  static String cut(String s, int n) {
    return s == null || s.length() <= n ? s : s.substring(0, n);
  }
}
