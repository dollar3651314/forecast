package com.forecast.app.settle.run;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 结算、退款、彩池对账（forecast S5）。只有 settle 模块使用（表名以 settle_ 开头），SQL 在 {@code mapper/settle/SettleMapper.xml}。
 * 明细的金额列写入后不改：这里没有改 stake / gross / fee / payout / amount 的语句（{@code SettleAppendOnlyTest}）。
 */
@Mapper
public interface SettleMapper {

  // ---------- 结算单 / 退款单 ----------

  int insertRun(SettleRows.Run r);

  SettleRows.Run runByMarket(@Param("marketId") long marketId, @Param("kind") String kind);

  SettleRows.Run runById(@Param("id") long id);

  SettleRows.Run runByPublicNo(@Param("publicNo") long publicNo);

  /** 锁住结算单（派彩批次、收尾、重试串行） */
  SettleRows.Run lockRun(@Param("id") long id);

  /** 派彩中的单（派彩任务每轮处理），旧的在前 */
  List<Long> payingRuns(@Param("limit") int limit);

  /** 状态 from → to（条件更新）；abnormalReason 只在进入 ABNORMAL 时写，doneAt 只在进入 DONE 时写 */
  int casRunStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to, @Param("abnormalReason") String abnormalReason,
      @Param("doneAt") LocalDateTime doneAt);

  /** 结算异常的单（计算时就没通过、还没有明细）重新计算后写入新的数字，进入派彩中 */
  int recalculated(SettleRows.Run r);

  List<SettleRows.Run> listRuns(@Param("status") String status, @Param("kind") String kind, @Param("offset") int offset, @Param("limit") int limit);

  long countRuns(@Param("status") String status, @Param("kind") String kind);

  // ---------- 结算明细 ----------

  int insertItems(@Param("items") List<SettleRows.Item> items);

  /** 下一批待派的明细：按 user_id、id，最多 limit 行；同一个客户的明细不跨批（见调用方） */
  List<SettleRows.Item> pendingItems(@Param("runId") long runId, @Param("limit") int limit);

  /** 这些客户还没派的明细（把一批补齐到客户边界） */
  List<SettleRows.Item> pendingItemsOfUsers(@Param("runId") long runId, @Param("userIds") Collection<Long> userIds);

  /** PENDING → DONE（条件更新，第二道防重复）；返回改了几行 */
  int markItemsDone(@Param("ids") Collection<Long> ids, @Param("now") LocalDateTime now);

  long countPendingItems(@Param("runId") long runId);

  /** 还没派的本金合计（派彩中的市场对账：账本里这个市场还锁着的 = 这个数） */
  BigDecimal pendingStake(@Param("runId") long runId);

  List<SettleRows.Item> items(@Param("runId") long runId, @Param("offset") int offset, @Param("limit") int limit);

  // ---------- 退款明细 ----------

  int insertRefunds(@Param("refunds") List<SettleRows.Refund> refunds);

  List<SettleRows.Refund> pendingRefunds(@Param("runId") long runId, @Param("limit") int limit);

  List<SettleRows.Refund> pendingRefundsOfUsers(@Param("runId") long runId, @Param("userIds") Collection<Long> userIds);

  /** 当场退的（E14、E15、提前截止）：插入后立即在同一事务里记账，按 id 取回 */
  List<SettleRows.Refund> refundsByBets(@Param("betIds") Collection<Long> betIds);

  int markRefundsDone(@Param("ids") Collection<Long> ids, @Param("now") LocalDateTime now);

  long countPendingRefunds(@Param("runId") long runId);

  BigDecimal pendingRefundAmount(@Param("runId") long runId);

  /** 这个市场已经退掉的金额合计（全部原因，对账用） */
  BigDecimal refundedAmount(@Param("marketId") long marketId);

  List<SettleRows.Refund> refundItems(@Param("runId") long runId, @Param("offset") int offset, @Param("limit") int limit);

  // ---------- 彩池对账差异 ----------

  /** 同一个对象同一天同一种差异只记一次（唯一键）；返回 1 = 新记的 */
  int insertIssue(SettleRows.Issue i);

  /** 这个对象这种差异还有没处理的（之前哪天发现的都算） */
  long openIssueCount(@Param("refKey") String refKey, @Param("kind") String kind);

  long openIssuesOfMarket(@Param("marketId") long marketId);

  long openIssues();

  List<SettleRows.Issue> listIssues(@Param("status") String status, @Param("offset") int offset, @Param("limit") int limit);

  long countIssues(@Param("status") String status);

  SettleRows.Issue issue(@Param("id") long id);

  int resolveIssue(@Param("id") long id, @Param("by") long by, @Param("note") String note, @Param("now") LocalDateTime now);

  /** 对账要看的已结算 / 已退款的单：最近完成的（since 之后）和还没完成的 */
  List<SettleRows.Run> runsForRecon(@Param("since") LocalDateTime since);
}
