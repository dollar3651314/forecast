package com.forecast.app.wallet.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.notification.api.MailApi;
import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.address.AddressService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 以太坊体系只用合约地址（开发记录/钱包-合约地址方案.md）：<b>没有配置合约地址参数</b>时（测试环境、生产在 Amos 部署合约之前），
 * ERC20、BEP20 "暂未开通"，<b>不退回 xpub 派生</b>；TRC20 照常；健康检查只在 {@code alerts} 里报 {@code deposit.evm} DOWN，{@code status} 仍是 UP、HTTP 200
 * （DOWN 会让部署脚本回滚）；以前留下的以太坊体系地址仍然扫块入账。真实 MySQL 8.4（C37）。
 * 配好合约地址时的情况见 DepositFlowTest（默认测试配置就配了四个合约地址）和 sweep 包的测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@TestPropertySource(properties = {"forecast.wallet.sweep.evm.factory=", "forecast.wallet.sweep.evm.implementation=", "forecast.wallet.sweep.evm.parent=",
    "forecast.wallet.sweep.evm.batcher="})
class EvmContractOnlyTest {

  static final String ERC20_USDT = "0xdAC17F958D2ee523a2206206994597C13D831ec7";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired AddressService addresses;
  @Autowired DepositService deposits;
  @Autowired ChainClients clients;
  @Autowired LedgerApi ledger;
  @MockitoBean MailApi mail;

  @AfterEach
  void cleanup() {
    for (Chain c : Chain.values()) clients.remove(c);
  }

  @SuppressWarnings("unchecked")
  Cookie register(String email) throws Exception {
    mvc.perform(post("/api/auth/email-code").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}"));
    ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(mail, atLeastOnce()).send(eq(email), eq("EMAIL_CODE"), p.capture());
    return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"code\":\"" + p.getValue().get("code")
        + "\",\"password\":\"password123\",\"firstName\":\"A\",\"lastName\":\"B\",\"birthDate\":\"1990-05-01\",\"nationality\":\"CN\"}")).andReturn().getResponse().getCookie("fc_session");
  }

  @Test
  void 没配合约地址_ERC20和BEP20暂未开通_不分配xpub地址_TRC20照常() throws Exception {
    String email = "evm" + System.nanoTime() + "@example.com";
    Cookie c = register(email);
    long uid = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    assertThat(jdbc.queryForList("SELECT family FROM wallet_address WHERE user_id = ?", String.class, uid)).as("只分配了 TRON，以太坊体系没有 xpub 地址")
        .containsExactly("TRON");
    assertThat(addresses.addressOf(uid, Chain.ERC20)).isEmpty();
    assertThat(addresses.addressOf(uid, Chain.BSC)).isEmpty();
    assertThat(addresses.open(Chain.ERC20)).isFalse();
    assertThat(addresses.open(Chain.TRC20)).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_address WHERE user_id = ? AND family = 'ETH'", Integer.class, uid)).as("取地址也不会补出 xpub 地址").isZero();

    var r = mvc.perform(get("/api/wallet/addresses").cookie(c)).andReturn().getResponse();
    assertThat(r.getStatus()).isEqualTo(200);
    String tron = addresses.addressOf(uid, Chain.TRC20).orElseThrow();
    assertThat(r.getContentAsString()).as("没开通的链不在列表里，客户端显示暂未开通")
        .isEqualTo("[{\"network\":\"TRC20\",\"address\":\"" + tron + "\",\"confirmations\":19,\"etaMinutes\":1,\"minDeposit\":\"1\"}]");
  }

  @Test
  void 没配合约地址_健康检查status仍是UP_只在alerts里报deposit_evm() throws Exception {
    var r = mvc.perform(get("/api/health")).andReturn().getResponse();
    assertThat(r.getStatus()).as("缺配置不能让部署脚本回滚").isEqualTo(200);
    assertThat(r.getContentAsString()).contains("\"status\":\"UP\"").contains("\"deposit.evm\":\"DOWN\"").contains("\"alert\":\"ALERT\"")
        .doesNotContain("FC_SWEEP_EVM");
  }

  @Test
  void 以前留下的以太坊体系地址_没配合约时不显示但照常入账() {
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "old" + uid + "@example.com");
    ledger.openAccount(uid);
    String old = "0x00000000000000000000000000000002" + String.format("%08x", uid);
    jdbc.update("INSERT INTO wallet_address (user_id, family, scheme, scheme_ver, address_index, address, address_key, status, active_key) "
        + "VALUES (?, 'ETH', 'XPUB', 0, ?, ?, ?, 'ACTIVE', CONCAT(?, ':ETH'))", uid, (int) uid, old, old, uid);
    assertThat(addresses.addressOf(uid, Chain.ERC20)).as("不再给客户显示").isEmpty();

    FakeChain f = new FakeChain(40000);
    clients.register(Chain.ERC20, f);
    jdbc.update("REPLACE INTO wallet_chain_cursor (chain, last_block) VALUES ('ERC20', 39999)");
    String tx = f.transfer(40000, ERC20_USDT, old, new BigDecimal("9").movePointRight(6).toBigIntegerExact());
    f.head = 40011;
    deposits.run(Chain.ERC20);
    assertThat(jdbc.queryForObject("SELECT status FROM wallet_deposit WHERE tx_hash = ?", String.class, tx)).isEqualTo("CREDITED");
    assertThat(ledger.snapshot(uid).balance()).isEqualByComparingTo("9");
  }
}
