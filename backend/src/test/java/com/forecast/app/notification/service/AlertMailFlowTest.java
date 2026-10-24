package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.common.health.AlertProperties;
import com.forecast.app.notification.api.MailApi;
import com.forecast.app.support.MutableClock;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 告警邮件真的发出去（E8，AC2-20 "告警邮件 1 分钟内发出"）：告警检查（每 15 秒）→ 邮件任务（模板 OPS_ALERT / OPS_RECOVERED）→
 * 发送任务（每 2 秒）→ SMTP（测试进程里的 GreenMail）。这里直接调用这两步，不等定时器。
 */
class AlertMailFlowTest extends NotifyTestSupport {

  @Autowired MailApi mailApi;

  @Test
  void 断流时告警邮件经任务表和SMTP发出_中英文_恢复时发恢复邮件() throws Exception {
    String ops = "ops" + System.nanoTime() + "@example.com";
    AlertProperties d = AlertProperties.defaults();
    AlertProperties props = new AlertProperties(true, List.of(ops), d.throttle(), d.scanStall(), d.nodeFailures(), d.mailFailures());
    AlertMonitorTest.Switch market = new AlertMonitorTest.Switch("market") {
      @Override
      public List<Item> evaluate() {
        return List.of(new Item("market", "行情全部断流", "Market data stopped", ok, ok ? "正常" : "开市的 40 个品种已经 31 秒没有收到任何报价"));
      }
    };
    MutableClock clock = new MutableClock();
    AlertMonitor monitor = new AlertMonitor(List.<AlertCheck>of(market), props, mailApi, clock);

    market.ok = false;
    monitor.check();
    worker.drain();
    List<MimeMessage> mails = mailsTo(ops);
    assertThat(mails).hasSize(1);
    assertThat(mails.getFirst().getSubject()).contains("告警：行情全部断流").contains("Alert: Market data stopped");
    assertThat(body(mails.getFirst())).contains("开市的 40 个品种已经 31 秒没有收到任何报价").contains("2026-10-14T10:00:00Z");
    assertThat(jdbc.queryForObject("SELECT status FROM notify_record WHERE recipient = ? AND template_code = 'OPS_ALERT'", String.class, ops)).isEqualTo("SENT");

    clock.advance(Duration.ofMinutes(3));
    market.ok = true;
    monitor.check();
    worker.drain();
    assertThat(mailsTo(ops)).hasSize(2);
    assertThat(mailsTo(ops).get(1).getSubject()).contains("已恢复：行情全部断流");
    assertThat(body(mailsTo(ops).get(1))).contains("2026-10-14T10:03:00Z");
  }
}
