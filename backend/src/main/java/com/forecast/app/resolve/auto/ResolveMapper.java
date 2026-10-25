package com.forecast.app.resolve.auto;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * resolve 模块的 SQL（resources/mapper/resolve/ResolveMapper.xml，forecast S6）。只有 resolve 模块能用。
 * {@code resolve_source_fetch}、{@code resolve_record} 只有 INSERT（只增不改，ResolveAppendOnlyTest 扫描）；进度表的改动都是带旧状态（和版本号）的条件更新。
 */
@Mapper
interface ResolveMapper {

  /** 市场进入待裁决时建（重复调用没有效果：主键 market_id，INSERT IGNORE） */
  int insertTaskIgnore(ResolveRows.Task t);

  ResolveRows.Task task(@Param("marketId") long marketId);

  /** 到时间该处理的（等待取数、正在结算的） */
  List<Long> dueTasks(@Param("now") LocalDateTime now, @Param("limit") int limit);

  /** 这一轮没定下来：次数 + 1、下一次的时间 */
  int retryLater(@Param("marketId") long marketId, @Param("version") int version, @Param("next") LocalDateTime next, @Param("reason") String reason,
      @Param("now") LocalDateTime now);

  /** 结果定了：PENDING → RESOLVING（记下结果；结算失败时 next 之后续跑） */
  int toResolving(@Param("marketId") long marketId, @Param("version") int version, @Param("optionId") long optionId, @Param("next") LocalDateTime next,
      @Param("reason") String reason, @Param("now") LocalDateTime now);

  /** 结算已经触发：RESOLVING → RESOLVED */
  int toResolved(@Param("marketId") long marketId, @Param("reason") String reason, @Param("now") LocalDateTime now);

  /** 结算失败：RESOLVING 的下一次时间往后推 */
  int resolvingLater(@Param("marketId") long marketId, @Param("next") LocalDateTime next, @Param("reason") String reason, @Param("now") LocalDateTime now);

  /** 转人工：PENDING → MANUAL */
  int toManual(@Param("marketId") long marketId, @Param("version") int version, @Param("reason") String reason, @Param("now") LocalDateTime now);

  /** 市场已不在待裁决：PENDING / RESOLVING → CLOSED */
  int close(@Param("marketId") long marketId, @Param("reason") String reason, @Param("now") LocalDateTime now);

  List<ResolveRows.Task> manualTasks(@Param("offset") int offset, @Param("limit") int limit);

  long countManual();

  int insertRecord(ResolveRows.Record r);

  List<ResolveRows.Record> records(@Param("marketId") long marketId);

  ResolveRows.Record latestRecord(@Param("marketId") long marketId, @Param("kind") String kind);

  int insertFetch(ResolveRows.Fetch f);

  List<ResolveRows.Fetch> fetches(@Param("marketId") long marketId, @Param("limit") int limit);

  List<ResolveRows.Fetch> fetchesByIds(@Param("ids") List<Long> ids);
}
