package com.forecast.app.resolve.source;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个来源解析出来的窗口价格：第一根 1 分钟 K 线的开盘价 = 开始价，最后一根的收盘价 = 结束价（需求第 4.3 节）。
 *
 * @param count 窗口里的 K 线根数（必须等于窗口分钟数才算齐）
 * @param firstOpenTime 第一根的开盘时间（= 窗口开始）
 * @param lastOpenTime 最后一根的开盘时间（= 窗口结束 − 1 分钟）
 */
public record Candles(BigDecimal open, BigDecimal close, int count, Instant firstOpenTime, Instant lastOpenTime) {

  public static final String UP = "UP";
  public static final String DOWN = "DOWN";

  /** 结束价 ≥ 开始价判"涨"，否则"跌"（E9：正好相等判涨） */
  public String direction() {
    return close.compareTo(open) >= 0 ? UP : DOWN;
  }

  /** 收盘 = 开盘（E9 的"正好相等"） */
  public boolean tie() {
    return close.compareTo(open) == 0;
  }

  /** 存档里的 parsed（价格是字符串，原样不舍入） */
  public Map<String, Object> toMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("startPrice", open.toPlainString());
    m.put("endPrice", close.toPlainString());
    m.put("direction", direction());
    m.put("count", count);
    m.put("firstOpenTime", firstOpenTime.toString());
    m.put("lastOpenTime", lastOpenTime.toString());
    return m;
  }
}
