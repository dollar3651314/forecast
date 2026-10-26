package com.forecast.app.resolve.sports;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** resolve 模块体育部分各表的一行（MyBatis 按字段名映射）。时间都是 UTC。 */
final class SportsRows {

  private SportsRows() {}

  /** resolve_fixture */
  static class Fixture {
    Long id;
    long publicNo;
    String sport;
    String competitionCode;
    String competitionName;
    String leagueRef;
    String homeName;
    String homeShort;
    String awayName;
    String awayShort;
    LocalDateTime kickoffAt;
    LocalDateTime originalKickoffAt;
    String primarySource;
    String primaryRef;
    String primaryStatus;
    String primaryRawStatus;
    String backupSource;
    String backupRef;
    boolean backupSwapped;
    String backupHome;
    String backupAway;
    LocalDateTime backupKickoffAt;
    Long mappingConfirmedBy;
    LocalDateTime mappingConfirmedAt;
    /** JSON */
    String candidates;
    LocalDateTime candidatesAt;
    String candidatesError;
    LocalDateTime kickoffAlertedAt;
    LocalDateTime fetchedAt;
    int version;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
  }

  /** resolve_fixture_market */
  static class FixtureMarket {
    long marketId;
    long fixtureId;
    String play;
    BigDecimal line;
    long createdBy;
    LocalDateTime createdAt;
  }

  /** resolve_sports_task */
  static class Task {
    long marketId;
    long fixtureId;
    String sport;
    String play;
    BigDecimal line;
    String primarySource;
    String primaryRef;
    String backupSource;
    String backupRef;
    boolean backupSwapped;
    LocalDateTime closeAt;
    LocalDateTime kickoffAt;
    String status;
    int attempts;
    LocalDateTime nextAttemptAt;
    LocalDateTime finishedSeenAt;
    LocalDateTime settleAfterAt;
    LocalDateTime giveUpAt;
    LocalDateTime unfinishedDeadlineAt;
    Long resultOptionId;
    LocalDateTime actualStartAt;
    String lastReason;
    LocalDateTime lastAttemptAt;
    int version;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
  }

  /** resolve_sports_template */
  static class Template {
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
    /** JSON */
    String options;
    int version;
    Long updatedBy;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
  }

  /** resolve_record（只增不改；和 S6、S7 同一张表） */
  static class Record {
    Long id;
    long marketId;
    String kind;
    Long optionId;
    LocalDateTime actualStartAt;
    /** JSON */
    String detail;
    String note;
    String actor;
    LocalDateTime createdAt;
  }
}
