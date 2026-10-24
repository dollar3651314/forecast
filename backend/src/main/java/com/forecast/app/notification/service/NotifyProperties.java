package com.forecast.app.notification.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 通知参数（application.yml 的 forecast.notify）。
 *
 * @param workerEnabled 是否定时发送邮件任务（测试环境关，测试里直接调用 {@link NotifyWorker#drain()}）
 * @param mail SMTP 服务器（通知方案 N1）。值都由 Amos 填在服务器环境变量里；host 为空表示没有配置，邮件只写日志、不发
 * @param push Web Push（S15b，通知方案 N2）
 * @param staffAlertEmails 业务告警收件人（{@link StaffAlerts}），环境变量 FC_RISK_ALERT_EMAILS，逗号分隔
 */
@ConfigurationProperties("forecast.notify")
record NotifyProperties(@DefaultValue("true") boolean workerEnabled, @DefaultValue Mail mail, @DefaultValue Push push,
    @DefaultValue java.util.List<String> staffAlertEmails) {

  /**
   * @param security STARTTLS（一般是 587 端口）、SSL（一般是 465 端口）或 NONE（只在测试里用）
   */
  record Mail(String host, @DefaultValue("587") int port, String username, String password, String from,
      @DefaultValue("STARTTLS") String security) {

    boolean configured() {
      return host != null && !host.isBlank() && from != null && !from.isBlank();
    }

    /** 不打印密码 */
    @Override
    public String toString() {
      return "Mail[host=" + host + ", port=" + port + ", username=" + username + ", from=" + from + ", security=" + security
          + ", password=" + (password == null || password.isEmpty() ? "(空)" : "(已设置)") + "]";
    }
  }

  /**
   * Web Push。
   *
   * @param vapidPublicKey 公钥（65 字节未压缩格式，base64url），客户端订阅时用
   * @param vapidPrivateKey 私钥（32 字节，base64url）；只由 Amos 填进服务器
   * @param vapidSubject 联系方式，{@code mailto:} 或 {@code https://} 开头
   * @param ttl 推送服务最多保存多久（设备离线时）
   * @param allowedHosts 允许的推送服务主机，{@code *.} 开头表示子域名
   * @param allowLocalhost 测试用：允许发到本机的推送服务替身
   * @param maxDevices 每个客户最多几台设备，超过时删掉最早的订阅
   */
  record Push(String vapidPublicKey, String vapidPrivateKey, String vapidSubject, @DefaultValue("24h") java.time.Duration ttl,
      @DefaultValue({"fcm.googleapis.com", "*.push.apple.com", "updates.push.services.mozilla.com", "*.notify.windows.com"}) java.util.List<String> allowedHosts,
      @DefaultValue("false") boolean allowLocalhost, @DefaultValue("10") int maxDevices) {

    boolean anySet() {
      return notBlank(vapidPublicKey) || notBlank(vapidPrivateKey) || notBlank(vapidSubject);
    }

    boolean allSet() {
      return notBlank(vapidPublicKey) && notBlank(vapidPrivateKey) && notBlank(vapidSubject);
    }

    private static boolean notBlank(String s) {
      return s != null && !s.isBlank();
    }

    /** 不打印私钥 */
    @Override
    public String toString() {
      return "Push[publicKey=" + vapidPublicKey + ", subject=" + vapidSubject + ", privateKey=" + (notBlank(vapidPrivateKey) ? "(已设置)" : "(空)") + "]";
    }
  }
}
