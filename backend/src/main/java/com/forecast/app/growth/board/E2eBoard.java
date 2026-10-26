package com.forecast.app.growth.board;

import com.forecast.app.common.e2e.E2eGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试的币安 1 小时 K 线替身（只在 profile e2e 下存在，forecast S16）。e2e 配置里数据页的币安地址指向这里，端到端走真实的取数、口径和缓存。
 * <ul>
 *   <li>{@code POST /api/e2e/board/klines} {"candles":[["开盘","收盘"], …], "price":"现价", "fail":false}：从早到晚的已收盘 K 线（最后一根之后再接一根还没收盘的，收盘价 = price）</li>
 *   <li>{@code GET /api/e2e/board/binance/api/v3/klines?symbol=&interval=1h&limit=}：照币安的格式返回（时间按服务器时钟，e2e 下可以拨）</li>
 *   <li>{@code POST /api/e2e/board/refresh}：立即刷新一次数据页的行情</li>
 * </ul>
 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eBoard {

  record Klines(List<List<String>> candles, String price, Boolean fail) {}

  private final BtcBoard board;
  private final Clock clock;
  private final List<List<String>> candles = new CopyOnWriteArrayList<>();
  private volatile String price = "65000.00";
  private volatile boolean fail;

  E2eBoard(BtcBoard board, Clock clock) {
    this.board = board;
    this.clock = clock;
  }

  @PostMapping("/api/e2e/board/klines")
  Map<String, Object> set(@RequestBody Klines k) {
    candles.clear();
    if (k.candles() != null) candles.addAll(k.candles());
    if (k.price() != null) price = k.price();
    fail = Boolean.TRUE.equals(k.fail());
    return Map.of("candles", candles.size());
  }

  @GetMapping("/api/e2e/board/binance/api/v3/klines")
  ResponseEntity<Object> klines(@RequestParam String symbol, @RequestParam(defaultValue = "500") int limit) {
    if (fail) return ResponseEntity.status(500).body(Map.of("code", -1, "msg", "e2e: 500"));
    Instant hour = clock.instant().truncatedTo(ChronoUnit.HOURS);
    List<Object> out = new ArrayList<>();
    int n = candles.size();
    for (int i = 0; i < n; i++) {
      long open = hour.minus(n - i, ChronoUnit.HOURS).toEpochMilli();
      List<String> c = candles.get(i);
      out.add(List.of(open, c.get(0), c.get(0), c.get(1), c.get(1), "1.0", open + 3_599_999, "1.0", 1, "0.5", "0.5", "0"));
    }
    long open = hour.toEpochMilli();
    out.add(List.of(open, price, price, price, price, "1.0", open + 3_599_999, "1.0", 1, "0.5", "0.5", "0"));
    return ResponseEntity.ok(out.subList(Math.max(0, out.size() - limit), out.size()));
  }

  @PostMapping("/api/e2e/board/refresh")
  Map<String, Object> refresh() {
    return Map.of("ok", board.refresh());
  }
}
