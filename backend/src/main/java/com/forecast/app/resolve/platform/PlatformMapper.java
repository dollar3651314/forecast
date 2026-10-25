package com.forecast.app.resolve.platform;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 平台裁决的 SQL（resources/mapper/resolve/PlatformMapper.xml，forecast S7）。只有 resolve 模块能用。
 * {@code resolve_record}、{@code resolve_attachment} 只有 INSERT（只增不改，ResolveAppendOnlyTest 扫描）；{@code resolve_case}、{@code resolve_dispute} 的改动都是带条件的更新。
 */
@Mapper
interface PlatformMapper {

  // ---------- 进度 resolve_case ----------

  /** 市场进入待裁决（或自动裁决转人工）时建；主键是市场 id，重复调用没有效果（INSERT IGNORE，项目地图约定 34） */
  int insertCaseIgnore(PlatformRows.Case c);

  PlatformRows.Case findCase(@Param("marketId") long marketId);

  /** 加锁读（在市场行锁之后） */
  PlatformRows.Case lockCase(@Param("marketId") long marketId);

  /** 阶段跟着市场状态（market 的状态跳转事务里）；进入 CLOSED 时清掉待确认的提议和等待 */
  int syncStage(@Param("marketId") long marketId, @Param("stage") String stage, @Param("now") LocalDateTime now);

  int setProposal(@Param("marketId") long marketId, @Param("proposalId") long proposalId, @Param("now") LocalDateTime now,
      @Param("confirmDueAt") LocalDateTime confirmDueAt);

  int clearProposal(@Param("marketId") long marketId, @Param("proposalId") long proposalId, @Param("now") LocalDateTime now);

  int confirmCase(@Param("marketId") long marketId, @Param("optionId") long optionId, @Param("actualStartAt") LocalDateTime actualStartAt,
      @Param("resolvedBy") String resolvedBy, @Param("recordId") long recordId, @Param("deadline") LocalDateTime deadline, @Param("now") LocalDateTime now);

  int setHold(@Param("marketId") long marketId, @Param("kind") String kind, @Param("until") LocalDateTime until, @Param("now") LocalDateTime now);

  int clearHold(@Param("marketId") long marketId, @Param("proposeDueAt") LocalDateTime proposeDueAt, @Param("now") LocalDateTime now);

  /** 第一个争议：写争议处理时限（只写一次） */
  int disputeOpened(@Param("marketId") long marketId, @Param("dueAt") LocalDateTime dueAt, @Param("now") LocalDateTime now);

  int setDecision(@Param("marketId") long marketId, @Param("decision") String decision, @Param("recordId") long recordId,
      @Param("optionId") Long optionId, @Param("resolvedBy") String resolvedBy, @Param("now") LocalDateTime now);

  /** 已经交给结算（只记一次） */
  int markSettleRequested(@Param("marketId") long marketId, @Param("now") LocalDateTime now);

  /** 超时提醒已发（kind：PROPOSE / CONFIRM / DISPUTE；只记一次） */
  int markAlerted(@Param("marketId") long marketId, @Param("kind") String kind, @Param("now") LocalDateTime now);

  List<PlatformRows.Case> listCases(@Param("tab") String tab, @Param("now") LocalDateTime now, @Param("offset") int offset, @Param("limit") int limit);

  long countCases(@Param("tab") String tab, @Param("now") LocalDateTime now);

  /** 该交给结算的：公示期满（还在公示中），或争议结论是维持 / 改判；还没交过 */
  List<Long> settleDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

  /** E6 / E7 等到时间还没解除的 */
  List<Long> holdsDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

  /** 超时还没提醒过的（kind：PROPOSE / CONFIRM / DISPUTE） */
  List<PlatformRows.Case> overdue(@Param("kind") String kind, @Param("now") LocalDateTime now, @Param("limit") int limit);

  // ---------- 裁决记录 resolve_record（只增不改） ----------

  int insertRecord(PlatformRows.Record r);

  PlatformRows.Record record(@Param("id") long id);

  List<PlatformRows.Record> records(@Param("marketId") long marketId);

  List<PlatformRows.Record> recordsByIds(@Param("ids") Collection<Long> ids);

  // ---------- 争议 resolve_dispute ----------

  int insertDispute(PlatformRows.Dispute d);

  PlatformRows.Dispute disputeOfUser(@Param("marketId") long marketId, @Param("userId") long userId);

  List<PlatformRows.Dispute> disputesOf(@Param("marketId") long marketId);

  List<PlatformRows.Dispute> openDisputes(@Param("marketId") long marketId);

  int countDisputes(@Param("marketId") long marketId);

  List<OpenCount> openCounts(@Param("marketIds") Collection<Long> marketIds);

  /** 一个市场待处理的争议一起给结论（只改 OPEN 的） */
  int decideDisputes(@Param("marketId") long marketId, @Param("status") String status, @Param("note") String note, @Param("by") Long by,
      @Param("now") LocalDateTime now);

  List<PlatformRows.Dispute> listDisputes(@Param("status") String status, @Param("offset") int offset, @Param("limit") int limit);

  long countAllDisputes(@Param("status") String status);

  /** 每个市场待处理争议的个数 */
  class OpenCount {
    public long marketId;
    public int n;
  }

  // ---------- 存档截图 resolve_attachment（只增不改） ----------

  int insertAttachment(PlatformRows.Attachment a);

  PlatformRows.Attachment attachment(@Param("publicNo") long publicNo);

  List<PlatformRows.Attachment> attachmentsOf(@Param("marketId") long marketId);
}
