package com.forecast.app.resolve.web;

import com.forecast.app.common.e2e.E2eGuard;
import com.forecast.app.resolve.auto.CryptoResolver;
import com.forecast.app.resolve.platform.PublicityService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试的替身数据源（只在 profile e2e 下存在，forecast S6）。e2e 配置里币安、OKX 的地址指向这里（application-e2e.yml），
 * 所以端到端走的是<b>真实的适配器、网关（超时、重试、熔断、存档）和裁决器</b>，只是 K 线是测试设的：
 * <ul>
 *   <li>{@code POST /api/e2e/candles} {@code {"symbol":"BTCUSDT","windowStart":"…Z","windowEnd":"…Z","open":"100","close":"100","sources":["BINANCE","OKX"],"fail":false}}：
 *       设一个窗口的开始价、结束价（fail = true 时这个来源返回 500）</li>
 *   <li>{@code GET /api/e2e/candles/binance/api/v3/klines}、{@code GET /api/e2e/candles/okx/api/v5/market/history-candles}：照真实接口的格式返回</li>
 *   <li>{@code POST /api/e2e/resolve/tick}：立即跑一轮自动裁决（不用等 15 秒的任务）；S7 起同时跑一轮平台裁决的任务（公示期满结算、E6 / E7 到期作废、超时提醒）</li>
 * </ul>
 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eCandles {

  record Set(String symbol, String windowStart, String windowEnd, String open, String close, List<String> sources, Boolean fail) {}

  private record Window(BigDecimal open, BigDecimal close, long start, long end, boolean fail) {}

  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  private final CryptoResolver resolver;
  private final PublicityService publicity;

  E2eCandles(CryptoResolver resolver, PublicityService publicity) {
    this.resolver = resolver;
    this.publicity = publicity;
  }

  @PostMapping("/api/e2e/candles")
  ResponseEntity<Map<String, Object>> set(@RequestBody Set body) {
    if (body == null || body.symbol() == null || body.windowStart() == null || body.windowEnd() == null || body.open() == null || body.close() == null) {
      return ResponseEntity.badRequest().build();
    }
    long s = Instant.parse(body.windowStart()).toEpochMilli();
    long e = Instant.parse(body.windowEnd()).toEpochMilli();
    List<String> sources = body.sources() == null || body.sources().isEmpty() ? List.of("BINANCE", "OKX") : body.sources();
    for (String src : sources) {
      windows.put(key(src, body.symbol(), s), new Window(new BigDecimal(body.open()), new BigDecimal(body.close()), s, e, Boolean.TRUE.equals(body.fail())));
    }
    return ResponseEntity.ok(Map.of("sources", sources));
  }

  @GetMapping("/api/e2e/candles/binance/api/v3/klines")
  ResponseEntity<Object> binance(@RequestParam String symbol, @RequestParam long startTime, @RequestParam long endTime) {
    Window w = windows.get(key("BINANCE", symbol, startTime));
    if (w != null && w.fail()) return ResponseEntity.status(500).body(Map.of("code", -1, "msg", "e2e: 500"));
    List<Object> out = new ArrayList<>();
    if (w != null) {
      for (long t = w.start(); t < w.end() && t <= endTime; t += 60_000) {
        BigDecimal o = t == w.start() ? w.open() : w.open();
        BigDecimal c = t + 60_000 >= w.end() ? w.close() : w.open();
        out.add(List.of(t, o.toPlainString(), o.max(c).toPlainString(), o.min(c).toPlainString(), c.toPlainString(), "1.0", t + 59_999, "1.0", 1, "0.5", "0.5", "0"));
      }
    }
    return ResponseEntity.ok(out);
  }

  @GetMapping("/api/e2e/candles/okx/api/v5/market/history-candles")
  ResponseEntity<Object> okx(@RequestParam String instId, @RequestParam long after, @RequestParam long before) {
    String symbol = instId.replace("-", "");
    Window w = windows.get(key("OKX", symbol, before + 1));
    if (w != null && w.fail()) return ResponseEntity.status(500).body(Map.of("code", "50001", "msg", "e2e: 500"));
    List<Object> data = new ArrayList<>();
    if (w != null) {
      for (long t = w.end() - 60_000; t >= w.start(); t -= 60_000) {
        BigDecimal o = w.open();
        BigDecimal c = t + 60_000 >= w.end() ? w.close() : w.open();
        data.add(List.of(String.valueOf(t), o.toPlainString(), o.max(c).toPlainString(), o.min(c).toPlainString(), c.toPlainString(), "1", "1", "1", "1"));
      }
    }
    return ResponseEntity.ok(Map.of("code", "0", "msg", "", "data", data));
  }

  @PostMapping("/api/e2e/resolve/tick")
  Map<String, Object> tick() {
    return Map.of("markets", resolver.resolveDue(), "publicity", publicity.settleDue(), "holds", publicity.holdsDue(), "reminders", publicity.remindOverdue());
  }

  private static String key(String source, String symbol, long start) {
    return source.toUpperCase() + ":" + symbol + ":" + start;
  }
}
