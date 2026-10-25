package com.forecast.app.wallet.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.WalletProperties;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** F3：配置了节点却没配置 USDT 合约时不扫块，健康检查报错；配齐了才接上客户端。 */
class ChainClientSetupTest {

  WalletProperties props(String rpc, String contract) {
    return new WalletProperties(null, BigDecimal.ONE, Map.of(Chain.ERC20, new WalletProperties.ChainConfig(rpc, contract, 6, 12, 12, 20, null)));
  }

  @Test
  void 有节点没合约_健康检查不通过_不扫块() {
    ChainClients clients = new ChainClients();
    ChainClientSetup s = new ChainClientSetup(props("http://127.0.0.1:1", ""), clients, new ObjectMapper());
    s.connect();
    assertThat(s.ok()).isFalse();
    assertThat(clients.get(Chain.ERC20)).isEmpty();
  }

  @Test
  void 都配齐了_接上以太坊体系客户端() {
    ChainClients clients = new ChainClients();
    ChainClientSetup s = new ChainClientSetup(props("http://127.0.0.1:1", "0xdAC17F958D2ee523a2206206994597C13D831ec7"), clients, new ObjectMapper());
    s.connect();
    assertThat(s.ok()).isTrue();
    assertThat(clients.get(Chain.ERC20)).get().isInstanceOf(EvmChainClient.class);
  }

  @Test
  void 没有配置节点_不扫块也不算错() {
    ChainClients clients = new ChainClients();
    ChainClientSetup s = new ChainClientSetup(props("", ""), clients, new ObjectMapper());
    s.connect();
    assertThat(s.ok()).isTrue();
    assertThat(clients.get(Chain.ERC20)).isEmpty();
  }
}
