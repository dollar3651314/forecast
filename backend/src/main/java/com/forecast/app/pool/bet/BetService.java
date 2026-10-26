package com.forecast.app.pool.bet;

import static com.forecast.app.pool.bet.PoolQueries.money;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.account.api.KycStatus;
import com.forecast.app.account.api.UserStatus;
import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.id.Snowflake;
import com.forecast.app.common.push.UserEventsApi;
import com.forecast.app.ledger.api.AccountSnapshot;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.CustomerFlowApi;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.notification.api.NotifyApi;
import com.forecast.app.pool.api.PoolMath;
import com.forecast.app.pool.push.PoolChanges;
import java.time.Clock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 下注（forecast S4，需求说明书第 3.3 节，技术方案/v1-架构方案.md 第 4.2 节）。<b>一次下注一个事务</b>，三把锁固定顺序：
 * <ol>
 *   <li>市场行<b>共享锁</b>（{@link MarketApi#lockForBet}，截止屏障）：状态是已上架、{@code openAt ≤ receivedAt < closeAt}、选项属于这个市场；</li>
 *   <li>客户账户行<b>排他锁</b>（{@link LedgerApi#lock}）：同一客户的下注、提现在这里排队；锁内再查一次请求号（幂等）、
 *       求和这个客户在本市场的持仓（单客户单市场上限）、看可用余额；</li>
 *   <li>记账"下注锁定"（可用 −b、下注锁定 +b，{@link LedgerApi#applyAll}）→ 写下注（含奖池快照）→ 持仓（同一选项合成一个）→
 *       <b>选项奖池原子加</b>（最后一步，锁持有最短）。</li>
 * </ol>
 * 所以不会死锁（截止任务拿市场行排他锁、提现只锁账户行，都是同一个顺序的前缀），同一客户的并发下注不会超过余额和上限（AC-18）。
 * <p>事务用 <b>READ COMMITTED</b>：上限求和、幂等查询都在账户行锁之后读，读到的是锁内最新提交的数据（可重复读的快照可能停在加锁之前）；
 * 也不会在持仓的二级索引上加间隙锁，不同客户的下注不会互相挡住。
 * <p>拒单原因（AC-16，客户端按错误码翻译成中英文）：{@code MARKET_NOT_OPEN}、{@code MARKET_CLOSED}、{@code MARKET_PAUSED}、{@code MARKET_CANCELLED}、
 * {@code BET_BELOW_MIN}、{@code BET_ABOVE_MAX}、{@code BET_OVER_USER_CAP}、{@code INSUFFICIENT_FUNDS}、{@code KYC_REQUIRED}、{@code USER_FROZEN}、
 * {@code BET_STAFF_ACCOUNT}；另有 {@code NOT_FOUND}（市场不存在）、{@code OPTION_NOT_FOUND}、{@code BET_REQUEST_REUSED}（同一个请求号换了内容）、
 * 字段错误 {@code VALIDATION_FAILED}。
 */
@Service
public class BetService {

  /** 需求第 3.3 节的 11 种拒单原因（AC-16），客户端 {@code web/client/src/api/errors.ts} 的 SERVER_CODES 都有中英文翻译 */
  public static final List<String> REJECT_CODES = List.of("MARKET_NOT_OPEN", "MARKET_CLOSED", "MARKET_PAUSED", "MARKET_CANCELLED", "BET_BELOW_MIN",
      "BET_ABOVE_MAX", "BET_OVER_USER_CAP", "INSUFFICIENT_FUNDS", "KYC_REQUIRED", "USER_FROZEN", "BET_STAFF_ACCOUNT");
  /** 账本幂等键的业务类型：BET : 下注编号 */
  static final String BIZ = "BET";
  private static final int OPENED_CACHE_MAX = 10_000;

  /** 一次下注请求（{@code receivedAt}：请求进入应用时的服务器时间） */
  public record Place(long userId, String marketNo, String optionNo, String amount, String clientReqId, Instant receivedAt) {}

  private final PoolMapper mapper;
  private final PoolQueries views;
  private final MarketApi markets;
  private final LedgerApi ledger;
  private final AccountApi accounts;
  private final CustomerFlowApi flows;
  private final SystemConfig config;
  private final NotifyApi notify;
  private final Snowflake ids;
  private final PoolChanges changes;
  private final UserEventsApi events;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final TransactionTemplate openTx;
  /** 已经在彩池登记过的市场（对外编号），不用每次下注都查 */
  private final Map<Long, Boolean> opened = new ConcurrentHashMap<>();

  BetService(PoolMapper mapper, PoolQueries views, MarketApi markets, LedgerApi ledger, AccountApi accounts, CustomerFlowApi flows, SystemConfig config,
      NotifyApi notify, Snowflake ids, PoolChanges changes, UserEventsApi events, Clock clock, PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.views = views;
    this.markets = markets;
    this.ledger = ledger;
    this.accounts = accounts;
    this.flows = flows;
    this.config = config;
    this.notify = notify;
    this.ids = ids;
    this.changes = changes;
    this.events = events;
    this.clock = clock;
    this.tx = new TransactionTemplate(tm);
    this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.openTx = new TransactionTemplate(tm);
    this.openTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** 校验过的请求 */
  private record Checked(long marketNo, long optionNo, BigDecimal amount, String clientReqId) {}

  /**
   * 事务里的结果：新下的注（带上市场、选项编号和记账后的两项余额，返回时不用再查），或者已有的（幂等，返回时再查）。
   */
  private record Outcome(PoolRows.Bet bet, boolean duplicate, MarketApi.MarketRef market, Long optionNo, BigDecimal available, BigDecimal betLocked,
      PoolViews.Pool pool) {

    static Outcome existing(PoolRows.Bet b) {
      return new Outcome(b, true, null, null, null, null, null);
    }
  }

  public PoolViews.BetResult place(Place in) {
    Checked c = check(in);
    LocalDateTime receivedAt = LocalDateTime.ofInstant(in.receivedAt().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    // 幂等（先在锁外查一次）：同一个请求号已经下过注，直接返回第一次的结果——即使市场此刻已经截止（网络重试不应该变成"已截止"）
    PoolRows.Bet existing = mapper.findBetByReq(in.userId(), c.clientReqId());
    if (existing != null) return result(Outcome.existing(sameRequest(existing, c)));
    ensureOpened(c.marketNo());
    Outcome o;
    try {
      o = tx.execute(st -> placeInTx(in.userId(), c, receivedAt));
    } catch (DuplicateKeyException e) {
      // 同一个请求号并发进来（账户行锁之后已经查过，正常不会走到这里）：返回已有的
      PoolRows.Bet b = mapper.findBetByReq(in.userId(), c.clientReqId());
      if (b == null) throw e;
      o = Outcome.existing(sameRequest(b, c));
    }
    return result(o);
  }

  private Outcome placeInTx(long userId, Checked c, LocalDateTime receivedAt) {
    // ① 市场行共享锁 + 截止屏障（MARKET_NOT_OPEN / CLOSED / PAUSED / CANCELLED、NOT_FOUND、OPTION_NOT_FOUND）
    MarketApi.BetTarget t = markets.lockForBet(c.marketNo(), c.optionNo(), receivedAt.toInstant(ZoneOffset.UTC));
    // 金额范围（参数设置，betting_params:edit）
    if (c.amount().compareTo(views.minStake()) < 0) throw new BizException("BET_BELOW_MIN", "低于单笔最小下注金额 " + money(views.minStake()) + " USDT");
    if (c.amount().compareTo(views.maxStake()) > 0) throw new BizException("BET_ABOVE_MAX", "高于单笔最大下注金额 " + money(views.maxStake()) + " USDT");
    // 客户：冻结、员工、KYC（累计充值扣冲正 > 阈值且没通过 L1，第 3.10 节）
    if (accounts.status(userId) == UserStatus.FROZEN) throw new BizException("USER_FROZEN", "账户已冻结，不能下注");
    if (accounts.isStaff(userId)) throw new BizException("BET_STAFF_ACCOUNT", "员工账户不能下注");
    if (accounts.kycStatus(userId) != KycStatus.APPROVED) {
      BigDecimal threshold = config.decimal(ConfigKey.KYC_DEPOSIT_THRESHOLD, new BigDecimal("1000"));
      if (flows.netDeposits(userId).compareTo(threshold) > 0) throw new BizException("KYC_REQUIRED", "累计充值超过 " + money(threshold) + " USDT，请先完成身份认证再下注");
    }
    // ② 客户账户行排他锁：同一客户的下注在这里排队
    AccountSnapshot acc = ledger.lock(userId);
    PoolRows.Bet dup = mapper.findBetByReq(userId, c.clientReqId());
    if (dup != null) return Outcome.existing(sameRequest(dup, c));
    // 单客户单市场上限：这个客户在本市场所有选项上的有效下注合计（锁内读，READ COMMITTED 读到最新提交的）
    BigDecimal used = mapper.sumUserMarket(userId, t.marketId());
    if (used.add(c.amount()).compareTo(t.userCap()) > 0) {
      throw new BizException("BET_OVER_USER_CAP", "超过本市场的累计上限 " + money(t.userCap()) + " USDT（还能押 " + money(t.userCap().subtract(used).max(BigDecimal.ZERO)) + " USDT）");
    }
    if (acc.available().compareTo(c.amount()) < 0) throw new BizException("INSUFFICIENT_FUNDS", "可用余额不足");
    // ③ 记账、下注、通知、持仓、奖池（选项奖池原子加放在最后：热点行的锁只持有"最后一条语句 + 提交"）
    LocalDateTime now = receivedAt;
    long betNo = ids.next();
    List<PoolRows.OptionTotal> before = mapper.totals(t.marketId());
    String snapshot = views.snapshotJson(t.feeRate(), before, t.optionId(), c.amount());
    int bettors = views.bettorCount(t.marketId()); // 返回结果用的人数（缓存 1 秒）；在加奖池之前取，不在持有热点行锁时查库
    String biz = String.valueOf(betNo);
    List<com.forecast.app.ledger.api.LedgerResult> posted = ledger.applyAll(List.of(
        new LedgerCommand(userId, Bucket.AVAILABLE, c.amount().negate(), LedgerEntryType.BET_LOCK, BIZ, biz, "下注", t.marketId()),
        new LedgerCommand(userId, Bucket.BET_LOCKED, c.amount(), LedgerEntryType.BET_LOCK, BIZ, biz, "下注", t.marketId())));
    PoolRows.Bet b = new PoolRows.Bet();
    b.publicNo = betNo;
    b.userId = userId;
    b.marketId = t.marketId();
    b.optionId = t.optionId();
    b.amount = c.amount();
    b.clientReqId = c.clientReqId();
    b.receivedAt = receivedAt;
    b.status = "ACTIVE";
    b.snapshot = snapshot;
    b.createdAt = now;
    mapper.insertBet(b);
    // 通知 #8 下注成功（只发站内信）；和下注同一个事务，回滚就不发
    Map<String, String> params = new LinkedHashMap<>();
    params.put("market", t.title().zhCN());
    params.put("marketEn", t.title().en());
    params.put("marketTw", t.title().zhTW());
    params.put("option", t.optionLabel().zhCN());
    params.put("optionEn", t.optionLabel().en());
    params.put("optionTw", t.optionLabel().zhTW());
    params.put("amount", money(c.amount()));
    params.put("betNo", biz);
    notify.toUser(userId, "BET_PLACED", params);
    PoolRows.Position p = mapper.findPosition(t.marketId(), t.optionId(), userId);
    boolean newBettor = p == null;
    if (newBettor) {
      p = new PoolRows.Position();
      p.userId = userId;
      p.marketId = t.marketId();
      p.optionId = t.optionId();
      p.amount = c.amount();
      p.betCount = 1;
      p.createdAt = now;
      p.updatedAt = now;
      mapper.insertPosition(p);
    } else {
      mapper.addToPosition(p.id, c.amount(), now);
    }
    // 选项奖池原子加：最后一步（这一行的锁从这里持有到提交）
    if (mapper.addToTotal(t.optionId(), c.amount(), newBettor ? 1 : 0, now) != 1) {
      // 已经定格（截止任务拿到了排他锁才会定格，正常走不到）或者奖池行不存在
      throw new BizException("MARKET_CLOSED", "市场已截止下注");
    }
    // 提交以后：推送奖池变化（合并，每个市场每秒最多一次）、通知客户端刷新余额
    changes.afterCommit(t.marketId());
    afterCommit(() -> events.send(userId, Map.of("type", "account")));
    if (newBettor) afterCommit(() -> views.forgetBettors(t.marketId())); // 多了一个下注的客户：人数的缓存作废
    MarketApi.MarketRef ref = new MarketApi.MarketRef(t.marketId(), t.marketNo(), MarketStatus.LISTED, t.category(), t.title(), null, t.closeAt(), null,
        t.feeRate(), t.userCap());
    // 返回给客户的"下注后的奖池"：事务里读到的奖池加上这一笔（不再另查；推送会在 1 秒内给出包含别人下注的最新奖池）
    for (PoolRows.OptionTotal r : before) {
      if (r.optionId == t.optionId()) {
        r.amount = r.amount.add(c.amount());
        if (newBettor) r.bettors++;
      }
    }
    PoolViews.Pool pool = views.pool(MarketStatus.LISTED, t.feeRate(), before, List.of(), bettors, null, null);
    return new Outcome(b, false, ref, t.optionNo(), posted.get(0).valueAfter(), posted.get(1).valueAfter(), pool);
  }

  // ---------------------------------------------------------------------

  /** 请求的格式（一次列出全部字段错误，C56）：选项编号、金额（> 0、最多 2 位小数）、请求号（8～64 位字母数字和 - _） */
  private static Checked check(Place in) {
    ValidationException.Collector errs = new ValidationException.Collector();
    Long market = parseNo(in.marketNo());
    if (market == null) throw new BizException("NOT_FOUND", "市场不存在");
    Long option = null;
    if (in.optionNo() == null || in.optionNo().isBlank()) errs.add("optionNo", "required");
    else if ((option = parseNo(in.optionNo())) == null) errs.add("optionNo", "invalid");
    BigDecimal amount = null;
    if (in.amount() == null || in.amount().isBlank()) errs.add("amount", "required");
    else {
      amount = PoolMath.parseStake(in.amount());
      if (amount == null) {
        // 区分"不是正数"和"多于 2 位小数"（不舍入，直接拒绝）
        boolean digits = in.amount().strip().matches("\\d{1,12}\\.\\d{3,}") && new BigDecimal(in.amount().strip()).signum() > 0;
        errs.add("amount", digits ? "amountDigits" : "positiveNumber");
      }
    }
    String req = in.clientReqId() == null ? "" : in.clientReqId().strip();
    if (req.isEmpty()) errs.add("clientReqId", "required");
    else if (!req.matches("[A-Za-z0-9_-]{8,64}")) errs.add("clientReqId", "invalid");
    errs.throwIfAny();
    return new Checked(market, option, amount, req);
  }

  private static Long parseNo(String s) {
    if (s == null || !s.strip().matches("\\d{1,19}")) return null;
    try {
      return Long.parseLong(s.strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** 同一个请求号：市场、选项、金额都相同才算重复提交；换了内容是客户端的错误（请求号要每次新生成） */
  private PoolRows.Bet sameRequest(PoolRows.Bet b, Checked c) {
    Map<Long, MarketApi.OptionRef> o = markets.optionsById(List.of(b.optionId));
    MarketApi.OptionRef opt = o.get(b.optionId);
    boolean same = opt != null && opt.optionNo() == c.optionNo() && b.amount.compareTo(c.amount()) == 0
        && markets.findById(b.marketId).map(m -> m.publicNo() == c.marketNo()).orElse(false);
    if (!same) throw new BizException("BET_REQUEST_REUSED", "这个请求号已经用于另一笔下注，请刷新后重新下注");
    return b;
  }

  /** 市场还没在彩池登记（S4 之前上架的、测试直接建的）：先在单独的小事务里补上奖池行，下注事务里只做原子加 */
  private void ensureOpened(long marketNo) {
    if (opened.containsKey(marketNo)) return;
    Optional<MarketApi.MarketRef> m = markets.find(marketNo);
    if (m.isEmpty() || (m.get().status() != MarketStatus.LISTED && m.get().status() != MarketStatus.PAUSED)) return; // 交给 lockForBet 拒绝
    long id = m.get().marketId();
    if (mapper.marketOpened(id) == null) {
      openTx.executeWithoutResult(st -> {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        mapper.insertMarketIfAbsent(id, m.get().status().name(), now);
        for (MarketApi.OptionRef o : markets.options(id)) mapper.insertTotalIfAbsent(o.optionId(), id, o.optionNo(), o.seq(), now);
      });
    }
    if (opened.size() > OPENED_CACHE_MAX) opened.clear();
    opened.put(marketNo, Boolean.TRUE);
  }

  /** 下注结果：新下的注用事务里已有的数据（不再查库）；重复请求再查一次市场、选项、余额和现在的奖池 */
  private PoolViews.BetResult result(Outcome o) {
    PoolRows.Bet b = o.bet();
    MarketApi.MarketRef m;
    long optionNo;
    BigDecimal available;
    BigDecimal betLocked;
    if (o.duplicate()) {
      m = markets.findById(b.marketId).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
      MarketApi.OptionRef opt = markets.optionsById(List.of(b.optionId)).get(b.optionId);
      optionNo = opt == null ? 0 : opt.optionNo();
      AccountSnapshot a = ledger.snapshot(b.userId);
      available = a.available();
      betLocked = a.betLocked();
    } else {
      m = o.market();
      optionNo = o.optionNo();
      available = o.available();
      betLocked = o.betLocked();
    }
    return new PoolViews.BetResult(String.valueOf(b.publicNo), String.valueOf(m.publicNo()), String.valueOf(optionNo), money(b.amount),
        PoolQueries.iso(b.receivedAt), b.status.toLowerCase(java.util.Locale.ROOT), o.duplicate(), money(available), money(betLocked),
        o.pool() != null ? o.pool() : views.marketPool(m));
  }

  private static void afterCommit(Runnable r) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          r.run();
        }
      });
    } else {
      r.run();
    }
  }
}
