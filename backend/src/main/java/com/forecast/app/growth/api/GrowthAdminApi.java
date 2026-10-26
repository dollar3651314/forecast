package com.forecast.app.growth.api;

import java.util.List;

/**
 * 后台"来源统计"（forecast S13，MF-1、MF-10；需求第 6.2 节、AC-94）。权限点 growth:view 和导出的审计在 admin 模块（{@code admin/ops/GrowthOps}）。
 * <p>口径（开发记录 S13 第 8 节）：
 * <ul>
 *   <li>周 = UTC 的 ISO 周（周一 00:00 起）；注册周按 {@code account_user.created_at}。</li>
 *   <li>来源按 basis：{@code first} 首次来源（默认）/ {@code reg} 注册来源；没带来源注册的归"直接访问"（三个值都是空串）。</li>
 *   <li>访问 = 那一周带这个来源参数打开网站的人数（同一访问者同一天同一来源算一次，{@code growth_visit}）。</li>
 *   <li>首充、激活（首注）= 这一周注册的客户里，到现在为止充值入账过、下过注的人数；第 2 周仍下注 = 注册后第 8～14 天（按 UTC 日期）下过注的人数，
 *       只对第 2 周已经过完的客户算（week2Eligible 是分母）。</li>
 *   <li>市场：结束时间在范围里的市场，下注人数、作废 / 取消原因；按周的有效市场率（已结算 / 已结束）和每场下注人数的中位数（加场门槛的数据，需求第 4.2 节）。</li>
 * </ul>
 * 导出的文件只有客户编号，没有邮箱、地址、KYC 信息（AC-94）。
 */
public interface GrowthAdminApi {

  /**
   * 一个注册周 × 一个来源。
   *
   * @param refCustomer 分享人（S15）：ref 是分享码时换成它的客户编号；旧链接（S14）里 src=share 的纯数字 ref 就是客户编号，原样给；
   *     其他（渠道编号、不认识的码）为空。只在后台显示
   */
  record Cohort(String week, String src, String cmp, String ref, long visits, long registrations, long firstDeposits, long activated, long week2Active,
      long week2Eligible, String refCustomer) {}

  /** 一个已结束的市场 */
  record MarketRow(String marketNo, String category, String titleZhCN, String titleEn, String status, String endedAt, int bettors, String pool, String reason) {}

  /** 作废或取消的一个原因 */
  record ReasonCount(String status, String reason, int count) {}

  /** 一周的市场情况：结束、已结算、作废、取消、有效市场率（百分比，1 位小数；没有结束的市场为空）、每场下注人数的中位数 */
  record MarketWeek(String week, int ended, int settled, int voided, int cancelled, String validRate, String medianBettors) {}

  /** 合计（所有来源） */
  record Totals(long visits, long registrations, long firstDeposits, long activated, long week2Active, long week2Eligible) {}

  /**
   * 来源统计。from、to：UTC 日期（含两头，按周对齐到所在周的周一、周日），最多 53 周；truncated：注册人数超过上限、只统计了前面的。
   */
  record Report(String from, String to, String basis, List<Cohort> cohorts, Totals totals, List<MarketWeek> marketWeeks, List<ReasonCount> reasons,
      List<MarketRow> markets, boolean truncated) {}

  /** from、to 为空时是最近 8 周（含本周）；basis：first（默认）/ reg */
  Report report(String from, String to, String basis);

  /** CSV 导出的种类：sources 按注册周和来源、markets 按市场、customers 按客户（只有客户编号） */
  List<String> EXPORT_KINDS = List.of("sources", "markets", "customers");

  /** CSV（UTF-8 带 BOM，Excel 直接打开）；lang：zh（默认）/ en 表头 */
  String csv(String kind, String from, String to, String basis, String lang);
}
