package com.forecast.app.account.api;

import java.time.Instant;
import java.util.List;

/**
 * 客户的来源（forecast S13，MF-1，需求第 5.2 节"来源统计"）：首次来源（第一次带参数访问时的值）和注册来源（注册时的值）。
 * 只给后台的来源统计和增长数据导出用（growth 模块）；<b>客户自己看不到</b>，也不给任何人奖励（MQ-5）。
 */
public interface AttributionApi {

  /** 一次来源：渠道、活动、分享人或渠道编号、Telegram 机器人 start 参数原文（都可能为空） */
  record Source(String src, String cmp, String ref, String start) {}

  /** 一个注册的客户：注册时间（account_user.created_at）、首次来源、注册来源（没带来源注册的两个都为空） */
  record Registration(long userId, Instant registeredAt, Source first, Source reg) {}

  /** 注册时间在 [from, to) 的客户，按客户编号排，最多 limit 个 */
  List<Registration> registrations(Instant from, Instant to, int limit);
}
