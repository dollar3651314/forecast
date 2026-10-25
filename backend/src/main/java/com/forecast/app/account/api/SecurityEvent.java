package com.forecast.app.account.api;

import java.time.LocalDateTime;

/**
 * 账号安全事件（表 account_security_event，v2 提现自动审批 R6）。
 *
 * @param type PASSWORD_CHANGE 改密码 / PASSWORD_RESET 忘记密码重置 / EMAIL_CHANGE 改邮箱（v1 没有这个功能，先留类型）/ FREEZE 冻结 / UNFREEZE 解冻
 * @param at 发生时间（UTC）
 */
public record SecurityEvent(String type, LocalDateTime at) {

  public static final String PASSWORD_CHANGE = "PASSWORD_CHANGE";
  public static final String PASSWORD_RESET = "PASSWORD_RESET";
  public static final String EMAIL_CHANGE = "EMAIL_CHANGE";
  public static final String FREEZE = "FREEZE";
  public static final String UNFREEZE = "UNFREEZE";
}
