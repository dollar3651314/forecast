package com.forecast.app.account.api;

import java.time.LocalDateTime;
import java.util.Optional;

/** account 模块对其他模块开放的操作（后台 S16、提现 S13 使用）。 */
public interface AccountApi {

  /** 冻结：状态改为 FROZEN，立即作废该用户的所有登录（AC-04）。 */
  void freeze(long userId);

  /** 解冻：状态改回 ACTIVE；之前的登录不会恢复，需要重新登录。 */
  void unfreeze(long userId);

  /** 用户状态；用户不存在时抛 {@code BizException("USER_NOT_FOUND")}。 */
  UserStatus status(long userId);

  /** 登录邮箱（通知模块发邮件时用）；用户不存在时抛 {@code BizException("USER_NOT_FOUND")}。 */
  String email(long userId);

  /** 注册时间（UTC）；用户不存在时抛 {@code BizException("USER_NOT_FOUND")}。提现自动审批 R7 用。 */
  LocalDateTime registeredAt(long userId);

  /**
   * 时间在 (since, 现在] 里最近的一条安全事件（改密码、重置密码、改邮箱、冻结、解冻）；没有时为空。正好在 since 的不算（提现自动审批 R6 的边界）。
   */
  Optional<SecurityEvent> lastSecurityEventSince(long userId, LocalDateTime since);

  /**
   * 是不是员工账户（forecast S4，需求说明书第 3.3、8.1 节：员工账户不能下注）。后台登记员工在 S10；用户不存在时抛 {@code BizException("USER_NOT_FOUND")}。
   */
  boolean isStaff(long userId);

  /** 当前 KYC 状态。提现前要求 APPROVED（需求 4.1-6）；forecast S4 起累计充值超过 KYC 阈值时下注也要求 APPROVED（第 3.10 节）。 */
  KycStatus kycStatus(long userId);

  /** 审核通过：只能审核"审核中"的申请；通过后个人资料锁定，并通知客户。 */
  void approveKyc(long applicationId, long reviewerId);

  /** 驳回：必须填原因（AC-05），客户可以重新提交。 */
  void rejectKyc(long applicationId, long reviewerId, String reason);
}
