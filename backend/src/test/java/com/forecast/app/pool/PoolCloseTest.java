package com.forecast.app.pool;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.market.core.MarketService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * pool 对 market 的 {@code MarketPoolView}（S3 留给 S4 的接口）：上架时建奖池行、截止时定格奖池和数有效选项（E3、E4）、
 * 下注客户名单（#22 费率修改通知，AC-37 的通知部分用真实名单）、状态副本（我的下注分组）。真实 MySQL。
 */
class PoolCloseTest extends PoolTestSupport {

  @Autowired MarketService marketService;

  @Test
  void 上架时建好各选项的奖池行_状态副本跟着市场走() throws Exception {
    Market m = market(3);
    assertThat(jdbc.queryForList("SELECT CONCAT(seq, ':', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM amount)), ':', bettors) FROM pool_option_total WHERE market_id = ? ORDER BY seq", String.class, m.id()))
        .containsExactly("1:0:0", "2:0:0", "3:0:0");
    assertThat(jdbc.queryForObject("SELECT status FROM pool_market WHERE market_id = ?", String.class, m.id())).isEqualTo("LISTED");
    admin.pause(Long.parseLong(m.no()), 1, "测试");
    assertThat(jdbc.queryForObject("SELECT status FROM pool_market WHERE market_id = ?", String.class, m.id())).isEqualTo("PAUSED");
    admin.cancel(Long.parseLong(m.no()), 1, "测试");
    assertThat(jdbc.queryForObject("SELECT status FROM pool_market WHERE market_id = ?", String.class, m.id())).isEqualTo("CANCELLED");
  }

  @Test
  void 截止_定格奖池_两个以上选项有人押进入截止下注_之后不能下注_奖池不再变() throws Exception {
    User a = user("500");
    User b = user("500");
    Instant close = NOW.plus(Duration.ofMinutes(30));
    Market m = market(3, close, null);
    bet(a.id(), m, 0, "60");
    bet(a.id(), m, 0, "40");
    bet(b.id(), m, 2, "25");
    advance(Duration.ofMinutes(30));
    assertThat(marketService.closeDue()).isGreaterThanOrEqualTo(1);
    assertThat(status(m)).isEqualTo("CLOSED");
    assertThat(jdbc.queryForList("SELECT CONCAT(seq, ':', COALESCE(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM final_amount)), 'null'), ':', COALESCE(final_bettors, 'null')) FROM pool_option_total "
        + "WHERE market_id = ? ORDER BY seq", String.class, m.id())).containsExactly("1:100:1", "2:0:0", "3:25:1");
    assertThat(jdbc.queryForObject("SELECT frozen_at IS NOT NULL FROM pool_market WHERE market_id = ?", Boolean.class, m.id())).isTrue();
    assertThat(tryBet(b.id(), m, 1, "1")).isEqualTo("MARKET_CLOSED");
    // 客户端详情：已定格，概率和最终赔率按定格的奖池
    JsonNode pool = getJson("/api/markets/" + m.no(), a.cookie()).get("pool");
    assertThat(pool.get("frozen").asBoolean()).isTrue();
    assertThat(pool.get("total").asString()).isEqualTo("125.00");
    assertThat(pool.get("options").get(0).get("pct").asString()).isEqualTo("80.0%");
    // 截止以后、结算以前改费率：最终赔率按新费率重新显示（第 3.4 节），奖池不变
    admin.changeFee(Long.parseLong(m.no()), "8", 1, "截止后改费率");
    JsonNode after = getJson("/api/markets/" + m.no(), a.cookie()).get("pool");
    assertThat(after.get("feeRate").asString()).isEqualTo("8");
    assertThat(after.get("total").asString()).isEqualTo("125.00");
    // 主胜：(100 + 25 × 0.92) ÷ 100 = 1.23
    assertThat(after.get("options").get(0).get("mult").asString()).isEqualTo("1.23");
    assertThat(after.get("mine").get(0).get("ifWin").asString()).as("100 + 25 − 25 × 8%").isEqualTo("123.00");
  }

  @Test
  void E3_截止时只有一个选项有人押_同一事务作废_E4没人押也作废() throws Exception {
    User a = user("500");
    User b = user("500");
    Instant close = NOW.plus(Duration.ofMinutes(20));
    Market one = market(2, close, null);
    bet(a.id(), one, 1, "10");
    bet(b.id(), one, 1, "20");
    Market none = market(2, close, null);
    advance(Duration.ofMinutes(20));
    marketService.closeDue();
    assertThat(status(one)).isEqualTo("VOIDED");
    assertThat(status(none)).isEqualTo("VOIDED");
    assertThat(jdbc.queryForObject("SELECT void_reason FROM market_question WHERE id = ?", String.class, one.id())).isEqualTo("NOT_ENOUGH_OPTIONS");
    assertThat(jdbc.queryForObject("SELECT SUM(final_amount) FROM pool_option_total WHERE market_id = ?", java.math.BigDecimal.class, one.id()))
        .as("作废前也定格了（S5 按它全额退款）").isEqualByComparingTo("30");
    assertThat(jdbc.queryForObject("SELECT status FROM pool_market WHERE market_id = ?", String.class, one.id())).isEqualTo("VOIDED");
    // 我的下注：作废的市场归到"已退回"（S5 退款前也是）
    JsonNode mine = getJson("/api/me/bets?tab=refunded", a.cookie());
    assertThat(mine.get("total").asLong()).isEqualTo(1);
    assertThat(mine.get("items").get(0).get("marketStatus").asString()).isEqualTo("voided");
  }

  @Test
  void AC37_上架后改费率_有下注的客户都收到22_名单来自真实的下注() throws Exception {
    User a = user("500");
    User b = user("500");
    User c = user("500");
    Market m = market(2);
    bet(a.id(), m, 0, "10");
    bet(a.id(), m, 1, "10");
    bet(b.id(), m, 1, "10");
    var r = admin.changeFee(Long.parseLong(m.no()), "8", 1, "调整");
    assertThat(r.notified()).as("a、b 各一封，a 押了两个选项也只发一次").isEqualTo(2);
    List<Long> got = jdbc.queryForList("SELECT user_id FROM notify_message WHERE template_code = 'FEE_CHANGED' AND user_id IN (?, ?, ?) ORDER BY user_id", Long.class,
        a.id(), b.id(), c.id());
    assertThat(got).containsExactly(a.id(), b.id());
  }
}
