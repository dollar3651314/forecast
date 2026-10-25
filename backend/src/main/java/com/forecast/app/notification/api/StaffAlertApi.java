package com.forecast.app.notification.api;

import java.util.Map;

/**
 * 给运营、财务人员的业务告警邮件（充值冲正挂账、归集失败或卡住、来历不明的转出、入金对账差异、热钱包余额低、提现卡住）。
 * 收件人是 {@code forecast.notify.staff-alert-emails}（环境变量 {@code FC_RISK_ALERT_EMAILS}）；没有配置时只记日志。
 * <p>复制源里这个方法在 risk 模块（{@code RiskApi.notifyStaff}），差价合约的风控模块删除后搬到通知模块（forecast S1）。
 */
public interface StaffAlertApi {

  /**
   * @param template 邮件模板编号（notify_template.code）
   * @param params 模板变量
   */
  void notifyStaff(String template, Map<String, String> params);
}
