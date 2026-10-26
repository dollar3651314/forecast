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
    /** 结果（S5 起：超管手工填结果、S6 自动裁决、S7 确认结果时写） */
    Long winningOptionId;
    /** 实际开赛时间（E14，结算时退回这之后收到的下注） */
    LocalDateTime actualStartAt;
    String resolvedBy;
    LocalDateTime openAt;
    LocalDateTime closeAt;
    LocalDateTime eventStartAt;
    LocalDateTime eventEndAt;
    BigDecimal feeRate;
    BigDecimal userCap;
    Integer disputeWindowMin;
    /** 争议窗口截止（S7：确认结果进入公示时写） */
    LocalDateTime disputeDeadlineAt;
    String voidReason;
    String cancelReason;
    String coverPath;
    /** 由哪个自动生成模板生成（S6），手工建的为空 */
    Long autogenId;
    /** 列入今日场次（S13，MF-3）：加密场次表生成的自动带；人工上架的由上架的人勾选 */
    boolean todaySlate;
    /** 进入已结算 / 已取消 / 已作废的时间（S13，MF-6 开奖记录和周报） */
    LocalDateTime endedAt;
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

  /** 后台列表的分组数量（S9） */
  static class StatusCount {
    String status;
    boolean submitted;
    long n;
  }
}
