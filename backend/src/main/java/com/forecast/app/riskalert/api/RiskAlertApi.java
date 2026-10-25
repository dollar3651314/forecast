package com.forecast.app.riskalert.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * riskalert 给其他模块的只读查询（forecast S10）：提现自动审批 R14、R15（需求说明书第 8.2 节）。
 * 实现只读本模块的表、账本和彩池，不依赖 wallet 的业务类（wallet 调用这里，避免互相依赖）。
 */
public interface RiskAlertApi {

  /**
   * R14 关联账户对押：从 since 起本客户有派彩的市场里，有关联账户押了<b>其他选项</b>的那些市场，本客户在这些市场的已结算盈亏合计。
   *
   * @param profit 这些市场的净盈利合计（派彩 + 补偿 − 已结算本金 − 抽成；亏的市场按负数抵）
   * @param marketIds 这些市场
   * @param linkedUsers 在这些市场押了其他选项的关联账户
   */
  record LinkedOpposite(BigDecimal profit, List<Long> marketIds, List<Long> linkedUsers) {}

  LinkedOpposite linkedOpposite(long userId, LocalDateTime since);

  /** R15 可疑押中：从 since 起（告警时间）本客户还没处理（新 / 已查看）的"新账户押中冷门""尾盘大额"告警有几条 */
  long openSuspiciousAlerts(long userId, LocalDateTime since);
}
