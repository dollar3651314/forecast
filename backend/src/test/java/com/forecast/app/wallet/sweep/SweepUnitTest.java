package com.forecast.app.wallet.sweep;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.wallet.SweepEvmProperties;
import com.forecast.app.wallet.api.SweepSettings;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.SignedRawTransaction;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.utils.Numeric;

/** 归集的纯函数和配置：批量合约的调用数据和事件签名、交易签名、设置校验、发起账户核对。 */
class SweepUnitTest {

  @Test
  void 选择器和事件签名_和合约一致() {
    // 和 contracts/src/SweepBatcher.sol 一致：值来自 forge inspect SweepBatcher methodIdentifiers / events
    assertThat(SweepAbi.SWEEP).isEqualTo("0xfaa89528");
    assertThat(SweepAbi.IS_OPERATOR).isEqualTo("0x6d70f7ae");
    assertThat(SweepAbi.SWEPT_TOPIC).isEqualTo("0xefdff3513196ed629888ad247b814400e2fa566cac904fd2044ce748f14054c6");
    assertThat(SweepAbi.SWEEP_FAILED_TOPIC).isEqualTo("0x6cdceb6c2340f6ae8e1d771f2cf9a96cad1b8592ecd923154ad1553ea7847432");
    assertThat(SweepAbi.TRANSFER_TOPIC).isEqualTo("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");
    assertThat(SweepAbi.selector("transfer(address,uint256)")).isEqualTo("0xa9059cbb");
  }

  @Test
  void sweep调用数据_编码后能解回来() {
    String usdt = "0xdac17f958d2ee523a2206206994597c13d831ec7";
    List<String> salts = List.of("0x" + "11".repeat(32), "0x" + "ab".repeat(32));
    String data = SweepAbi.sweepData(usdt, salts);
    assertThat(data).startsWith(SweepAbi.SWEEP);
    assertThat(data.length()).isEqualTo(10 + 64 * 5);
    assertThat(SweepAbi.decodeSweep(data)).containsExactly(usdt, salts.get(0), salts.get(1));
  }

  @Test
  void 回滚原因解码() {
    String err = "0x08c379a0" + "0".repeat(62) + "20" + "0".repeat(62) + "16" + Numeric.toHexStringNoPrefix("Address is not allowed".getBytes()) + "0".repeat(20);
    assertThat(SweepAbi.reason(err)).isEqualTo("Address is not allowed");
    assertThat(SweepAbi.reason("0x")).contains("没有回滚原因");
    assertThat(SweepAbi.reason("0xdeadbeef")).isEqualTo("0xdeadbeef");
    // SweepFailed(bytes reason) 的 data：offset 0x20、长度、内容
    String inner = err.substring(2);
    String eventData = "0x" + "0".repeat(62) + "20" + String.format("%064x", inner.length() / 2) + inner;
    assertThat(SweepAbi.eventReason(eventData)).isEqualTo("Address is not allowed");
    assertThat(SweepAbi.eventReason("0x")).contains("没有回滚原因");
  }

  @Test
  void 签名_以太坊EIP1559_BSC_legacy_签名人是发起账户() throws Exception {
    ECKeyPair k = Keys.createEcKeyPair();
    Credentials c = Credentials.create(k);
    String to = "0xba7c000000000000000000000000000000000001";
    String raw = SweepAbi.sign(c, 11155111, true, BigInteger.valueOf(7), BigInteger.valueOf(300_000), BigInteger.valueOf(3_000_000_000L),
        BigInteger.valueOf(50_000_000L), to, "0x1234");
    SignedRawTransaction t = (SignedRawTransaction) TransactionDecoder.decode(raw);
    assertThat(t.getFrom()).isEqualToIgnoringCase(c.getAddress());
    assertThat(t.getNonce()).isEqualTo(7);
    assertThat(t.getTo()).isEqualToIgnoringCase(to);
    String legacy = SweepAbi.sign(c, 97, false, BigInteger.ONE, BigInteger.valueOf(300_000), BigInteger.valueOf(3_000_000_000L), BigInteger.ZERO, to, "0x1234");
    SignedRawTransaction l = (SignedRawTransaction) TransactionDecoder.decode(legacy);
    assertThat(l.getChainId()).isEqualTo(97L);
    assertThat(l.getFrom()).isEqualToIgnoringCase(c.getAddress());
  }

  @Test
  void 设置_默认值_校验() {
    SweepSettings d = SweepSettings.parse(Map.of());
    assertThat(d.threshold("ERC20")).isEqualByComparingTo("100");
    assertThat(d.threshold("BSC")).isEqualByComparingTo("10");
    assertThat(d.threshold("TRC20")).isEqualByComparingTo("100");
    assertThat(d.maxFeeRatioPct()).isEqualByComparingTo("3");
    assertThat(d.capPerAddress()).isEqualByComparingTo("5000");
    assertThat(d.capPerBatch()).isEqualByComparingTo("20000");
    assertThat(d.toConfig()).containsEntry("sweep.threshold.ERC20", "100").containsEntry("sweep.evmCap.perBatch", "20000").hasSize(8);

    List<SweepSettings.Problem> p = new ArrayList<>();
    SweepSettings ok = SweepSettings.normalize(new SweepSettings.Input(Map.of("ERC20", "200", "BSC", "20.5", "TRC20", "100"), "2.5", "0", "30000",
        Map.of("ERC20", "0.05", "BSC", "0")), p);
    assertThat(p).isEmpty();
    assertThat(ok.capPerAddress()).as("0 = 不限").isEqualByComparingTo("0");
    assertThat(SweepSettings.diff(d, ok)).containsKeys("threshold.ERC20", "threshold.BSC", "maxFeeRatioPct", "evmCap.perAddress", "evmCap.perBatch",
        "operatorMin.ERC20");

    p.clear();
    assertThat(SweepSettings.normalize(new SweepSettings.Input(Map.of("ERC20", "-1", "BSC", "abc"), "101", "1", "2", Map.of("ERC20", "0")), p)).isNull();
    assertThat(p).extracting(SweepSettings.Problem::field).contains("thresholds.ERC20", "thresholds.BSC", "thresholds.TRC20", "maxFeeRatioPct", "operatorMin.BSC");
    p.clear();
    assertThat(SweepSettings.normalize(new SweepSettings.Input(Map.of("ERC20", "1", "BSC", "1", "TRC20", "1"), "3", "500", "100", Map.of("ERC20", "0", "BSC", "0")), p))
        .isNull();
    assertThat(p).singleElement().isEqualTo(new SweepSettings.Problem("evmCaps.perAddress", "aboveBatchCap"));
    p.clear();
    assertThat(SweepSettings.normalize(null, p)).isNull();
    assertThat(p).isNotEmpty();
  }

  static SweepEvmProperties props(String address, String key) {
    return new SweepEvmProperties(null, null, null, null, address, key, null, null, 30, true, true, 100, 160000, 45000, 40000, new BigDecimal("0.05"),
        Duration.ofMinutes(30), 3, Duration.ofMinutes(5));
  }

  @Test
  void 发起账户_核对私钥和地址_对不上只是不能归集() throws Exception {
    ECKeyPair k = Keys.createEcKeyPair();
    String key = Numeric.toHexStringWithPrefixZeroPadded(k.getPrivateKey(), 64);
    String addr = "0x" + Keys.getAddress(k);
    SweepOperator ok = new SweepOperator(props(addr, key));
    assertThat(ok.credentials()).isPresent();
    assertThat(ok.problem()).isEmpty();
    assertThat(new SweepOperator(props(null, null)).problem()).get().asString().contains("没有配置发起账户");
    assertThat(new SweepOperator(props(null, null)).anyConfigured()).isFalse();
    assertThat(new SweepOperator(props(addr, null)).problem()).get().asString().contains("没有配置私钥");
    assertThat(new SweepOperator(props(null, key)).problem()).get().asString().contains("没有配置地址");
    assertThat(new SweepOperator(props("0x000000000000000000000000000000000000dEaD", key)).problem()).get().asString().contains("对不上");
    SweepOperator bad = new SweepOperator(props(addr, "not-a-key"));
    assertThat(bad.problem()).get().asString().contains("格式不对");
    assertThat(bad.anyConfigured()).isTrue();
    // 私钥不出现在 toString（日志）里
    assertThat(props(addr, key).toString()).doesNotContain(key.substring(2));
  }
}
