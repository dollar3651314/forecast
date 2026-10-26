package com.forecast.app.resolve.sports;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 体育玩法（需求说明书 v1.3 第 4.2 节；MQ-10：冷启动期优先大小球 2.5、NBA 大小分）。每个玩法一个规则文本模板（{@code resolve_sports_template}）。
 * 结果（outcome）和市场选项的对应写在市场的 {@code source_ref.options} 里（结果 → 选项序号）。
 */
enum Play {
  /** 足球胜平负（90 分钟，不含加时点球） */
  FOOTBALL_1X2("FOOTBALL", List.of("HOME", "DRAW", "AWAY"), false, null),
  /** 足球大小球（90 分钟总进球，分界线 .5，默认 2.5） */
  FOOTBALL_OU("FOOTBALL", List.of("OVER", "UNDER"), true, new BigDecimal("2.5")),
  /** NBA 胜负（含加时） */
  NBA_ML("NBA", List.of("HOME", "AWAY"), false, null),
  /** NBA 大小分（含加时，分界线 .5，没有默认值：运营按场次填） */
  NBA_TOTAL("NBA", List.of("OVER", "UNDER"), true, null);

  final String sport;
  final List<String> outcomes;
  final boolean needsLine;
  final BigDecimal defaultLine;

  Play(String sport, List<String> outcomes, boolean needsLine, BigDecimal defaultLine) {
    this.sport = sport;
    this.outcomes = outcomes;
    this.needsLine = needsLine;
    this.defaultLine = defaultLine;
  }

  static Play of(String s) {
    if (s == null) return null;
    try {
      return valueOf(s.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** 分界线：正数、.5 结尾（E8：不会出现正好相等）、不超过 999.5 */
  static boolean validLine(BigDecimal line) {
    if (line == null || line.signum() <= 0 || line.compareTo(new BigDecimal("999.5")) > 0) return false;
    BigDecimal twice = line.multiply(BigDecimal.valueOf(2));
    return twice.stripTrailingZeros().scale() <= 0 && twice.toBigInteger().testBit(0);
  }
}
