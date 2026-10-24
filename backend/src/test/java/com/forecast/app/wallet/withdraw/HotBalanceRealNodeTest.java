package com.forecast.app.wallet.withdraw;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.support.MutableClock;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.wallet.api.HotWalletApi;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

/**
 * 可选的真实节点核对（不在 CI 里跑，要访问外网）：用三条公共测试网节点查<b>公开地址</b>的余额，确认查询代码和真实节点的返回格式对得上。
 * 不用任何私钥；"热钱包地址"用的是测试 USDT 合约自己的地址（公开的）。运行：{@code FC_REAL_NODE_CHECK=1 mvn test -Dtest=HotBalanceRealNodeTest}。
 */
@EnabledIfEnvironmentVariable(named = "FC_REAL_NODE_CHECK", matches = "1")
class HotBalanceRealNodeTest {

  static final String SEPOLIA_USDT = "0xaA8E23Fb1079EA71e0a56F48a2aA51851D8433D0";
  static final String BSC_TEST_USDT = "0x337610d27c682E347C9cD60BD4b3b107C9d34dDd";
  static final String NILE_USDT = "TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf";

  @Test
  void 三条测试网查公开地址的余额() {
    Map<Chain, WalletProperties.ChainConfig> m = new EnumMap<>(Chain.class);
    m.put(Chain.ERC20, new WalletProperties.ChainConfig("https://ethereum-sepolia-rpc.publicnode.com", SEPOLIA_USDT, 6, 12, 12, 20, null));
    m.put(Chain.BSC, new WalletProperties.ChainConfig("https://bsc-testnet-rpc.publicnode.com", BSC_TEST_USDT, 18, 15, 3, 50, null));
    m.put(Chain.TRC20, new WalletProperties.ChainConfig("https://nile.trongrid.io", NILE_USDT, 6, 19, 3, 10, null));
    // 以太坊体系两条链用同一个"热钱包地址"：这里用 Sepolia 测试 USDT 合约地址（BSC 上它是普通地址，余额多半是 0）
    HotBalanceMonitor mon = new HotBalanceMonitor(new WalletProperties(null, null, BigDecimal.ONE, m), SEPOLIA_USDT, NILE_USDT, HotBalanceProperties.defaults(),
        (c, gas) -> BigDecimal.ZERO, new MutableClock(), new ObjectMapper());
    mon.refresh();
    for (HotWalletApi.Balance b : mon.hotBalances()) {
      System.out.println("真实节点：" + b.chain() + " " + b.asset() + " 地址 " + b.address() + " 余额 " + b.balance() + " 错误 " + b.error());
    }
    assertThat(mon.hotBalances()).hasSize(6).allMatch(b -> b.error() == null && b.balance() != null);
  }
}
