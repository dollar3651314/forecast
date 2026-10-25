package com.forecast.app.resolve.source;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import tools.jackson.databind.JsonNode;

/** 三个体育适配器共用的解析小工具（forecast S8）：取文字、取比分（非负整数）、取时间（UTC）。 */
final class Scores {

  private Scores() {}

  static String text(JsonNode n) {
    if (n == null || n.isNull() || n.isMissingNode()) return null;
    String s = n.isString() ? n.asString() : n.isNumber() ? n.asString() : null;
    if (s == null) return null;
    s = s.strip();
    return s.isEmpty() ? null : s;
  }

  /** 比分：非负整数（字符串或数字）；没有为 null；不是非负整数算格式错误（需求第 4.3 节"比分是非负整数"） */
  static Integer score(JsonNode n, String what) throws SourceException {
    String s = text(n);
    if (s == null) return null;
    try {
      int v = Integer.parseInt(s);
      if (v < 0) throw SourceException.badFormat(what + " 是负数：" + s);
      return v;
    } catch (NumberFormatException e) {
      throw SourceException.badFormat(what + " 不是整数：" + (s.length() > 20 ? s.substring(0, 20) : s));
    }
  }

  /** ISO 时间：带时区的按时区，不带的按 UTC（TheSportsDB 的 strTimestamp） */
  static Instant instant(String s) {
    if (s == null || s.isBlank()) return null;
    String v = s.strip();
    try {
      return OffsetDateTime.parse(v).toInstant();
    } catch (DateTimeParseException e) {
      try {
        return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
      } catch (DateTimeParseException e2) {
        try {
          return Instant.parse(v);
        } catch (DateTimeParseException e3) {
          return null;
        }
      }
    }
  }

  static String date(LocalDate d) {
    return d.toString();
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  static String trim(String base) {
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  /** 编号只能是字母、数字、减号（拼进地址，防止注入路径和参数） */
  static String ref(String ref) {
    if (ref == null || !ref.matches("[A-Za-z0-9-]{1,40}")) throw new IllegalArgumentException("比赛编号不对：" + ref);
    return ref;
  }
}
