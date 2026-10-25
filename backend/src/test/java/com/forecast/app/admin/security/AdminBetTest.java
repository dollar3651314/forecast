package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.pool.bet.BetService;
import com.forecast.app.support.FixedClockConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 后台查看某个市场的下注明细（forecast S4，{@code bet:view}）：奖池汇总、下注列表（客户邮箱、收到时间到毫秒）、筛选、权限点。 */
class AdminBetTest extends AdminTestSupport {

  @Autowired MarketAdminApi markets;
  @Autowired BetService bets;
  @Autowired LedgerApi ledger;
  final ObjectMapper json = new ObjectMapper();

  long customer(String email) {
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    ledger.openAccount(id);
    ledger.apply(new LedgerCommand(id, Bucket.AVAILABLE, new BigDecimal("500"), LedgerEntryType.DEPOSIT, "TEST", "dep-" + id, "测试入金"));
    return id;
  }

  @Test
  void 下注明细_奖池汇总_客户邮箱_筛选_要bet_view() throws Exception {
    I18nText t = new I18nText("Bets", "下注", "下注");
    Instant now = FixedClockConfig.NOW;
    MarketAdminApi.Detail d = markets.createDraft(new MarketAdminApi.DraftInput("FOOTBALL", t, t, "AUTO", "a", "b", null, now.minusSeconds(60).toString(),
        now.plus(Duration.ofDays(1)).toString(), now.plus(Duration.ofDays(1)).toString(), now.plus(Duration.ofDays(2)).toString(), null, null, null,
        List.of(new MarketAdminApi.OptionInput(t, t), new MarketAdminApi.OptionInput(t, t))), 1);
    markets.publish(Long.parseLong(d.publicNo()), true, 1);
    String o1 = d.options().get(0).optionNo();
    String o2 = d.options().get(1).optionNo();
    String email = unique("bettor") + "@example.com";
    long a = customer(email);
    long b = customer(unique("other") + "@example.com");
    bets.place(new BetService.Place(a, d.publicNo(), o1, "60", "req-a1-" + a, now));
    bets.place(new BetService.Place(a, d.publicNo(), o1, "40", "req-a2-" + a, now.plusMillis(5)));
    bets.place(new BetService.Place(b, d.publicNo(), o2, "25.5", UUID.randomUUID().toString(), now.plusMillis(9)));

    Admin viewer = withRoles(role(Permission.BET_VIEW));
    MockHttpServletResponse r = call(get("/api/admin/markets/" + d.publicNo() + "/bets"), viewer.cookie(), null);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode x = json.readTree(r.getContentAsString());
    assertThat(x.get("status").asString()).isEqualTo("listed");
    assertThat(x.get("total").asString()).isEqualTo("125.50");
    assertThat(x.get("activeTotal").asString()).as("有效下注合计 = 奖池合计").isEqualTo("125.50");
    assertThat(x.get("bettors").asInt()).isEqualTo(2);
    assertThat(x.get("options").get(0).toString()).isEqualTo("{\"optionNo\":\"" + o1 + "\",\"seq\":1,\"amount\":\"100.00\",\"bettors\":1,\"finalAmount\":null,"
        + "\"finalBettors\":null}");
    JsonNode items = x.get("bets").get("items");
    assertThat(x.get("bets").get("total").asLong()).isEqualTo(3);
    assertThat(items.get(0).get("bet").get("amount").asString()).as("按收到时间倒序").isEqualTo("25.50");
    assertThat(items.get(2).get("bet").get("receivedAt").asString()).as("收到时间到毫秒").isEqualTo(now.toString());
    assertThat(items.get(1).get("bet").get("receivedAt").asString()).isEqualTo(now.plusMillis(5).toString());
    assertThat(items.get(1).get("email").asString()).isEqualTo(email);
    assertThat(items.get(1).get("bet").get("clientReqId").asString()).isEqualTo("req-a2-" + a);
    // 筛选：选项、客户、状态
    assertThat(json.readTree(call(get("/api/admin/markets/" + d.publicNo() + "/bets?optionNo=" + o1), viewer.cookie(), null).getContentAsString()).get("bets")
        .get("total").asLong()).isEqualTo(2);
    assertThat(json.readTree(call(get("/api/admin/markets/" + d.publicNo() + "/bets?userId=" + b), viewer.cookie(), null).getContentAsString()).get("bets")
        .get("total").asLong()).isEqualTo(1);
    assertThat(json.readTree(call(get("/api/admin/markets/" + d.publicNo() + "/bets?status=refunded"), viewer.cookie(), null).getContentAsString()).get("bets")
        .get("total").asLong()).isZero();
    assertThat(call(get("/api/admin/markets/" + d.publicNo() + "/bets?optionNo=123&status=x"), viewer.cookie(), null).getContentAsString())
        .contains("\"field\":\"optionNo\"").contains("\"field\":\"status\"");
    assertThat(call(get("/api/admin/markets/99999/bets"), viewer.cookie(), null).getStatus()).isEqualTo(404);
    // 只有 market:view 没有 bet:view：403
    Admin marketOnly = withRoles(role(Permission.MARKET_VIEW));
    assertThat(call(get("/api/admin/markets/" + d.publicNo() + "/bets"), marketOnly.cookie(), null).getStatus()).isEqualTo(403);
  }
}
