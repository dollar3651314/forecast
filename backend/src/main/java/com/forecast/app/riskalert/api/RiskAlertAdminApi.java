package com.forecast.app.riskalert.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 后台的风控告警和关联账户（forecast S10，需求说明书第 6.2 节"风控告警"、"客户与 KYC"的关联账户）；权限和审计在 admin 模块。 */
public interface RiskAlertAdminApi {

  /** 告警类型：尾盘大额、关联账户对押、新账户押中冷门、充了不玩就提（需求第 8.1 节）；24 小时人工加款累计超过阈值（S17B，需求第 21 节） */
  List<String> KINDS = List.of("LATE_LARGE_BET", "LINKED_OPPOSITE", "NEW_ACCOUNT_LONGSHOT", "DEPOSIT_NO_BET", "MANUAL_CREDIT_LARGE");

  /** 处理状态：新、已查看、已处理、误报 */
  List<String> STATUSES = List.of("NEW", "SEEN", "HANDLED", "FALSE_POSITIVE");

  /**
   * 一条告警。
   *
   * @param marketId 和哪个市场有关（内部 id）；充了不玩就提为空
   * @param detail 判定时的数字（键见 {@code riskalert.core.AlertRules}）
   */
  record Alert(long id, String kind, long userId, Long marketId, BigDecimal amount, Map<String, String> detail, String status, String note,
      Long handledBy, LocalDateTime handledAt, LocalDateTime createdAt) {}

  record Page(List<Alert> items, long total) {}

  /** 按类型、状态、客户筛选（为空不筛选；userIds 为空列表表示没有匹配的客户，返回空），按 id 倒序 */
  Page alerts(String kind, String status, Collection<Long> userIds, int offset, int limit);

  /** 每种类型每种状态的条数：kind → status → 条数 */
  Map<String, Map<String, Long>> counts();

  Optional<Alert> alert(long id);

  /**
   * 改处理状态（在调用方的事务里或自己开事务）。
   *
   * @return 改之前的告警；状态和原来一样时什么都不改
   */
  Alert setStatus(long id, String status, String note, long adminId);

  /** 一个关联：和哪个客户、什么原因、共同的值、第一次发现的时间 */
  record Link(long userId, String reason, String evidence, LocalDateTime firstSeenAt) {}

  /** 这个客户的关联账户（每个原因一行，按客户号） */
  List<Link> linksOf(long userId);
}
