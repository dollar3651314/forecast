package com.forecast.app.notification.api;

import java.util.Collection;
import java.util.Map;

/**
 * 给员工的业务告警和提醒邮件（充值冲正挂账、归集失败、对账差异、热钱包余额低、提现卡住；forecast 的上架、裁决、争议、结算异常提醒）。
 * <p>收件人（forecast S9，SQ-14：按角色发）：模板配了收件人权限点（{@code notify_template.staff_perms}）时，发给有其中任意一个权限点的在用管理员
 * （超级管理员算有全部）；模板没配、或者一个这样的管理员都没有时，发到告警邮箱 {@code forecast.notify.staff-alert-emails}
 * （环境变量 {@code FC_RISK_ALERT_EMAILS}，兜底）；告警邮箱也没配时只记日志。
 * <p>复制源里这个方法在 risk 模块（{@code RiskApi.notifyStaff}），差价合约的风控模块删除后搬到通知模块（forecast S1）。
 */
public interface StaffAlertApi {

  /**
   * 按模板配置的收件人权限点发。
   *
   * @param template 邮件模板编号（notify_template.code）
   * @param params 模板变量
   */
  void notifyStaff(String template, Map<String, String> params);

  /**
   * 指定收件人权限点发（同一个模板不同情况发给不同的人，例如裁决超时：待提议超时给 {@code resolve:propose}、待确认超时给 {@code resolve:confirm}）。
   *
   * @param permissions 权限点编号；为空时同 {@link #notifyStaff(String, Map)}
   */
  void notifyStaff(String template, Map<String, String> params, Collection<String> permissions);
}
