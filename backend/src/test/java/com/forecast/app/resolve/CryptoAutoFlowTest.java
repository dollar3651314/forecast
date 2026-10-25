package com.forecast.app.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.market.api.MarketAutogenAdminApi;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 加密市场的完整流程（forecast S6，后端）：<b>自动生成 → 下注 → 截止 → 窗口结束 → 自动裁决 → 自动结算</b>，用替身数据源，两种结算事务边界都跑。
 * 默认模板（BTC 1 小时、每天 13:00 UTC 一场，MQ-1 节目化），测试时钟 2026-10-14 10:00 UTC。
 */
class CryptoAutoFlowTest extends CryptoTestSupport {

  static final long DEFAULT_NO = 1000000000000000002L;

  @Autowired MarketAutogenAdminApi autogenAdmin;

  @BeforeEach
  @AfterEach
  void reset() {
    jdbc.update("UPDATE market_question SET autogen_id = NULL WHERE autogen_id IS NOT NULL");
    jdbc.update("DELETE FROM market_autogen WHERE public_no <> ?", DEFAULT_NO);
    // 这里只看一场的完整流程：提前 60 分钟生成，启用时只生成今天 13:00 这一场（默认值 1440 见 CryptoAutogenTest，F-I2-1）
    jdbc.update("UPDATE market_autogen SET enabled = 0, mode = 'SCHEDULE', slots = '[\"13:00\"]', rolling_count = 3, generate_ahead_min = 60, user_cap = NULL, "
        + "checked_by = NULL, checked_at = NULL, checked_template_version = NULL WHERE public_no = ?", DEFAULT_NO);
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @MethodSource("modes")
  void 自动生成_下注_窗口结束_自动裁决_自动结算(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    autogenAdmin.enable(DEFAULT_NO, true, 1);
    assertThat(autogen.generateDue()).isEqualTo(1);
    Map<String, Object> row = jdbc.queryForMap("SELECT id, public_no FROM market_question WHERE autogen_id = (SELECT id FROM market_autogen WHERE public_no = ?)",
        DEFAULT_NO);
    long id = ((Number) row.get("id")).longValue();
    List<String> opts = jdbc.queryForList("SELECT CAST(public_no AS CHAR) FROM market_option WHERE market_id = ? ORDER BY seq", String.class, id);
    Market m = new Market(id, String.valueOf(row.get("public_no")), opts);
    // 客户端能看到（已上架、开放中）
    User alice = user("500");
    User bob = user("500");
    User carol = user("500");
    JsonNode card = getJson("/api/markets/" + m.no(), alice.cookie());
    assertThat(card.path("status").asString()).isEqualTo("listed");
    assertThat(card.path("category").asString()).isEqualTo("CRYPTO_1H");
    assertThat(card.path("title").path("zhCN").asString()).isEqualTo("BTC 涨还是跌：2026-10-14 13:00–14:00 UTC");
    // 下注：涨 60 + 40（Alice 两笔）、跌 100（Bob）、涨 50（Carol）
    bet(alice.id(), m, 0, "60");
    bet(alice.id(), m, 0, "40");
    bet(bob.id(), m, 1, "100");
    bet(carol.id(), m, 0, "50");
    // 窗口开始前 30 秒截止：12:59:29.999 还能押，12:59:30 不能
    advance(Duration.between(Instant.now(clock), Instant.parse("2026-10-14T12:59:29.999Z")));
    assertThat(tryBet(bob.id(), m, 1, "1")).isEqualTo("OK");
    advance(Duration.ofMillis(1));
    assertThat(tryBet(bob.id(), m, 1, "1")).isEqualTo("MARKET_CLOSED");
    marketService.closeDue();
    assertThat(status(m)).isEqualTo("CLOSED");
    // 窗口结束（14:00）：进入待裁决；K 线：开始 67000、结束 66950（跌）
    advance(Duration.between(Instant.now(clock), Instant.parse("2026-10-14T14:00:00Z")));
    marketService.endDue();
    assertThat(status(m)).isEqualTo("PENDING_RESOLUTION");
    BOOK.price("BINANCE", "BTCUSDT", Instant.parse("2026-10-14T13:00:00Z"), "67000.00", "66950.00");
    BOOK.price("OKX", "BTCUSDT", Instant.parse("2026-10-14T13:00:00Z"), "67001.2", "66948.7");
    assertThat(resolver.resolveDue()).doesNotContainKey(id);
    // 窗口结束 + 1 分钟：自动裁决（跌）→ 结算
    advance(Duration.ofMinutes(1));
    Map<Long, String> r = resolver.resolveDue();
    assertThat(r.get(id)).isEqualTo(batch == 0 ? "settled" : "settling");
    if (batch > 0) settle.drainAll();
    assertThat(status(m)).isEqualTo("SETTLED");
    // T = 251，赢方（跌）只有 Bob 的 101（100 + 截止前 1 毫秒的 1，合成一个持仓）：G = 101 × 150 ÷ 101 = 150，F = 7.5，派彩 101 + 150 − 7.5 = 243.5
    assertThat(available(bob.id())).isEqualByComparingTo("642.5");
    assertThat(available(alice.id())).isEqualByComparingTo("400");
    assertThat(available(carol.id())).isEqualByComparingTo("450");
    settledConserved(m, "251");
    // 详情：开始价、结束价、来源
    JsonNode d = getJson("/api/markets/" + m.no(), bob.cookie());
    assertThat(d.path("status").asString()).isEqualTo("settled");
    assertThat(d.path("winningOptionNo").asString()).isEqualTo(m.options().get(1));
    assertThat(d.path("resolution").path("result").asString()).isEqualTo("down");
    assertThat(d.path("resolution").path("startPrice").asString()).isEqualTo("67000.00");
    assertThat(d.path("resolution").path("endPrice").asString()).isEqualTo("66950.00");
    assertThat(d.path("resolution").path("source").asString()).isEqualTo("Binance BTC/USDT");
    assertThat(inbox(bob.id(), "SETTLE_WON")).isEqualTo(1);
    assertThat(inbox(alice.id(), "SETTLE_LOST")).isEqualTo(1);
  }
}
