package com.forecast.app.content.check;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数字核对（forecast S15，自动化需求第 4.3 节第 2 条、EX-1、AC-107）：文中出现的每一个数字都必须能在"引用的数据"里找到，允许按显示规则舍入
 * （引用 58.33 → 文中 58.3 或 58；四舍五入或向下取整都算）。找不到的数字返回位置（后台标红），这条草稿不能发布。
 * <ul>
 *   <li>数字：阿拉伯数字，可以带千分位逗号和小数（"1,234.56"、"58.3"）；紧跟在字母后面的不算（队名 U19、币种代号里的数字）。</li>
 *   <li>日期、时间拆成几段数字分别核对（"2026-10-25 13:00" → 2026、10、25、13、00），引用的数据里的日期时间同样拆开。</li>
 *   <li>行首的列表序号（"1. ""2、"）不算。</li>
 *   <li>中文数字（"两场""三成"）不核对（写作规则要求一律用阿拉伯数字，提示里写明）。</li>
 * </ul>
 */
public final class NumberCheck {

  private NumberCheck() {}

  /** 一个对不上的数字：原文和位置 [start, end) */
  public record Hit(String text, int start, int end) {}

  static final Pattern NUM = Pattern.compile("(?<![A-Za-z0-9_])(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d+))?");
  static final Pattern LIST_MARK = Pattern.compile("(?m)^\\s*\\d{1,2}[.、)）]\\s?");

  /** 引用的数据里出现的全部数字 */
  public static Set<BigDecimal> numbersIn(Collection<String> texts) {
    Set<BigDecimal> out = new HashSet<>();
    for (String t : texts) {
      if (t == null) continue;
      Matcher m = NUM.matcher(t);
      while (m.find()) out.add(value(m));
    }
    return out;
  }

  /** 文中对不上的数字（空 = 全部能找到） */
  public static List<Hit> check(String text, Set<BigDecimal> refs) {
    List<Hit> out = new ArrayList<>();
    if (text == null || text.isEmpty()) return out;
    String masked = mask(text);
    Matcher m = NUM.matcher(masked);
    while (m.find()) {
      BigDecimal v = value(m);
      int scale = m.group(2) == null ? 0 : m.group(2).length();
      if (!found(v, scale, refs)) out.add(new Hit(text.substring(m.start(), m.end()), m.start(), m.end()));
    }
    return out;
  }

  /** 列表序号换成同样长度的空格（位置不变） */
  private static String mask(String text) {
    Matcher m = LIST_MARK.matcher(text);
    StringBuilder sb = new StringBuilder(text);
    while (m.find()) for (int i = m.start(); i < m.end(); i++) sb.setCharAt(i, ' ');
    return sb.toString();
  }

  static boolean found(BigDecimal v, int scale, Set<BigDecimal> refs) {
    for (BigDecimal r : refs) {
      if (r.compareTo(v) == 0) return true;
      if (r.scale() > scale) {
        if (r.setScale(scale, RoundingMode.HALF_UP).compareTo(v) == 0) return true;
        if (r.setScale(scale, RoundingMode.DOWN).compareTo(v) == 0) return true;
      }
    }
    return false;
  }

  private static BigDecimal value(Matcher m) {
    String intPart = m.group(1).replace(",", "");
    return new BigDecimal(m.group(2) == null ? intPart : intPart + "." + m.group(2));
  }
}
