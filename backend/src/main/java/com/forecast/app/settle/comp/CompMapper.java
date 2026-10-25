package com.forecast.app.settle.comp;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 补偿和结算报表的 SQL（resources/mapper/settle/CompMapper.xml，forecast S7）。只有 settle 模块能用。
 * {@code settle_compensation_item} 只有 INSERT；{@code settle_compensation} 没有 UPDATE、DELETE（SettleAppendOnlyTest 扫描）。
 */
@Mapper
interface CompMapper {

  int insertComp(CompRows.Comp c);

  int insertItems(@Param("items") List<CompRows.Item> items);

  CompRows.Comp compByMarket(@Param("marketId") long marketId);

  CompRows.Comp compByPublicNo(@Param("publicNo") long publicNo);

  List<CompRows.Item> items(@Param("compensationId") long compensationId);

  List<CompRows.Comp> listComps(@Param("offset") int offset, @Param("limit") int limit);

  long countComps();

  /** 这个市场的结算单（PAYOUT），没有为空 */
  CompRows.Run payoutRun(@Param("marketId") long marketId);

  /** 一张结算单的全部明细（持仓、客户、选项、本金、派彩） */
  List<CompRows.Settled> settled(@Param("runId") long runId);

  /** 报表：每天完成的结算单的抽成和尾差（按完成时间） */
  List<CompRows.Day> revenueByDay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

  /** 报表：每天的补偿合计和张数（按确认时间） */
  List<CompRows.Day> compensationByDay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
