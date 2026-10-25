package com.forecast.app.notification.service;

import java.time.LocalDateTime;

/** 通知模块的数据库行（MyBatis 按字段名映射）。 */
final class Rows {

  private Rows() {}

  /** notify_template 的一行 */
  static class TemplateRow {
    String code;
    String audience;
    String kind;
    String nameZh;
    String nameEn;
    String titleZh;
    String titleEn;
    String bodyZh;
    String bodyEn;
    /** 繁体（S9）；员工模板为空，按简体发 */
    String titleTw;
    String bodyTw;
    /** 需求第 7 节的编号（例如 #13），员工模板为空 */
    String reqNo;
    /** 员工模板：收件人是有这些权限点之一的管理员（逗号分隔，S9，SQ-14）；为空或没有这样的管理员时发告警邮箱 */
    String staffPerms;
    String variables;
    String channels;
    boolean builtIn;
    boolean enabled;
    LocalDateTime updatedAt;

    boolean has(String channel) {
      for (String c : channels.split(",")) if (c.trim().equals(channel)) return true;
      return false;
    }
  }

  /** notify_pref 的一行；没有记录时用 {@link #defaults()} */
  static class PrefRow {
    boolean pushEnabled = true;
    boolean emailEnabled = true;
    String lang = Render.ZH;

    static PrefRow defaults() {
      return new PrefRow();
    }
  }

  /** 批量读偏好时带上用户 id */
  static class PrefUserRow extends PrefRow {
    long userId;
  }

  /** notify_message 的一行 */
  static class MessageRow {
    Long id;
    long userId;
    String kind;
    String templateCode;
    String params;
    String title;
    String body;
    LocalDateTime readAt;
    LocalDateTime createdAt;
  }

  /** notify_record 的一行 */
  static class RecordRow {
    Long id;
    Long userId;
    String recipient;
    String source;
    String templateCode;
    String channel;
    String title;
    String status;
    String error;
    Long operatorId;
    LocalDateTime createdAt;
  }

  /** notify_push_sub 的一行 */
  static class PushSubRow {
    Long id;
    long userId;
    String endpoint;
    String p256dh;
    String auth;
  }
}
