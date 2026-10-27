package com.forecast.app.market.autogen;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 窗口计算（需求第 3.7 节加密的开放、截止：窗口开始前 30 秒截止，15 分钟提前 60 分钟、1 小时提前 3 小时开放；第 4.2 节、AC-28；MQ-1 节目化）。
 */
class AutogenWindowsTest {

  static final Duration H1 = Duration.ofHours(1);
  static final Duration M15 = Duration.ofMinutes(15);
  static final Duration OPEN_1H = Duration.ofMinutes(180);
  static final Duration OPEN_15M = Duration.ofMinutes(60);
  static final Duration CLOSE = Duration.ofSeconds(30);
  static final List<LocalTime> SLOT_13 = List.of(LocalTime.of(13, 0));

  static Instant at(String s) {
    return Instant.parse(s);
  }

  @Test
  void 一个窗口的四个时间() {
    AutogenWindows.Window w = AutogenWindows.window(at("2026-11-01T13:00:00Z"), H1, OPEN_1H, CLOSE);
    assertThat(w.openAt()).isEqualTo(at("2026-11-01T10:00:00Z"));
    assertThat(w.closeAt()).isEqualTo(at("2026-11-01T12:59:30Z"));
    assertThat(w.start()).isEqualTo(at("2026-11-01T13:00:00Z"));
    assertThat(w.end()).isEqualTo(at("2026-11-01T14:00:00Z"));
    // 开放提前量不比截止提前量大（参数改得不合理）：开放 = 截止前 1 分钟
    AutogenWindows.Window odd = AutogenWindows.window(at("2026-11-01T13:00:00Z"), M15, Duration.ofMinutes(1), Duration.ofMinutes(5));
    assertThat(odd.openAt()).isEqualTo(at("2026-11-01T12:54:00Z"));
  }

  @Test
  void 按场次_每天13点一场_开放前60分钟生成() {
    Duration ahead = Duration.ofMinutes(60);
    // 09:00 之前不生成（开放 10:00，提前 60 分钟生成）
    assertThat(AutogenWindows.due("SCHEDULE", H1, SLOT_13, 3, ahead, OPEN_1H, CLOSE, at("2026-11-01T08:59:59Z"))).isEmpty();
    List<AutogenWindows.Window> d = AutogenWindows.due("SCHEDULE", H1, SLOT_13, 3, ahead, OPEN_1H, CLOSE, at("2026-11-01T09:00:00Z"));
    assertThat(d).extracting(AutogenWindows.Window::start).containsExactly(at("2026-11-01T13:00:00Z"));
    // 截止前一刻还在；截止以后不再"该存在"（已生成的照常，不再新生成）
    assertThat(AutogenWindows.due("SCHEDULE", H1, SLOT_13, 3, ahead, OPEN_1H, CLOSE, at("2026-11-01T12:59:29.999Z"))).hasSize(1);
    assertThat(AutogenWindows.due("SCHEDULE", H1, SLOT_13, 3, ahead, OPEN_1H, CLOSE, at("2026-11-01T12:59:30Z"))).isEmpty();
    // 生成提前量 0：开放时间才生成
    assertThat(AutogenWindows.due("SCHEDULE", H1, SLOT_13, 3, Duration.ZERO, OPEN_1H, CLOSE, at("2026-11-01T09:59:59Z"))).isEmpty();
    assertThat(AutogenWindows.due("SCHEDULE", H1, SLOT_13, 3, Duration.ZERO, OPEN_1H, CLOSE, at("2026-11-01T10:00:00Z"))).hasSize(1);
    // 提前 1 天生成：今天 13:00 的和明天 13:00 的都该存在
    assertThat(AutogenWindows.due("SCHEDULE", H1, SLOT_13, 3, Duration.ofDays(1), OPEN_1H, CLOSE, at("2026-11-01T12:00:00Z")))
        .extracting(AutogenWindows.Window::start).containsExactly(at("2026-11-01T13:00:00Z"), at("2026-11-02T13:00:00Z"));
  }

  @Test
  void 按场次_跨零点和多个场次() {
    List<LocalTime> slots = List.of(LocalTime.of(0, 0), LocalTime.of(23, 0), LocalTime.of(13, 0));
    // 开放 3 小时前、生成再提前 60 分钟：22:00 时 00:00（明天）和 23:00 都该存在
    assertThat(AutogenWindows.due("SCHEDULE", H1, slots, 3, Duration.ofMinutes(60), OPEN_1H, CLOSE, at("2026-11-01T22:00:00Z")))
        .extracting(AutogenWindows.Window::start).containsExactly(at("2026-11-01T23:00:00Z"), at("2026-11-02T00:00:00Z"));
    // 预览：接下来的 4 个窗口（不管生成时间）
    assertThat(AutogenWindows.upcoming("SCHEDULE", H1, AutogenWindows.daily(slots), 4, OPEN_1H, CLOSE, at("2026-11-01T22:00:00Z"))).extracting(AutogenWindows.Window::start)
        .containsExactly(at("2026-11-01T23:00:00Z"), at("2026-11-02T00:00:00Z"), at("2026-11-02T13:00:00Z"), at("2026-11-02T23:00:00Z"));
  }

  @Test
  void 按场次_限定星期几_S13() {
    // 2026-11-07 是周六、11-08 周日、11-09 周一（UTC 日期）。13:00 只在周末，01:00 每天
    List<AutogenWindows.Slot> slots = List.of(AutogenService.slot("13:00@6,7"), AutogenService.slot("01:00"));
    assertThat(AutogenWindows.upcoming("SCHEDULE", H1, slots, 6, OPEN_1H, CLOSE, at("2026-11-06T12:00:00Z"))).extracting(AutogenWindows.Window::start)
        .containsExactly(at("2026-11-07T01:00:00Z"), at("2026-11-07T13:00:00Z"), at("2026-11-08T01:00:00Z"), at("2026-11-08T13:00:00Z"),
            at("2026-11-09T01:00:00Z"), at("2026-11-10T01:00:00Z"));
    // 写法：七天全选 = 每天；格式不对、重复的星期为空；标准写法星期从小到大
    assertThat(AutogenService.format(AutogenService.slot("13:00@7,6"))).isEqualTo("13:00@6,7");
    assertThat(AutogenService.format(AutogenService.slot("13:00@1,2,3,4,5,6,7"))).isEqualTo("13:00");
    assertThat(AutogenService.slot("13:00@8")).isNull();
    assertThat(AutogenService.slot("13:00@6,6")).isNull();
    assertThat(AutogenService.slot("13:00@")).isNull();
    assertThat(AutogenService.slot("25:00")).isNull();
  }

  @Test
  void 场次写法宽松_都规范成HHmm_S12c() {
    // 测试环境反馈：填 "1:00" 后台显示北京 09:00，保存却报"场次格式不正确"。下面这些写法都要能用，保存成标准写法
    for (String in : List.of("1:00", "1:0", "01:00", "1", "01", " 1:00 ", "\t01:00\u3000", "1：00", "1 : 00", "\u00A01:00")) {
      AutogenService.SlotParse p = AutogenService.parseSlot(in);
      assertThat(p.code()).as(in).isNull();
      assertThat(AutogenService.format(p.slot())).as(in).isEqualTo("01:00");
    }
    assertThat(AutogenService.format(AutogenService.slot("0"))).isEqualTo("00:00");
    assertThat(AutogenService.format(AutogenService.slot("23:59"))).isEqualTo("23:59");
    assertThat(AutogenService.format(AutogenService.slot("9:5"))).as("分一位 = 那个数，9:5 是 09:05").isEqualTo("09:05");
    assertThat(AutogenService.format(AutogenService.slot(" 13:00 @ 7 , 6 "))).isEqualTo("13:00@6,7");
    assertThat(AutogenService.format(AutogenService.slot("1@6"))).isEqualTo("01:00@6");
    // 不合法：说清楚错在哪
    assertThat(AutogenService.parseSlot("").code()).isEqualTo("required");
    assertThat(AutogenService.parseSlot("   ").code()).isEqualTo("required");
    assertThat(AutogenService.parseSlot(null).code()).isEqualTo("required");
    for (String bad : List.of("abc", "1:", ":00", "1:000", "100", "01-00", "1.00", "1:00pm", "-1:00", "1 00", "１:00")) {
      assertThat(AutogenService.parseSlot(bad).code()).as(bad).isEqualTo("timeFormat");
    }
    for (String bad : List.of("24:00", "25:00", "12:60", "99:99")) {
      assertThat(AutogenService.parseSlot(bad).code()).as(bad).isEqualTo("outOfRange");
    }
    for (String bad : List.of("13:00@", "13:00@8", "13:00@0", "13:00@6,6", "13:00@6,", "13:00@a")) {
      assertThat(AutogenService.parseSlot(bad).code()).as(bad).isEqualTo("invalidDays");
    }
  }

  @Test
  void 滚动_任何时刻未来至少N个窗口可下注或待开放_AC28() {
    // 15 分钟 × 4：每分钟检查一次，跑一整天
    for (Instant t = at("2026-11-01T00:00:00Z"); t.isBefore(at("2026-11-02T00:00:00Z")); t = t.plusSeconds(37)) {
      List<AutogenWindows.Window> w = AutogenWindows.due("ROLLING", M15, List.of(), 4, Duration.ZERO, OPEN_15M, CLOSE, t);
      assertThat(w).hasSize(4);
      for (AutogenWindows.Window x : w) {
        assertThat(x.closeAt()).isAfter(t);
        assertThat(x.start().getEpochSecond() % 900).isZero();
      }
      assertThat(w.get(0).closeAt()).isBeforeOrEqualTo(t.plus(M15));
    }
    // 1 小时 × 3
    List<AutogenWindows.Window> h = AutogenWindows.due("ROLLING", H1, List.of(), 3, Duration.ZERO, OPEN_1H, CLOSE, at("2026-11-01T12:59:29Z"));
    assertThat(h).extracting(AutogenWindows.Window::start).containsExactly(at("2026-11-01T13:00:00Z"), at("2026-11-01T14:00:00Z"), at("2026-11-01T15:00:00Z"));
    // 12:59:30 起 13:00 的窗口已截止：下一组从 14:00 开始
    assertThat(AutogenWindows.due("ROLLING", H1, List.of(), 3, Duration.ZERO, OPEN_1H, CLOSE, at("2026-11-01T12:59:30Z")).get(0).start())
        .isEqualTo(at("2026-11-01T14:00:00Z"));
  }
}
