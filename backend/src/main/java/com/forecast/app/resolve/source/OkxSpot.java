package com.forecast.app.resolve.source;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * OKX 现货 K 线（备源，AQ-13；技术方案第 6.1 节）：{@code GET /api/v5/market/history-candles?instId=BTC-USDT&bar=1m&after=<窗口结束>&before=<窗口开始 − 1>}
 * （after = 早于这个时间、before = 晚于这个时间，都不含）。返回 {@code {"code":"0","data":[[ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm], …]}}，
 * 新的在前；<b>只用 confirm = 1（已收盘）的</b>，窗口里有没收盘的算"还不全"。一次最多 100 根，够 15 分钟 / 1 小时的窗口。
 */
@Component
public class OkxSpot implements CandleSource {

  @Override
  public String name() {
    return "OKX";
  }

  @Override
  public String key() {
    return "okx";
  }

  @Override
  public String label(CandleQuery q) {
    return "OKX " + q.asset() + "-USDT";
  }

  @Override
  public URI uri(String baseUrl, CandleQuery q) {
    return URI.create(BinanceSpot.trim(baseUrl) + "/api/v5/market/history-candles?instId=" + q.asset() + "-USDT&bar=1m&after=" + q.windowEnd().toEpochMilli()
        + "&before=" + (q.windowStart().toEpochMilli() - 1) + "&limit=100");
  }

  @Override
  public Candles parse(String body, CandleQuery q) throws SourceException {
    JsonNode root = Klines.read(body);
    if (!root.isObject()) throw SourceException.badFormat("不是对象");
    String code = root.path("code").isMissingNode() ? null : root.path("code").asString();
    if (!"0".equals(code)) throw SourceException.badFormat("错误码 " + code + "：" + root.path("msg").asString());
    JsonNode data = root.path("data");
    if (!data.isArray()) throw SourceException.badFormat("data 不是数组");
    long ws = q.windowStart().toEpochMilli();
    long we = q.windowEnd().toEpochMilli();
    List<Klines.Bar> bars = new ArrayList<>();
    for (JsonNode k : data) {
      if (!k.isArray() || k.size() < 9) throw SourceException.badFormat("K 线字段不全");
      long ts = Klines.millis(k.get(0), "时间");
      if (ts >= ws && ts < we && !"1".equals(k.get(8).asString())) throw SourceException.notReady("窗口里有还没收盘的 K 线（confirm = 0）");
      bars.add(new Klines.Bar(ts, Klines.price(k.get(1), "开盘价"), Klines.price(k.get(4), "收盘价")));
    }
    return Klines.window(bars, q, true);
  }
}
