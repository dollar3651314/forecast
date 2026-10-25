package com.forecast.app.settle.comp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 补偿各表的一行（forecast S7）。时间 UTC。 */
final class CompRows {

  private CompRows() {}

  /** settle_compensation */
  static class Comp {
    Long id;
    long publicNo;
    long marketId;
    long runId;
    long originalOptionId;
    Long correctOptionId;
    BigDecimal feeRate;
    BigDecimal totalAmount;
    int userCount;
    String reason;
    String status;
    long createdBy;
    LocalDateTime createdAt;
  }

  /** settle_compensation_item（只增不改） */
  static class Item {
    Long id;
    long compensationId;
    long marketId;
    long userId;
    BigDecimal shouldPayout;
    BigDecimal actualPayout;
    BigDecimal amount;
    LocalDateTime createdAt;
  }

  /** 结算明细里算补偿要用的列 */
  static class Settled {
    long positionId;
    long userId;
    long optionId;
    BigDecimal stake;
    BigDecimal payout;
  }

  /** 结算单（settle_run）里补偿要用的列 */
  static class Run {
    long id;
    String status;
    Long winningOptionId;
    BigDecimal feeRate;
  }

  /** 报表的一天 */
  static class Day {
    LocalDate day;
    BigDecimal fee;
    BigDecimal dust;
    BigDecimal amount;
    int n;
  }
}
