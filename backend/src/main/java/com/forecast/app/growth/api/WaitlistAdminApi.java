package com.forecast.app.growth.api;

import java.util.List;

/**
 * 后台"等候页订阅"（forecast S16，MF-8；需求第 6.2 节"来源统计 … 等候页订阅列表（订阅方式、订阅时间、是否已通知）"；SQ-22）。
 * 权限点（growth:view 查看；content:publish ★ 发送上线通知）和审计在 admin 模块（{@code admin/ops/WaitlistOps}）。
 * <p>列表里的邮箱<b>打码</b>（a***@example.com）：后台只用来看订阅情况，不导出、不显示完整邮箱（第 2.2 节 growth:view"不导出邮箱以外的个人信息"，
 * 这里连邮箱也不完整显示，开发记录 S16 D-217；S16 分支上原编号 D-203，整合 4 改号）。
 */
public interface WaitlistAdminApi {

  /**
   * 一个订阅。
   *
   * @param status pending 待确认 / confirmed 已确认 / notified 已发上线通知 / unsubscribed 已退订
   * @param email 打码后的邮箱
   */
  record Item(long id, String email, String lang, String status, String src, String cmp, String ref, String start, String createdAt, String confirmedAt,
      String notifiedAt, String unsubscribedAt) {}

  /** 各状态的数量 */
  record Counts(long pending, long confirmed, long notified, long unsubscribed) {}

  /**
   * 上线通知（只有一次）。
   *
   * @param status none 还没发 / sending 发送中 / done 发完了
   * @param recipients 按下时已确认的订阅数；sent 已写成邮件任务的封数
   */
  record Launch(String status, String at, Long adminId, String reason, int recipients, int sent, String finishedAt) {}

  record Page(List<Item> items, long total, int page, int pageSize, Counts counts, Launch launch) {}

  /** status 为空不筛选（pending / confirmed / notified / unsubscribed） */
  Page list(String status, int page, int pageSize);

  Launch launch();

  /**
   * 发送上线通知（SQ-22：只发这一封；<b>全站只能发一次</b>）。已经发过时抛 {@code BizException("LAUNCH_ALREADY_SENT")}（409）。
   * 只记下"要发"，邮件由任务一个订阅一个事务地写（{@code LaunchNoticeJob}），按下以后才确认的不发（等候页那时已经关闭）。
   */
  Launch startLaunch(long adminId, String reason);

  // =====================================================================
  // forecast S19（P15）：开放交易时，把 Cloudflare 上的等候页名单导进来
  // =====================================================================

  /**
   * Cloudflare 导出文件（cf/src/admin.ts 的 export.json，格式 fc-cf-waitlist-v1）里的一项。时间是 ISO（UTC）。
   *
   * @param status confirmed 已确认 / unsubscribed 已退订（导出里只有这两种）
   * @param unsubToken Cloudflare 上的退订随机串（新增时沿用，Cloudflare 发出去的退订链接以后指向生产仍然有效）
   * @param ipHash 留邮箱时 IP 的 sha256（和这里同一种算法）
   */
  record ImportItem(String email, String lang, String status, String src, String cmp, String ref, String start, String createdAt, String confirmedAt,
      String unsubscribedAt, String unsubToken, String ipHash) {}

  /** 文件里第 row 个（从 1 起）的 field 不对：code = emailInvalid / statusInvalid / timeInvalid */
  record ImportProblem(int row, String field, String code) {}

  /**
   * 导入结果。
   *
   * @param added 新增为已确认
   * @param confirmed 后台原来是待确认、改成已确认
   * @param unsubscribed Cloudflare 上退订了、后台改成已退订
   * @param unchanged 已经一样（重复导入时全部在这里），或者后台已退订（退订优先，不替人重新订阅）
   * @param notAdded Cloudflare 上已退订、后台没有这个邮箱：不新增（不保存退订者的邮箱）
   * @param invalid 格式不对、没有导入的项数（明细在 problems）
   * @param afterLaunch 上线通知已经发完以后才变成已确认的个数：他们不会再收到上线通知（全站只发一次）
   */
  record ImportResult(int total, int added, int confirmed, int unsubscribed, int unchanged, int notAdded, int invalid, int afterLaunch,
      List<ImportProblem> problems, Launch launch) {}

  /**
   * 导入 Cloudflare 上的名单（S19，P15）。<b>不发任何邮件</b>；可以重复导入，结果一样。上线通知仍由 {@link #startLaunch} 发（只能一次），
   * 正在发的时候导入的已确认会被发送任务接着发到。
   */
  ImportResult importFromCloudflare(List<ImportItem> items);
}
