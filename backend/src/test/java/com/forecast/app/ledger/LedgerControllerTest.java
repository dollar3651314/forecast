package com.forecast.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.support.FixedClockConfig;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 客户端的账户和资金流水接口（{@code GET /api/ledger/account}、{@code GET /api/ledger/entries}），真实 MySQL（C37）。
 * 复制源的同类测试（客户端资金流水）用下单产生流水；forecast S1 删除了下单，改用充值、提现冻结、提现退回。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class LedgerControllerTest {

  @Autowired MockMvc mvc;
  @Autowired LedgerApi ledger;
  @Autowired JdbcTemplate jdbc;
  final ObjectMapper json = new ObjectMapper();

  /** 一个有会话的客户（直接写库，令牌只在测试里） */
  record User(long id, Cookie cookie) {}

  User user() throws Exception {
    String email = "lc" + System.nanoTime() + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    ledger.openAccount(id);
    String token = UUID.randomUUID().toString();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    jdbc.update("INSERT INTO account_session (user_id, token_hash, created_at, last_seen_at, expires_at) VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), "
        + "UTC_TIMESTAMP(3) + INTERVAL 1 DAY)", id, hash);
    return new User(id, new Cookie("fc_session", token));
  }

  void apply(long uid, Bucket b, String amount, LedgerEntryType type, String bizType, String bizId, String remark) {
    ledger.apply(new LedgerCommand(uid, b, new BigDecimal(amount), type, bizType, bizId, remark));
  }

  JsonNode getJson(String url, Cookie c) throws Exception {
    return json.readTree(mvc.perform(get(url).cookie(c)).andReturn().getResponse().getContentAsString());
  }

  int status(String url, Cookie c) throws Exception {
    return mvc.perform(c == null ? get(url) : get(url).cookie(c)).andReturn().getResponse().getStatus();
  }

  @Test
  void 账户_三项_可用_下注中_提现冻结_余额是可用加下注中() throws Exception {
    User u = user();
    apply(u.id(), Bucket.AVAILABLE, "100.5", LedgerEntryType.DEPOSIT, "DEPOSIT", "TRC20:0xaa:" + u.id(), "充值 TRC20");
    apply(u.id(), Bucket.AVAILABLE, "-30", LedgerEntryType.WITHDRAW_FREEZE, "WITHDRAW", "w-" + u.id(), "提现冻结");
    apply(u.id(), Bucket.WITHDRAW_FROZEN, "30", LedgerEntryType.WITHDRAW_FREEZE, "WITHDRAW", "w-" + u.id(), "提现冻结");
    assertThat(getJson("/api/ledger/account", u.cookie()).toString())
        .isEqualTo("{\"currency\":\"USDT\",\"balance\":\"70.5\",\"available\":\"70.5\",\"betLocked\":\"0\",\"withdrawFrozen\":\"30\"}");
    apply(u.id(), Bucket.AVAILABLE, "-20.25", LedgerEntryType.BET_LOCK, "BET", "b-" + u.id(), "下注");
    apply(u.id(), Bucket.BET_LOCKED, "20.25", LedgerEntryType.BET_LOCK, "BET", "b-" + u.id(), "下注");
    assertThat(getJson("/api/ledger/account", u.cookie()).toString()).as("下注中的钱还算余额，但不算可用")
        .isEqualTo("{\"currency\":\"USDT\",\"balance\":\"70.5\",\"available\":\"50.25\",\"betLocked\":\"20.25\",\"withdrawFrozen\":\"30\"}");
    JsonNode entries = getJson("/api/ledger/entries?range=all", u.cookie());
    assertThat(entries.get("total").asLong()).as("S4 起客户端流水有下注（可用 −b 那一条）").isEqualTo(3);
    assertThat(entries.get("items").get(0).get("type").asString()).isEqualTo("bet");
    assertThat(entries.get("items").get(0).get("amount").asString()).isEqualTo("-20.25");
    assertThat(entries.get("items").get(0).get("market").isNull()).as("没有记市场的流水，market 为 null").isTrue();
    assertThat(getJson("/api/ledger/entries?type=bet&range=all", u.cookie()).get("total").asLong()).isEqualTo(1);
    assertThat(status("/api/ledger/account", null)).as("要登录").isEqualTo(401);
  }

  @Test
  void 资金流水_按类型和时间筛选_ref取网络_只看余额的变动_只看自己的() throws Exception {
    User u = user();
    User other = user();
    apply(u.id(), Bucket.AVAILABLE, "100", LedgerEntryType.DEPOSIT, "DEPOSIT", "BSC:0xbb:" + u.id(), "充值 BSC");
    apply(u.id(), Bucket.AVAILABLE, "-40", LedgerEntryType.WITHDRAW_FREEZE, "WITHDRAW", "w1-" + u.id(), "提现冻结");
    apply(u.id(), Bucket.WITHDRAW_FROZEN, "40", LedgerEntryType.WITHDRAW_FREEZE, "WITHDRAW", "w1-" + u.id(), "提现冻结");
    apply(u.id(), Bucket.WITHDRAW_FROZEN, "-40", LedgerEntryType.WITHDRAW_UNFREEZE, "WITHDRAW", "w1-" + u.id(), "提现退回");
    apply(u.id(), Bucket.AVAILABLE, "40", LedgerEntryType.WITHDRAW_UNFREEZE, "WITHDRAW", "w1-" + u.id(), "提现退回");
    apply(other.id(), Bucket.AVAILABLE, "5", LedgerEntryType.DEPOSIT, "DEPOSIT", "BSC:0xcc:" + other.id(), "充值 BSC");

    JsonNode all = getJson("/api/ledger/entries?range=all", u.cookie());
    assertThat(all.get("total").asLong()).as("提现冻结那一项的变动不算（只看余额）").isEqualTo(3);
    assertThat(all.get("items").get(0).get("type").asString()).isEqualTo("withdrawRefund");
    assertThat(all.get("items").get(1).get("type").asString()).isEqualTo("withdraw");
    assertThat(all.get("items").get(1).get("amount").asString()).isEqualTo("-40");
    assertThat(all.get("items").get(1).get("ref").asString()).isEmpty();
    assertThat(all.get("items").get(2).get("type").asString()).isEqualTo("deposit");
    assertThat(all.get("items").get(2).get("ref").asString()).isEqualTo("BSC");
    assertThat(all.get("items").get(2).get("time").asString()).as("时间带时区").endsWith("Z");
    assertThat(getJson("/api/ledger/entries?type=deposit&range=all", u.cookie()).get("total").asLong()).isEqualTo(1);
    assertThat(getJson("/api/ledger/entries?range=all&page=2&pageSize=2", u.cookie()).get("items").size()).isEqualTo(1);
    assertThat(status("/api/ledger/entries?type=pnl", u.cookie())).as("差价合约的流水类型没有了").isEqualTo(400);
    assertThat(status("/api/ledger/entries?range=1y", u.cookie())).isEqualTo(400);
    assertThat(status("/api/ledger/entries", null)).isEqualTo(401);
  }
}
