package com.forecast.app.pool.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * "我的下注"要的结算结果，<b>由 settle 模块实现</b>（forecast S11，{@code settle/view/ClientSettleViews}）。pool 不依赖 settle 的内部类
 * （settle 依赖 pool，反过来由 settle 提供这个接口的实现，和 {@code market.api.MarketPoolView} 同样的做法）。没有实现时我的下注里没有派彩、抽成。
 * 只读，只算<b>派彩已完成</b>（结算单 DONE）的市场：派彩分批进行中（"结算中"）的不算，和"已结算"分组（市场状态 SETTLED）一致。
 */
public interface PoolResultView {

  /**
   * 本人在一个市场一个选项上的结算结果（结算按持仓算：同一选项的几笔下注合在一起，只舍入一次，需求第 3.5 节）。
   *
   * @param stake 持仓本金 b
   * @param payout 实际到账的派彩 = b + G − F（输了为 0）
   * @param fee 抽成 F（输了为 0）
   */
  record PositionResult(long marketId, long optionId, boolean win, BigDecimal stake, BigDecimal payout, BigDecimal fee) {}

  /**
   * 本人全部已结算市场的合计。
   *
   * @param settledStake 已结算的下注本金（赢的、输的都算）
   * @param payout 派彩合计（已扣抽成）
   * @param compensation 补偿合计（E13）
   */
  record Totals(BigDecimal settledStake, BigDecimal payout, BigDecimal compensation) {}

  /** 本人在这些市场的持仓结算结果；没结算完的市场不在结果里 */
  List<PositionResult> results(long userId, Collection<Long> marketIds);

  /** 本人的已结算合计；没有时全 0 */
  Totals totals(long userId);
}
