package com.forecast.app.content.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** content_* 表的读写（forecast S15），见 resources/mapper/content/ContentMapper.xml。只有 content 模块使用。 */
@Mapper
public interface ContentMapper {

  // ---------- 设置 ----------
  List<ContentRows.Setting> settings();

  int updateSetting(@Param("key") String key, @Param("value") String value, @Param("adminId") Long adminId, @Param("now") LocalDateTime now);

  // ---------- 词表 ----------
  List<ContentRows.Word> words(@Param("kind") String kind);

  int insertWord(@Param("kind") String kind, @Param("word") String word, @Param("adminId") long adminId, @Param("now") LocalDateTime now);

  ContentRows.Word word(@Param("id") long id);

  int deleteWord(@Param("id") long id);

  // ---------- 模板 ----------
  List<ContentRows.Template> templates();

  ContentRows.Template template(@Param("code") String code);

  int updateTemplate(ContentRows.Template t);

  // ---------- 经济日历 ----------
  int insertEcon(ContentRows.Econ e);

  ContentRows.Econ econ(@Param("id") long id);

  List<ContentRows.Econ> econs(@Param("from") LocalDateTime from, @Param("limit") int limit);

  /** 到了该生成草稿的（SCHEDULED、发布时间 ≤ due） */
  List<ContentRows.Econ> econDue(@Param("due") LocalDateTime due, @Param("limit") int limit);

  int updateEcon(ContentRows.Econ e);

  /** 条件更新状态（带版本号） */
  int casEcon(@Param("id") long id, @Param("version") int version, @Param("status") String status, @Param("marketNo") Long marketNo,
      @Param("note") String note, @Param("now") LocalDateTime now);

  /** DRAFTING 卡住的退回 FAILED */
  int failStuckEcon(@Param("before") LocalDateTime before, @Param("now") LocalDateTime now);

  // ---------- 过滤日志 ----------
  int insertFilterIgnore(@Param("source") String source, @Param("ref") String ref, @Param("title") String title, @Param("reason") String reason,
      @Param("matched") String matched, @Param("now") LocalDateTime now);

  List<ContentRows.Filter> filters(@Param("limit") int limit);

  List<String> filteredRefs(@Param("source") String source, @Param("refs") Collection<String> refs);

  // ---------- 草稿 ----------
  int insertDraft(ContentRows.Draft d);

  ContentRows.Draft draft(@Param("draftNo") long draftNo);

  List<ContentRows.Draft> group(@Param("groupNo") long groupNo);

  List<ContentRows.Draft> drafts(@Param("status") String status, @Param("kind") String kind, @Param("lang") String lang, @Param("offset") int offset,
      @Param("limit") int limit);

  long countDrafts(@Param("status") String status, @Param("kind") String kind, @Param("lang") String lang);

  /** 起草完成（GENERATING → 结果） */
  int finishDraft(@Param("id") long id, @Param("status") String status, @Param("title") String title, @Param("body") String body,
      @Param("checks") String checks, @Param("checkOk") boolean checkOk, @Param("failReason") String failReason, @Param("aiCalls") int aiCalls,
      @Param("now") LocalDateTime now);

  /** 编辑（带版本号；PENDING / FAILED） */
  int editDraft(@Param("id") long id, @Param("version") int version, @Param("title") String title, @Param("body") String body, @Param("checks") String checks,
      @Param("checkOk") boolean checkOk, @Param("adminId") long adminId, @Param("now") LocalDateTime now);

  /** 状态 from → to（带版本号） */
  int moveDraft(@Param("id") long id, @Param("version") int version, @Param("from") String from, @Param("to") String to, @Param("reason") String reason,
      @Param("adminId") Long adminId, @Param("now") LocalDateTime now);

  /** 过期：PENDING / FAILED / GENERATING 且 expires_at ≤ now → EXPIRED */
  int expireDrafts(@Param("now") LocalDateTime now);

  /** 起草卡住（进程退出等）：GENERATING 超过 stuckBefore 的标成失败 */
  int failStuck(@Param("stuckBefore") LocalDateTime stuckBefore, @Param("now") LocalDateTime now);

  long countByStatusSince(@Param("status") String status, @Param("since") LocalDateTime since);

  /** 这个市场今天（since 之后）有没有这类草稿 */
  long countMarketDrafts(@Param("marketNo") long marketNo, @Param("kind") String kind, @Param("since") LocalDateTime since);

  /** 这一周（since 之后）起草的文章组数（不含驳回、失败的） */
  long countArticleGroups(@Param("since") LocalDateTime since);

  List<ContentRows.Draft> publishedFor(@Param("marketNo") long marketNo, @Param("kind") String kind);

  // ---------- 页面 ----------
  int insertPageIgnore(ContentRows.Page p);

  ContentRows.Page page(@Param("slug") String slug);

  ContentRows.Page pageByMarket(@Param("marketNo") long marketNo);

  List<ContentRows.Page> pages(@Param("visibleOnly") boolean visibleOnly, @Param("limit") int limit);

  long countPagesOn(@Param("day") LocalDate day);

  int updatePageData(@Param("id") long id, @Param("data") String data, @Param("dataAt") LocalDateTime dataAt, @Param("now") LocalDateTime now);

  int updatePageIntro(@Param("id") long id, @Param("lang") String lang, @Param("intro") String intro, @Param("introTw") String introTw,
      @Param("now") LocalDateTime now);

  int updateArticle(@Param("id") long id, @Param("lang") String lang, @Param("title") String title, @Param("titleTw") String titleTw, @Param("body") String body,
      @Param("bodyTw") String bodyTw, @Param("now") LocalDateTime now);

  int setPageStatus(@Param("id") long id, @Param("status") String status, @Param("now") LocalDateTime now);

  // ---------- 日志 ----------
  int insertLog(ContentRows.Log l);

  List<ContentRows.Log> logs(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("action") String action, @Param("offset") int offset,
      @Param("limit") int limit);

  long countLogs(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("action") String action);

  long countLogsSince(@Param("action") String action, @Param("since") LocalDateTime since);

  // ---------- Gemini 用量 ----------
  int ensureAiDay(@Param("day") LocalDate day);

  /** 计一次调用：当天总数 &lt; cap 才加（cap 已按用途算好）；返回改到的行数 */
  int reserveAi(@Param("day") LocalDate day, @Param("cap") int cap, @Param("score") boolean score);

  int aiSuccess(@Param("day") LocalDate day, @Param("tokensIn") long tokensIn, @Param("tokensOut") long tokensOut);

  int aiFailure(@Param("day") LocalDate day);

  /** 记下"今天已经发过这种告警"：没发过才改到 1 行 */
  int markAlert(@Param("day") LocalDate day, @Param("kind") String kind);

  ContentRows.AiDay aiDay(@Param("day") LocalDate day);

  List<ContentRows.AiDay> aiDays(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
