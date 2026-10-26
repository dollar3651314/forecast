package com.forecast.app.admin.ops;

import com.forecast.app.account.api.CustomerAdminApi;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.pool.api.PoolAdminApi;
import com.forecast.app.resolve.api.ResolveDeskApi;
import com.forecast.app.settle.api.SettleAdminApi;
import com.forecast.app.wallet.api.WalletAdminApi;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 后台仪表盘的彩池部分（forecast S9，需求说明书第 6.2 节"仪表盘"）：今日 / 近 7 天的下注额、下注人数、平台收入（抽成 + 尾差）、补偿支出、
 * 新注册、充值、提现；待处理：待上架、待提议、待确认、自动裁决失败、超时、争议中、结算异常、彩池对账差异、出金待审核；
 * 场次（S13，SQ-21）：同时开放的市场数 / 上限、今日场次数。
 * "今天"按 UTC 日期（需求第 3.7 节）；"近 7 天" = 今天和前 6 天。和复制源的 {@code /api/admin/dashboard} 分开取（C41：一处慢不拖垮整页）。
 * 只读，不加事务（每个模块的查询各自只读）。
 */
@Service
class MarketDashboardOps {

  /** 一段时间的数字 */
  record Period(String from, String to, String betAmount, long bets, long bettors, String revenue, String compensation, long registrations, String deposit,
      long depositCount, String withdraw, long withdrawCount) {}

  /** 待处理事项（各自的数量） */
  record Pending(long review, long awaitingProposal, long awaitingConfirm, long autoFailed, long overdue, long publicizing, long disputed,
      long settleAbnormal, long poolReconOpen, long withdrawalsPending) {}

  /** S13（SQ-21，需求第 4.2 节）：同时开放的市场数和上限（0 = 不限）、今天（UTC）列入今日场次的市场数 */
  record Slate(long open, int limit, long today) {}

  record View(Period today, Period week, Pending pending, Slate slate, String updatedAt) {}

  private final PoolAdminApi pool;
  private final SettleAdminApi settle;
  private final MarketAdminApi markets;
  private final ResolveDeskApi desk;
  private final CustomerAdminApi customers;
  private final WalletAdminApi wallet;
  private final Clock clock;

  MarketDashboardOps(PoolAdminApi pool, SettleAdminApi settle, MarketAdminApi markets, ResolveDeskApi desk, CustomerAdminApi customers, WalletAdminApi wallet,
      Clock clock) {
    this.pool = pool;
    this.settle = settle;
    this.markets = markets;
    this.desk = desk;
    this.customers = customers;
    this.wallet = wallet;
    this.clock = clock;
  }

  View view() {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    MarketAdminApi.SlateStats s = markets.slateStats();
    return new View(period(today, today), period(today.minusDays(6), today), pending(), new Slate(s.open(), s.limit(), s.today()), clock.instant().toString());
  }

  private Period period(LocalDate from, LocalDate to) {
    LocalDateTime start = from.atStartOfDay();
    PoolAdminApi.BetStats b = pool.betStats(start.toInstant(ZoneOffset.UTC), to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    SettleAdminApi.Report r = settle.report(from.toString(), to.toString());
    WalletAdminApi.Bucket dep = wallet.creditedSince(start);
    WalletAdminApi.Bucket wd = wallet.summary(start).completedToday();
    return new Period(from.toString(), to.toString(), b.amount(), b.bets(), b.bettors(), r.revenue(), r.compensationExpense(), customers.registeredSince(start),
        Ops.money(dep.amount()), dep.count(), Ops.money(wd.amount()), wd.count());
  }

  private Pending pending() {
    return new Pending(markets.counts().review(), cases("awaiting_proposal"), cases("awaiting_confirm"), cases("auto_failed"), cases("overdue"),
        cases("publicizing"), cases("disputed"), settle.runs("abnormal", null, 1, 1).total(), settle.issues("open", 1, 1).total(), wallet.pendingReview());
  }

  private long cases(String tab) {
    return desk.cases(tab, 1, 1).total();
  }

  /** 裁决台各分组的数量（裁决台的标签用） */
  Map<String, Long> resolveCounts() {
    Map<String, Long> m = new LinkedHashMap<>();
    for (String tab : List.of("all", "awaiting_proposal", "awaiting_confirm", "auto_failed", "on_hold", "overdue", "publicizing", "disputed")) m.put(tab, cases(tab));
    return m;
  }
}
