package com.forecast.app.resolve.auto;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.resolve.source.Candles;
import com.forecast.app.resolve.source.SourceGateway;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 加密涨跌的判定（需求第 4.3 节、E9、E10；架构方案第 6.2 节、X-7）：两源方向一致才定；"正好相等"导致不同以首选为准；第三源只补位。 */
class CryptoDecisionTest {

  static SourceGateway.Result ok(String src, String open, String close) {
    Candles c = new Candles(new BigDecimal(open), new BigDecimal(close), 60, Instant.EPOCH, Instant.EPOCH);
    return new SourceGateway.Result(src, src, SourceGateway.Outcome.OK, c, null, 1L, "sha", Instant.EPOCH, 1);
  }

  static SourceGateway.Result bad(String src, SourceGateway.Outcome o) {
    return new SourceGateway.Result(src, src, o, null, "HTTP 500", 2L, null, Instant.EPOCH, 3);
  }

  @Test
  void 两源一致() {
    CryptoDecision.Verdict v = CryptoDecision.decide(ok("BINANCE", "100", "101"), ok("OKX", "100.5", "100.9"), null);
    assertThat(v.direction()).isEqualTo("UP");
    assertThat(v.basis()).isEqualTo("BINANCE");
    assertThat(v.used()).containsExactly("BINANCE", "OKX");
    assertThat(CryptoDecision.decide(ok("BINANCE", "100", "99"), ok("OKX", "100", "99.99"), null).direction()).isEqualTo("DOWN");
  }

  @Test
  void 收盘等于开盘两边都判涨_E9() {
    assertThat(CryptoDecision.decide(ok("BINANCE", "100", "100"), ok("OKX", "100", "100"), null).direction()).isEqualTo("UP");
  }

  @Test
  void 正好相等导致方向不同_以首选为准() {
    // 主源正好相等（涨），备源跌
    CryptoDecision.Verdict v = CryptoDecision.decide(ok("BINANCE", "100", "100"), ok("OKX", "100", "99.9"), null);
    assertThat(v.direction()).isEqualTo("UP");
    assertThat(v.reason()).contains("以首选为准");
    // 备源正好相等（涨），主源跌：仍以首选（跌）为准
    assertThat(CryptoDecision.decide(ok("BINANCE", "100", "99.9"), ok("OKX", "100", "100"), null).direction()).isEqualTo("DOWN");
  }

  @Test
  void 方向不一致_定不下来() {
    CryptoDecision.Verdict v = CryptoDecision.decide(ok("BINANCE", "100", "101"), ok("OKX", "100", "99"), null);
    assertThat(v.decided()).isFalse();
    assertThat(v.reason()).contains("方向不一致").contains("BINANCE UP").contains("OKX DOWN");
  }

  @Test
  void 一方拿不到_没有第三源_定不下来() {
    CryptoDecision.Verdict v = CryptoDecision.decide(ok("BINANCE", "100", "101"), bad("OKX", SourceGateway.Outcome.FAILED), null);
    assertThat(v.decided()).isFalse();
    assertThat(v.reason()).contains("拿不到数据").contains("OKX failed");
    assertThat(CryptoDecision.decide(bad("BINANCE", SourceGateway.Outcome.CIRCUIT_OPEN), bad("OKX", SourceGateway.Outcome.NOT_READY), null).decided()).isFalse();
  }

  @Test
  void 第三源补位() {
    // 备源拿不到：主源 + 第三源
    CryptoDecision.Verdict v = CryptoDecision.decide(ok("BINANCE", "100", "101"), bad("OKX", SourceGateway.Outcome.FAILED), ok("COINBASE", "99", "100"));
    assertThat(v.direction()).isEqualTo("UP");
    assertThat(v.used()).containsExactly("BINANCE", "COINBASE");
    assertThat(v.reason()).contains("第三源补位");
    // 主源拿不到：备源 + 第三源，一致才定；正好相等也不能"以首选为准"（首选没有）
    assertThat(CryptoDecision.decide(bad("BINANCE", SourceGateway.Outcome.FAILED), ok("OKX", "100", "99"), ok("COINBASE", "99", "98")).direction())
        .isEqualTo("DOWN");
    assertThat(CryptoDecision.decide(bad("BINANCE", SourceGateway.Outcome.FAILED), ok("OKX", "100", "100"), ok("COINBASE", "99", "98")).decided()).isFalse();
    // 主备都拿到时第三源不参与
    assertThat(CryptoDecision.decide(ok("BINANCE", "100", "101"), ok("OKX", "100", "99"), ok("COINBASE", "100", "101")).decided()).isFalse();
  }
}
