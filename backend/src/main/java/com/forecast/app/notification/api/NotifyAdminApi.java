package com.forecast.app.notification.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** 后台使用的通知操作（页面、权限、二次确认和审计在 S16）。 */
public interface NotifyAdminApi {

  /**
   * 手动发送给单个客户（原型"手动发送"，不支持群发）。标题和正文原样发送，不按模板翻译。
   * 推送和邮件同样遵守客户的通知偏好，关闭的渠道记为已跳过。
   */
  void manual(long userId, Set<NotifyChannel> channels, String title, String body, long operatorId);

  /** 一个模板；audience USER / STAFF / ANY，channels 是 INBOX、PUSH、EMAIL 的子集 */
  record Template(String code, String audience, String kind, String nameZh, String nameEn, String titleZh, String bodyZh, String titleEn, String bodyEn,
      List<String> variables, List<NotifyChannel> channels, boolean builtIn, boolean enabled, LocalDateTime updatedAt) {}

  /** 一条通知记录；status PENDING / SENT / FAILED / SKIPPED，source TEMPLATE / MANUAL */
  record Record(long id, Long userId, String recipient, String source, String templateCode, NotifyChannel channel, String title, String status,
      String error, Long operatorId, LocalDateTime createdAt) {}

  record Page<T>(List<T> items, long total) {}

  List<Template> templates();

  /**
   * 改模板文案（为空的字段不改）。正文里只能用模板列出的变量；内置模板不能停用（停用会让出金等流程的通知发不出去）。
   */
  Template updateTemplate(String code, String titleZh, String bodyZh, String titleEn, String bodyEn, Boolean enabled);

  /** channel、status 为空表示不筛选；userIds 不为空时只查这些客户 */
  Page<Record> records(NotifyChannel channel, String status, Collection<Long> userIds, int offset, int limit);
}
