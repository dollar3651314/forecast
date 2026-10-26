package com.forecast.app.market.autogen;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 算哪些窗口该生成（纯计算，forecast S6，需求第 4.2 节、AC-28；MQ-1 节目化）。时间一律 UTC。
 * <ul>
 *   <li>窗口开始时间按周期对齐（15 分钟 / 整点）；截止 = 窗口开始 − 截止提前量；开放 = 窗口开始 − 开放提前量（参数设置）。</li>
 *   <li><b>按场次（SCHEDULE）</b>：每天每个场次一个窗口（S13：场次可以限定星期几，按 UTC 日期的星期）；还能下注（截止时间在现在之后）、
 *       并且到了"开放前 generateAhead 分钟"的生成。</li>
 *   <li><b>滚动（ROLLING）</b>：截止时间在现在之后的最近 N 个窗口都生成——任何时刻至少 N 个窗口可下注或待开放（AC-28）。</li>
 * </ul>
 */
final class AutogenWindows {

  private AutogenWindows() {}

  /**
   * 场次表的一项（需求第 4.2 节"一组 UTC 窗口开始时刻 + 星期几"，S13 补上星期几）：days 为空 = 每天；否则只在这些星期（UTC 日期）生成。
   */
  record Slot(LocalTime time, Set<DayOfWeek> days) {

    static Slot daily(LocalTime t) {
      return new Slot(t, EnumSet.noneOf(DayOfWeek.class));
    }

    boolean on(LocalDate d) {
      return days.isEmpty() || days.contains(d.getDayOfWeek());
    }
  }

  static List<Slot> daily(List<LocalTime> times) {
    return times.stream().map(Slot::daily).toList();
  }

  /** 一个窗口的四个时间（开放、截止、窗口开始 = 事件开始、窗口结束 = 事件结束） */
  record Window(Instant openAt, Instant closeAt, Instant start, Instant end) {}

  static Window window(Instant start, Duration period, Duration openLead, Duration closeLead) {
    Instant close = start.minus(closeLead);
    Instant open = start.minus(openLead);
    // 开放提前量不比截止提前量大时（参数设置改得不合理），开放 = 截止前 1 分钟，保证 开放 < 截止（第 3.7 节第 3 条）
    if (!open.isBefore(close)) open = close.minus(Duration.ofMinutes(1));
    return new Window(open, close, start, start.plus(period));
  }

  /** 现在该存在（已上架）的窗口，按开始时间排序 */
  static List<Window> due(String mode, Duration period, List<LocalTime> slots, int rollingCount, Duration generateAhead, Duration openLead,
      Duration closeLead, Instant now) {
    return dueSlots(mode, period, daily(slots), rollingCount, generateAhead, openLead, closeLead, now);
  }

  static List<Window> dueSlots(String mode, Duration period, List<Slot> slots, int rollingCount, Duration generateAhead, Duration openLead,
      Duration closeLead, Instant now) {
    if ("ROLLING".equals(mode)) return rolling(period, rollingCount, openLead, closeLead, now);
    List<Window> out = new ArrayList<>();
    for (Window w : scheduled(period, slots, openLead, closeLead, now, Duration.ofDays(8))) {
      if (!now.isBefore(w.openAt().minus(generateAhead))) out.add(w);
    }
    return out;
  }

  /** 接下来的 n 个窗口（后台预览；按场次时不管生成时间） */
  static List<Window> upcoming(String mode, Duration period, List<Slot> slots, int n, Duration openLead, Duration closeLead, Instant now) {
    if ("ROLLING".equals(mode)) return rolling(period, n, openLead, closeLead, now);
    List<Window> all = scheduled(period, slots, openLead, closeLead, now, Duration.ofDays(8));
    return all.subList(0, Math.min(n, all.size()));
  }

  /** 截止时间在 now 之后的最近 n 个对齐的窗口 */
  static List<Window> rolling(Duration period, int n, Duration openLead, Duration closeLead, Instant now) {
    long p = period.toMillis();
    long x = now.plus(closeLead).toEpochMilli();
    long first = Math.floorDiv(x, p) * p + p; // 最小的、开始时间 > now + 截止提前量的对齐窗口
    List<Window> out = new ArrayList<>();
    for (int i = 0; i < n; i++) out.add(window(Instant.ofEpochMilli(first + i * p), period, openLead, closeLead));
    return out;
  }

  /** 按场次：从昨天到 now + horizon，截止时间在 now 之后的窗口 */
  static List<Window> scheduled(Duration period, List<Slot> slots, Duration openLead, Duration closeLead, Instant now, Duration horizon) {
    List<Window> out = new ArrayList<>();
    LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(1);
    LocalDate last = LocalDate.ofInstant(now.plus(horizon).plus(openLead), ZoneOffset.UTC);
    for (LocalDate d = day; !d.isAfter(last); d = d.plusDays(1)) {
      for (Slot sl : slots) {
        if (!sl.on(d)) continue;
        Window w = window(d.atTime(sl.time()).toInstant(ZoneOffset.UTC), period, openLead, closeLead);
        if (w.closeAt().isAfter(now)) out.add(w);
      }
    }
    out.sort((a, b) -> a.start().compareTo(b.start()));
    return out;
  }
}
