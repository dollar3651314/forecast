package com.forecast.app.market.api;

/**
 * 客户端市场详情里的裁决依据（{@code resolution} 字段），<b>由 resolve 模块实现</b>（forecast S6，{@code resolve/auto/ResolutionViews}）。
 * market 不依赖 resolve（技术方案第 2.3 节的依赖方向），和 {@link MarketPoolView} 同样的做法。没有实现时详情里没有这个字段的值。
 * <p>需求第 4.3 节"已结算市场详情页公开显示开始价、结束价和来源"、AC-27：market 只在市场已结算时调用。
 */
public interface MarketResolutionView {

  /**
   * 已结算市场的裁决依据（结构由 resolve 定：自动裁决的开始价、结束价、来源、各来源的数据）；没有自动裁决记录时返回 null。只读。
   */
  Object clientResolution(long marketId);
}
