package com.forecast.app.pool.bet;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketPoolView;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.pool.api.Odds;
import com.forecast.app.pool.api.PoolAdminApi;
import com.forecast.app.pool.api.PoolApi;
import com.forecast.app.pool.api.PoolMath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 彩池的读（forecast S4）：客户端接口里的奖池（市场列表、详情、下注结果、推送共用一份）、我的下注、后台下注明细、R8 的有效下注本金。
 * 概率和预计赔率按需求第 3.4 节算好成字符串（{@link Odds}），用市场<b>当前</b>的费率；截止后用定格的奖池（{@code final_amount}）。
 */
@Service
public class PoolQueries implements PoolApi, PoolAdminApi {

  /** 截止以后奖池不再变的状态（没有定格记录时也按"已定格"显示，例如 S4 以前就截止的市场） */
  static final Set<MarketStatus> AFTER_CLOSE = EnumSet.of(MarketStatus.CLOSED, MarketStatus.PENDING_RESOLUTION, MarketStatus.PUBLICIZING,
      MarketStatus.DISPUTED, MarketStatus.SETTLED, MarketStatus.VOIDED);
  static final List<String> TABS = List.of("active", "pending", "settled", "refunded");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final PoolMapper mapper;
  private final MarketApi markets;
  private final SystemConfig config;
  private final ObjectMapper json;

  PoolQueries(PoolMapper mapper, MarketApi markets, SystemConfig config, ObjectMapper json) {
    this.mapper = mapper;
    this.markets = markets;
    this.config = config;
    this.json = json;
  }

  // =====================================================================
  // 奖池视图
  // =====================================================================

  /** 一个选项的显示用数据（定格后用定格的值） */
  private record Line(long optionNo, int seq, BigDecimal amount, int bettors) {}

  /**
   * 由奖池行算出视图。
   *
   * @param fallback 市场在彩池里还没有奖池行时（还没人下注、S4 以前上架的）按这些选项显示全 0
   * @param positions 本人的持仓（详情）；列表、推送为 null
   * @param limits 下注限额（详情）；列表、推送为 null
   */
  PoolViews.Pool pool(MarketStatus status, BigDecimal feeRate, List<PoolRows.OptionTotal> rows, List<MarketPoolView.OptionRef> fallback, int bettors,
      List<PoolRows.Position> positions, PoolViews.Limits limits) {
    boolean frozen = rows.isEmpty() ? AFTER_CLOSE.contains(status) : rows.stream().anyMatch(r -> r.finalAmount != null);
    List<Line> lines = new ArrayList<>();
    if (rows.isEmpty()) {
      for (MarketPoolView.OptionRef o : fallback) lines.add(new Line(o.optionNo(), o.seq(), BigDecimal.ZERO, 0));
    } else {
      for (PoolRows.OptionTotal r : rows) {
        boolean f = r.finalAmount != null;
        lines.add(new Line(r.optionNo, r.seq, f ? r.finalAmount : r.amount, f && r.finalBettors != null ? r.finalBettors : r.bettors));
      }
    }
    BigDecimal total = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    List<PoolViews.OptionPool> options = lines.stream().map(l -> new PoolViews.OptionPool(String.valueOf(l.optionNo), l.seq, money(l.amount), l.bettors,
        Odds.percent(l.amount, total, 1), Odds.percent(l.amount, total, 0), Odds.multiplier(l.amount, total, feeRate))).toList();
    List<PoolViews.Mine> mine = null;
    if (positions != null) {
      Map<Long, Line> byOption = new LinkedHashMap<>();
      if (!rows.isEmpty()) for (int i = 0; i < rows.size(); i++) byOption.put(rows.get(i).optionId, lines.get(i));
      mine = new ArrayList<>();
      for (PoolRows.Position p : positions) {
        Line l = byOption.get(p.optionId);
        if (l == null || p.amount.signum() <= 0) continue;
        // 持仓已经在奖池里：这个选项赢了按现在（截止后是定格）的奖池结算能拿回多少
        String ifWin = l.amount.signum() > 0 ? money(PoolMath.payout(p.amount, l.amount, total, feeRate).payout()) : money(p.amount);
        mine.add(new PoolViews.Mine(String.valueOf(l.optionNo), l.seq, money(p.amount), p.betCount, ifWin));
      }
    }
    return new PoolViews.Pool(money(total), bettors, frozen, percent(feeRate), options, mine, limits);
  }

  /** 客户端市场列表、详情（{@link MarketPoolView#clientPools}）：一次查完一页市场的奖池 */
  @Transactional(readOnly = true)
  Map<Long, Object> clientPools(List<MarketPoolView.PoolContext> ctx, Long userId) {
    if (ctx.isEmpty()) return Map.of();
    List<Long> ids = ctx.stream().map(MarketPoolView.PoolContext::marketId).toList();
    Map<Long, List<PoolRows.OptionTotal>> totals = mapper.totalsOf(ids).stream().collect(Collectors.groupingBy(t -> t.marketId));
    Map<Long, Integer> counts = mapper.bettorCounts(ids).stream().collect(Collectors.toMap(c -> c.marketId, c -> c.n));
    Map<Long, List<PoolRows.Position>> mine = userId == null ? Map.of()
        : mapper.positionsOf(userId, ids).stream().collect(Collectors.groupingBy(p -> p.marketId));
    Map<Long, Object> out = new LinkedHashMap<>();
    for (MarketPoolView.PoolContext c : ctx) {
      List<PoolRows.Position> pos = userId == null ? null : mine.getOrDefault(c.marketId(), List.of());
      out.put(c.marketId(), pool(c.status(), c.feeRate(), totals.getOrDefault(c.marketId(), List.of()), c.options(), counts.getOrDefault(c.marketId(), 0), pos,
          userId == null ? null : limits(c.userCap(), pos)));
    }
    return out;
  }

  /**
   * 推送、下注结果用：一个市场现在的奖池（不含本人的持仓）。有持仓的客户数（不重复）要数这个市场的全部持仓，
   * 热门市场每笔下注都数一次太贵：同一个市场 1 秒内用上一次数的（只是显示的人数，金额、概率、赔率都是现查的）。
   * 不开只读事务（每秒每个市场都要调用，两条查询不需要同一个快照，省掉事务开关的语句）。
   */
  public PoolViews.Pool marketPool(MarketApi.MarketRef m) {
    List<PoolRows.OptionTotal> rows = mapper.totals(m.marketId());
    List<MarketPoolView.OptionRef> fallback = rows.isEmpty()
        ? markets.options(m.marketId()).stream().map(o -> new MarketPoolView.OptionRef(o.optionId(), o.optionNo(), o.seq())).toList() : List.of();
    return pool(m.status(), m.feeRate(), rows, fallback, bettorCount(m.marketId()), null, null);
  }

  private static final long BETTOR_CACHE_NANOS = 1_000_000_000L;
  /** 市场 → {人数, 数的时刻 nanoTime} */
  private final Map<Long, long[]> bettorCache = new java.util.concurrent.ConcurrentHashMap<>();

  /** 这个市场多了下注的客户（下注事务提交后）：下次重新数 */
  void forgetBettors(long marketId) {
    bettorCache.remove(marketId);
  }

  int bettorCount(long marketId) {
    long now = System.nanoTime();
    long[] c = bettorCache.get(marketId);
    if (c != null && now - c[1] < BETTOR_CACHE_NANOS) return (int) c[0];
    int n = mapper.bettorCounts(List.of(marketId)).stream().mapToInt(x -> x.n).sum();
    if (bettorCache.size() > 10_000) bettorCache.clear();
    bettorCache.put(marketId, new long[] {n, now});
    return n;
  }

  PoolViews.Limits limits(BigDecimal userCap, List<PoolRows.Position> positions) {
    BigDecimal used = positions.stream().map(p -> p.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal remaining = userCap.subtract(used).max(BigDecimal.ZERO);
    return new PoolViews.Limits(money(minStake()), money(maxStake()), money(userCap), money(used), money(remaining));
  }

  BigDecimal minStake() {
    return config.decimal(ConfigKey.BET_MIN_AMOUNT, BigDecimal.ONE);
  }

  BigDecimal maxStake() {
    return config.decimal(ConfigKey.BET_MAX_AMOUNT, new BigDecimal("5000"));
  }

  // =====================================================================
  // 我的下注
  // =====================================================================

  @Transactional(readOnly = true)
  public PoolViews.MyBets myBets(long userId, String tab, int page, int pageSize) {
    String t = tab == null || tab.isBlank() || tab.strip().equals("all") ? null : tab.strip();
    if (t != null && !TABS.contains(t)) throw new ValidationException(List.of(new ValidationException.Field("tab", "invalid")));
    int p = Math.max(1, page);
    int size = Math.min(100, Math.max(1, pageSize));
    List<PoolRows.BetLine> lines = mapper.myBets(userId, t, (p - 1) * size, size);
    Map<String, PoolViews.TabSum> summary = new LinkedHashMap<>();
    for (String x : TABS) summary.put(x, new PoolViews.TabSum(0, "0.00"));
    for (PoolRows.TabSum s : mapper.mySummary(userId)) summary.put(s.tab, new PoolViews.TabSum(s.n, money(s.amount)));
    return new PoolViews.MyBets(myBetViews(lines), mapper.countMyBets(userId, t), p, size, summary);
  }

  List<PoolViews.MyBet> myBetViews(List<PoolRows.BetLine> lines) {
    if (lines.isEmpty()) return List.of();
    Map<Long, MarketApi.MarketRef> refs = markets.refs(lines.stream().map(l -> l.marketId).collect(Collectors.toSet()));
    Map<Long, MarketApi.OptionRef> opts = markets.optionsById(lines.stream().map(l -> l.optionId).collect(Collectors.toSet()));
    return lines.stream().map(l -> {
      MarketApi.MarketRef m = refs.get(l.marketId);
      MarketApi.OptionRef o = opts.get(l.optionId);
      Snapshot s = snapshot(l.snapshot);
      String mult = Odds.NONE;
      if (s != null) {
        BigDecimal pi = s.pools.getOrDefault(String.valueOf(l.optionNo), BigDecimal.ZERO);
        mult = Odds.multiplier(pi, s.total, s.feeRate);
      }
      return new PoolViews.MyBet(String.valueOf(l.publicNo), m == null ? null : String.valueOf(m.publicNo()), text(m == null ? null : m.title()),
          MarketStatus.valueOf(l.marketStatus).api(), String.valueOf(l.optionNo), l.seq, text(o == null ? null : o.label()), money(l.amount), iso(l.receivedAt),
          l.tab, l.status.toLowerCase(java.util.Locale.ROOT), l.refundReason, mult, s == null ? null : percent(s.feeRate));
    }).toList();
  }

  // =====================================================================
  // 下注时的奖池快照（pool_bet.snapshot，只做展示）
  // =====================================================================

  /** 快照：下注后各选项（对外编号）的奖池、合计、当时的费率 */
  record Snapshot(BigDecimal feeRate, BigDecimal total, Map<String, BigDecimal> pools) {}

  String snapshotJson(BigDecimal feeRate, List<PoolRows.OptionTotal> rows, long optionId, BigDecimal stake) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("feeRate", feeRate.stripTrailingZeros().toPlainString());
    List<Map<String, String>> options = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (PoolRows.OptionTotal r : rows) {
      BigDecimal a = r.optionId == optionId ? r.amount.add(stake) : r.amount;
      total = total.add(a);
      options.add(Map.of("optionNo", String.valueOf(r.optionNo), "amount", money(a)));
    }
    s.put("total", money(total));
    s.put("options", options);
    return json.writeValueAsString(s);
  }

  Snapshot snapshot(String text) {
    if (text == null || text.isBlank()) return null;
    try {
      JsonNode n = json.readTree(text);
      Map<String, BigDecimal> pools = new LinkedHashMap<>();
      for (JsonNode o : n.get("options")) pools.put(o.get("optionNo").asString(), new BigDecimal(o.get("amount").asString()));
      return new Snapshot(new BigDecimal(n.get("feeRate").asString()), new BigDecimal(n.get("total").asString()), pools);
    } catch (RuntimeException e) {
      return null;
    }
  }

  // =====================================================================
  // PoolApi：给其他模块
  // =====================================================================

  @Override
  @Transactional(readOnly = true)
  public BigDecimal effectiveStakeSince(long userId, LocalDateTime since) {
    BigDecimal v = mapper.effectiveStakeSince(userId, since);
    return v == null ? BigDecimal.ZERO : v;
  }

  // =====================================================================
  // PoolAdminApi：后台下注明细（bet:view）
  // =====================================================================

  @Override
  @Transactional(readOnly = true)
  public MarketBets marketBets(long marketNo, String optionNo, Long userId, String status, int page, int pageSize) {
    MarketApi.MarketRef m = markets.find(marketNo).filter(x -> x.status() != MarketStatus.DRAFT).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
    ValidationException.Collector errs = new ValidationException.Collector();
    Long optionId = null;
    if (optionNo != null && !optionNo.isBlank()) {
      optionId = markets.options(m.marketId()).stream().filter(o -> String.valueOf(o.optionNo()).equals(optionNo.strip())).map(MarketApi.OptionRef::optionId)
          .findFirst().orElse(null);
      if (optionId == null) errs.add("optionNo", "invalid");
    }
    String st = null;
    if (status != null && !status.isBlank()) {
      st = switch (status.strip()) {
        case "active" -> "ACTIVE";
        case "refunded" -> "REFUNDED";
        default -> null;
      };
      if (st == null) errs.add("status", "invalid");
    }
    errs.throwIfAny();
    int p = Math.max(1, page);
    int size = Math.min(200, Math.max(1, pageSize));
    List<PoolRows.OptionTotal> rows = mapper.totals(m.marketId());
    PoolViews.Pool pool = marketPool(m);
    List<OptionRow> options = rows.stream().map(r -> new OptionRow(String.valueOf(r.optionNo), r.seq, money(r.amount), r.bettors,
        r.finalAmount == null ? null : money(r.finalAmount), r.finalBettors)).toList();
    List<BetRow> bets = mapper.marketBets(m.marketId(), optionId, userId, st, (p - 1) * size, size).stream()
        .map(l -> new BetRow(String.valueOf(l.publicNo), l.userId, String.valueOf(l.optionNo), l.seq, money(l.amount), iso(l.receivedAt),
            l.status.toLowerCase(java.util.Locale.ROOT), l.refundReason, l.clientReqId)).toList();
    BigDecimal active = mapper.sumActiveBets(m.marketId());
    return new MarketBets(String.valueOf(m.publicNo()), m.status().api(), pool.total(), money(active == null ? BigDecimal.ZERO : active), pool.bettors(),
        pool.frozen(), options, new Page<>(bets, mapper.countMarketBets(m.marketId(), optionId, userId, st), p, size));
  }

  // =====================================================================
  // 格式
  // =====================================================================

  /** 金额：2 位小数（下注金额、奖池在业务上都是 2 位小数，库里是 8 位） */
  static String money(BigDecimal v) {
    return v == null ? null : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /** 费率：库里的小数（0.0525）→ 接口里的百分比（"5.25"），和市场接口一致 */
  static String percent(BigDecimal rate) {
    if (rate == null) return null;
    BigDecimal p = rate.multiply(HUNDRED).stripTrailingZeros();
    return p.signum() == 0 ? "0" : p.toPlainString();
  }

  static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }

  static PoolViews.Text text(I18nText t) {
    return t == null ? null : new PoolViews.Text(t.en(), t.zhCN(), t.zhTW());
  }
}
