package com.forecast.app.notification.service;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 通知模块的表（notify_*）。只有 notification 模块使用。 */
@Mapper
interface NotifyMapper {

  Rows.TemplateRow template(@Param("code") String code);

  Rows.PrefRow pref(@Param("userId") long userId);

  void upsertPref(@Param("userId") long userId, @Param("push") boolean push, @Param("email") boolean email);

  void upsertLang(@Param("userId") long userId, @Param("lang") String lang);

  void insertMessage(Rows.MessageRow m);

  List<Rows.MessageRow> messages(@Param("userId") long userId, @Param("offset") int offset, @Param("limit") int limit);

  long countMessages(@Param("userId") long userId);

  long countUnread(@Param("userId") long userId);

  int markRead(@Param("userId") long userId, @Param("id") long id, @Param("now") LocalDateTime now);

  int markAllRead(@Param("userId") long userId, @Param("now") LocalDateTime now);

  void insertRecord(Rows.RecordRow r);

  void updateRecord(@Param("id") long id, @Param("status") String status, @Param("error") String error);

  /** 某个客户的通知记录，最新的在前（测试和后台 S16 用） */
  List<Rows.RecordRow> recordsOf(@Param("userId") long userId, @Param("limit") int limit);

  Rows.RecordRow record(@Param("id") long id);

  /** 同一个推送地址已经有了就改成这个客户、更新密钥 */
  void upsertPushSub(@Param("userId") long userId, @Param("endpoint") String endpoint, @Param("hash") String hash, @Param("p256dh") String p256dh,
      @Param("auth") String auth, @Param("now") LocalDateTime now);

  List<Rows.PushSubRow> pushSubs(@Param("userId") long userId);

  // ---------- 批量发送（NotifyApi.toUsers，forecast S5） ----------

  /** 一批客户的通知偏好（没有设置过的不在结果里，按默认值） */
  List<Rows.PrefUserRow> prefsOf(@Param("userIds") java.util.Collection<Long> userIds);

  /** 一批客户的推送订阅 */
  List<Rows.PushSubRow> pushSubsOf(@Param("userIds") java.util.Collection<Long> userIds);

  /** 多行插入站内信，回填 id */
  int insertMessages(@Param("rows") List<Rows.MessageRow> rows);

  /** 多行插入通知记录，回填 id */
  int insertRecords(@Param("rows") List<Rows.RecordRow> rows);

  Rows.PushSubRow pushSub(@Param("id") long id);

  int deletePushSub(@Param("id") long id);

  int deletePushSubByEndpoint(@Param("userId") long userId, @Param("hash") String hash);

  /** 超过 max 台设备时删掉最早的 */
  int trimPushSubs(@Param("userId") long userId, @Param("max") int max);

  /** 后台（S16b） */
  List<Rows.TemplateRow> templates();

  void updateTemplate(@Param("code") String code, @Param("titleZh") String titleZh, @Param("bodyZh") String bodyZh, @Param("titleEn") String titleEn,
      @Param("bodyEn") String bodyEn, @Param("titleTw") String titleTw, @Param("bodyTw") String bodyTw, @Param("enabled") boolean enabled);

  List<Rows.RecordRow> records(@Param("channel") String channel, @Param("status") String status, @Param("userIds") java.util.Collection<Long> userIds,
      @Param("offset") int offset, @Param("limit") int limit);

  long countRecords(@Param("channel") String channel, @Param("status") String status, @Param("userIds") java.util.Collection<Long> userIds);
}
