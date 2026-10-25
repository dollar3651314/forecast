package com.forecast.app.wallet.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 从 xpub 派生 TRON 充值地址。xpub 来自 BIP39 公开的测试助记词（"abandon … about"）；
 * 期望地址用独立的 Python 库 bip_utils 生成并核对过。以太坊体系不用 xpub（只用 CREATE2 合约地址，见 {@link Create2Test}、EvmContractOnlyTest）。
 */
class AddressDerivationTest {

  static final String TRON_XPUB = "xpub6D1AabNHCupeiLM65ZR9UStMhJ1vCpyV4XbZdyhMZBiJXALQtmn9p42VTQckoHVn8WNqS7dqnJokZHAHcHGoaQgmv8D45oNUKx6DZMNZBCd";

  @Test
  void TRON_m44_195_0_0_序号() {
    assertThat(AddressService.deriveTron(TRON_XPUB, 0)).isEqualTo("TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH");
    assertThat(AddressService.deriveTron(TRON_XPUB, 1)).isEqualTo("TSeJkUh4Qv67VNFwY8LaAxERygNdy6NQZK");
    assertThat(AddressService.deriveTron(TRON_XPUB, 7)).isEqualTo("TFj86XAQYBPvwUknt9yxmbc4k3zwUC7iVW");
  }

  @Test
  void 以太坊地址大小写校验_EIP55() {
    assertThat(Addresses.checksum("5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")).isEqualTo("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed");
  }

  @Test
  void 错误的xpub被拒绝() {
    assertThatThrownBy(() -> AddressService.deriveTron(TRON_XPUB.substring(0, TRON_XPUB.length() - 1) + "u", 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AddressService.deriveTron("not-an-xpub", 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Bip32.child(Bip32.parse(TRON_XPUB), -1)).as("不能硬化派生").isInstanceOf(IllegalArgumentException.class);
  }
}
