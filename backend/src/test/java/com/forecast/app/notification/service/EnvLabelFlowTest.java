package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * 测试环境标识（S20）走完整配置：部署时设的环境变量 FC_ENV_LABEL、FC_VERSION 经 application.yml 生效。
 * 邮件发到测试进程里的 SMTP 服务器；站内信不加前缀（页面上已经有标识条）。同一个 Spring 上下文里顺便检查健康检查的 version、env，免得多启动一个上下文。
 */
@TestPropertySource(properties = {"FC_ENV_LABEL=test", "FC_VERSION=0123abc"})
class EnvLabelFlowTest extends NotifyTestSupport {

  @Test
  void 测试环境_邮件标题加前缀_站内信不加() throws Exception {
    User u = registerClean();
    assertThat(mailsTo(u.email()).getFirst().getSubject()).isEqualTo("[测试] Forecast 验证码 | [TEST] Forecast verification code");

    notify.toUser(u.id(), "DEPOSIT_CREDITED", Map.of("amount", "100", "chain", "TRC20"));
    worker.drain();
    MimeMessage zh = mailsTo(u.email()).getLast();
    assertThat(zh.getSubject()).isEqualTo("[测试] 充值已到账");
    assertThat(body(zh)).as("正文不变").isEqualTo("您通过 TRC20 充值的 100 USDT 已到账。");

    String inbox = call(get("/api/notifications?lang=zh-CN"), u.cookie(), null).getContentAsString();
    assertThat(inbox).contains("\"title\":\"充值已到账\"").doesNotContain("[测试]");
  }

  @Test
  void 健康检查返回提交号和测试环境标识() throws Exception {
    String health = call(get("/api/health"), null, null).getContentAsString();
    assertThat(health).contains("\"version\":\"0123abc\"").contains("\"env\":\"test\"");
  }
}
