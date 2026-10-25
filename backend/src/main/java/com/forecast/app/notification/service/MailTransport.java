package com.forecast.app.notification.service;

/** 把一封纯文本邮件交给邮件服务商。测试里换成替身。 */
interface MailTransport {

  /** 没有配置邮件服务器时抛这个异常；任务不重试，通知记录记"邮件未配置" */
  final class NotConfigured extends RuntimeException {
    NotConfigured() {
      super("邮件未配置");
    }
  }

  void send(String to, String subject, String body) throws Exception;
}
