package com.forecast.app.market.api;

/**
 * 市场的结算阶段（{@code market_question.settle_phase}，技术方案/v1-架构方案.md 第 4.3 节；forecast S5）。不是需求状态机里的状态：
 * 市场状态仍然按 17 条边走，结算三段只在"进入已结算"之前用这个字段记进度。结算开始以后（不是 {@link #NONE}）不能再改费率（需求第 3.8 节第 3 条）。
 * 只能经 {@link MarketApi#casSettlePhase} 改（带旧值的条件更新）。
 */
public enum SettlePhase {
  /** 还没开始结算 */
  NONE,
  /** 已算好、通过守恒校验，正在分批派彩（客户端显示"结算中"，X-2） */
  PAYING,
  /** 派彩完成、按账本复核守恒通过，市场已结算 */
  DONE,
  /** 结算异常（守恒校验不通过、结算前对账不平、有待处理的彩池对账差异）：不动钱，告警财务和超管，处理后重试 */
  ABNORMAL
}
