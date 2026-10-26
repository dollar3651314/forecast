package com.forecast.app.settle.api;

/** settle 模块给其他模块的只读查询（forecast S5）。 */
public interface SettleApi {

  /**
   * 待处理的彩池对账差异有几条（需求第 8.2 节 R12"彩池对账没有未处理的差异"、AC-38：有差异时提现转人工）。
   */
  long openPoolReconIssues();

  /**
   * 一张派彩已完成的结算单（forecast S10，风控"新账户押中冷门"按它检查）。
   *
   * @param feeRate 结算时的费率（小数，0.05）
   */
  record SettledRun(long runId, long marketId, long winningOptionId, java.math.BigDecimal poolTotal, java.math.BigDecimal winnerPool,
      java.math.BigDecimal feeRate, java.time.LocalDateTime doneAt) {}

  /**
   * 应用内事件：一个市场派彩完成、进入已结算（forecast S10）。在结算收尾的事务里发布，监听方用
   * {@code @TransactionalEventListener(AFTER_COMMIT)} 在提交以后处理（settle 不依赖 riskalert）。
   */
  record PayoutDone(SettledRun run) {}
}
