package com.forecast.app.market.autogen;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** market_autogen、market_template 的一行（MyBatis 按字段名映射）。时间都是 UTC。 */
final class AutogenRows {

  private AutogenRows() {}

  /** market_autogen */
  static class Autogen {
    Long id;
    long publicNo;
    String symbol;
    /** 15M / 1H */
    String period;
    /** SCHEDULE / ROLLING */
    String mode;
    /** JSON 数组 ["13:00"] */
    String slots;
    int rollingCount;
    int generateAheadMin;
    long templateId;
    BigDecimal userCap;
    boolean enabled;
    Long checkedBy;
    LocalDateTime checkedAt;
    Integer checkedTemplateVersion;
    int version;
    Long createdBy;
    Long updatedBy;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
  }

  /** market_template */
  static class Template {
    Long id;
    long publicNo;
    String kind;
    String name;
    String titleEn;
    String titleZhCn;
    String titleZhTw;
    String rulesEn;
    String rulesZhCn;
    String rulesZhTw;
    String sourcePrimary;
    String sourceBackup;
    /** JSON 数组 [{"outcome","label","winCondition"}] */
    String options;
    int version;
    Long updatedBy;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
  }
}
