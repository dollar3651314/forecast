package com.forecast.app.growth.api;

import java.util.List;
import java.util.Optional;

/**
 * 后台"Telegram 机器人"（forecast S16，MF-7、MQ-2；需求第 6.3 节"发布渠道和开关""模板怎么审定""怎么停用"）。
 * 权限点（content:view 查看；content:publish ★ 改频道、开关、审定 / 停用模板、紧急停止）、原因必填、审计在 admin 模块（{@code admin/ops/TelegramOps}）。
 * <p>和 S15"内容中心"的关系：S15 在内容中心留了"渠道开关"的占位；整合时内容中心的"发布渠道"读写这里的频道（{@code growth_tg_target}）、
 * 紧急停止读写这里的 {@code growth_tg_state}，两边只留一套（开发记录 S16 第 4.6 节）。
 */
public interface TelegramAdminApi {

  /**
   * 机器人的状态。
   *
   * @param configured 配置了 FC_TELEGRAM_BOT_TOKEN（没配置时机器人不启动）
   * @param healthy 最近调用正常（没有熔断、没有连续失败）
   */
  record Status(boolean configured, String botUsername, boolean healthy, String lastError, String lastOkAt, boolean emergencyStop, String stopReason,
      String stopAt, Long stopBy) {}

  /** 官方频道（CHANNEL_ZH / CHANNEL_EN） */
  record Target(String key, String lang, String chatId, boolean enabled, String enabledAt, int failStreak, String disabledReason, String updatedAt) {}

  /** 机器人所在的群 */
  record Group(String chatId, String title, String type, String status, boolean broadcast, String lang, String startParam, String addedAt, String broadcastAt) {}

  /**
   * 自动发布的卡片模板。
   *
   * @param status draft / approved / disabled
   * @param changed 已审定，但审定以后模板文字改过（按草稿处理、不发，要重新审定）
   */
  record Template(String kind, String lang, String status, boolean changed, String approvedAt, Long approvedBy) {}

  record Overview(Status status, List<Target> targets, List<Group> groups, List<Template> templates) {}

  /** 发布日志的一条：status pending / sent / failed / skipped / stopped；note 没发 / 失败的原因 */
  record Post(long id, String marketNo, String kind, String target, String lang, String status, int attempts, String postUrl, String note, String createdAt,
      String sentAt) {}

  record Posts(List<Post> items, long total, int page, int pageSize) {}

  /** 审定页的预览：用最近结束的一个真实市场生成的卡片文字（图片另取）；fingerprint 是这一版模板文字的指纹，审定时原样带回 */
  record Preview(String kind, String lang, String marketNo, String text, String fingerprint) {}

  Overview overview();

  Posts posts(String status, int page, int pageSize);

  /** 改频道的编号和开关（编号：@用户名 或 -100 开头的数字）；打开时编号必填 */
  Target updateTarget(String key, String chatId, boolean enabled, long adminId);

  /** 后台关闭一个群的播报（开启只能由群管理员在群里发 /broadcast_on） */
  Group stopGroupBroadcast(String chatId, long adminId);

  Optional<Preview> preview(String kind, String lang);

  /** 预览图（PNG）；没有可预览的市场时为空 */
  Optional<byte[]> previewPng(String kind, String lang);

  /** 审定：fingerprint 必须和现在的模板文字一致（预览以后模板又改了 → TELEGRAM_TEMPLATE_CHANGED 409） */
  Template approve(String kind, String lang, String fingerprint, long adminId);

  /** 停用（这种卡片不再发；重新审定恢复） */
  Template disable(String kind, String lang, long adminId);

  /** 紧急停止 / 恢复（邮件通知有 content:publish 的管理员） */
  Status setStop(boolean stop, String reason, long adminId);
}
