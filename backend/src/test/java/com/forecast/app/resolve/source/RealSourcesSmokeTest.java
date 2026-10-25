package com.forecast.app.resolve.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * <b>手动运行</b>的冒烟测试（forecast S6；agents C39"第三方接口的行为要用真实网络确认"）：打真实的币安（data-api.binance.vision）、OKX、Coinbase 接口，
 * 取最近一个完整的 1 小时窗口和 15 分钟窗口（BTC、ETH），用生产的超时和重试，检查返回格式和适配器的解析一致、两个来源的方向。
 * <b>默认跳过</b>（CI 和平时的 verify 都不跑，不依赖外网）。本地有网时：
 * <pre>mvn -o -B -ntp test -Dtest=RealSourcesSmokeTest -Dresolve.smoke=true</pre>
 * 结果写进 开发记录/S6-加密自动化.md（测的日期、出口、各来源的价格和耗时）。
 */
@EnabledIfSystemProperty(named = "resolve.smoke", matches = "true")
class RealSourcesSmokeTest {

  @Test
  void 真实接口_最近的完整窗口() {
    ResolveProperties props = new ResolveProperties();
    props.getSources().put("binance", new ResolveProperties.Source(true, "https://data-api.binance.vision"));
    props.getSources().put("okx", new ResolveProperties.Source(true, "https://www.okx.com"));
    props.getSources().put("coinbase", new ResolveProperties.Source(true, "https://api.coinbase.com"));
    List<SourceArchive.Entry> archived = new CopyOnWriteArrayList<>();
    SourceGateway gateway = new SourceGateway(props, e -> {
      archived.add(e);
      return archived.size();
    }, Clock.systemUTC());
    Instant now = Instant.now();
    long h = 3_600_000L;
    long q = 900_000L;
    // 窗口结束后至少 1 分钟（和生产一样）
    Instant end1h = Instant.ofEpochMilli((now.minus(Duration.ofMinutes(1)).toEpochMilli() / h) * h);
    Instant end15 = Instant.ofEpochMilli((now.minus(Duration.ofMinutes(1)).toEpochMilli() / q) * q);
    List<CandleQuery> queries = new ArrayList<>();
    for (String sym : List.of("BTCUSDT", "ETHUSDT")) {
      queries.add(new CandleQuery(sym, end1h.minus(Duration.ofHours(1)), end1h));
      queries.add(new CandleQuery(sym, end15.minus(Duration.ofMinutes(15)), end15));
    }
    List<CandleSource> sources = List.of(new BinanceSpot(), new OkxSpot(), new CoinbaseSpot());
    System.out.println("\n===== 真实数据源冒烟测试 " + now + " =====");
    List<String> mismatch = new ArrayList<>();
    for (CandleQuery cq : queries) {
      String primaryDir = null;
      for (CandleSource s : sources) {
        long t0 = System.nanoTime();
        SourceGateway.Result r = gateway.fetch(s, cq, null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        SourceArchive.Entry last = archived.get(archived.size() - 1);
        System.out.printf("%-8s %-8s %s～%s  %-9s 开始 %-14s 结束 %-14s 方向 %-4s 根数 %-3s 尝试 %d 次 HTTP %s %d 毫秒 sha256 %s %s%n", s.name(), cq.symbol(),
            cq.windowStart(), cq.windowEnd(), r.outcome(), r.ok() ? r.candles().open().toPlainString() : "-", r.ok() ? r.candles().close().toPlainString() : "-",
            r.ok() ? r.candles().direction() : "-", r.ok() ? r.candles().count() : "-", r.attempts(), last.httpStatus(), ms,
            r.bodySha256() == null ? "-" : r.bodySha256().substring(0, 12), r.error() == null ? "" : r.error());
        if (!(s instanceof CoinbaseSpot)) {
          assertThat(r.outcome()).as(s.name() + " " + cq).isEqualTo(SourceGateway.Outcome.OK);
          assertThat(r.candles().count()).isEqualTo(cq.minutes());
          assertThat(r.candles().firstOpenTime()).isEqualTo(cq.windowStart());
          if (primaryDir == null) primaryDir = r.candles().direction();
          else if (!primaryDir.equals(r.candles().direction())) mismatch.add(cq.symbol() + " " + cq.windowStart() + " 币安 " + primaryDir + " / OKX " + r.candles().direction());
        }
      }
    }
    System.out.println("方向不一致：" + (mismatch.isEmpty() ? "无" : mismatch));
    System.out.println("===== 结束 =====\n");
  }
}
