package com.forecast.app.market.core;

import com.forecast.app.market.api.I18nText;
import java.util.List;

/**
 * 客户端接口返回的结构（{@code GET /api/markets}、{@code GET /api/markets/{publicNo}}，forecast S3 的最小契约）。
 * 字段名定了以后客户端（S11）照着接；文字都是三种语言，客户端按自己的语言选，取不到用英文（RQ-18）。
 * 奖池、概率、预计赔率在 {@code pool} 字段（S4，结构由 pool 模块定：{@code pool/bet/PoolViews.Pool}）：列表里是各选项的奖池、概率（整数）、预计赔率；
 * 详情里概率 1 位小数，另有本人的持仓、单笔限额、本市场还能押多少。
 * <ul>
 *   <li>编号（publicNo、optionNo）是字符串（雪花 ID 超过 JavaScript 能精确表示的范围）。</li>
 *   <li>时间是 ISO-8601 UTC 字符串；费率是百分比字符串（"5" = 5%）；金额是字符串。</li>
 *   <li>状态是小写（listed、paused、closed、pending_resolution、publicizing、disputed、settled、cancelled、voided），草稿不会出现。</li>
 * </ul>
 */
public final class ClientViews {

  private ClientViews() {}

  public record OptionCard(String optionNo, int seq, I18nText label) {}

  /**
   * 列表里的一个市场；S11 加 {@code winningOptionNo}（已结算的结果，其他状态为空）；S13 加 {@code todaySlate}（列入今日场次，MF-3）。
   * 奖池里没有任何个人数据（不登录也能看，MF-4）。
   */
  public record Card(String publicNo, String category, I18nText title, String status, String openAt, String closeAt, String eventStartAt,
      String eventEndAt, String feeRate, List<OptionCard> options, Object pool, String winningOptionNo, boolean todaySlate) {}

  public record OptionDetail(String optionNo, int seq, I18nText label, I18nText winCondition) {}

  /** 补充说明：按发布时间先后 */
  public record Clarification(I18nText text, String time) {}

  /** 费率修改记录（公开：时间、旧 → 新；不含原因和操作人） */
  public record FeeChange(String oldRate, String newRate, String time) {}

  /**
   * 市场详情：规则区要的全部内容（判定条件在选项里、规则全文、裁决方式和来源、时间、费率和修改记录、补充说明）；
   * 取消、作废时带原因（系统作废是原因代码，例如 NOT_ENOUGH_OPTIONS、NO_WINNER，客户端翻译）。
   * S5 起：{@code winningOptionNo} 已结算市场的结果（其他状态为空）；{@code settling} 正在分批派彩（客户端显示"结算中"，架构方案 X-2）。
   * S6 起：{@code resolution} 已结算市场的裁决依据（自动裁决的开始价、结束价、来源，结构由 resolve 定：{@code resolve/auto/ResolutionViews}；其他状态为空）。
   * S11 起：{@code settle} 已结算 / 已取消 / 已作废市场的结算合计和本人的结果（结构由 settle 定：{@code settle/view/ClientSettleViews}）；
   * {@code reminder} 本人的 #23 开赛提醒状态（{@code market/remind/ReminderService.State}；还没开放的市场或本人订阅过的才有）。
   */
  public record Detail(String publicNo, String category, I18nText title, I18nText rules, String status, String resolutionMode, String sourcePrimary,
      String sourceBackup, String openAt, String closeAt, String eventStartAt, String eventEndAt, String feeRate, String userCap,
      Integer disputeWindowMinutes, String coverPath, String cancelReason, String voidReason, String winningOptionNo, boolean settling,
      List<OptionDetail> options, List<Clarification> clarifications, List<FeeChange> feeChanges, Object pool, Object resolution, Object settle,
      Object reminder) {}

  public record Page<T>(List<T> items, long total, int page, int pageSize) {}
}
