package com.forecast.app.market.core;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** market 模块各表的一行（MyBatis 按字段名映射，列名下划线转驼峰）。时间都是 UTC。 */
final class MarketRows {

  private MarketRows() {}

  /** market_question */
  static class Market {
    Long id;
    long publicNo;
    String category;
    String titleEn = "";
    String titleZhCn = "";
    String titleZhTw = "";
    String rulesEn = "";
    String rulesZhCn = "";
    String rulesZhTw = "";
    String resolutionMode;
    String sourcePrimary = "";
    String sourceBackup = "";
    /** JSON 文本 */
    String sourceRef;
    String status;
    boolean submittedForListing;
    String settlePhase;
    LocalDateTime openAt;
    LocalDateTime closeAt;
    LocalDateTime eventStartAt;
    LocalDateTime eventEndAt;
    BigDecimal feeRate;
    BigDecimal userCap;
    Integer disputeWindowMin;
    String voidReason;
    String cancelReason;
    String coverPath;
    Long createdBy;
    Long listedBy;
    LocalDateTime deletedAt;
    int version;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
  }

  /** market_option */
  static class Option {
    Long id;
    long publicNo;
    long marketId;
    int seq;
    String labelEn = "";
    String labelZhCn = "";
    String labelZhTw = "";
    /** JSON 文本 {"en","zhCN","zhTW"} */
    String winCondition;
    LocalDateTime createdAt;
  }

  /** market_status_log */
  static class StatusLog {
    Long id;
    long marketId;
    String fromStatus;
    String toStatus;
    String actor;
    String reason;
    LocalDateTime createdAt;
  }

  /** market_fee_change */
  static class FeeChange {
    Long id;
    long marketId;
    BigDecimal oldRate;
    BigDecimal newRate;
    String reason;
    long changedBy;
    LocalDateTime changedAt;
    boolean notified;
  }

  /** market_clarification */
  static class Clarification {
    Long id;
    long marketId;
    String textEn;
    String textZhCn;
    String textZhTw;
    long createdBy;
    LocalDateTime createdAt;
  }
}
