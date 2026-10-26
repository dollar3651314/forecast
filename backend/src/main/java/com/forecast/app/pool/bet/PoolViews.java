package com.forecast.app.pool.bet;

import java.util.List;
import java.util.Map;

/**
 * 客户端接口里和彩池有关的结构（forecast S4 的契约，{@code PoolClientContractTest} 核对字段名和顺序）。
 * 页面在原型确认后做（S11），字段先定下来：
 * <ul>
 *   <li>编号（betNo、marketNo、optionNo）是字符串；金额是 2 位小数的字符串（"6000.00"）；费率是百分比字符串（"5" = 5%，和市场接口一致）；
 *       时间是 ISO-8601 UTC。</li>
 *   <li>概率、预计赔率是<b>显示用的字符串</b>（按需求第 3.4 节舍入好，客户端直接显示）：{@code pct} 详情用（1 位小数），{@code pctList} 列表用（整数），
 *       可能是 {@code <1%}、{@code >99%}、{@code —}；{@code mult} 不带 x，Pᵢ = 0 时是 {@code —}。</li>
 * </ul>
 */
public final class PoolViews {

  private PoolViews() {}

  /**
   * 一个选项的奖池。
   *
   * @param bettors 在这个选项上有持仓的客户数
   */
  public record OptionPool(String optionNo, int seq, String amount, int bettors, String pct, String pctList, String mult) {}

  /**
   * 本人在一个选项上的持仓（市场详情）。
   *
   * @param ifWin 这个选项赢了、按现在的奖池（截止后是定格的奖池）和当前费率结算，能拿回多少（本金 + 盈利 − 抽成）
   */
  public record Mine(String optionNo, int seq, String amount, int betCount, String ifWin) {}

  /**
   * 下注限额（市场详情，下注抽屉用）。
   *
   * @param used 本人在这个市场所有选项上的有效下注合计
   * @param remaining 本市场还能押多少 = userCap − used（不小于 0）
   */
  public record Limits(String min, String max, String userCap, String used, String remaining) {}

  /**
   * 市场的奖池（市场列表、详情的 {@code pool} 字段；推送和下注结果里也是它）。
   *
   * @param bettors 有持仓的客户数（不重复）
   * @param frozen 截止时已定格（显示"最终赔率"；结算前改了费率时 {@code mult} 按新费率重新算，第 3.4 节）
   * @param mine 本人的持仓（只在详情里有，列表里为 null）
   * @param limits 下注限额（只在详情里有）
   */
  public record Pool(String total, int bettors, boolean frozen, String feeRate, List<OptionPool> options, List<Mine> mine, Limits limits) {}

  /**
   * 下注结果（{@code POST /api/markets/{publicNo}/bets}）。
   *
   * @param duplicate 同一个请求号已经下过注：这是第一次的结果，没有再扣钱（X-3）
   * @param available 下注后的可用余额
   * @param betLocked 下注后的"下注中"
   * @param pool 下注后的奖池（重复请求时是现在的奖池）
   */
  public record BetResult(String betNo, String marketNo, String optionNo, String amount, String receivedAt, String status, boolean duplicate,
      String available, String betLocked, Pool pool) {}

  /** 三种语言的文字（市场题目、选项） */
  public record Text(String en, String zhCN, String zhTW) {}

  /**
   * 我的下注的一条。
   *
   * @param tab active 进行中 / pending 待结果 / settled 已结算 / refunded 已退回
   * @param status active 有效 / refunded 已退回（S5 起）
   * @param refundReason 退回原因代码（CANCELLED / VOIDED / LATE_BET / STAFF，S5 起），没有时为 null
   * @param marketStatus 市场现在的状态（小写，和市场接口一致）
   * @param multAtBet 下注后那一刻这个选项的预计赔率（下注时的奖池快照，只做展示）
   * @param feeRateAtBet 下注时的费率（百分比）
   * @param category 市场品类（S11，大写，和市场接口一致）
   * @param finalMult 截止后的最终赔率（定格的奖池、当前费率，S11）；还没截止为 null
   * @param result 已结算的结果（S11）；其他分组为 null
   */
  public record MyBet(String betNo, String marketNo, Text market, String marketStatus, String optionNo, int seq, Text option, String amount,
      String receivedAt, String tab, String status, String refundReason, String multAtBet, String feeRateAtBet, String category, String finalMult,
      Result result) {}

  /**
   * 已结算下注的结果（forecast S11，需求 P4"派彩 / 抽成"）。结算按持仓算（同一客户同一选项的几笔下注合在一起，只舍入一次，需求第 3.5 节），
   * 所以派彩、抽成是<b>这个选项的持仓合计</b>，几笔下注显示同一组数字，客户端写明"本选项 n 笔合计"。
   *
   * @param won 押中了
   * @param positionAmount 这个选项的持仓本金合计
   * @param positionBets 持仓合计了几笔有效下注
   * @param payout 实际到账的派彩（已扣抽成；没押中为 0.00）
   * @param fee 抽成（没押中为 0.00）
   */
  public record Result(boolean won, String positionAmount, int positionBets, String payout, String fee) {}

  /** 一个分组的笔数和金额 */
  public record TabSum(long count, String amount) {}

  /**
   * 顶部汇总（forecast S11，需求 P4"总下注 · 总派彩 · 净盈亏"）。
   *
   * @param staked 总下注：有效下注的本金合计（进行中、待结果、已结算；已退回的不算）
   * @param payout 总派彩：已结算市场的派彩（已扣抽成）+ 补偿
   * @param net 净盈亏 = 总派彩 − 已结算的下注本金（还没结算的下注不算，和提现自动审批的"已结算盈亏"口径一致）
   */
  public record Totals(String staked, String payout, String net) {}

  /** 我的下注：一页 + 四个分组的汇总（键 active / pending / settled / refunded，没有的为 0）+ 顶部汇总（S11） */
  public record MyBets(List<MyBet> items, long total, int page, int pageSize, Map<String, TabSum> summary, Totals totals) {}
}
