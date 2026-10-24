package com.forecast.app.account.api;

/** KYC 状态：从未提交、审核中、通过、驳回。看最新一次提交。 */
public enum KycStatus {
  NONE,
  PENDING,
  APPROVED,
  REJECTED
}
