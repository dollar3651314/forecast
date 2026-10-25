package com.forecast.app.notification.api;

import java.util.Map;

/**
 * 按邮箱地址发邮件（验证码、风控人员通知）。发给客户的业务通知用 {@link NotifyApi}，会遵守客户的通知偏好。
 * 可以在业务事务里调用：邮件写成任务，业务提交后才会发出。不知道收件人语言，邮件中英文各一份。
 */
public interface MailApi {

  /** 模板编号，例如 {@code EMAIL_CODE}（变量 code、purpose） */
  void send(String to, String template, Map<String, String> params);

  /**
   * 知道收件人语言时只发一种语言（forecast S9：#23 场次提醒按订阅时的语言；zh-CN / zh-TW / en，其他按简体）。
   */
  void send(String to, String template, Map<String, String> params, String lang);
}
