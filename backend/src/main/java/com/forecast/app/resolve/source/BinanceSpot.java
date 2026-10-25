package com.forecast.app.resolve.source;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 币安现货 K 线（主源，AQ-13；技术方案第 6.1 节）：{@code GET /api/v3/klines?symbol=BTCUSDT&interval=1m&startTime=<窗口开始>&endTime=<窗口结束 − 1>}。
 * 返回数组，每根 {@code [开盘时间, 开盘价, 最高, 最低, 收盘价, 成交量, 收盘时间, …]}，价格是字符串。
 * 地址用 {@code data-api.binance.vision}（只读行情域名；开发环境实测 {@code api.binance.com} 返回 451，架构方案第 6.1 节）。
 */
@Component
public class BinanceSpot implements CandleSource {

  @Override
  public String name() {
    return "BINANCE";
  }

  @Override
  public String key() {
    return "binance";
  }

  @Override
  public String label(CandleQuery q) {
    return "Binance " + q.asset() + "/USDT";
  }

  @Override
  public URI uri(String baseUrl, CandleQuery q) {
    return URI.create(trim(baseUrl) + "/api/v3/klines?symbol=" + q.symbol() + "&interval=1m&startTime=" + q.windowStart().toEpochMilli() + "&endTime="
        + (q.windowEnd().toEpochMilli() - 1) + "&limit=1000");
  }

  @Override
  public Candles parse(String body, CandleQuery q) throws SourceException {
    JsonNode root = Klines.read(body);
    if (!root.isArray()) {
      // 出错时币安返回 {"code": -1121, "msg": "Invalid symbol."}
      throw SourceException.badFormat(root.has("code") ? "错误码 " + root.path("code").asString() + "：" + root.path("msg").asString() : "不是数组");
    }
    List<Klines.Bar> bars = new ArrayList<>();
    for (JsonNode k : root) {
      if (!k.isArray() || k.size() < 7) throw SourceException.badFormat("K 线字段不全");
      bars.add(new Klines.Bar(Klines.millis(k.get(0), "开盘时间"), Klines.price(k.get(1), "开盘价"), Klines.price(k.get(4), "收盘价")));
    }
    return Klines.window(bars, q, true);
  }

  static String trim(String base) {
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }
}
