package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.common.health.AlertMailSwitch;
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
    AlertProperties props = d.with(true, List.of(ops));
    AlertMonitorTest.Switch market = new AlertMonitorTest.Switch("market") {
      @Override
      public List<Item> evaluate() {
        return List.of(new Item("market", "行情全部断流", "Market data stopped", ok, ok ? "正常" : "开市的 40 个品种已经 31 秒没有收到任何报价"));
      }
    };
    MutableClock clock = new MutableClock();
    AlertMonitor monitor = new AlertMonitor(List.<AlertCheck>of(market), props, mailApi, AlertMailSwitch.ALL, clock);

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

  @Autowired AlertMailSwitch mailSwitch;
  @Autowired com.forecast.app.common.config.SystemConfig config;

  /**
   * S12b：后台开关真的从数据库（common_config 的 alert.mail.*，迁移 V28 的默认值：开、没有静音）读；"仍未恢复"提醒（模板 OPS_STILL_DOWN，迁移 V28）
   * 真的经过任务表和 SMTP 发出；未开通的项不发；总开关关掉、静音的项不写邮件任务。
   */
  @Test
  void S12b_未开通不发_出错发一次_24小时提醒经SMTP发出_后台关掉或静音后不写邮件任务() throws Exception {
    String ops = "ops" + System.nanoTime() + "@example.com";
    try {
      assertThat(mailSwitch.mailEnabled()).as("迁移 V28 的默认值：开").isTrue();
      assertThat(mailSwitch.muted()).isEmpty();
      AlertMailControlTest.Tri tg = new AlertMailControlTest.Tri("telegram.bot", AlertCheck.Status.NOT_CONFIGURED);
      AlertMailControlTest.Tri node = new AlertMailControlTest.Tri("node.TRC20", AlertCheck.Status.DOWN);
      MutableClock clock = new MutableClock();
      AlertMonitor monitor = new AlertMonitor(List.<AlertCheck>of(tg, node), AlertProperties.defaults().with(true, List.of(ops)), mailApi, mailSwitch, clock);
      monitor.check();
      clock.advance(Duration.ofHours(24));
      monitor.check();
      worker.drain();
      assertThat(mailsTo(ops)).extracting(MimeMessage::getSubject).as("未开通的 telegram.bot 不发；node.TRC20 告警一封、24 小时后提醒一封")
          .hasSize(2).noneMatch(t -> t.contains("Telegram"));
      assertThat(mailsTo(ops).get(1).getSubject()).contains("仍未恢复：项目node.TRC20").contains("Still down: item node.TRC20");
      assertThat(body(mailsTo(ops).get(1))).contains("每 24 小时提醒一次").contains("node.TRC20 坏了");

      // 后台关掉总开关：恢复邮件也不写任务
      jdbc.update("UPDATE common_config SET value = 'false' WHERE config_key = 'alert.mail.enabled'");
      config.refresh();
      assertThat(mailSwitch.mailEnabled()).isFalse();
      node.status = AlertCheck.Status.UP;
      monitor.check();
      AlertMailControlTest.Tri scan = new AlertMailControlTest.Tri("scan.ERC20", AlertCheck.Status.DOWN);
      AlertMonitor other = new AlertMonitor(List.<AlertCheck>of(scan), AlertProperties.defaults().with(true, List.of(ops)), mailApi, mailSwitch, clock);
      other.check();
      worker.drain();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE recipient = ?", Integer.class, ops)).as("总开关关掉：不写邮件任务").isEqualTo(2);

      // 打开总开关、静音 scan.ERC20：仍然不发
      jdbc.update("UPDATE common_config SET value = 'true' WHERE config_key = 'alert.mail.enabled'");
      jdbc.update("UPDATE common_config SET value = 'cards.font,scan.ERC20' WHERE config_key = 'alert.mail.muted'");
      config.refresh();
      assertThat(mailSwitch.muted()).containsExactly("cards.font", "scan.ERC20");
      other.check();
      worker.drain();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE recipient = ?", Integer.class, ops)).as("静音的项不发").isEqualTo(2);
    } finally {
      jdbc.update("UPDATE common_config SET value = 'true' WHERE config_key = 'alert.mail.enabled'");
      jdbc.update("UPDATE common_config SET value = '' WHERE config_key = 'alert.mail.muted'");
      config.refresh();
    }
  }
}
