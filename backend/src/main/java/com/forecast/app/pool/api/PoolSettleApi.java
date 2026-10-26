package com.forecast.app.pool.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 给 settle 模块的彩池接口（forecast S5，技术方案/v1-架构方案.md 第 4.3 节）：结算要读的奖池、持仓、有效下注，以及退款时把下注标成已退回。
 * <p>都在 settle 的事务里调用。读的方法是普通读：调用方已经锁了市场行（排他），这个市场没有进行中的下注；退款批次里调用时市场已经不能下注
 * （已取消 / 已作废，或在截止后）。
 */
public interface PoolSettleApi {

  /** 一个选项的奖池：现在的金额、截止时定格的金额（没截止为空）、有持仓的人数 */
  record OptionPool(long optionId, BigDecimal amount, BigDecimal finalAmount, int bettors) {}

  /** 一个持仓（同一客户同一选项的有效下注合计） */
  record Position(long id, long userId, long optionId, BigDecimal amount) {}

  /** 一笔有效下注 */
  record Bet(long id, long publicNo, long userId, long optionId, BigDecimal amount, LocalDateTime receivedAt) {}

  /** 各选项的奖池（按选项顺序） */
  List<OptionPool> pools(long marketId);

  /** 金额 &gt; 0 的持仓，按 user_id、id 升序（结算按这个顺序锁账户） */
  List<Position> positions(long marketId);

  /** 有效下注（ACTIVE），按 user_id、id 升序 */
  List<Bet> activeBets(long marketId);

  /** 收到时间 ≥ from 的有效下注（E14 晚于实际开赛、提前截止后新截止时间之后的），按 user_id、id 升序 */
  List<Bet> activeBetsReceivedFrom(long marketId, LocalDateTime from);

  /** 这些客户在这个市场的有效下注（E15 员工），按 user_id、id 升序 */
  List<Bet> activeBetsOfUsers(long marketId, Collection<Long> userIds);

  /** 这个客户有有效下注的市场，按市场 id 升序（forecast S10：员工补登时退回已有的下注，E15） */
  List<Long> marketsWithActiveBets(long userId);

  /** 还没到终点状态的市场（已上架到争议中）：每日彩池对账逐个检查"Σ有效下注 = Σ奖池 = Σ持仓 = 账本锁定" */
  List<Long> openMarkets();

  /** Σ有效下注 */
  BigDecimal sumActiveBets(long marketId);

  /** Σ持仓 */
  BigDecimal sumPositions(long marketId);

  /**
   * 把这些下注标成已退回（{@code pool_bet.status} ACTIVE → REFUNDED，写原因和时间），同一事务里同步扣掉持仓和选项奖池
   * （截止后已定格的奖池 {@code final_amount} 一起扣，人数按剩下的持仓重算），"Σ有效下注 = Σ奖池 = Σ持仓"在退款前后都成立。
   * 提交后推送一次新的奖池。
   *
   * @param reason {@link PoolApi#REFUND_REASONS} 之一
   * @return 这次真的从 ACTIVE 改成 REFUNDED 的下注 id（已经退过的不在里面：第三道防重复，调用方只给这些记账）
   */
  List<Long> markRefunded(long marketId, Collection<Long> betIds, String reason, LocalDateTime at);
}
