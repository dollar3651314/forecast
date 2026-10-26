package com.forecast.app.common.attribution;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 来源参数（forecast S13，MF-1；需求第 5.2 节"来源统计"、AC-83）：链接上的 {@code src}（渠道）、{@code cmp}（活动）、{@code ref}（分享人或渠道编号），
 * 以及 Telegram 机器人深链的 {@code start} 参数。<b>只用于来源统计，不给任何人奖励</b>（MQ-5）。
 * <p>注册（account）和访问统计（growth）共用这里的清洗和解码，规则只有一份：
 * <ul>
 *   <li>每个值只接受 {@code [A-Za-z0-9._~-]}，1～64 个字符（前后空白去掉）；不合法的当成没有（不影响注册）。</li>
 *   <li>{@code start} 用"同样的编码"（需求第 5.2 节）：几段用两个下划线连接，每段是"键-值"，键 s = src、c = cmp、r = ref，
 *       例如 {@code s-tg__c-w1__r-10023}（Telegram 只允许 {@code A-Za-z0-9_-}、最多 64 个字符）。链接上同时有 src 等参数时以链接上的为准；
 *       解不出来的 start 只保留原文。</li>
 * </ul>
 */
public final class Attribution {

  private Attribution() {}

  static final Pattern VALUE = Pattern.compile("[A-Za-z0-9._~-]{1,64}");
  static final Pattern START = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  /** 浏览器里保存 30 天（需求第 5.2 节），服务器多留一天余量 */
  public static final Duration MAX_AGE = Duration.ofDays(31);

  /** 一次来源（四个值都可能为空） */
  public record Source(String src, String cmp, String ref, String start) {

    public boolean isEmpty() {
      return src == null && cmp == null && ref == null && start == null;
    }
  }

  /** 清洗一个值：不合法为空 */
  public static String clean(String v) {
    if (v == null) return null;
    String s = v.strip();
    return VALUE.matcher(s).matches() ? s : null;
  }

  /** 清洗并解码：start 里的 s / c / r 只补链接上没有的那几个 */
  public static Source of(String src, String cmp, String ref, String start) {
    String s = clean(src);
    String c = clean(cmp);
    String r = clean(ref);
    String st = start == null ? null : start.strip();
    if (st != null && !START.matcher(st).matches()) st = null;
    if (st != null) {
      for (String seg : st.split("__")) {
        if (seg.length() < 3 || seg.charAt(1) != '-') continue;
        String v = clean(seg.substring(2));
        if (v == null) continue;
        switch (seg.charAt(0)) {
          case 's' -> s = s == null ? v : s;
          case 'c' -> c = c == null ? v : c;
          case 'r' -> r = r == null ? v : r;
          default -> {}
        }
      }
    }
    return new Source(s, c, r, st);
  }

  /** 浏览器记的时间：解析不了、在未来、比 31 天还早的，用 now */
  public static Instant at(String iso, Instant now) {
    if (iso == null || iso.isBlank()) return now;
    try {
      Instant t = Instant.parse(iso.strip());
      if (t.isAfter(now) || t.isBefore(now.minus(MAX_AGE))) return now;
      return t;
    } catch (RuntimeException e) {
      return now;
    }
  }
}
