package com.forecast.app.market.api;

import java.time.Instant;

/**
 * 市场的事件，<b>由 settle（S5）等模块实现</b>，可以有多个；都在 market 的事务里同步调用，抛异常会让整个操作回滚。
 * S5 起 settle 实现退款（{@link #onRefundRequired}、{@link #onCloseAdvanced}），S6 起 resolve 实现 {@link #onStatusChanged}（进入待裁决时建自动裁决的进度）。
 */
public interface MarketListener {

  /**
   * 市场进入 CANCELLED 或 VOIDED（截止前取消、截止时有效选项不足 2 个、人工或系统作废）：S5 在同一个事务里建退款单（全额退款，不收费）。
   */
  default void onRefundRequired(long marketId, MarketStatus to, String reason) {}

  /**
   * 截止时间被提前（需求第 3.2 节第 4 条）：新截止时间之后收到的下注要全额退回（和 E14 同样的逻辑，S4、S5 实现）。
   */
  default void onCloseAdvanced(long marketId, Instant oldCloseAt, Instant newCloseAt) {}

  /**
   * 每次状态跳转成功以后（同一个事务里，状态历史已写；forecast S6）：resolve 在市场进入"待裁决"时建自动裁决的进度行。
   * 进入取消、作废时先调这个、再调 {@link #onRefundRequired}。
   */
  default void onStatusChanged(long marketId, MarketStatus from, MarketStatus to) {}
}
