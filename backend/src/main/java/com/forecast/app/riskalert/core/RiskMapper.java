package com.forecast.app.riskalert.core;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** riskalert_* 表的读写（forecast S10），见 resources/mapper/riskalert/RiskMapper.xml。只有 riskalert 模块使用。 */
@Mapper
public interface RiskMapper {

  // ---------- 线索和关联 ----------

  int insertSignal(@Param("kind") String kind, @Param("value") String value, @Param("userId") long userId, @Param("at") LocalDateTime at);

  /** 用过这个线索的客户（按客户号升序，最多 limit 个） */
  List<Long> signalUsers(@Param("kind") String kind, @Param("value") String value, @Param("limit") int limit);

  int insertLink(@Param("a") long userA, @Param("b") long userB, @Param("reason") String reason, @Param("evidence") String evidence,
      @Param("at") LocalDateTime at);

  class LinkRow {
    public long other;
    public String reason;
    public String evidence;
    public LocalDateTime firstSeenAt;
  }

  /** 和这个客户关联的客户（每个原因一行） */
  List<LinkRow> linksOf(@Param("userId") long userId);

  /** 和这个客户关联的客户号（去重） */
  List<Long> linkedUsers(@Param("userId") long userId);

  // ---------- 告警 ----------

  class AlertRow {
    public Long id;
    public String kind;
    public long userId;
    public Long marketId;
    public String refKey;
    public BigDecimal amount;
    public String detail;
    public String status;
    public String note;
    public Long handledBy;
    public LocalDateTime handledAt;
    public LocalDateTime createdAt;
  }

  /** 同一情况（kind、客户、ref_key）只记一次：返回 1 = 新记的 */
  int insertAlert(AlertRow row);

  List<AlertRow> alerts(@Param("kind") String kind, @Param("status") String status, @Param("userIds") Collection<Long> userIds,
      @Param("offset") int offset, @Param("limit") int limit);

  long countAlerts(@Param("kind") String kind, @Param("status") String status, @Param("userIds") Collection<Long> userIds);

  class CountRow {
    public String kind;
    public String status;
    public long n;
  }

  List<CountRow> countByKindStatus();

  AlertRow lockAlert(@Param("id") long id);

  AlertRow alert(@Param("id") long id);

  int setStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to, @Param("note") String note, @Param("by") long adminId,
      @Param("at") LocalDateTime at);

  long openAlertsOf(@Param("userId") long userId, @Param("kinds") Collection<String> kinds, @Param("since") LocalDateTime since);

  // ---------- 扫描进度 ----------

  class CursorRow {
    public String name;
    public long lastId;
  }

  CursorRow cursor(@Param("name") String name);

  int moveCursor(@Param("name") String name, @Param("lastId") long lastId);
}
