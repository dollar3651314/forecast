package com.forecast.app.wallet.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.SweepEvmProperties;
import com.forecast.app.wallet.WalletProperties;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 合约充值地址的算法（方案第 4.13.1、4.14 节"地址算法三方一致"）：和 Foundry 生成、并用真实 BitGo 工厂部署核对过的 1,000 个测试向量逐个比较
 * （src/test/resources/wallet/create2-vectors.json，contracts/script/GenVectors.s.sol、contracts/test/Vectors.t.sol）。
 */
class Create2Test {

  @Test
  void 和Foundry测试向量一致_1000个() throws Exception {
    JsonNode v;
    try (InputStream in = getClass().getResourceAsStream("/wallet/create2-vectors.json")) {
      v = new ObjectMapper().readTree(in);
    }
    String factory = v.path("factory").asString();
    String impl = v.path("implementation").asString();
    int n = v.path("addresses").size();
    assertThat(n).isEqualTo(1000);
    for (int i = 0; i < n; i++) {
      long user = v.path("userIds").get(i).asLong();
      String salt = Create2.salt(user);
      assertThat(salt).as("盐 #%d", i).isEqualTo(v.path("salts").get(i).asString());
      String a = Create2.forwarderAddress(factory, impl, v.path("parents").get(i).asString(), v.path("feeAddresses").get(i).asString(), salt);
      assertThat(a).as("地址 #%d", i).isEqualTo(v.path("addresses").get(i).asString());
    }
  }

  @Test
  void 输入检查() {
    assertThatThrownBy(() -> Create2.salt(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Create2.forwarderAddress("0x12", "0x1111111111111111111111111111111111111111", "0x1111111111111111111111111111111111111111",
        "0x1111111111111111111111111111111111111111", Create2.salt(1))).isInstanceOf(IllegalArgumentException.class);
    assertThat(Create2.isAddress(" 0x5aFE00000000000000000000000000000000A001 ")).isTrue();
    assertThat(Create2.isAddress("T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb")).isFalse();
  }

  // ---------- 配置检查（ForwarderSchemes） ----------

  static final String F = "0x1111111111111111111111111111111111111111";
  static final String I = "0x2222222222222222222222222222222222222222";
  static final String P = "0x5afe00000000000000000000000000000000a001";
  static final String B = "0xba7c000000000000000000000000000000000001";

  static SweepEvmProperties props(String f, String i, String p, String b) {
    return new SweepEvmProperties(f, i, p, b, null, null, null, null, 30, true, true, 100, 160000, 45000, 40000, new BigDecimal("0.05"), Duration.ofMinutes(30), 3,
        Duration.ofMinutes(5));
  }

  static WalletProperties wallet(String usdt) {
    return new WalletProperties(null, null, BigDecimal.ONE, Map.of(Chain.ERC20, new WalletProperties.ChainConfig("http://x", usdt, 6, 12, 12, 20, null)));
  }

  @Test
  void 没配合约地址_用xpub_没有问题() {
    ForwarderSchemes s = new ForwarderSchemes(mock(AddressMapper.class), props(null, "", null, null), wallet("0xdAC17F958D2ee523a2206206994597C13D831ec7"), "", null);
    assertThat(s.current()).isEmpty();
    assertThat(s.problem()).isEmpty();
  }

  @Test
  void 只配了一部分或格式不对_当作没配_报出问题() {
    ForwarderSchemes s = new ForwarderSchemes(mock(AddressMapper.class), props(F, I, null, null), wallet("0xdAC17F958D2ee523a2206206994597C13D831ec7"), "", null);
    assertThat(s.current()).isEmpty();
    assertThat(s.problem()).get().asString().contains("只配了 2 个");
    s = new ForwarderSchemes(mock(AddressMapper.class), props(F, I, "TXYZ", B), wallet("0xdAC17F958D2ee523a2206206994597C13D831ec7"), "", null);
    assertThat(s.problem()).get().asString().contains("FC_SWEEP_EVM_PARENT 不是以太坊地址");
    s = new ForwarderSchemes(mock(AddressMapper.class), props(F, I, B, B), wallet("0xdAC17F958D2ee523a2206206994597C13D831ec7"), "", null);
    assertThat(s.problem()).get().asString().contains("不能等于");
  }

  @Test
  void parent不能是USDT合约_热钱包_我们的充值地址() {
    AddressMapper m = mock(AddressMapper.class);
    ForwarderSchemes s = new ForwarderSchemes(m, props(F, I, P, B), wallet(P.toUpperCase().replace("0X", "0x")), "", null);
    assertThat(s.problem()).get().asString().contains("USDT 合约");
    s = new ForwarderSchemes(m, props(F, I, P, B), wallet("0xdAC17F958D2ee523a2206206994597C13D831ec7"), P, null);
    assertThat(s.problem()).get().asString().contains("热钱包");
    when(m.ownerOf(anyString(), anyString())).thenReturn(new AddressMapper.AddressRow(1, 1, "ETH", "XPUB", 0, 1, P, null, "ACTIVE", null, null));
    s = new ForwarderSchemes(m, props(F, I, P, B), wallet("0xdAC17F958D2ee523a2206206994597C13D831ec7"), "", null);
    assertThat(s.problem()).get().asString().contains("充值地址");
    assertThat(s.current()).isEmpty();
  }
}
