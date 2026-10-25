package com.forecast.app.resolve.source;

import java.time.Duration;
import java.time.Instant;

/**
 * 取一个窗口的 1 分钟 K 线（加密涨跌，需求第 4.3 节）。
 *
 * @param symbol 币安的交易对，例如 BTCUSDT（各适配器自己换算成 BTC-USDT、BTC-USD）
 * @param windowStart 窗口开始（含），整分钟
 * @param windowEnd 窗口结束（不含），整分钟
 */
public record CandleQuery(String symbol, Instant windowStart, Instant windowEnd) {

  /** 窗口里应有几根 1 分钟 K 线 */
  public int minutes() {
    return (int) Duration.between(windowStart, windowEnd).toMinutes();
  }

  /** 币种（BTCUSDT → BTC） */
  public String asset() {
    return symbol.endsWith("USDT") ? symbol.substring(0, symbol.length() - 4) : symbol;
  }
}
