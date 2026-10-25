package com.forecast.app.resolve.api;

import java.time.Instant;

/**
 * 裁决结果定了以后结算（forecast S6）。<b>由 settle 模块实现</b>（{@code settle/run/SettleResultHandler} → {@code SettleService.settle}），
 * resolve 不依赖 settle 的内部类（技术方案第 2.3 节依赖方向：resolve ← settle）。
 * <p>同一市场重复调用（含并发、崩溃后续跑）只结算一次（settle 的三道防重复）。<b>不能在事务里调用</b>（结算自己开事务、按固定顺序加锁）。
 */
public interface ResultHandler {

  /**
   * @param winningOptionId 赢的选项（内部 id）
   * @param actualStartAt 实际开赛时间（E14），加密市场为空
   * @param resolvedBy 谁给的结果：{@code AUTO}（自动裁决）或管理员 id
   */
  void settle(long marketId, long winningOptionId, Instant actualStartAt, String resolvedBy);
}
