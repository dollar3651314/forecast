package com.forecast.app.settle.api;

import java.util.List;
import java.util.Optional;

/**
 * 后台的结算、退款、彩池对账（forecast S5；admin 模块的 {@code SettleOps} 负责审计和权限）。金额一律 2 位小数字符串，编号一律字符串。
 * <p>"超管手工填结果直接结算"是 S7 正式裁决台做好之前的临时入口（技术方案/v1-架构方案.md 第 16 节 S5）。
 */
public interface SettleAdminApi {

  /**
   * 一张结算单或退款单。
   *
   * @param kind payout / refund
   * @param status paying / done / abnormal
   * @param reason 退款原因（取消时运营填的原因、NOT_ENOUGH_OPTIONS、NO_WINNER、NO_POOL、人工作废的原因）
   * @param feeRate 结算时的费率（百分比，"5"）
   * @param platformIncome 抽成 + 尾差
   * @param pendingItems 还没派完的明细
   * @param batchSize 派彩时每批的持仓数（0 = 计算和派彩在同一个事务里一次完成）
   */
  record Run(String runNo, String marketNo, String kind, String status, String reason, String winningOptionNo, String feeRate, String poolTotal,
      String winnerPool, String loserPool, String sumGross, String sumFee, String dust, String sumPayout, String platformIncome, int winnerPositions,
      int loserPositions, int itemCount, long pendingItems, boolean conservationOk, String abnormalReason, int batchSize, String resolvedBy,
      String calculatedAt, String doneAt) {}

  /** 结算明细（每个持仓一行） */
  record PayoutItem(long userId, String optionNo, boolean win, String stake, String gross, String fee, String payout, String status) {}

  /** 退款明细（每笔下注一行） */
  record RefundItem(long userId, String betNo, String amount, String reason, String status) {}

  /** 一张单的详情：明细分页（结算单是持仓，退款单是下注） */
  record RunDetail(Run run, Page<PayoutItem> payoutItems, Page<RefundItem> refundItems) {}

  /**
   * 一个市场的结算情况。
   *
   * @param marketStatus 市场状态（小写）
   * @param settlePhase none / paying / done / abnormal
   */
  record Outcome(String marketNo, String marketStatus, String settlePhase, Run payout, Run refund) {}

  /** 彩池对账的一条差异 */
  record Issue(long id, String marketNo, String refKey, String checkDate, String kind, String source, String expected, String actual, String diff,
      String detail, String status, Long resolvedBy, String resolvedAt, String note, String createdAt) {}

  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  /**
   * 超管手工填结果并直接结算（临时入口）：市场必须是待裁决；optionNo 是赢的选项；actualStartAt（ISO-8601 UTC，可空）是实际开赛时间（E14：早于截止时间时，
   * 之后收到的下注全额退回）。同一市场重复调用只结算一次（返回已有的结算单）。
   *
   * @param resolvedBy 给结果的人（管理员 id）
   */
  Outcome settleManually(long marketNo, String optionNo, String actualStartAt, String resolvedBy);

  /**
   * 只校验、不改数据：手工填结果的字段错误（{@code optionNo}：required / invalid；{@code actualStartAt}：invalid / future），后台把它和原因、勾选的错误一次列出（C56）。
   * 市场不存在抛 {@code NOT_FOUND}。
   */
  List<com.forecast.app.common.error.ValidationException.Field> checkManual(long marketNo, String optionNo, String actualStartAt);

  /** 人工作废（E5 比赛取消等；临时入口，S7 换成裁决台）：待裁决、公示中、争议中 → 已作废，全额退款 */
  Outcome voidManually(long marketNo, String reason, String actor);

  Optional<Outcome> outcome(long marketNo);

  /** 结算单、退款单列表（status：paying / done / abnormal；kind：payout / refund），新的在前 */
  Page<Run> runs(String status, String kind, int page, int pageSize);

  Optional<RunDetail> run(long runNo, int page, int pageSize);

  /**
   * 结算异常处理后重试：没有明细的（计算时就没通过）重新计算；派到一半停下的（对账发现差异）接着派。这个市场还有待处理的对账差异时拒绝。
   */
  Run retry(long runNo, String actor);

  /** 彩池对账差异（status：open / resolved），新的在前 */
  Page<Issue> issues(String status, int page, int pageSize);

  /** 标记已处理（处理说明必填） */
  Issue resolveIssue(long id, long adminId, String note);
}
