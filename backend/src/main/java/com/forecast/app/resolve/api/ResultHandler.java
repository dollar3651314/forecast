package com.forecast.app.resolve.api;

import com.forecast.app.market.api.MarketStatus;
import java.time.Instant;

/**
 * 裁决结果定了以后结算（forecast S6 起）。<b>由 settle 模块实现</b>（{@code settle/run/SettleResultHandler} → {@code SettleService.settle}），
 * resolve 不依赖 settle 的内部类（技术方案第 2.3 节依赖方向：resolve ← settle）。
 * <p>同一市场重复调用（含并发、崩溃后续跑）只结算一次（settle 的三道防重复）。<b>不能在事务里调用</b>（结算自己开事务、按固定顺序加锁）。
 */
public interface ResultHandler {

  /**
   * 结算（市场在待裁决、公示中、争议中都可以）。
   *
   * @param winningOptionId 赢的选项（内部 id）
   * @param actualStartAt 实际开赛时间（E14），加密市场为空
   * @param resolvedBy 谁给的结果：{@code AUTO}（自动裁决）或管理员 id（公开的裁决人编号）
   */
  default void settle(long marketId, long winningOptionId, Instant actualStartAt, String resolvedBy) {
    settle(marketId, winningOptionId, actualStartAt, resolvedBy, null);
  }

  /**
   * 结算，但只在市场<b>正好是 expect 状态</b>时开始（forecast S7）：在锁住市场行以后检查，不是就什么都不做。
   * 公示期满的任务用 {@code PUBLICIZING}：和"客户提交争议（公示中 → 争议中）"在市场行锁上排队，争议先到就不结算，结算先开始就不再接受争议。
   *
   * @param expect 为空 = 待裁决、公示中、争议中都可以
   */
  void settle(long marketId, long winningOptionId, Instant actualStartAt, String resolvedBy, MarketStatus expect);
}
