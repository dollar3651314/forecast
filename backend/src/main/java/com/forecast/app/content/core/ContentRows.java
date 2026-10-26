package com.forecast.app.content.core;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** content_* 表的行（forecast S15）。字段名和列名按驼峰对应（MyBatis map-underscore-to-camel-case）。 */
public final class ContentRows {

  private ContentRows() {}

  public static class Draft {
    public long id;
    public long draftNo;
    public long groupNo;
    public String kind;
    public String lang;
    public String title;
    public String body;
    public String refs;
    public String checks;
    public boolean checkOk;
    public String status;
    public String failReason;
    public String rejectReason;
    public Long marketNo;
    public String pageSlug;
    public String topic;
    public LocalDateTime expiresAt;
    public int aiCalls;
    public Long createdBy;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public Long editedBy;
    public Long publishedBy;
    public LocalDateTime publishedAt;
    public int version;
  }

  public static class Page {
    public long id;
    public String slug;
    public String kind;
    public Long marketNo;
    public String titleEn;
    public String titleZhCn;
    public String titleZhTw;
    public String introEn;
    public String introZhCn;
    public String introZhTw;
    public String bodyEn;
    public String bodyZhCn;
    public String bodyZhTw;
    public String data;
    public LocalDateTime dataAt;
    public String status;
    public LocalDate createdDay;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
  }

  public static class Econ {
    public long id;
    public String kind;
    public String period;
    public LocalDateTime releaseAt;
    public String prevValue;
    public String sourceUrl;
    public String status;
    public Long marketNo;
    public String note;
    public Long createdBy;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public int version;
  }

  public static class Template {
    public String code;
    public String titleEn;
    public String titleZhCn;
    public String titleZhTw;
    public String rulesEn;
    public String rulesZhCn;
    public String rulesZhTw;
    public String options;
    public String sourcePrimary;
    public String sourceBackup;
    public int version;
    public Long updatedBy;
    public LocalDateTime updatedAt;
  }

  public static class Word {
    public long id;
    public String kind;
    public String word;
    public boolean builtIn;
    public Long createdBy;
    public LocalDateTime createdAt;
  }

  public static class Filter {
    public long id;
    public String source;
    public String ref;
    public String title;
    public String reason;
    public String matched;
    public LocalDateTime createdAt;
  }

  public static class Log {
    public long id;
    public String action;
    public String kind;
    public String ref;
    public String lang;
    public String outcome;
    public String detail;
    public int aiCalls;
    public Long adminId;
    public LocalDateTime createdAt;
  }

  public static class AiDay {
    public LocalDate day;
    public int used;
    public int contentUsed;
    public int scoreUsed;
    public int failures;
    public int consecutiveFailures;
    public long tokensIn;
    public long tokensOut;
    public String alerts;
  }

  public static class Setting {
    public String settingKey;
    public String value;
    public Long updatedBy;
    public LocalDateTime updatedAt;
  }
}
