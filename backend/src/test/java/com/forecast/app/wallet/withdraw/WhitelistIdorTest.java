package com.forecast.app.wallet.withdraw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.ledger.api.LedgerApi;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 越权：客户 B 拿到客户 A 的白名单 ID，经接口（不是直接调服务）尝试删除，返回 404，A 的数据不变；B 的列表里看不到 A 的（真实 MySQL，C37）。
 * 复制源的同一个测试（客户越权）还检查持仓、挂单，forecast S1 删除了交易，只留白名单这部分；下注、争议的越权检查随 S4 以后各步加。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WhitelistIdorTest {

  @Autowired MockMvc mvc;
  @Autowired LedgerApi ledger;
  @Autowired JdbcTemplate jdbc;
  final ObjectMapper json = new ObjectMapper();

  /** 一个有会话的客户（直接写库，令牌只在测试里） */
  Cookie login() throws Exception {
    String email = "idor" + System.nanoTime() + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    ledger.openAccount(id);
    String token = UUID.randomUUID().toString();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    jdbc.update("INSERT INTO account_session (user_id, token_hash, created_at, last_seen_at, expires_at) VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), "
        + "UTC_TIMESTAMP(3) + INTERVAL 1 DAY)", id, hash);
    return new Cookie("fc_session", token);
  }

  MockHttpServletResponse call(MockHttpServletRequestBuilder b, Cookie c, String body) throws Exception {
    b = b.cookie(c);
    if (body != null) b = b.contentType(MediaType.APPLICATION_JSON).content(body);
    return mvc.perform(b).andReturn().getResponse();
  }

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  @Test
  void 客户不能删别人的白名单_列表里也看不到() throws Exception {
    Cookie a = login();
    Cookie b = login();
    MockHttpServletResponse r = call(post("/api/wallet/whitelist"), a, "{\"network\":\"TRC20\",\"address\":\"TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t\",\"label\":\"a\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    String whitelist = body(r).get("id").asString();

    assertThat(call(delete("/api/wallet/whitelist/" + whitelist), b, null).getStatus()).as("删别人的白名单").isEqualTo(404);
    // 用别人的白名单提现：WithdrawFlowTest 已经测过（NOT_WHITELISTED）
    assertThat(body(call(get("/api/wallet/whitelist"), b, null)).size()).as("B 的列表里没有 A 的地址").isZero();
    assertThat(body(call(get("/api/wallet/whitelist"), a, null)).size()).as("A 的地址还在").isEqualTo(1);
  }
}
