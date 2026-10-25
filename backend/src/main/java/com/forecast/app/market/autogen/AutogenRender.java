package com.forecast.app.market.autogen;

import com.forecast.app.market.api.I18nText;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则文本模板的变量（需求第 4.2 节"规则文本模板（含变量：币种、窗口开始和结束）"，forecast S6）。时间一律 UTC（第 3.7 节第 1 条）。
 * <ul>
 *   <li>{@code {asset}} BTC；{@code {pair}} BTC/USDT；{@code {okxPair}} BTC-USDT</li>
 *   <li>{@code {date}} 窗口开始的日期 2026-11-01；{@code {startTime}} 13:00；{@code {endTime}} 14:00；{@code {minutes}} 窗口长度 60</li>
 *   <li>{@code {closeLeadSeconds}} 截止提前量（参数设置里的 crypto.closeLeadSeconds）</li>
 * </ul>
 */
final class AutogenRender {

  static final List<String> VARIABLES = List.of("asset", "pair", "okxPair", "date", "startTime", "endTime", "minutes", "closeLeadSeconds");
  private static final Set<String> KNOWN = Set.copyOf(VARIABLES);
  private static final Pattern VAR = Pattern.compile("\\{([A-Za-z]+)}");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

  private AutogenRender() {}

  /** 第一期支持的币种（RQ-13：BTC、ETH）：币安 / OKX 都是对 USDT 的现货 */
  static final List<String> SYMBOLS = List.of("BTCUSDT", "ETHUSDT");

  static String asset(String symbol) {
    return symbol.substring(0, symbol.length() - "USDT".length());
  }

  static Map<String, String> vars(String symbol, Instant windowStart, Instant windowEnd, long closeLeadSeconds) {
    Map<String, String> v = new LinkedHashMap<>();
    String a = asset(symbol);
    v.put("asset", a);
    v.put("pair", a + "/USDT");
    v.put("okxPair", a + "-USDT");
    v.put("date", DATE.format(windowStart));
    v.put("startTime", HM.format(windowStart));
    v.put("endTime", HM.format(windowEnd));
    v.put("minutes", String.valueOf((windowEnd.getEpochSecond() - windowStart.getEpochSecond()) / 60));
    v.put("closeLeadSeconds", String.valueOf(closeLeadSeconds));
    return v;
  }

  static String render(String text, Map<String, String> vars) {
    if (text == null) return "";
    Matcher m = VAR.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String v = vars.get(m.group(1));
      m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group() : v));
    }
    m.appendTail(out);
    return out.toString();
  }

  static I18nText render(I18nText t, Map<String, String> vars) {
    return new I18nText(render(t.en(), vars), render(t.zhCN(), vars), render(t.zhTW(), vars));
  }

  /** 文字里不认识的变量（模板保存时检查） */
  static List<String> unknown(String text) {
    List<String> out = new ArrayList<>();
    if (text == null) return out;
    Matcher m = VAR.matcher(text);
    while (m.find()) if (!KNOWN.contains(m.group(1))) out.add(m.group(1));
    return out;
  }
}
