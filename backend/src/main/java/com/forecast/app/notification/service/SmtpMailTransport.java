package com.forecast.app.notification.service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 用 SMTP 发邮件（通知方案 N1）：主流服务商都支持 SMTP，换服务商只改配置。
 * 没有配置时（服务商还没定，N-05）不发，健康检查照常通过，启动时记一条警告。
 */
@Component
class SmtpMailTransport implements MailTransport {

  private static final Logger log = LoggerFactory.getLogger(SmtpMailTransport.class);

  private final NotifyProperties.Mail cfg;
  private final JavaMailSenderImpl sender;

  SmtpMailTransport(NotifyProperties props) {
    this.cfg = props.mail();
    if (!cfg.configured()) {
      log.warn("邮件未配置（FC_MAIL_HOST、FC_MAIL_FROM 为空），邮件只记录、不发送");
      this.sender = null;
      return;
    }
    JavaMailSenderImpl s = new JavaMailSenderImpl();
    s.setHost(cfg.host());
    s.setPort(cfg.port());
    if (cfg.username() != null && !cfg.username().isBlank()) {
      s.setUsername(cfg.username());
      s.setPassword(cfg.password());
    }
    s.setDefaultEncoding(StandardCharsets.UTF_8.name());
    Properties p = s.getJavaMailProperties();
    p.put("mail.smtp.auth", String.valueOf(cfg.username() != null && !cfg.username().isBlank()));
    p.put("mail.smtp.connectiontimeout", "10000");
    p.put("mail.smtp.timeout", "10000");
    p.put("mail.smtp.writetimeout", "10000");
    switch (cfg.security().toUpperCase()) {
      case "SSL" -> p.put("mail.smtp.ssl.enable", "true");
      case "NONE" -> { }
      default -> {
        p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.starttls.required", "true");
      }
    }
    this.sender = s;
    log.info("邮件服务器 {}", cfg);
  }

  @Override
  public void send(String to, String subject, String body) throws Exception {
    if (sender == null) throw new NotConfigured();
    MimeMessage msg = sender.createMimeMessage();
    MimeMessageHelper h = new MimeMessageHelper(msg, false, StandardCharsets.UTF_8.name());
    h.setFrom(new InternetAddress(cfg.from()));
    h.setTo(to);
    h.setSubject(subject);
    h.setText(body, false);
    sender.send(msg);
  }
}
