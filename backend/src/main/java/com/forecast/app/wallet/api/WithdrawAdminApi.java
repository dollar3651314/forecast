package com.forecast.app.wallet.api;

/** 出金审核的后台操作（页面、权限、二次确认和审计日志在 S16）。每次操作都记提现事件。 */
public interface WithdrawAdminApi {

  /** 审核通过：< 3,000 直接进入待上链；≥ 3,000 延迟 6 小时。只能审核"待审核"的提现 */
  void approve(long withdrawalId, long adminId);

  /** 驳回：必须填原因，冻结金额退回 */
  void reject(long withdrawalId, long adminId, String reason);

  /** 大额延迟期内紧急取消：资金退回（AC-21） */
  void emergencyCancel(long withdrawalId, long adminId, String reason);
}
