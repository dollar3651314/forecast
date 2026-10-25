package com.forecast.app.market.api;

/**
 * 客户端市场详情里的裁决信息（{@code resolution} 字段），<b>由 resolve 模块实现</b>（forecast S6 起，{@code resolve/auto/ResolutionViews}）。
 * market 不依赖 resolve（技术方案第 2.3 节的依赖方向），和 {@link MarketPoolView} 同样的做法。没有实现时详情里没有这个字段的值。
 * <ul>
 *   <li>S6：已结算的自动裁决市场公开开始价、结束价和来源（需求第 4.3 节、AC-27）。</li>
 *   <li>S7：平台裁决的市场在公示中、争议中、已结算、已作废时公开结果、依据、裁决人编号、争议截止时间、争议结论（需求第 4.4 节、第 5.2 节 P2、AC-34），
 *       不显示争议人；当前客户自己的争议和能不能提交争议另给。</li>
 * </ul>
 */
public interface MarketResolutionView {

  /**
   * 裁决信息（结构由 resolve 定）；没有可公开的裁决记录时返回 null。只读。market 只在公示中、争议中、已结算、已作废时调用。
   *
   * @param status 市场现在的状态
   * @param userId 当前客户（没登录为空）：带上他自己的争议、能不能提交争议
   */
  Object clientResolution(long marketId, MarketStatus status, Long userId);
}
