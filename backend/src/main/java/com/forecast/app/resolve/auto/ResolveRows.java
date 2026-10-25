package com.forecast.app.resolve.auto;

import java.time.LocalDateTime;

/** resolve 模块各表的一行（MyBatis 按字段名映射）。时间都是 UTC。 */
final class ResolveRows {

  private ResolveRows() {}

  /** resolve_auto_task */
  static class Task {
    long marketId;
    String symbol;
    LocalDateTime windowStart;
    LocalDateTime windowEnd;
    String status;
    int attempts;
    LocalDateTime nextAttemptAt;
    LocalDateTime deadlineAt;
    Long resultOptionId;
    String lastReason;
    LocalDateTime lastAttemptAt;
    int version;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
  }

  /** resolve_record（只增不改） */
  static class Record {
    Long id;
    long marketId;
    String kind;
    Long optionId;
    /** JSON */
    String detail;
    String note;
    String actor;
    LocalDateTime createdAt;
  }

  /** resolve_source_fetch（只增不改） */
  static class Fetch {
    Long id;
    Long marketId;
    String source;
    int attempt;
    String requestUrl;
    Integer httpStatus;
    String body;
    String bodySha256;
    String outcome;
    /** JSON */
    String parsed;
    String error;
    LocalDateTime fetchedAt;
    int elapsedMs;
  }
}
