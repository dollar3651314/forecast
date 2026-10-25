package com.forecast.app.wallet.withdraw;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 热钱包启动核对（提现方案 W1）：私钥算出的地址必须和配置的地址一致；私钥不出现在 toString。测试私钥就是数字 1。 */
class HotWalletTest {

  static final String SECRET = "0x" + "0".repeat(63) + "1";
  static final String ETH_ADDR = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf";
  static final String TRON_ADDR = "TMVQGm1qAQYVdetCeGRRkTWYYrLXuHK2HC";
  static final String OTHER_ETH = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed";

  HotWalletProperties props(String ethSecret, String ethAddr, String tronSecret, String tronAddr) {
    return new HotWalletProperties(ethSecret, ethAddr, tronSecret, tronAddr, 11155111L, 97L, 100000, 30_000_000, Duration.ofMinutes(30), 3);
  }

  @Test
  void 私钥和地址一致_可以提现() {
    HotWallet h = new HotWallet(props(SECRET, ETH_ADDR, SECRET, TRON_ADDR));
    assertThat(h.ok()).isTrue();
    assertThat(h.eth()).isPresent();
    assertThat(h.tron()).isPresent();
  }

  @Test
  void 地址对不上或私钥格式不对_不能提现_健康检查不通过() {
    HotWallet wrong = new HotWallet(props(SECRET, OTHER_ETH, null, null));
    assertThat(wrong.ok()).isFalse();
    assertThat(wrong.eth()).isEmpty();
    HotWallet bad = new HotWallet(props(null, null, "not-hex", TRON_ADDR));
    assertThat(bad.ok()).isFalse();
    assertThat(bad.tron()).isEmpty();
  }

  @Test
  void 没配私钥_不提现但不算错_toString不输出私钥() {
    HotWallet none = new HotWallet(props(null, null, null, null));
    assertThat(none.ok()).isTrue();
    assertThat(none.eth()).isEmpty();
    assertThat(props(SECRET, ETH_ADDR, null, null).toString()).doesNotContain("0".repeat(63) + "1").contains("私钥不输出");
  }
}
