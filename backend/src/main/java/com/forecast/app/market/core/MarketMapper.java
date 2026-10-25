package com.forecast.app.market.core;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * market 模块的 SQL，见 resources/mapper/market/MarketMapper.xml。只有 market 模块能用。
 * 题目、选项、时间、裁决来源的修改语句都带 {@code status = 'DRAFT'}（上架后不能改，AC-12）；状态跳转都带旧状态（条件更新）。
 * {@code market_status_log}、{@code market_clarification}、{@code market_fee_change} 只有 INSERT（只增不改）。
 */
@Mapper
interface MarketMapper {

  int insertMarket(MarketRows.Market m);

  /** 编辑草稿：只改草稿（status = DRAFT、没删除），返回改到的行数 */
  int updateDraft(MarketRows.Market m);

  int softDeleteDraft(@Param("id") long id, @Param("now") LocalDateTime now);

  int insertOption(MarketRows.Option o);

  /** 删掉草稿的全部选项（编辑草稿时整体替换）；不是草稿时删不到 */
  int deleteDraftOptions(@Param("marketId") long marketId);

  MarketRows.Market findByPublicNo(@Param("publicNo") long publicNo);

  MarketRows.Market findById(@Param("id") long id);

  /** 一批市场（按内部 id；删除的草稿也在内，调用方看状态） */
  List<MarketRows.Market> findByIds(@Param("ids") Collection<Long> ids);

  /** 一批选项（按内部 id） */
  List<MarketRows.Option> optionsByIds(@Param("ids") Collection<Long> ids);

  /** 排他锁（后台改状态、改费率、截止任务） */
  MarketRows.Market lockById(@Param("id") long id);

  /** 按对外编号锁市场行（排他）；不存在时为空 */
  MarketRows.Market lockByPublicNo(@Param("publicNo") long publicNo);

  /** 写结果（结算开始前），返回 0 = 结算已经开始 */
  int setResult(@Param("id") long id, @Param("winningOptionId") long winningOptionId, @Param("actualStartAt") LocalDateTime actualStartAt,
      @Param("resolvedBy") String resolvedBy, @Param("now") LocalDateTime now);

  /** 结算阶段 from → to（条件更新） */
  int casSettlePhase(@Param("id") long id, @Param("from") String from, @Param("to") String to, @Param("now") LocalDateTime now);

  /** 共享锁（下注前的截止屏障） */
  MarketRows.Market lockForShareByPublicNo(@Param("publicNo") long publicNo);

  List<MarketRows.Option> options(@Param("marketId") long marketId);

  List<MarketRows.Option> optionsOf(@Param("marketIds") Collection<Long> marketIds);

  MarketRows.Option option(@Param("marketId") long marketId, @Param("publicNo") long publicNo);

  /**
   * 带旧状态的条件更新。listedBy、cancelReason、voidReason 不为空时一起写。返回 1 = 跳转成功，0 = 状态已经不是 from（别人改过）。
   */
  int transition(@Param("id") long id, @Param("from") String from, @Param("to") String to, @Param("now") LocalDateTime now,
      @Param("listedBy") Long listedBy, @Param("cancelReason") String cancelReason, @Param("voidReason") String voidReason);

  int insertStatusLog(MarketRows.StatusLog l);

  List<MarketRows.StatusLog> statusLog(@Param("marketId") long marketId);

  int setSubmitted(@Param("id") long id, @Param("submitted") boolean submitted, @Param("now") LocalDateTime now);

  /** 改费率：结算开始前（settle_phase = NONE）、状态不是终点 */
  int updateFee(@Param("id") long id, @Param("rate") BigDecimal rate, @Param("now") LocalDateTime now);

  int insertFeeChange(MarketRows.FeeChange c);

  List<MarketRows.FeeChange> feeChanges(@Param("marketId") long marketId);

  int insertClarification(MarketRows.Clarification c);

  List<MarketRows.Clarification> clarifications(@Param("marketId") long marketId);

  /** 提前截止：只能提前（新时间早于原截止时间、晚于开放时间），只在已上架 / 已暂停时 */
  int advanceClose(@Param("id") long id, @Param("closeAt") LocalDateTime closeAt, @Param("now") LocalDateTime now);

  /** 到了截止时间、还在已上架 / 已暂停的市场（截止任务） */
  List<Long> dueToClose(@Param("now") LocalDateTime now, @Param("limit") int limit);

  /** 截止下注、事件已结束的市场（事件结束任务） */
  List<Long> dueToEnd(@Param("now") LocalDateTime now, @Param("limit") int limit);

  List<MarketRows.Market> adminList(@Param("status") String status, @Param("category") String category, @Param("submitted") Boolean submitted,
      @Param("keyword") String keyword, @Param("no") Long no, @Param("offset") int offset, @Param("limit") int limit);

  long adminCount(@Param("status") String status, @Param("category") String category, @Param("submitted") Boolean submitted,
      @Param("keyword") String keyword, @Param("no") Long no);

  /** 客户端列表：只列 statuses 里的状态（不含草稿），sort = closeAt（截止时间先到的在前）/ newest（新上架的在前） */
  List<MarketRows.Market> clientList(@Param("statuses") Collection<String> statuses, @Param("category") String category, @Param("sort") String sort,
      @Param("offset") int offset, @Param("limit") int limit);

  long clientCount(@Param("statuses") Collection<String> statuses, @Param("category") String category);

  /** 自动生成模板在 [from, to] 里已经生成的市场（按窗口开始时间） */
  List<MarketRows.Market> generated(@Param("autogenId") long autogenId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
