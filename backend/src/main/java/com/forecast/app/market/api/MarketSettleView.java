package com.forecast.app.market.api;

/**
 * 客户端市场详情里的结算信息（{@code settle} 字段），<b>由 settle 模块实现</b>（forecast S11，{@code settle/view/ClientSettleViews}）。
 * market 不依赖 settle（技术方案第 2.3 节的依赖方向），和 {@link MarketPoolView}、{@link MarketResolutionView} 同样的做法；没有实现时详情里没有这个字段的值。
 * <ul>
 *   <li>已结算：结算用的费率、奖池合计、派彩合计、抽成合计、尾差（需求第 3.5 节"结算公开"、P2"彩池怎么算 + 本市场的数字"），本人的本金、派彩、抽成、补偿；</li>
 *   <li>已取消、已作废：退款合计和本人的退款（全额）。</li>
 * </ul>
 */
public interface MarketSettleView {

  /**
   * 结算信息（结构由 settle 定）；还没有结算单（或派彩还没完成）时返回 null。只读。market 只在已结算、已取消、已作废时调用。
   *
   * @param userId 当前客户（没登录为空）：带上他自己的结果
   */
  Object clientSettle(long marketId, MarketStatus status, Long userId);
}
