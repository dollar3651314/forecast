package com.forecast.app.resolve.source;

import com.forecast.app.common.health.AlertCheck;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 数据源告警（技术方案第 6.3 节：{@code alerts.source.binance}、{@code alerts.source.okx}、{@code alerts.source.coinbase}；forecast S6）。
 * 熔断打开（连续失败）时 DOWN。只进健康检查的 {@code alerts} 和告警邮件，<b>不让 status 变成 DOWN</b>（项目地图约定 11）。只列出打开了的数据源。
 */
@Component
class SourceAlerts implements AlertCheck {

  private final SourceGateway gateway;
  private final List<CandleSource> sources;

  SourceAlerts(SourceGateway gateway, List<CandleSource> sources) {
    this.gateway = gateway;
    this.sources = sources;
  }

  @Override
  public List<Item> evaluate() {
    List<Item> out = new ArrayList<>();
    for (CandleSource s : sources) {
      if (!gateway.enabled(s)) continue;
      CircuitBreaker b = gateway.breaker(s.name());
      boolean ok = b.state() != CircuitBreaker.State.OPEN;
      out.add(new Item("source." + s.key(), "数据源 " + s.name() + " 连续失败（K 线）", "Data source " + s.name() + " failing (klines)", ok,
          ok ? "正常" : "熔断已打开：连续失败 " + b.consecutiveFailures() + " 次，自动裁决会重试，30 分钟仍不行转人工"));
    }
    return out;
  }
}
