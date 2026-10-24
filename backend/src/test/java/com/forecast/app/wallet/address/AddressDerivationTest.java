package com.forecast.app.wallet.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.wallet.Chain;
import org.junit.jupiter.api.Test;

/**
 * 从 xpub 派生充值地址。xpub 来自 BIP39 公开的测试助记词（"abandon … about"）；
 * 期望地址用独立的 Python 库 bip_utils 生成并核对过（以太坊第 0 个地址也是公开的测试向量）。
 */
class AddressDerivationTest {

  static final String ETH_XPUB = "xpub6DCoCpSuQZB2jawqnGMEPS63ePKWkwWPH4TU45Q7LPXWuNd8TMtVxRrgjtEshuqpK3mdhaWHPFsBngh5GFZaM6si3yZdUsT8ddYM3PwnATt";
  static final String TRON_XPUB = "xpub6D1AabNHCupeiLM65ZR9UStMhJ1vCpyV4XbZdyhMZBiJXALQtmn9p42VTQckoHVn8WNqS7dqnJokZHAHcHGoaQgmv8D45oNUKx6DZMNZBCd";

  @Test
  void 以太坊体系_m44_60_0_0_序号() {
    assertThat(AddressService.derive(Chain.Family.ETH, ETH_XPUB, 0)).isEqualTo("0x9858EfFD232B4033E47d90003D41EC34EcaEda94");
    assertThat(AddressService.derive(Chain.Family.ETH, ETH_XPUB, 1)).isEqualTo("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0");
    assertThat(AddressService.derive(Chain.Family.ETH, ETH_XPUB, 7)).isEqualTo("0x593814d3309e2dF31D112824F0bb5aa7Cb0D7d47");
  }

  @Test
  void TRON_m44_195_0_0_序号() {
    assertThat(AddressService.derive(Chain.Family.TRON, TRON_XPUB, 0)).isEqualTo("TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH");
    assertThat(AddressService.derive(Chain.Family.TRON, TRON_XPUB, 1)).isEqualTo("TSeJkUh4Qv67VNFwY8LaAxERygNdy6NQZK");
    assertThat(AddressService.derive(Chain.Family.TRON, TRON_XPUB, 7)).isEqualTo("TFj86XAQYBPvwUknt9yxmbc4k3zwUC7iVW");
  }

  @Test
  void 以太坊地址大小写校验_EIP55() {
    assertThat(Addresses.checksum("5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")).isEqualTo("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed");
  }

  @Test
  void 错误的xpub被拒绝() {
    assertThatThrownBy(() -> AddressService.derive(Chain.Family.ETH, ETH_XPUB.substring(0, ETH_XPUB.length() - 1) + "u", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AddressService.derive(Chain.Family.ETH, "not-an-xpub", 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Bip32.child(Bip32.parse(ETH_XPUB), -1)).as("不能硬化派生").isInstanceOf(IllegalArgumentException.class);
  }
}
