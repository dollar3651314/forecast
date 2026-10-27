package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.common.health.AlertMailSwitch;
import com.forecast.app.common.health.AlertProperties;
import com.forecast.app.notification.api.MailApi;
import com.forecast.app.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 告警邮件的规则（E8，AC2-20）：异常时发告警、恢复时发恢复、同一项 30 分钟内不重复、没有收件人只记日志、检查本身出错不影响别的项。
 * 单元测试（邮件替身、可拨动的时钟）；邮件真的经过任务表和 SMTP 发出见 {@link AlertMailFlowTest}。
 */
class AlertMonitorTest {

  /** 记下发了哪些邮件："收件人 模板 变量" */
  static class Outbox implements MailApi {
    final List<String> sent = new ArrayList<>();

    @Override
    public void send(String to, String template, Map<String, String> params) {
      sent.add(to + " " + template + " " + params.get("itemZh") + (params.containsKey("detail") ? " " + params.get("detail") : ""));
    }

    @Override
    public void send(String to, String template, Map<String, String> params, String lang) {
      send(to, template, params);
    }
  }

  /** 状态可以改的检查项 */
  static class Switch implements AlertCheck {
    final String name;
    volatile boolean ok = true;

    Switch(String name) {
      this.name = name;
    }

    @Override
    public List<Item> evaluate() {
      return List.of(new Item(name, "项目" + name, "item " + name, ok, ok ? "正常" : name + " 坏了"));
    }
  }

  final MutableClock clock = new MutableClock();
  final Outbox outbox = new Outbox();

  static AlertProperties props(String... emails) {
    AlertProperties d = AlertProperties.defaults();
    return d.with(true, List.of(emails));
  }

  @Test
  void 异常时发一次告警_持续异常不重复_恢复时发恢复邮件_每个收件人一封() {
    Switch market = new Switch("market");
    AlertMonitor m = new AlertMonitor(List.of(market), props("ops@example.com", " ", "cto@example.com"), outbox, AlertMailSwitch.ALL, clock);
    m.check();
    assertThat(outbox.sent).as("正常时不发").isEmpty();
    market.ok = false;
    m.check();
    assertThat(outbox.sent).containsExactly("ops@example.com OPS_ALERT 项目market market 坏了", "cto@example.com OPS_ALERT 项目market market 坏了");
    clock.advance(Duration.ofMinutes(45));
    m.check();
    assertThat(outbox.sent).as("一直异常：这次异常已经发过，不再发").hasSize(2);
    market.ok = true;
    m.check();
    assertThat(outbox.sent).hasSize(4).endsWith("cto@example.com OPS_RECOVERED 项目market");
    m.check();
    assertThat(outbox.sent).as("恢复只发一次").hasSize(4);
  }

  @Test
  void 同一项30分钟内反复断开不重复发_过了30分钟还在异常再发一封() {
    Switch node = new Switch("node.TRC20");
    AlertMonitor m = new AlertMonitor(List.of(node), props("ops@example.com"), outbox, AlertMailSwitch.ALL, clock);
    node.ok = false;
    m.check();
    node.ok = true;
    clock.advance(Duration.ofMinutes(1));
    m.check();
    assertThat(outbox.sent).extracting(s -> s.split(" ")[1]).containsExactly("OPS_ALERT", "OPS_RECOVERED");
    // 5 分钟后又断：距离上一封告警不到 30 分钟，不发；恢复时也不发恢复（这次没发告警）
    clock.advance(Duration.ofMinutes(4));
    node.ok = false;
    m.check();
    assertThat(outbox.sent).hasSize(2);
    clock.advance(Duration.ofMinutes(10));
    node.ok = true;
    m.check();
    assertThat(outbox.sent).as("这次没发告警，也不发恢复").hasSize(2);
    // 再断，并且一直持续到距离上一封告警满 30 分钟：发
    node.ok = false;
    m.check();
    assertThat(outbox.sent).hasSize(2);
    clock.advance(Duration.ofMinutes(15));
    m.check();
    assertThat(outbox.sent).hasSize(3).last().asString().startsWith("ops@example.com OPS_ALERT");
  }

  @Test
  void 每一项分别节流_检查本身出错不影响别的项() {
    Switch market = new Switch("market");
    Switch mail = new Switch("mail");
    AlertCheck broken = () -> {
      throw new IllegalStateException("检查出错");
    };
    AlertMonitor m = new AlertMonitor(List.of(broken, market, mail), props("ops@example.com"), outbox, AlertMailSwitch.ALL, clock);
    market.ok = false;
    m.check();
    mail.ok = false;
    m.check();
    assertThat(outbox.sent).extracting(s -> s.split(" ")[2]).containsExactly("项目market", "项目mail");
  }

  @Test
  void 没有配置告警邮箱时只记日志() {
    Switch market = new Switch("market");
    AlertMonitor m = new AlertMonitor(List.of(market), props(), outbox, AlertMailSwitch.ALL, clock);
    market.ok = false;
    m.check();
    market.ok = true;
    m.check();
    assertThat(outbox.sent).isEmpty();
  }

  @Test
  void 关闭时定时任务不运行() {
    Switch market = new Switch("market");
    AlertProperties d = AlertProperties.defaults();
    AlertProperties off = d.with(false, List.of("ops@example.com"));
    market.ok = false;
    new AlertMonitor(List.of(market), off, outbox, AlertMailSwitch.ALL, clock).tick();
    assertThat(outbox.sent).isEmpty();
    new AlertMonitor(List.of(market), props("ops@example.com"), outbox, AlertMailSwitch.ALL, clock).tick();
    assertThat(outbox.sent).hasSize(1);
  }

  @Test
  void 邮件连续5封发送失败告警_成功一封恢复_没配邮件服务器不检查() {
    MailHealth h = new MailHealth(AlertProperties.defaults(), true);
    for (int i = 0; i < 4; i++) h.failed("MailConnectException: Couldn't connect to host");
    assertThat(h.evaluate()).singleElement().matches(AlertCheck.Item::ok);
    h.failed("MailConnectException: Couldn't connect to host");
    AlertCheck.Item down = h.evaluate().getFirst();
    assertThat(down.ok()).isFalse();
    assertThat(down.name()).isEqualTo("mail");
    assertThat(down.detail()).contains("连续 5 封");
    h.succeeded();
    assertThat(h.evaluate().getFirst().ok()).isTrue();
    assertThat(new MailHealth(AlertProperties.defaults(), false).evaluate()).isEmpty();
  }

  /** 可以"不再检查"的项（例如热钱包余额告警的下限改回 0） */
  static class Toggle extends Switch {
    volatile boolean enabled = true;

    Toggle(String name) {
      super(name);
    }

    @Override
    public List<Item> evaluate() {
      return enabled ? super.evaluate() : List.of();
    }
  }

  @Test
  void 不再检查的项_清掉状态不发恢复邮件_重新启用后按新的一次异常处理() {
    Toggle hot = new Toggle("hot.ERC20.usdt");
    AlertMonitor m = new AlertMonitor(List.of(hot), props("ops@example.com"), outbox, AlertMailSwitch.ALL, clock);
    hot.ok = false;
    m.check();
    assertThat(outbox.sent).containsExactly("ops@example.com OPS_ALERT 项目hot.ERC20.usdt hot.ERC20.usdt 坏了");
    hot.enabled = false;
    m.check();
    assertThat(outbox.sent).as("下限改回 0：不发\"已恢复\"").hasSize(1);
    clock.advance(Duration.ofMinutes(31));
    hot.enabled = true;
    m.check();
    assertThat(outbox.sent).as("重新启用、仍然低：过了节流期再发一封告警").hasSize(2);
    hot.ok = true;
    m.check();
    assertThat(outbox.sent).last().asString().contains("OPS_RECOVERED");
  }

  @Test
  void 有检查出错的一轮不清别的项的状态() {
    Toggle hot = new Toggle("hot.BSC.gas");
    AlertCheck bad = () -> {
      throw new IllegalStateException("坏了");
    };
    java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(false);
    AlertCheck flaky = () -> {
      if (fail.get()) return bad.evaluate();
      return List.of();
    };
    AlertMonitor m = new AlertMonitor(List.of(flaky, hot), props("ops@example.com"), outbox, AlertMailSwitch.ALL, clock);
    hot.ok = false;
    m.check();
    fail.set(true);
    hot.enabled = false;
    m.check();
    fail.set(false);
    hot.enabled = true;
    m.check();
    assertThat(outbox.sent).as("状态没被清：持续异常不重复发").hasSize(1);
  }
}
