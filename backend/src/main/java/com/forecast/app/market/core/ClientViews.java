package com.forecast.app.market.core;

import com.forecast.app.market.api.I18nText;
import java.util.List;

/**
 * 客户端接口返回的结构（{@code GET /api/markets}、{@code GET /api/markets/{publicNo}}，forecast S3 的最小契约）。
 * 字段名定了以后客户端（S11）照着接；文字都是三种语言，客户端按自己的语言选，取不到用英文（RQ-18）。
 * 奖池、概率、预计赔率在 S4 加（pool 模块），S3 还没有这些字段。
 * <ul>
 *   <li>编号（publicNo、optionNo）是字符串（雪花 ID 超过 JavaScript 能精确表示的范围）。</li>
 *   <li>时间是 ISO-8601 UTC 字符串；费率是百分比字符串（"5" = 5%）；金额是字符串。</li>
 *   <li>状态是小写（listed、paused、closed、pending_resolution、publicizing、disputed、settled、cancelled、voided），草稿不会出现。</li>
 * </ul>
 */
public final class ClientViews {

  private ClientViews() {}

  public record OptionCard(String optionNo, int seq, I18nText label) {}

  /** 列表里的一个市场 */
  public record Card(String publicNo, String category, I18nText title, String status, String openAt, String closeAt, String eventStartAt,
      String eventEndAt, String feeRate, List<OptionCard> options) {}

  public record OptionDetail(String optionNo, int seq, I18nText label, I18nText winCondition) {}

  /** 补充说明：按发布时间先后 */
  public record Clarification(I18nText text, String time) {}

  /** 费率修改记录（公开：时间、旧 → 新；不含原因和操作人） */
  public record FeeChange(String oldRate, String newRate, String time) {}

  /**
   * 市场详情：规则区要的全部内容（判定条件在选项里、规则全文、裁决方式和来源、时间、费率和修改记录、补充说明）；
   * 取消、作废时带原因（系统作废是原因代码，例如 NOT_ENOUGH_OPTIONS，客户端翻译）。
   */
  public record Detail(String publicNo, String category, I18nText title, I18nText rules, String status, String resolutionMode, String sourcePrimary,
      String sourceBackup, String openAt, String closeAt, String eventStartAt, String eventEndAt, String feeRate, String userCap,
      Integer disputeWindowMinutes, String coverPath, String cancelReason, String voidReason, List<OptionDetail> options,
      List<Clarification> clarifications, List<FeeChange> feeChanges) {}

  public record Page<T>(List<T> items, long total, int page, int pageSize) {}
}
