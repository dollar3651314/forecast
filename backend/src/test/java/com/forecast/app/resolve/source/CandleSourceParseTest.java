package com.forecast.app.resolve.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 三个适配器的请求地址和解析（forecast S6，技术方案第 6.1 节）：格式照真实接口（2026-10-25 冒烟测试实际返回的结构，见 RealSourcesSmokeTest）。
 * 开始价 = 第一根 1 分钟 K 线的开盘价，结束价 = 最后一根的收盘价；结束 ≥ 开始判涨（E9）；K 线不全、OKX 未收盘算"还不全"；格式不对算格式错误。
 */
class CandleSourceParseTest {

  static final Instant WS = Instant.parse("2026-11-01T13:00:00Z");
  static final Instant WE = Instant.parse("2026-11-01T14:00:00Z");
  static final CandleQuery Q = new CandleQuery("BTCUSDT", WS, WE);
  final BinanceSpot binance = new BinanceSpot();
  final OkxSpot okx = new OkxSpot();
  final CoinbaseSpot coinbase = new CoinbaseSpot();

  /** 币安格式：[openTime, open, high, low, close, volume, closeTime, …]，按时间正序 */
  static String binanceBody(Instant ws, int minutes, String open, String close) {
    List<String> rows = new ArrayList<>();
    for (int i = 0; i < minutes; i++) {
      long t = ws.toEpochMilli() + i * 60_000L;
      String o = i == 0 ? open : "67000.00000000";
      String c = i == minutes - 1 ? close : "67000.00000000";
      rows.add("[" + t + ",\"" + o + "\",\"67100.00000000\",\"66900.00000000\",\"" + c + "\",\"1.234\"," + (t + 59_999) + ",\"8.3\",10,\"0.5\",\"3.1\",\"0\"]");
    }
    return "[" + String.join(",", rows) + "]";
  }

  /** OKX 格式：{"code":"0","data":[[ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm]]}，新的在前 */
  static String okxBody(Instant ws, int minutes, String open, String close, String lastConfirm) {
    List<String> rows = new ArrayList<>();
    for (int i = minutes - 1; i >= 0; i--) {
      long t = ws.toEpochMilli() + i * 60_000L;
      String o = i == 0 ? open : "67000";
      String c = i == minutes - 1 ? close : "67000";
      String confirm = i == minutes - 1 ? lastConfirm : "1";
      rows.add("[\"" + t + "\",\"" + o + "\",\"67100\",\"66900\",\"" + c + "\",\"1\",\"2\",\"3\",\"" + confirm + "\"]");
    }
    return "{\"code\":\"0\",\"msg\":\"\",\"data\":[" + String.join(",", rows) + "]}";
  }

  @Test
  void 请求地址() {
    assertThat(binance.uri("https://data-api.binance.vision/", Q).toString())
        .isEqualTo("https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1m&startTime=1793538000000&endTime=1793541599999&limit=1000");
    assertThat(okx.uri("https://www.okx.com", Q).toString())
        .isEqualTo("https://www.okx.com/api/v5/market/history-candles?instId=BTC-USDT&bar=1m&after=1793541600000&before=1793537999999&limit=100");
    assertThat(coinbase.uri("https://api.coinbase.com", Q).toString())
        .isEqualTo("https://api.coinbase.com/api/v3/brokerage/market/products/BTC-USD/candles?start=1793538000&end=1793541540&granularity=ONE_MINUTE");
    assertThat(binance.label(Q)).isEqualTo("Binance BTC/USDT");
    assertThat(okx.label(Q)).isEqualTo("OKX BTC-USDT");
    assertThat(coinbase.label(Q)).isEqualTo("Coinbase BTC-USD");
  }

  @Test
  void 币安_开始价是第一根的开盘价_结束价是最后一根的收盘价() throws Exception {
    Candles c = binance.parse(binanceBody(WS, 60, "67012.34000000", "67100.01000000"), Q);
    assertThat(c.open()).isEqualByComparingTo("67012.34");
    assertThat(c.close()).isEqualByComparingTo("67100.01");
    assertThat(c.count()).isEqualTo(60);
    assertThat(c.firstOpenTime()).isEqualTo(WS);
    assertThat(c.lastOpenTime()).isEqualTo(WE.minusSeconds(60));
    assertThat(c.direction()).isEqualTo("UP");
    assertThat(binance.parse(binanceBody(WS, 60, "67012.34", "67012.33"), Q).direction()).isEqualTo("DOWN");
  }

  @Test
  void 收盘等于开盘判涨_E9() throws Exception {
    Candles c = binance.parse(binanceBody(WS, 60, "67000.10000000", "67000.1"), Q);
    assertThat(c.tie()).isTrue();
    assertThat(c.direction()).isEqualTo("UP");
    assertThat(okx.parse(okxBody(WS, 60, "5", "5.000", "1"), Q).direction()).isEqualTo("UP");
  }

  @Test
  void 币安_窗口外的K线不算_不全算还没好() throws Exception {
    // 多返回了窗口前后的 K 线：只取窗口里的
    String body = binanceBody(WS.minusSeconds(60), 62, "1", "2");
    Candles c = binance.parse(body, Q);
    assertThat(c.count()).isEqualTo(60);
    // 少一根
    assertThatThrownBy(() -> binance.parse(binanceBody(WS, 59, "1", "2"), Q)).isInstanceOfSatisfying(SourceException.class,
        e -> assertThat(e.kind()).isEqualTo(SourceException.Kind.NOT_READY));
    // 空数组（窗口还没开始或没数据）
    assertThatThrownBy(() -> binance.parse("[]", Q)).isInstanceOfSatisfying(SourceException.class, e -> assertThat(e.kind()).isEqualTo(SourceException.Kind.NOT_READY));
  }

  @Test
  void 币安_格式不对() {
    for (String body : List.of("<html>", "", "{\"code\":-1121,\"msg\":\"Invalid symbol.\"}", "[[1793538000000,\"abc\",\"1\",\"1\",\"1\",\"1\",1]]",
        "[[1793538000000,\"1\",\"1\"]]", "[1,2,3]", "[[\"x\",\"1\",\"1\",\"1\",\"1\",\"1\",1]]", "[[1793538000000,\"-1\",\"1\",\"1\",\"1\",\"1\",1]]")) {
      assertThatThrownBy(() -> binance.parse(body, Q)).as(body).isInstanceOfSatisfying(SourceException.class,
          e -> assertThat(e.kind()).isEqualTo(SourceException.Kind.BAD_FORMAT));
    }
    // 时间重复
    String dup = binanceBody(WS, 60, "1", "2").replace("]]", "],[" + WS.toEpochMilli() + ",\"1\",\"1\",\"1\",\"1\",\"1\",1]]");
    assertThatThrownBy(() -> binance.parse(dup, Q)).isInstanceOfSatisfying(SourceException.class, e -> assertThat(e.kind()).isEqualTo(SourceException.Kind.BAD_FORMAT));
  }

  @Test
  void OKX_新的在前_只用已收盘的() throws Exception {
    Candles c = okx.parse(okxBody(WS, 60, "67000.5", "66999.9", "1"), Q);
    assertThat(c.open()).isEqualByComparingTo("67000.5");
    assertThat(c.close()).isEqualByComparingTo("66999.9");
    assertThat(c.direction()).isEqualTo("DOWN");
    // 最后一根还没收盘（confirm = 0）：还不全
    assertThatThrownBy(() -> okx.parse(okxBody(WS, 60, "1", "2", "0"), Q)).isInstanceOfSatisfying(SourceException.class,
        e -> assertThat(e.kind()).isEqualTo(SourceException.Kind.NOT_READY));
    assertThatThrownBy(() -> okx.parse(okxBody(WS, 30, "1", "2", "1"), Q)).isInstanceOfSatisfying(SourceException.class,
        e -> assertThat(e.kind()).isEqualTo(SourceException.Kind.NOT_READY));
  }

  @Test
  void OKX_错误码和格式不对() {
    for (String body : List.of("{\"code\":\"51001\",\"msg\":\"Instrument ID does not exist\",\"data\":[]}", "[]", "{\"code\":\"0\",\"data\":{}}",
        "{\"code\":\"0\",\"data\":[[\"1793538000000\",\"1\"]]}", "{\"data\":[]}")) {
      assertThatThrownBy(() -> okx.parse(body, Q)).as(body).isInstanceOfSatisfying(SourceException.class,
          e -> assertThat(e.kind()).isEqualTo(SourceException.Kind.BAD_FORMAT));
    }
  }

  @Test
  void Coinbase_没成交的分钟可以没有_首尾必须有() throws Exception {
    long ws = WS.getEpochSecond();
    String body = "{\"candles\":[{\"start\":\"" + (ws + 3540) + "\",\"low\":\"1\",\"high\":\"3\",\"open\":\"2\",\"close\":\"2.5\",\"volume\":\"1\"},"
        + "{\"start\":\"" + (ws + 600) + "\",\"low\":\"1\",\"high\":\"3\",\"open\":\"2\",\"close\":\"2\",\"volume\":\"1\"},"
        + "{\"start\":\"" + ws + "\",\"low\":\"1\",\"high\":\"3\",\"open\":\"2.1\",\"close\":\"2\",\"volume\":\"1\"}]}";
    Candles c = coinbase.parse(body, Q);
    assertThat(c.open()).isEqualByComparingTo("2.1");
    assertThat(c.close()).isEqualByComparingTo("2.5");
    assertThat(c.count()).isEqualTo(3);
    String noLast = "{\"candles\":[{\"start\":\"" + ws + "\",\"low\":\"1\",\"high\":\"3\",\"open\":\"2.1\",\"close\":\"2\",\"volume\":\"1\"}]}";
    assertThatThrownBy(() -> coinbase.parse(noLast, Q)).isInstanceOfSatisfying(SourceException.class,
        e -> assertThat(e.kind()).isEqualTo(SourceException.Kind.NOT_READY));
    assertThatThrownBy(() -> coinbase.parse("{\"error\":\"NOT_FOUND\"}", Q)).isInstanceOfSatisfying(SourceException.class,
        e -> assertThat(e.kind()).isEqualTo(SourceException.Kind.BAD_FORMAT));
  }
}
