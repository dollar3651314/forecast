package com.forecast.app.resolve.sports;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 体育赛程和自动裁决的 SQL（resources/mapper/resolve/SportsMapper.xml，forecast S8）。只有 resolve 模块能用。
 * {@code resolve_record} 只有 INSERT（只增不改，ResolveAppendOnlyTest 扫描）；进度表、赛程表的改动都是带旧状态或版本号的条件更新。
 */
@Mapper
interface SportsMapper {

  // ---------- 赛程 ----------

  int insertFixture(SportsRows.Fixture f);

  SportsRows.Fixture fixtureByPrimary(@Param("source") String source, @Param("ref") String ref);

  SportsRows.Fixture fixture(@Param("id") long id);

  SportsRows.Fixture fixtureByNo(@Param("publicNo") long publicNo);

  SportsRows.Fixture lockFixture(@Param("id") long id);

  /** 同步到的新信息（队名、时间、状态） */
  int updateFromSync(SportsRows.Fixture f);

  int setCandidates(@Param("id") long id, @Param("candidates") String candidates, @Param("error") String error, @Param("now") LocalDateTime now);

  int confirmMapping(@Param("id") long id, @Param("source") String source, @Param("ref") String ref, @Param("swapped") boolean swapped,
      @Param("home") String home, @Param("away") String away, @Param("kickoff") LocalDateTime kickoff, @Param("by") long by, @Param("now") LocalDateTime now);

  int clearMapping(@Param("id") long id, @Param("now") LocalDateTime now);

  /** 开赛时间变化的提醒：同一个新时间只发一次（更新到 1 行才发） */
  int markKickoffAlerted(@Param("id") long id, @Param("kickoff") LocalDateTime kickoff, @Param("now") LocalDateTime now);

  List<SportsRows.Fixture> fixtures(@Param("sport") String sport, @Param("mapping") String mapping, @Param("from") LocalDateTime from,
      @Param("to") LocalDateTime to, @Param("offset") int offset, @Param("limit") int limit);

  long countFixtures(@Param("sport") String sport, @Param("mapping") String mapping, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

  /** 还没确认对应、还没找过候选的（开赛在 now 之后），按开赛时间 */
  List<SportsRows.Fixture> needCandidates(@Param("now") LocalDateTime now, @Param("limit") int limit);

  // ---------- 从赛程生成的市场 ----------

  int insertFixtureMarket(SportsRows.FixtureMarket m);

  List<SportsRows.FixtureMarket> fixtureMarkets(@Param("fixtureId") long fixtureId);

  SportsRows.FixtureMarket fixtureMarket(@Param("marketId") long marketId);

  // ---------- 自动裁决的进度 ----------

  int insertTaskIgnore(SportsRows.Task t);

  SportsRows.Task task(@Param("marketId") long marketId);

  List<Long> dueTasks(@Param("now") LocalDateTime now, @Param("limit") int limit);

  int retryLater(@Param("marketId") long marketId, @Param("version") int version, @Param("next") LocalDateTime next, @Param("reason") String reason,
      @Param("now") LocalDateTime now);

  /** 第一次看到主源完赛：记下时间，等 30 分钟 */
  int finishedSeen(@Param("marketId") long marketId, @Param("version") int version, @Param("seen") LocalDateTime seen,
      @Param("settleAfter") LocalDateTime settleAfter, @Param("giveUp") LocalDateTime giveUp, @Param("reason") String reason, @Param("now") LocalDateTime now);

  int toResolving(@Param("marketId") long marketId, @Param("version") int version, @Param("optionId") long optionId,
      @Param("actualStartAt") LocalDateTime actualStartAt, @Param("next") LocalDateTime next, @Param("reason") String reason, @Param("now") LocalDateTime now);

  int toResolved(@Param("marketId") long marketId, @Param("reason") String reason, @Param("now") LocalDateTime now);

  int resolvingLater(@Param("marketId") long marketId, @Param("next") LocalDateTime next, @Param("reason") String reason, @Param("now") LocalDateTime now);

  int toManual(@Param("marketId") long marketId, @Param("version") int version, @Param("reason") String reason, @Param("now") LocalDateTime now);

  int close(@Param("marketId") long marketId, @Param("reason") String reason, @Param("now") LocalDateTime now);

  // ---------- 规则文本模板 ----------

  List<SportsRows.Template> templates();

  SportsRows.Template template(@Param("kind") String kind);

  int updateTemplate(@Param("t") SportsRows.Template t, @Param("oldVersion") int oldVersion);

  // ---------- 裁决记录（只增） ----------

  int insertRecord(SportsRows.Record r);

  /** 判断分界线是否重复用：这场比赛这个玩法这条线已有的市场 */
  List<Long> marketsOf(@Param("fixtureId") long fixtureId, @Param("play") String play, @Param("line") BigDecimal line);
}
