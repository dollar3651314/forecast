package com.forecast.app.resolve.source;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Coinbase 现货 K 线（第三源，<b>默认关闭</b>，AQ-13；技术方案第 6.1 节 X-7：只在主备一方拿不到时补位）：
 * {@code GET /api/v3/brokerage/market/products/BTC-USD/candles?start=<秒>&end=<秒>&granularity=ONE_MINUTE}。
 * 注意是 <b>BTC-USD</b>（不是 USDT），只比较涨跌方向。返回 {@code {"candles":[{"start":"秒","open","close",…}]}}，新的在前；
 * 没有成交的分钟没有 K 线，所以只要求第一分钟和最后一分钟都有。
 */
@Component
public class CoinbaseSpot implements CandleSource {

  @Override
  public String name() {
    return "COINBASE";
  }

  @Override
  public String key() {
    return "coinbase";
  }

  @Override
  public String label(CandleQuery q) {
    return "Coinbase " + q.asset() + "-USD";
  }

  @Override
  public URI uri(String baseUrl, CandleQuery q) {
    return URI.create(BinanceSpot.trim(baseUrl) + "/api/v3/brokerage/market/products/" + q.asset() + "-USD/candles?start=" + q.windowStart().getEpochSecond()
        + "&end=" + (q.windowEnd().getEpochSecond() - 60) + "&granularity=ONE_MINUTE");
  }

  @Override
  public Candles parse(String body, CandleQuery q) throws SourceException {
    JsonNode root = Klines.read(body);
    JsonNode candles = root.path("candles");
    if (!root.isObject() || !candles.isArray()) throw SourceException.badFormat(root.has("error") ? "错误：" + root.path("error").asString() : "没有 candles 数组");
    List<Klines.Bar> bars = new ArrayList<>();
    for (JsonNode c : candles) {
      if (!c.isObject()) throw SourceException.badFormat("K 线不是对象");
      bars.add(new Klines.Bar(Klines.millis(c.path("start"), "start") * 1000, Klines.price(c.path("open"), "开盘价"), Klines.price(c.path("close"), "收盘价")));
    }
    return Klines.window(bars, q, false);
  }
}
