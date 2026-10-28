package com.forecast.app.ledger.adjust;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 余额调整（forecast S17）的 SQL，见 resources/mapper/ledger/AdjustMapper.xml。只有 ledger 模块能用。
 * 表只增不改：这里没有 UPDATE、DELETE（LedgerAppendOnlyTest 检查）。
 */
@Mapper
interface AdjustMapper {

  // ---------- 测试入金（S17A） ----------

  class TestCreditRow {
    long id;
    long publicNo;
    long userId;
    BigDecimal amount;
    BigDecimal availableBefore;
    BigDecimal availableAfter;
    String reason;
    String requestId;
    long createdBy;
    LocalDateTime createdAt;
  }

  int insertTestCredit(TestCreditRow row);

  TestCreditRow testCreditByRequest(@Param("requestId") String requestId);

  TestCreditRow testCreditByNo(@Param("publicNo") long publicNo);

  /** 报表：[start, end) 每天的测试入金合计和笔数（按 UTC 日期） */
  class DayRow {
    java.time.LocalDate day;
    BigDecimal amount;
    int n;
  }

  List<DayRow> testCreditByDay(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

  // ---------- 人工调账（S17B） ----------

  class AdjustRow {
    long id;
    long publicNo;
    long userId;
    String direction;
    String category;
    BigDecimal amount;
    BigDecimal availableBefore;
    BigDecimal availableAfter;
    String refNo;
    String reason;
    boolean overLimit;
    String requestId;
    long createdBy;
    LocalDateTime createdAt;
  }

  int insertAdjust(AdjustRow row);

  AdjustRow adjustByRequest(@Param("requestId") String requestId);

  List<AdjustRow> listAdjusts(@Param("userId") Long userId, @Param("direction") String direction, @Param("category") String category,
      @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("offset") int offset, @Param("limit") int limit);

  long countAdjusts(@Param("userId") Long userId, @Param("direction") String direction, @Param("category") String category,
      @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

  /** 报表：[start, end) 每天的人工加款、人工扣款合计和笔数 */
  class AdjustDayRow {
    java.time.LocalDate day;
    BigDecimal credit;
    int credits;
    BigDecimal debit;
    int debits;
  }

  List<AdjustDayRow> adjustByDay(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

  /** 风控：id 大于 afterId 的人工加款 */
  List<AdjustRow> creditsAfter(@Param("afterId") long afterId, @Param("limit") int limit);

  BigDecimal creditedBetween(@Param("userId") long userId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("upToId") long upToId);
}
