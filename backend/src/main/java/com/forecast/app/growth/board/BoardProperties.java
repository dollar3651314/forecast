package com.forecast.app.growth.board;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据页（MF-17）的币安行情（{@code forecast.growth.board.*}，forecast S16）。
 * <ul>
 *   <li>{@code refresh-enabled}：每 {@code refresh-interval}（默认 60 秒）取一次 BTC 的 1 小时 K 线，结果放在内存里，页面和机器人直接读（不在请求里调外部接口）；
 *       后端测试关闭，测试直接调用 {@link BtcBoard#refresh()}。</li>
 *   <li>{@code binance-url}：和自动裁决同一个只读行情域名（api.binance.com 在部分出口返回 451，架构方案第 6.1 节）；端到端指向后端自己的替身。</li>
 *   <li>{@code hours}：统计过去多少个已经收盘的小时（默认 24）。</li>
 *   <li>超时、重试、熔断（C81）：连接 3 秒、读取 5 秒、最多重试 2 次（只重试超时、断开、5xx、429）；连续 5 次取不到后停 5 分钟再试。</li>
 * </ul>
 * 这些数字只用于展示，不用于结算，所以不写 {@code resolve_source_fetch} 存档（和自动裁决的数据源不同，见开发记录 S16 D-197）。
 */
@ConfigurationProperties("forecast.growth.board")
public class BoardProperties {

  private boolean refreshEnabled = true;
  private Duration refreshInterval = Duration.ofSeconds(60);
  private String binanceUrl = "https://data-api.binance.vision";
  private String symbol = "BTCUSDT";
  private int hours = 24;
  private Duration connectTimeout = Duration.ofSeconds(3);
  private Duration readTimeout = Duration.ofSeconds(5);
  private int retries = 2;
  private int breakerThreshold = 5;
  private Duration breakerOpen = Duration.ofMinutes(5);

  public boolean isRefreshEnabled() {
    return refreshEnabled;
  }

  public void setRefreshEnabled(boolean v) {
    this.refreshEnabled = v;
  }

  public Duration getRefreshInterval() {
    return refreshInterval;
  }

  public void setRefreshInterval(Duration v) {
    this.refreshInterval = v == null || v.toMillis() < 1000 ? Duration.ofSeconds(1) : v;
  }

  public String getBinanceUrl() {
    return binanceUrl;
  }

  public void setBinanceUrl(String v) {
    this.binanceUrl = v == null ? "" : v.strip().replaceAll("/+$", "");
  }

  public String getSymbol() {
    return symbol;
  }

  public void setSymbol(String v) {
    this.symbol = v;
  }

  public int getHours() {
    return hours;
  }

  public void setHours(int v) {
    this.hours = Math.max(1, Math.min(v, 168));
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration v) {
    this.connectTimeout = v;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(Duration v) {
    this.readTimeout = v;
  }

  public int getRetries() {
    return retries;
  }

  public void setRetries(int v) {
    this.retries = Math.max(0, Math.min(v, 5));
  }

  public int getBreakerThreshold() {
    return breakerThreshold;
  }

  public void setBreakerThreshold(int v) {
    this.breakerThreshold = Math.max(1, v);
  }

  public Duration getBreakerOpen() {
    return breakerOpen;
  }

  public void setBreakerOpen(Duration v) {
    this.breakerOpen = v;
  }
}
