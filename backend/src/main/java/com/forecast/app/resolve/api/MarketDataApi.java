package com.forecast.app.resolve.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 公开行情数据（forecast S15，给自动化程序的数据管道用：BTC 每小时涨跌统计页、行情短文、数据卡、对照图；自动化需求第 5 节、MF-9、MF-17）。
 * 外部调用照常走数据源网关（超时、有上限的重试、熔断、原始返回存档，约定 40），<b>不能在数据库事务里调用</b>。取不到时返回 ok = false 和原因，调用方按
 * "数据不可用"降级，不影响别的功能。
 */
public interface MarketDataApi {

  /** 一根已收盘的 1 小时 K 线 */
  record Bar(Instant openTime, BigDecimal open, BigDecimal close) {}

  /**
   * 最近的 1 小时 K 线。
   *
   * @param source 来源标签（"Binance BTC/USDT" / "OKX BTC-USDT"），页面和文章里要写明
   * @param bars 按时间从早到晚，最后一根是当前整点之前那一小时
   * @param sha256 原始返回的 sha256（主源一次请求；备源多页时是最后一页）
   */
  record Hourly(String symbol, String source, boolean ok, List<Bar> bars, String error, Instant fetchedAt, String sha256) {}

  /** 最近 hours 根（1～168）已收盘的 1 小时 K 线：主源币安，拿不到用 OKX；都拿不到 ok = false */
  Hourly hourly(String symbol, int hours);

  /** Polymarket 一个选项的价格（0～1 的小数，即市场的隐含概率） */
  record Outcome(String name, String price) {}

  /**
   * Polymarket 公开接口的一个市场（MF-9 对照图的素材）。<b>接口使用条款还没核实，默认关闭</b>（{@code forecast.resolve.sources.polymarket.enabled}，
   * 环境变量 {@code FC_SOURCE_POLYMARKET_ENABLED}）；关闭时 enabled = false、不调用。用的时候必须写"数据来自 Polymarket 公开接口，非 Polymarket 官方"和查询时间（F-11）。
   */
  record PolymarketQuote(boolean enabled, boolean ok, String slug, String question, List<Outcome> outcomes, String error, Instant fetchedAt) {}

  PolymarketQuote polymarket(String slug);
}
