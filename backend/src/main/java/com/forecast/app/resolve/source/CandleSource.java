package com.forecast.app.resolve.source;

import java.net.URI;

/**
 * 一个 K 线数据源（技术方案/v1-架构方案.md 第 6.1 节 CandleSource）：只负责"拼请求地址、解析返回"；
 * 超时、重试、熔断、存档在 {@link SourceGateway} 统一做，判定规则（两源一致）在 {@code resolve/auto/CryptoResolver}。
 */
public interface CandleSource {

  /** 存档和配置里的名字：BINANCE / OKX / COINBASE */
  String name();

  /** 配置 {@code forecast.resolve.sources.<key>} 的 key：binance / okx / coinbase */
  String key();

  /** 规则区、详情页显示的来源（例如 "Binance BTC/USDT"） */
  String label(CandleQuery q);

  URI uri(String baseUrl, CandleQuery q);

  /**
   * 解析返回（HTTP 2xx 的原文）。
   *
   * @throws SourceException NOT_READY：窗口的 K 线还不全或没收盘；BAD_FORMAT：格式不对
   */
  Candles parse(String body, CandleQuery q) throws SourceException;
}
