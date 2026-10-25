package com.forecast.app.market.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/** 费率、时间、金额在接口和数据库之间的换算（market 模块内部用）。 */
final class MarketFormats {

  static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  /** 费率上限 20%（需求第 3.8 节第 3 条） */
  static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(20);
  private static final DateTimeFormatter NOTICE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

  private MarketFormats() {}

  /** 数据库里的费率（0.0525）→ 接口里的百分比（"5.25"） */
  static String percent(BigDecimal rate) {
    if (rate == null) return null;
    BigDecimal p = rate.multiply(HUNDRED).stripTrailingZeros();
    return p.signum() == 0 ? "0" : p.toPlainString();
  }

  /**
   * 后台输入的百分比 → 数据库里的费率：0～20，最多 2 位小数，换算时不舍入（技术方案第 4.1 节 FeeRate）；不合法返回 null。
   */
  static BigDecimal rateOf(String percent) {
    if (percent == null) return null;
    try {
      BigDecimal p = new BigDecimal(percent.strip());
      if (p.signum() < 0 || p.compareTo(MAX_PERCENT) > 0 || p.stripTrailingZeros().scale() > 2) return null;
      return p.divide(HUNDRED, 4, RoundingMode.UNNECESSARY);
    } catch (NumberFormatException | ArithmeticException e) {
      return null;
    }
  }

  static String money(BigDecimal v) {
    if (v == null) return null;
    return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
  }

  /** 时间一律精确到毫秒（DATETIME(3)；MySQL 默认会把多出来的位四舍五入，可能让时间往后跳） */
  static LocalDateTime ldt(Instant i) {
    return i == null ? null : LocalDateTime.ofInstant(i.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  static Instant instant(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC);
  }

  /** 接口里的时间：ISO-8601 UTC，例如 2026-11-01T14:55:00Z */
  static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }

  /** 通知里的时间（需求第 3.7 节第 1 条：写 UTC），例如 2026-11-01 14:55 UTC */
  static String noticeTime(LocalDateTime t) {
    return t == null ? "" : NOTICE_TIME.format(t);
  }

  /** 解析接口里的时间（ISO-8601，必须带时区，例如 Z 或 +08:00）；不合法返回 null */
  static Instant parseInstant(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return java.time.OffsetDateTime.parse(s.strip()).toInstant();
    } catch (DateTimeParseException e) {
      try {
        return Instant.parse(s.strip());
      } catch (DateTimeParseException e2) {
        return null;
      }
    }
  }
}
