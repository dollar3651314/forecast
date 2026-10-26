package com.forecast.app.settle.api;

import java.util.List;
import java.util.Optional;

/**
 * 后台的结算、退款、彩池对账（forecast S5；admin 模块的 {@code SettleOps} 负责审计和权限）。金额一律 2 位小数字符串，编号一律字符串。
 * <p>S7：S5 的临时入口"超管手工填结果直接结算"已删除（SQ-10，结果走裁决台：提议 → 确认 → 公示 → 结算）；人工作废在 resolve 的裁决台（{@code ResolveDeskApi.voidMarket}）。
 * S7 加结算预览、补偿（E13）、平台收入和补偿支出的报表。
 */
public interface SettleAdminApi {

  /** 员工补登时退回的结果（forecast S10，E15）：退了几个市场、几笔、合计（2 位小数字符串） */
  record StaffRefund(int markets, int bets, String amount) {}

  /** 员工补登（E15）：退回这个客户在结算还没开始的市场里的有效下注（{@code SettleService.refundStaffBets}） */
  StaffRefund refundStaffBets(long userId);

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
   * 结算预览（forecast S7，裁决台"按提议结果的结算预览"，需求第 6.2 节、W4）：按这个结果<b>现在</b>结算的守恒数字，不改任何数据。
   *
   * @param outcome settle 结算 / void_no_winner 没人押赢方，会作废（E1）/ void_no_pool E14、E15 退回后奖池为 0，会作废
   * @param conserved ΣPay + ΣF + D = T 且 0 ≤ D &lt; 0.01 × 赢方持仓数
   * @param lateBets、lateAmount E14：实际开赛时间之后收到、会全额退回的下注
   * @param staffBets、staffAmount E15：员工账户的下注，会退回
   */
  record Preview(String marketNo, String optionNo, String actualStartAt, String outcome, String feeRate, String poolTotal, String winnerPool, String loserPool,
      String sumGross, String sumFee, String dust, String sumPayout, String platformIncome, int winnerPositions, int loserPositions, boolean conserved,
      int lateBets, String lateAmount, int staffBets, String staffAmount) {}

  /**
   * 按这个结果的结算预览。optionNo 必填、是这个市场的选项；actualStartAt（ISO-8601 UTC，可空）。字段错误一次列出（{@code ValidationException}）。
   */
  Preview preview(long marketNo, String optionNo, String actualStartAt);

  // ---------------------------------------------------------------------
  // 补偿（forecast S7，E13、AC-35）
  // ---------------------------------------------------------------------

  /** 一个客户的补偿：按正确结果应得、实际已得、补偿 = 应得 − 实际（&gt; 0） */
  record CompLine(long userId, String shouldPayout, String actualPayout, String amount) {}

  /**
   * 补偿的计算结果（预览）。
   *
   * @param correctOptionNo 正确结果；{@code "void"} = 本应作废（全额退款）
   * @param feeRate 原结算单的费率（百分比）
   */
  record CompPlan(String marketNo, String originalOptionNo, String correctOptionNo, String feeRate, String total, int userCount, List<CompLine> lines) {}

  /** 一张补偿单（已入账） */
  record Compensation(String compensationNo, String marketNo, String originalOptionNo, String correctOptionNo, String feeRate, String total, int userCount,
      String reason, long createdBy, String createdAt, List<CompLine> lines) {}

  /** 只校验：正确结果的字段错误（{@code correctOptionNo}：required / invalid / sameAsResult）。市场不存在抛 NOT_FOUND */
  List<com.forecast.app.common.error.ValidationException.Field> checkCompensation(long marketNo, String correctOptionNo);

  /** 预览：选市场和正确结果 → 每个客户应补多少（只读） */
  CompPlan compensationPreview(long marketNo, String correctOptionNo);

  /** 确认补偿并入账（每个市场一次）；原因和权限在 admin（{@code settle:compensate}，高危） */
  Compensation compensate(long marketNo, String correctOptionNo, String reason, long adminId);

  /** 补偿单列表（新的在前；明细为空） */
  Page<Compensation> compensations(int page, int pageSize);

  Optional<Compensation> compensation(long compensationNo);

  /**
   * 报表的一天（需求第 6.2 节"报表：平台收入 = 抽成 + 结算尾差（补偿支出单列）"）。
   *
   * @param revenue 平台收入 = 抽成 + 尾差（不扣补偿）
   * @param compensation 补偿支出（单列）
   */
  record ReportDay(String date, String feeIncome, String dust, String revenue, String compensation, int compensations) {}

  record Report(String from, String to, String feeIncome, String dust, String revenue, String compensationExpense, int compensations, List<ReportDay> days) {}

  /** from、to：UTC 日期 YYYY-MM-DD（含两头），最多 366 天 */
  Report report(String from, String to);

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
