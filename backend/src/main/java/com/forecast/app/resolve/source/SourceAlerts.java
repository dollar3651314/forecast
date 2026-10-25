package com.forecast.app.resolve.source;

import com.forecast.app.common.health.AlertCheck;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 数据源告警（技术方案第 6.3 节：{@code alerts.source.binance}、{@code alerts.source.okx}、{@code alerts.source.coinbase}；forecast S6；
 * S8 起 {@code alerts.source.football_data}、{@code alerts.source.balldontlie}、{@code alerts.source.thesportsdb}）。
 * 熔断打开（连续失败）时 DOWN。只进健康检查的 {@code alerts} 和告警邮件，<b>不让 status 变成 DOWN</b>（项目地图约定 11）。
 * <ul>
 *   <li>加密：只列出打开了的数据源。</li>
 *   <li>体育（S8）：打开了但没有配置 key 的显示 DOWN"未开通"（Amos 要求：没配 key 时这个来源未开通，健康检查只放进 alerts）；
 *       配置里关掉（{@code enabled: false}）的不列。</li>
 * </ul>
 */
@Component
class SourceAlerts implements AlertCheck {

  private final SourceGateway gateway;
  private final List<CandleSource> sources;
  private final List<ScoreSource> scores;
  private final ResolveProperties props;

  SourceAlerts(SourceGateway gateway, List<CandleSource> sources, List<ScoreSource> scores, ResolveProperties props) {
    this.gateway = gateway;
    this.sources = sources;
    this.scores = scores;
    this.props = props;
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
    for (ScoreSource s : scores) {
      if (!props.getSources().containsKey(s.key())) continue;
      ResolveProperties.Source cfg = props.source(s.key());
      if (!cfg.isEnabled()) continue;
      String name = "source." + s.key().replace('-', '_');
      if (!cfg.usable()) {
        out.add(new Item(name, "数据源 " + s.label() + " 未开通", "Data source " + s.label() + " not configured", false,
            cfg.unusableReason() + "：赛程同步和体育自动裁决不用这个来源，用到它的市场转人工"));
        continue;
      }
      CircuitBreaker b = gateway.breaker(s.name());
      boolean ok = b.state() != CircuitBreaker.State.OPEN;
      out.add(new Item(name, "数据源 " + s.label() + " 连续失败（体育）", "Data source " + s.label() + " failing (sports)", ok,
          ok ? "正常" : "熔断已打开：连续失败 " + b.consecutiveFailures() + " 次，体育自动裁决会重试，仍不行转人工"));
    }
    return out;
  }
}
