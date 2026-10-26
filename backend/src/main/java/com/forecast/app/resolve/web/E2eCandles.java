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
 *       设一个窗口的开始价、结束价（fail = true 时这个来源返回 500）；S14 加可选的 {@code delayMs}（0～4000）：这个来源回复前先等这么久，
 *       让端到端里两个触发（定时任务、用例的 tick）的取数一定重叠（crypto.spec.ts 偶发失败的根因复现）</li>
 *   <li>{@code GET /api/e2e/candles/binance/api/v3/klines}、{@code GET /api/e2e/candles/okx/api/v5/market/history-candles}：照真实接口的格式返回</li>
 *   <li>S15：{@code interval=1h}（币安）/ {@code bar=1H}（OKX）返回固定规律的 1 小时 K 线（{@link #hourlyClose}：开盘 60000 + 小时序号 × 10，
 *       每 3 小时里 2 涨 1 跌），给自动化程序的 BTC 每小时涨跌统计页、行情短文；{@code POST /api/e2e/candles/hourly} {@code {"fail": true}} 让两个来源都返回 500
 *       （"数据不可用"的降级）</li>
 *   <li>{@code POST /api/e2e/resolve/tick}：立即跑一轮自动裁决（不用等 15 秒的任务）；S7 起同时跑一轮平台裁决的任务（公示期满结算、E6 / E7 到期作废、超时提醒）</li>
 * </ul>
 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eCandles {

  record Set(String symbol, String windowStart, String windowEnd, String open, String close, List<String> sources, Boolean fail, Long delayMs) {}

  private record Window(BigDecimal open, BigDecimal close, long start, long end, boolean fail, long delayMs) {}

  /** 回复前最多等多久（比网关的读取超时 5 秒短，不让取数因为替身超时） */
  static final long MAX_DELAY_MS = 4_000;

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
      long delay = body.delayMs() == null ? 0 : Math.max(0, Math.min(MAX_DELAY_MS, body.delayMs()));
      windows.put(key(src, body.symbol(), s), new Window(new BigDecimal(body.open()), new BigDecimal(body.close()), s, e, Boolean.TRUE.equals(body.fail()), delay));
    }
    return ResponseEntity.ok(Map.of("sources", sources));
  }

  /** S15：1 小时 K 线是否返回 500 */
  private volatile boolean hourlyFail;

  record Hourly(Boolean fail) {}

  @PostMapping("/api/e2e/candles/hourly")
  Map<String, Object> hourly(@RequestBody Hourly body) {
    hourlyFail = body != null && Boolean.TRUE.equals(body.fail());
    return Map.of("fail", hourlyFail);
  }

  /** 第 h 小时（从 1970 起的小时序号）的开盘价、收盘价：每 3 小时里第 0 个跌，另外两个涨 */
  static BigDecimal[] hourlyBar(long hourIndex) {
    BigDecimal open = BigDecimal.valueOf(60_000 + (hourIndex % 1000) * 10);
    BigDecimal close = hourIndex % 3 == 0 ? open.subtract(BigDecimal.valueOf(25)) : open.add(BigDecimal.valueOf(35));
    return new BigDecimal[] {open, close};
  }

  static BigDecimal hourlyClose(long hourIndex) {
    return hourlyBar(hourIndex)[1];
  }

  @GetMapping("/api/e2e/candles/binance/api/v3/klines")
  ResponseEntity<Object> binance(@RequestParam String symbol, @RequestParam long startTime, @RequestParam long endTime,
      @RequestParam(required = false) String interval) {
    if ("1h".equals(interval)) {
      if (hourlyFail) return ResponseEntity.status(500).body(Map.of("code", -1, "msg", "e2e: 500"));
      List<Object> out = new ArrayList<>();
      for (long t = (startTime + 3_599_999) / 3_600_000 * 3_600_000; t <= endTime; t += 3_600_000) {
        BigDecimal[] b = hourlyBar(t / 3_600_000);
        out.add(List.of(t, b[0].toPlainString(), b[0].max(b[1]).toPlainString(), b[0].min(b[1]).toPlainString(), b[1].toPlainString(), "1.0", t + 3_599_999,
            "1.0", 1, "0.5", "0.5", "0"));
      }
      return ResponseEntity.ok(out);
    }
    Window w = windows.get(key("BINANCE", symbol, startTime));
    pause(w);
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
  ResponseEntity<Object> okx(@RequestParam String instId, @RequestParam long after, @RequestParam long before, @RequestParam(required = false) String bar) {
    String symbol = instId.replace("-", "");
    if ("1H".equals(bar)) {
      if (hourlyFail) return ResponseEntity.status(500).body(Map.of("code", "50001", "msg", "e2e: 500"));
      List<Object> data = new ArrayList<>();
      for (long t = (after - 1) / 3_600_000 * 3_600_000; t > before && data.size() < 100; t -= 3_600_000) {
        if (t >= after) continue;
        BigDecimal[] b = hourlyBar(t / 3_600_000);
        data.add(List.of(String.valueOf(t), b[0].toPlainString(), b[0].max(b[1]).toPlainString(), b[0].min(b[1]).toPlainString(), b[1].toPlainString(), "1", "1",
            "1", "1"));
      }
      return ResponseEntity.ok(Map.of("code", "0", "msg", "", "data", data));
    }
    Window w = windows.get(key("OKX", symbol, before + 1));
    pause(w);
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

  private static void pause(Window w) {
    if (w == null || w.delayMs() <= 0) return;
    try {
      Thread.sleep(w.delayMs());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String key(String source, String symbol, long start) {
    return source.toUpperCase() + ":" + symbol + ":" + start;
  }
}
