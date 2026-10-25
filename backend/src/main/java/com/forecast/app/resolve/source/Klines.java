package com.forecast.app.resolve.source;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 三个适配器共用的解析小工具：读 JSON、取数字、按窗口筛选、按时间排序、检查根数。 */
final class Klines {

  static final ObjectMapper JSON = new ObjectMapper();
  static final long MINUTE = 60_000;

  private Klines() {}

  /** 一根 1 分钟 K 线（开盘时间毫秒、开盘价、收盘价） */
  record Bar(long openTime, BigDecimal open, BigDecimal close) {}

  static JsonNode read(String body) throws SourceException {
    try {
      JsonNode n = JSON.readTree(body == null ? "" : body);
      if (n == null || n.isMissingNode() || n.isNull()) throw SourceException.badFormat("返回为空");
      return n;
    } catch (JacksonException e) {
      throw SourceException.badFormat("不是合法的 JSON");
    }
  }

  static BigDecimal price(JsonNode n, String what) throws SourceException {
    String s = n == null || n.isNull() || n.isMissingNode() ? "" : (n.isString() ? n.asString() : n.isNumber() ? n.asString() : "");
    try {
      BigDecimal v = new BigDecimal(s.strip());
      if (v.signum() <= 0) throw SourceException.badFormat(what + " 不是正数：" + s);
      return v;
    } catch (NumberFormatException e) {
      throw SourceException.badFormat(what + " 不是数字：" + (s.length() > 40 ? s.substring(0, 40) : s));
    }
  }

  static long millis(JsonNode n, String what) throws SourceException {
    if (n != null && n.isIntegralNumber()) return n.asLong();
    if (n != null && n.isString()) {
      try {
        return Long.parseLong(n.asString().strip());
      } catch (NumberFormatException e) {
        // 落到下面
      }
    }
    throw SourceException.badFormat(what + " 不是时间戳");
  }

  /**
   * 只留窗口里的（开盘时间 ∈ [开始, 结束)），按时间排序；有重复时间算格式错误。
   *
   * @param strict true：根数必须等于窗口分钟数（币安、OKX 每分钟都有 K 线）；false：第一根和最后一根都在就行（Coinbase 没成交的分钟没有 K 线）
   */
  static Candles window(List<Bar> all, CandleQuery q, boolean strict) throws SourceException {
    long ws = q.windowStart().toEpochMilli();
    long we = q.windowEnd().toEpochMilli();
    List<Bar> in = new ArrayList<>();
    for (Bar b : all) if (b.openTime() >= ws && b.openTime() < we) in.add(b);
    in.sort(Comparator.comparingLong(Bar::openTime));
    for (int i = 1; i < in.size(); i++) if (in.get(i).openTime() == in.get(i - 1).openTime()) throw SourceException.badFormat("K 线时间重复");
    for (Bar b : in) if (b.openTime() % MINUTE != 0) throw SourceException.badFormat("K 线时间不是整分钟");
    int need = q.minutes();
    if (in.isEmpty()) throw SourceException.notReady("窗口里还没有 K 线（0 / " + need + "）");
    Bar first = in.get(0);
    Bar last = in.get(in.size() - 1);
    if (first.openTime() != ws || last.openTime() != we - MINUTE || (strict && in.size() != need)) {
      throw SourceException.notReady("窗口里的 K 线不全（" + in.size() + " / " + need + "）");
    }
    return new Candles(first.open(), last.close(), in.size(), Instant.ofEpochMilli(first.openTime()), Instant.ofEpochMilli(last.openTime()));
  }
}
