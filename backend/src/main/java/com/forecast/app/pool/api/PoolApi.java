package com.forecast.app.pool.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 给其他模块用的彩池查询（forecast S4）。下注本身只从客户端接口进来（{@code pool/web/BetController}），不对其他模块开放。
 * <p>S5（settle）会在这里加结算要用的"截止时定格的奖池""持仓"和退款时把下注标成已退回的操作；S4 先把数据准备好：
 * {@code pool_bet.status}（ACTIVE / REFUNDED）、{@code refund_reason}（{@link #REFUND_REASONS}）、{@code refunded_at}。
 */
public interface PoolApi {

  /** 下注退回的原因代码（S5 起写入 {@code pool_bet.refund_reason}）：取消、作废、晚于实际开赛（E14）、员工下注（E15） */
  java.util.List<String> REFUND_REASONS = java.util.List.of("CANCELLED", "VOIDED", "LATE_BET", "STAFF");

  /**
   * 提现自动审批 R8（需求第 8.2 节，S10 改造规则时用）：从 since 起（按服务器收到下注的时间）的<b>有效下注本金</b>合计。
   * 退回的下注（取消、作废、E14、E15）不算；已经取消、作废但还没退款完的市场里的下注也不算。
   */
  BigDecimal effectiveStakeSince(long userId, LocalDateTime since);
}
