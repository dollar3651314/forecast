package com.forecast.app.wallet.withdraw;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;

/**
 * 签名和独立工具一致（Python eth-account / eth-keys / bip_utils 生成的期望值）。
 * 测试私钥就是数字 1（明显不是真实密钥，也不会被密钥扫描当成密钥），它的地址是公开的测试数据。
 */
class SigningTest {

  static final Credentials TEST_KEY = Credentials.create("0x" + "0".repeat(63) + "1");
  static final String USDT = "0xdAC17F958D2ee523a2206206994597C13D831ec7";
  static final String TO = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed";

  @Test
  void 以太坊体系_USDT转账交易_签名结果逐字节一致() {
    assertThat(TEST_KEY.getAddress()).isEqualTo("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");
    String data = EvmWithdrawSender.transferData(TO, BigInteger.valueOf(95_000_000));
    String raw = EvmWithdrawSender.sign(TEST_KEY, 11155111L, BigInteger.valueOf(7), BigInteger.valueOf(20_000_000_000L), BigInteger.valueOf(100000), USDT, data);
    assertThat(raw).isEqualTo("0xf8ae078504a817c800830186a094dac17f958d2ee523a2206206994597c13d831ec780b844a9059cbb0000000000000000000000005aaeb6053f3e94c9b9a09f33669435e7ef1beaed0000000000000000000000000000000000000000000000000000000005a995c08401546d71a01da0669d4315cfa3163a65961c2d16a5ac00e202a3370ae56d72b608c19e6797a06e75b66c38eb9b7502d60680af8cdace4fdb06300cd8473f7db2b8f739bdec41");
    assertThat(Hash.sha3(raw)).isEqualTo("0xe2d7fe8398424168ee9f6e6af98645c6257b5e2793751df62371b461867a4888");
    assertThat(EvmWithdrawSender.decodeData(raw)).as("加价重发时取回同样的调用数据").isEqualTo(data);
  }

  @Test
  void TRON_交易ID签名_和独立工具一致_能恢复出热钱包公钥() throws Exception {
    String txId = "a".repeat(64);
    String sig = TronWithdrawSender.sign(TEST_KEY, txId);
    assertThat(sig).isEqualTo("cf8298b9d991a37626f9816dd75ff8427f5169a0f122a65b8554e9f6504c984f3490ec2e5a8ddaebd27bbb531fefad6336d2a794dbcf2e0f3cae0e7b34bd33e01c");
    byte[] b = HexFormat.of().parseHex(sig);
    var data = new Sign.SignatureData(b[64], java.util.Arrays.copyOfRange(b, 0, 32), java.util.Arrays.copyOfRange(b, 32, 64));
    BigInteger pub = Sign.signedMessageHashToKey(HexFormat.of().parseHex(txId), data);
    assertThat(pub).isEqualTo(TEST_KEY.getEcKeyPair().getPublicKey());
    assertThat(HotWallet.tronAddress(TEST_KEY)).isEqualTo("TMVQGm1qAQYVdetCeGRRkTWYYrLXuHK2HC");
  }
}
