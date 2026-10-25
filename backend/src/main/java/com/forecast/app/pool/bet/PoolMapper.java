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

  /** 一个市场有效下注的合计（对账、测试用） */
  BigDecimal sumActiveBets(@Param("marketId") long marketId);
}
