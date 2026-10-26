package com.forecast.app.pool;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * 客户端接口里和彩池有关的契约（forecast S4；页面在原型确认后做，S11 照着接）：字段名、顺序、格式。
 * 下注结果、市场列表和详情的 {@code pool} 字段、我的下注。字段的含义见 {@code pool/bet/PoolViews}。
 */
class PoolClientContractTest extends PoolTestSupport {

  @Test
  void 下注结果的字段() throws Exception {
    User u = user("100");
    Market m = market(2);
    MockHttpServletResponse r = postBet(u.cookie(), m.no(), m.options().get(0), "10.5", UUID.randomUUID().toString());
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode b = body(r);
    assertThat(keys(b)).containsExactly("betNo", "marketNo", "optionNo", "amount", "receivedAt", "status", "duplicate", "available", "betLocked", "pool");
    assertThat(b.get("betNo").isString()).as("编号是字符串").isTrue();
    assertThat(b.get("marketNo").asString()).isEqualTo(m.no());
    assertThat(b.get("optionNo").asString()).isEqualTo(m.options().get(0));
    assertThat(b.get("amount").asString()).isEqualTo("10.50");
    assertThat(b.get("receivedAt").asString()).isEqualTo(NOW.toString());
    assertThat(b.get("status").asString()).isEqualTo("active");
    assertThat(b.get("available").asString()).isEqualTo("89.50");
    assertThat(b.get("betLocked").asString()).isEqualTo("10.50");
    JsonNode pool = b.get("pool");
    assertThat(keys(pool)).containsExactly("total", "bettors", "frozen", "feeRate", "options", "mine", "limits");
    assertThat(pool.get("mine").isNull()).isTrue();
    assertThat(keys(pool.get("options").get(0))).containsExactly("optionNo", "seq", "amount", "bettors", "pct", "pctList", "mult");
  }

  @Test
  void 市场列表和详情的pool字段_详情带本人的持仓和限额() throws Exception {
    User a = user("1000");
    User b = user("1000");
    Market m = market(3, NOW.plus(Duration.ofDays(1)), "8000");
    // 第 3.5 节例子的比例（缩小 10 倍）：主胜 600、平 150、客胜 250
    bet(a.id(), m, 0, "60");
    bet(a.id(), m, 0, "40");
    bet(b.id(), m, 0, "500");
    bet(b.id(), m, 1, "150");
    bet(b.id(), m, 2, "250");

    JsonNode list = getJson("/api/markets?status=active&sort=newest&pageSize=50", a.cookie());
    JsonNode card = null;
    for (JsonNode i : list.get("items")) if (i.get("publicNo").asString().equals(m.no())) card = i;
    assertThat(card).isNotNull();
    JsonNode lp = card.get("pool");
    assertThat(keys(lp)).containsExactly("total", "bettors", "frozen", "feeRate", "options", "mine", "limits");
    assertThat(lp.get("total").asString()).isEqualTo("1000.00");
    assertThat(lp.get("bettors").asInt()).isEqualTo(2);
    assertThat(lp.get("frozen").asBoolean()).isFalse();
    assertThat(lp.get("feeRate").asString()).isEqualTo("5");
    assertThat(lp.get("mine").isNull()).as("列表里没有本人的持仓").isTrue();
    assertThat(lp.get("limits").isNull()).isTrue();
    // AC-19：列表概率 60% / 15% / 25%，预计赔率 1.63 / 6.38 / 3.85
    assertThat(lp.get("options").get(0).get("pctList").asString()).isEqualTo("60%");
    assertThat(lp.get("options").get(1).get("pctList").asString()).isEqualTo("15%");
    assertThat(lp.get("options").get(2).get("pctList").asString()).isEqualTo("25%");
    assertThat(lp.get("options").get(0).get("mult").asString()).isEqualTo("1.63");
    assertThat(lp.get("options").get(1).get("mult").asString()).isEqualTo("6.38");
    assertThat(lp.get("options").get(2).get("mult").asString()).isEqualTo("3.85");
    assertThat(lp.get("options").get(0).get("optionNo").asString()).isEqualTo(m.options().get(0));
    assertThat(lp.get("options").get(0).get("amount").asString()).isEqualTo("600.00");
    assertThat(lp.get("options").get(0).get("bettors").asInt()).isEqualTo(2);

    JsonNode dp = getJson("/api/markets/" + m.no(), a.cookie()).get("pool");
    assertThat(dp.get("options").get(0).get("pct").asString()).as("详情 1 位小数").isEqualTo("60.0%");
    // 本人的持仓：Alice 两笔合成一个持仓 100；主胜赢了按现在的奖池拿回 100 + 66.66 − 3.33 = 163.33（第 3.5 节例子同比例）
    assertThat(dp.get("mine").size()).isEqualTo(1);
    assertThat(keys(dp.get("mine").get(0))).containsExactly("optionNo", "seq", "amount", "betCount", "ifWin");
    assertThat(dp.get("mine").get(0).get("amount").asString()).isEqualTo("100.00");
    assertThat(dp.get("mine").get(0).get("betCount").asInt()).isEqualTo(2);
    assertThat(dp.get("mine").get(0).get("ifWin").asString()).isEqualTo("163.33");
    assertThat(keys(dp.get("limits"))).containsExactly("min", "max", "userCap", "used", "remaining");
    assertThat(dp.get("limits").toString()).isEqualTo("{\"min\":\"1.00\",\"max\":\"5000.00\",\"userCap\":\"8000.00\",\"used\":\"100.00\",\"remaining\":\"7900.00\"}");
    // 没下过注的客户：mine 是空数组
    User c = user("1");
    assertThat(getJson("/api/markets/" + m.no(), c.cookie()).get("pool").get("mine").size()).isZero();
    // 没人押的市场：全 0，概率 —，赔率 —
    Market empty = market(2);
    JsonNode ep = getJson("/api/markets/" + empty.no(), a.cookie()).get("pool");
    assertThat(ep.get("total").asString()).isEqualTo("0.00");
    assertThat(ep.get("options").get(0).get("pct").asString()).isEqualTo("—");
    assertThat(ep.get("options").get(0).get("mult").asString()).isEqualTo("—");
  }

  @Test
  void 我的下注_字段_四个分组和汇总_最新在前() throws Exception {
    User u = user("500");
    Market active = market(2);
    Market cancelled = market(2);
    bet(u.id(), active, 0, "10");
    advance(Duration.ofSeconds(1));
    bet(u.id(), cancelled, 1, "20");
    advance(Duration.ofSeconds(1));
    bet(u.id(), active, 1, "5");
    admin.cancel(Long.parseLong(cancelled.no()), 1, "测试");
    JsonNode all = getJson("/api/me/bets", u.cookie());
    assertThat(keys(all)).containsExactly("items", "total", "page", "pageSize", "summary", "totals");
    assertThat(all.get("total").asLong()).isEqualTo(3);
    JsonNode first = all.get("items").get(0);
    assertThat(keys(first)).containsExactly("betNo", "marketNo", "market", "marketStatus", "optionNo", "seq", "option", "amount", "receivedAt", "tab", "status",
        "refundReason", "multAtBet", "feeRateAtBet", "category", "finalMult", "result");
    assertThat(first.get("amount").asString()).as("最新的在前").isEqualTo("5.00");
    assertThat(first.get("marketNo").asString()).isEqualTo(active.no());
    assertThat(first.get("market").get("zhCN").asString()).isEqualTo("曼城 vs 利物浦 简");
    assertThat(first.get("option").get("en").asString()).isEqualTo("选项2 en");
    assertThat(first.get("tab").asString()).isEqualTo("active");
    assertThat(first.get("status").asString()).isEqualTo("active");
    assertThat(first.get("multAtBet").asString()).as("下注后 10 / 5：(15 − 5 × 0.05) ÷ 5").isEqualTo("2.90");
    assertThat(first.get("feeRateAtBet").asString()).isEqualTo("5");
    assertThat(all.get("summary").toString()).isEqualTo("{\"active\":{\"count\":2,\"amount\":\"15.00\"},\"pending\":{\"count\":0,\"amount\":\"0.00\"},"
        + "\"settled\":{\"count\":0,\"amount\":\"0.00\"},\"refunded\":{\"count\":1,\"amount\":\"20.00\"}}");
    JsonNode refunded = getJson("/api/me/bets?tab=refunded", u.cookie());
    assertThat(refunded.get("total").asLong()).isEqualTo(1);
    assertThat(refunded.get("items").get(0).get("marketStatus").asString()).isEqualTo("cancelled");
    assertThat(getJson("/api/me/bets?tab=pending", u.cookie()).get("items").size()).isZero();
    assertThat(getJson("/api/me/bets?tab=active&page=2&pageSize=1", u.cookie()).get("items").size()).isEqualTo(1);
    assertThat(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/me/bets?tab=x"), u.cookie(), null).getStatus()).isEqualTo(400);
    // 只看自己的
    User other = user("1");
    assertThat(getJson("/api/me/bets", other.cookie()).get("total").asLong()).isZero();
    assertThat(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/me/bets"), null, null).getStatus()).isEqualTo(401);
  }
}
