package com.forecast.app.settle.api;

/** settle 模块给其他模块的只读查询（forecast S5）。 */
public interface SettleApi {

  /**
   * 待处理的彩池对账差异有几条（需求第 8.2 节 R12"彩池对账没有未处理的差异"、AC-38：有差异时提现转人工）。
   */
  long openPoolReconIssues();
}
