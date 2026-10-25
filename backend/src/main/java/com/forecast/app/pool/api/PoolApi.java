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

  /** 这个市场有有效持仓的客户（按 user_id 升序；forecast S7：公示 #10、改判 #16 发给他们） */
  java.util.List<Long> bettors(long marketId);

  /** 这个客户在这个市场有没有有效持仓（forecast S7：只有在这个市场有下注的客户能提交争议，需求第 4.4 节、AC-32） */
  boolean hasPosition(long userId, long marketId);

  // ---------- forecast S10：风控（riskalert）和员工账户（E15）用 ----------

  /**
   * 一笔下注（风控扫描用）。
   *
   * @param poolAfter 下注后这个市场的奖池合计（含本笔，取下注时的快照 {@code pool_bet.snapshot}）
   */
  record BetSignal(long id, long publicNo, long userId, long marketId, long optionId, BigDecimal amount, LocalDateTime receivedAt, BigDecimal poolAfter) {}

  /**
   * id &gt; afterId 的下注（不论后来退没退回），按 id 升序，最多 limit 笔。
   *
   * @return 带写入时间 {@code createdAt}（= 收到的时间），风控按它判断事务是不是早已提交
   */
  java.util.List<BetSignal> betsAfter(long afterId, int limit);

  /** 这些客户在这个市场<b>有效下注</b>（没退回的）押过的选项：客户 → 选项 id；没下过注的客户不在结果里 */
  java.util.Map<Long, java.util.Set<Long>> optionsOf(long marketId, java.util.Collection<Long> userIds);

  /** 两个客户从 since 起（按收到下注的时间）都有有效下注的市场 */
  java.util.List<Long> commonMarkets(long userA, long userB, LocalDateTime since);

  /** 一笔有效下注（押中冷门的检查用） */
  record ActiveBet(long id, long publicNo, long userId, BigDecimal amount, LocalDateTime receivedAt) {}

  /** 这个市场这个选项上金额 ≥ minAmount 的有效下注，按 id 升序 */
  java.util.List<ActiveBet> activeBetsOn(long marketId, long optionId, BigDecimal minAmount);
}
