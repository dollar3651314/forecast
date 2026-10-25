package com.forecast.app.pool.bet;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 彩池（forecast S4）。只有 pool 模块使用（表名以 pool_ 开头），SQL 在 {@code mapper/pool/PoolMapper.xml}。
 * 下注事务里的语句按第 4.2 节的加锁顺序调用（见 {@link BetService}）；pool_market 只在市场状态跳转的事务里改。
 */
@Mapper
interface PoolMapper {

  // ---------- 上架、截止、状态副本（market 的事务里，市场行已加排他锁） ----------

  /** 登记市场（已有时不变） */
  int insertMarketIfAbsent(@Param("marketId") long marketId, @Param("status") String status, @Param("now") LocalDateTime now);

  /** 建一个选项的奖池行（已有时不变） */
  int insertTotalIfAbsent(@Param("optionId") long optionId, @Param("marketId") long marketId, @Param("optionNo") long optionNo, @Param("seq") int seq,
      @Param("now") LocalDateTime now);

  /** 市场已经在彩池登记了吗（1 / null） */
  Integer marketOpened(@Param("marketId") long marketId);

  int updateMarketStatus(@Param("marketId") long marketId, @Param("status") String status, @Param("now") LocalDateTime now);

  /** 截止：定格各选项的奖池（只定格一次） */
  int freezeTotals(@Param("marketId") long marketId, @Param("now") LocalDateTime now);

  int markFrozen(@Param("marketId") long marketId, @Param("now") LocalDateTime now);

  /** 有有效下注的选项个数（截止时数 E3、E4） */
  int countActiveOptions(@Param("marketId") long marketId);

  /** 在这个市场有有效下注的客户 */
  List<Long> bettors(@Param("marketId") long marketId);

  // ---------- 下注事务 ----------

  PoolRows.Bet findBetByReq(@Param("userId") long userId, @Param("clientReqId") String clientReqId);

  int insertBet(PoolRows.Bet b);

  PoolRows.Position findPosition(@Param("marketId") long marketId, @Param("optionId") long optionId, @Param("userId") long userId);

  int insertPosition(PoolRows.Position p);

  int addToPosition(@Param("id") long id, @Param("amount") BigDecimal amount, @Param("now") LocalDateTime now);

  /** 这个客户在这个市场所有选项上的有效下注合计（单客户单市场上限，在账户行锁之内读） */
  BigDecimal sumUserMarket(@Param("userId") long userId, @Param("marketId") long marketId);

  /** 选项奖池原子加（下注事务的最后一步）；已定格的不加，返回 0 */
  int addToTotal(@Param("optionId") long optionId, @Param("amount") BigDecimal amount, @Param("newBettor") int newBettor, @Param("now") LocalDateTime now);

  // ---------- 读 ----------

  /** 一个市场的各选项奖池，按选项顺序 */
  List<PoolRows.OptionTotal> totals(@Param("marketId") long marketId);

  List<PoolRows.OptionTotal> totalsOf(@Param("marketIds") Collection<Long> marketIds);

  /** 每个市场有持仓的客户数（不重复） */
  List<PoolRows.MarketCount> bettorCounts(@Param("marketIds") Collection<Long> marketIds);

  /** 一个客户在一批市场的持仓 */
  List<PoolRows.Position> positionsOf(@Param("userId") long userId, @Param("marketIds") Collection<Long> marketIds);

  /** 我的下注（tab 为空表示全部），最新的在前 */
  List<PoolRows.BetLine> myBets(@Param("userId") long userId, @Param("tab") String tab, @Param("offset") int offset, @Param("limit") int limit);

  long countMyBets(@Param("userId") long userId, @Param("tab") String tab);

  List<PoolRows.TabSum> mySummary(@Param("userId") long userId);

  PoolRows.BetLine betLine(@Param("userId") long userId, @Param("publicNo") long publicNo);

  /** 后台：一个市场的下注明细，按收到时间倒序 */
  List<PoolRows.BetLine> marketBets(@Param("marketId") long marketId, @Param("optionId") Long optionId, @Param("userId") Long userId,
      @Param("status") String status, @Param("offset") int offset, @Param("limit") int limit);

  long countMarketBets(@Param("marketId") long marketId, @Param("optionId") Long optionId, @Param("userId") Long userId, @Param("status") String status);

  /** 提现自动审批 R8：从 since 起的有效下注本金（退回的、取消 / 作废市场里的不算） */
  BigDecimal effectiveStakeSince(@Param("userId") long userId, @Param("since") LocalDateTime since);

  /** 这个客户在这个市场的有效持仓个数（S7：能不能提交争议） */
  int hasPosition(@Param("userId") long userId, @Param("marketId") long marketId);

  /** 一个市场有效下注的合计（对账、测试用） */
  BigDecimal sumActiveBets(@Param("marketId") long marketId);

  // ---------- 结算、退款（PoolSettleApi，forecast S5；settle 的事务里调用） ----------

  /** 金额 &gt; 0 的持仓，按 user_id、id */
  List<PoolRows.Position> settlePositions(@Param("marketId") long marketId);

  /** 有效下注：from 不为空时只要收到时间 ≥ from 的；userIds 不为空时只要这些客户的 */
  List<PoolRows.Bet> activeBets(@Param("marketId") long marketId, @Param("from") LocalDateTime from, @Param("userIds") Collection<Long> userIds);

  BigDecimal sumPositions(@Param("marketId") long marketId);

  /** 状态副本还不是终点的市场 */
  List<Long> openMarkets();

  /** 锁住这些还有效的下注（存在的行，只加行锁），返回锁到的 */
  List<PoolRows.Bet> lockActiveBets(@Param("marketId") long marketId, @Param("ids") Collection<Long> ids);

  int markRefunded(@Param("ids") Collection<Long> ids, @Param("reason") String reason, @Param("at") LocalDateTime at);

  /** 持仓扣掉这些下注（同一客户同一选项合计） */
  int subtractFromPositions(@Param("marketId") long marketId, @Param("ids") Collection<Long> ids, @Param("now") LocalDateTime now);

  /** 选项奖池扣掉这些下注（截止后定格的 final_amount 一起扣） */
  int subtractFromTotals(@Param("ids") Collection<Long> ids, @Param("now") LocalDateTime now);

  /** 按剩下的持仓重算这些选项的人数（定格的人数一起） */
  int recountBettors(@Param("marketId") long marketId, @Param("optionIds") Collection<Long> optionIds);
}
