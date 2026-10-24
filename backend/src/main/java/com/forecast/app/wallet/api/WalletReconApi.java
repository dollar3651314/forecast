package com.forecast.app.wallet.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 入金和出金对账（需求 4.4-7，S14）。页面在 S16 后台。 */
public interface WalletReconApi {

  /**
   * 一条对账问题。
   *
   * @param kind 问题类型，见 技术方案/v1-数据库设计.md 第 16 节
   * @param refId 涉及的对象（充值"链:交易:序号"、提现单号、挂账号、用户号）
   */
  record Issue(long id, String kind, String refId, Long userId, BigDecimal expected, BigDecimal actual, String detail, String status,
      LocalDateTime createdAt, Long resolvedBy, LocalDateTime resolvedAt, String note) {}

  /** 立刻对一次账，返回这次新发现的问题数（定时任务每小时跑一次） */
  int run();

  /** 按状态列出问题（OPEN 待处理 / RESOLVED 已处理），最新的在前 */
  List<Issue> issues(String status, int limit);

  /** 分页（后台，S16b）；status 为空表示全部 */
  List<Issue> issues(String status, int offset, int limit);

  long countIssues(String status);

  /**
   * 标记为已处理（修复 F5：只有"待处理"的才能标记，已处理的再标记会报错），必须写处理说明。
   * 挂账类问题处理后，对应的挂账也标为已处理。
   */
  void resolve(long issueId, long adminId, String note);
}
