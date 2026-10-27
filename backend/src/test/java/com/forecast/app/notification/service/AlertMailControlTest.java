package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.common.health.AlertMailSwitch;
import com.forecast.app.common.health.AlertProperties;
import com.forecast.app.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * forecast S12b：告警邮件要能控制是否发送（Amos 2026-10-27："告警邮件，我可以在什么地方控制 是否发送，否则在测试阶段我收到很多 没有的邮件"）。
 * <ul>
 *   <li>"未开通"（{@link AlertCheck.Status#NOT_CONFIGURED}）不发，重启后也不发；</li>
 *   <li>配置了但出错发一封；一直没恢复每 24 小时提醒一次（可调时钟）；恢复发一封；</li>
 *   <li>后台总开关关掉不发；"不发邮件的告警项"里的项不发，别的项照常。</li>
 * </ul>
 * 单元测试（邮件替身、可拨动的时钟、后台开关替身）；后台开关真的从数据库读、邮件真的经过任务表见 {@link AlertMailFlowTest}。
 */
class AlertMailControlTest {

  final MutableClock clock = new MutableClock();
  final AlertMonitorTest.Outbox outbox = new AlertMonitorTest.Outbox();
  final Settings settings = new Settings();

  /** 后台"参数设置 → 告警邮件"的替身 */
  static class Settings implements AlertMailSwitch {
    volatile boolean enabled = true;
    final Set<String> muted = new TreeSet<>();

    @Override
    public boolean mailEnabled() {
      return enabled;
    }

    @Override
    public Set<String> muted() {
      return Set.copyOf(muted);
    }
  }

  /** 状态可以是 UP / DOWN / NOT_CONFIGURED 的检查项 */
  static class Tri implements AlertCheck {
    final String name;
    volatile Status status = Status.UP;

    Tri(String name, Status status) {
      this.name = name;
      this.status = status;
    }

    @Override
    public List<Item> evaluate() {
      String detail = switch (status) {
        case UP -> "正常";
        case DOWN -> name + " 坏了";
        case NOT_CONFIGURED -> "未开通：没有配置 key";
      };
      return List.of(new Item(name, "项目" + name, "item " + name, status, detail));
    }
  }

  AlertMonitor monitor(AlertCheck... checks) {
    return new AlertMonitor(List.of(checks), AlertProperties.defaults().with(true, List.of("ops@example.com")), outbox, settings, clock);
  }

  List<String> templates() {
    return outbox.sent.stream().map(s -> s.split(" ")[1] + " " + s.split(" ")[2]).toList();
  }

  @Test
  void 未开通的项不发邮件_几天都不发_重启后也不发() {
    Tri tg = new Tri("telegram.bot", AlertCheck.Status.NOT_CONFIGURED);
    Tri bdl = new Tri("source.balldontlie", AlertCheck.Status.NOT_CONFIGURED);
    AlertMonitor m = monitor(tg, bdl);
    for (int i = 0; i < 4 * 24 * 3; i++) {
      m.check();
      clock.advance(Duration.ofMinutes(15));
    }
    assertThat(outbox.sent).as("3 天里一封都不发").isEmpty();
    AlertMonitor restarted = monitor(tg, bdl);
    restarted.check();
    assertThat(outbox.sent).as("应用重启（内存状态清空）后也不发").isEmpty();
  }

  @Test
  void 配置了但出错发一次_持续异常每24小时提醒一次_恢复发一封() {
    Tri node = new Tri("node.TRC20", AlertCheck.Status.UP);
    AlertMonitor m = monitor(node);
    m.check();
    node.status = AlertCheck.Status.DOWN;
    m.check();
    assertThat(templates()).containsExactly("OPS_ALERT 项目node.TRC20");
    // 每 15 秒查一次，查到差 1 分钟满 24 小时：不再发（原来的节流 30 分钟不会让持续异常重复发，现在的提醒间隔是 24 小时）
    for (int i = 0; i < 4 * 60 * 24 - 4; i++) {
      clock.advance(Duration.ofSeconds(15));
      m.check();
    }
    assertThat(outbox.sent).as("23 小时 59 分：只有第一封").hasSize(1);
    clock.advance(Duration.ofMinutes(1));
    m.check();
    assertThat(templates()).as("满 24 小时：提醒一次").containsExactly("OPS_ALERT 项目node.TRC20", "OPS_STILL_DOWN 项目node.TRC20");
    m.check();
    clock.advance(Duration.ofHours(23));
    m.check();
    assertThat(outbox.sent).as("提醒之后再等 24 小时").hasSize(2);
    clock.advance(Duration.ofHours(1));
    m.check();
    assertThat(templates()).last().isEqualTo("OPS_STILL_DOWN 项目node.TRC20");
    assertThat(outbox.sent).hasSize(3);
    node.status = AlertCheck.Status.UP;
    m.check();
    m.check();
    assertThat(templates()).as("恢复发一封").hasSize(4).last().isEqualTo("OPS_RECOVERED 项目node.TRC20");
  }

  @Test
  void 提醒间隔可以调_0表示不提醒() {
    Tri node = new Tri("scan.ERC20", AlertCheck.Status.DOWN);
    AlertProperties d = AlertProperties.defaults();
    AlertProperties sixHours = new AlertProperties(true, List.of("ops@example.com"), d.throttle(), d.scanStall(), d.nodeFailures(), d.mailFailures(), Duration.ofHours(6));
    AlertMonitor m = new AlertMonitor(List.of(node), sixHours, outbox, settings, clock);
    m.check();
    clock.advance(Duration.ofHours(6));
    m.check();
    assertThat(templates()).containsExactly("OPS_ALERT 项目scan.ERC20", "OPS_STILL_DOWN 项目scan.ERC20");

    outbox.sent.clear();
    AlertProperties never = new AlertProperties(true, List.of("ops@example.com"), d.throttle(), d.scanStall(), d.nodeFailures(), d.mailFailures(), Duration.ZERO);
    Tri other = new Tri("scan.BSC", AlertCheck.Status.DOWN);
    m = new AlertMonitor(List.of(other), never, outbox, settings, clock);
    m.check();
    clock.advance(Duration.ofDays(30));
    m.check();
    assertThat(templates()).containsExactly("OPS_ALERT 项目scan.BSC");
  }

  @Test
  void 节流期内反复断开又恢复_保持原来的处理() {
    Tri mail = new Tri("mail", AlertCheck.Status.DOWN);
    AlertMonitor m = monitor(mail);
    m.check();
    clock.advance(Duration.ofMinutes(1));
    mail.status = AlertCheck.Status.UP;
    m.check();
    clock.advance(Duration.ofMinutes(4));
    mail.status = AlertCheck.Status.DOWN;
    m.check();
    clock.advance(Duration.ofMinutes(5));
    mail.status = AlertCheck.Status.UP;
    m.check();
    assertThat(templates()).as("30 分钟内又断：不发告警，也不发恢复").containsExactly("OPS_ALERT 项目mail", "OPS_RECOVERED 项目mail");
    mail.status = AlertCheck.Status.DOWN;
    m.check();
    clock.advance(Duration.ofMinutes(20));
    m.check();
    assertThat(templates()).as("一直持续到节流期满：发").hasSize(3).last().isEqualTo("OPS_ALERT 项目mail");
  }

  @Test
  void 总开关关掉_只写日志不发_打开后还在异常的补发一封() {
    settings.enabled = false;
    Tri market = new Tri("market", AlertCheck.Status.DOWN);
    AlertMonitor m = monitor(market);
    m.check();
    clock.advance(Duration.ofDays(2));
    m.check();
    assertThat(outbox.sent).as("关掉时：告警、提醒都不发").isEmpty();
    settings.enabled = true;
    m.check();
    assertThat(templates()).as("打开后：这次异常还没发过，发一封").containsExactly("OPS_ALERT 项目market");
    settings.enabled = false;
    market.status = AlertCheck.Status.UP;
    m.check();
    assertThat(outbox.sent).as("关掉时恢复也不发").hasSize(1);
  }

  @Test
  void 静音的项不发_别的项照常() {
    settings.muted.add("cards.font");
    Tri font = new Tri("cards.font", AlertCheck.Status.DOWN);
    Tri scan = new Tri("scan.TRC20", AlertCheck.Status.DOWN);
    AlertMonitor m = monitor(font, scan);
    m.check();
    clock.advance(Duration.ofDays(1));
    m.check();
    font.status = AlertCheck.Status.UP;
    scan.status = AlertCheck.Status.UP;
    m.check();
    assertThat(templates()).as("只有没静音的项").containsExactly("OPS_ALERT 项目scan.TRC20", "OPS_STILL_DOWN 项目scan.TRC20", "OPS_RECOVERED 项目scan.TRC20");
  }

  @Test
  void 出错后变成未开通_清掉状态不发恢复_再配上又出错按新的一次() {
    Tri tg = new Tri("telegram.bot", AlertCheck.Status.DOWN);
    AlertMonitor m = monitor(tg);
    m.check();
    assertThat(templates()).containsExactly("OPS_ALERT 项目telegram.bot");
    tg.status = AlertCheck.Status.NOT_CONFIGURED;
    m.check();
    clock.advance(Duration.ofDays(3));
    m.check();
    assertThat(outbox.sent).as("去掉 token：不发恢复、不提醒").hasSize(1);
    tg.status = AlertCheck.Status.DOWN;
    m.check();
    assertThat(templates()).as("又配上 token 并且出错：新的一次异常").containsExactly("OPS_ALERT 项目telegram.bot", "OPS_ALERT 项目telegram.bot");
  }

  @Test
  void 读后台设置出错时按默认发() {
    AlertMailSwitch broken = new AlertMailSwitch() {
      @Override
      public boolean mailEnabled() {
        throw new IllegalStateException("数据库不可用");
      }

      @Override
      public Set<String> muted() {
        return Set.of();
      }
    };
    Tri market = new Tri("market", AlertCheck.Status.DOWN);
    new AlertMonitor(List.of(market), AlertProperties.defaults().with(true, List.of("ops@example.com")), outbox, broken, clock).check();
    assertThat(templates()).containsExactly("OPS_ALERT 项目market");
  }
}
