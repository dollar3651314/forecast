package com.forecast.app.wallet.sweep;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

/**
 * 批量合约 {@code contracts/src/SweepBatcher.sol} 的调用数据、事件和交易签名。只有这一份，改合约时一起改（{@code SweepAbiTest} 核对选择器和事件签名）。
 */
final class SweepAbi {

  private static final HexFormat HEX = HexFormat.of();
  /** sweep(address,bytes32[]) */
  static final String SWEEP = selector("sweep(address,bytes32[])");
  /** forwarderAddress(bytes32) */
  static final String FORWARDER_ADDRESS = selector("forwarderAddress(bytes32)");
  /** isOperator(address) */
  static final String IS_OPERATOR = selector("isOperator(address)");
  /** ERC20 Transfer(address,address,uint256) */
  static final String TRANSFER_TOPIC = topic("Transfer(address,address,uint256)");
  /** SweepBatcher.Swept(bytes32 indexed salt, address indexed forwarder, bool deployed) */
  static final String SWEPT_TOPIC = topic("Swept(bytes32,address,bool)");
  /** SweepBatcher.SweepFailed(bytes32 indexed salt, address indexed forwarder, bytes reason) */
  static final String SWEEP_FAILED_TOPIC = topic("SweepFailed(bytes32,address,bytes)");

  /** SweepBatcher 的自定义错误 */
  static final Map<String, String> ERRORS = Map.of(selector("NotOperator()"), "NotOperator：发起账户没有在批量合约里登记（或已被撤销）",
      selector("ZeroAddress()"), "ZeroAddress", selector("RenounceDisabled()"), "RenounceDisabled");

  private SweepAbi() {}

  /** 节点错误信息里的回滚数据换成可读的说明 */
  static String explain(String message) {
    if (message == null) return "";
    for (Map.Entry<String, String> e : ERRORS.entrySet()) {
      if (message.contains("data=" + e.getKey())) return message.replace("data=" + e.getKey(), e.getValue());
    }
    return message;
  }

  static String topic(String signature) {
    return "0x" + HEX.formatHex(new Keccak.Digest256().digest(signature.getBytes(StandardCharsets.US_ASCII)));
  }

  static String selector(String signature) {
    return topic(signature).substring(0, 10);
  }

  /** sweep(token, salts) 的调用数据 */
  static String sweepData(String token, List<String> salts) {
    StringBuilder sb = new StringBuilder(SWEEP);
    sb.append(word(token));
    sb.append(word(BigInteger.valueOf(64)));
    sb.append(word(BigInteger.valueOf(salts.size())));
    for (String s : salts) sb.append(strip(s).toLowerCase());
    return sb.toString();
  }

  /** 解析 sweep 调用数据（链替身用）：[token, salt...] */
  static List<String> decodeSweep(String data) {
    String d = strip(data);
    if (!("0x" + d.substring(0, 8)).equals(SWEEP)) throw new IllegalArgumentException("不是 sweep 调用");
    String token = "0x" + d.substring(8 + 24, 8 + 64);
    int n = new BigInteger(d.substring(8 + 128, 8 + 192), 16).intValueExact();
    java.util.ArrayList<String> out = new java.util.ArrayList<>();
    out.add(token);
    for (int i = 0; i < n; i++) out.add("0x" + d.substring(8 + 192 + i * 64, 8 + 256 + i * 64));
    return out;
  }

  static String addressData(String selector, String address) {
    return selector + word(address);
  }

  /** topic 里的地址（后 20 字节，小写） */
  static String topicAddress(String topic) {
    return "0x" + topic.substring(topic.length() - 40).toLowerCase();
  }

  /** 回滚原因：Error(string) 解出文字，其他原样返回十六进制（最多 200 字） */
  static String reason(String hex) {
    String d = strip(hex);
    try {
      if (d.startsWith("08c379a0") && d.length() >= 8 + 128) {
        int len = new BigInteger(d.substring(8 + 64, 8 + 128), 16).intValueExact();
        return new String(HEX.parseHex(d.substring(8 + 128, 8 + 128 + len * 2)), StandardCharsets.UTF_8);
      }
    } catch (RuntimeException e) {
      // 落到下面
    }
    String s = d.isEmpty() ? "（没有回滚原因）" : "0x" + d;
    return s.length() > 200 ? s.substring(0, 200) : s;
  }

  /** SweepFailed 事件 data 里的 bytes reason（ABI 编码的动态 bytes） */
  static String eventReason(String data) {
    String d = strip(data);
    try {
      int len = new BigInteger(d.substring(64, 128), 16).intValueExact();
      return reason(d.substring(128, 128 + len * 2));
    } catch (RuntimeException e) {
      return reason("");
    }
  }

  /** 签名：以太坊 EIP-1559（type 2），BSC legacy + EIP-155 */
  static String sign(Credentials c, long chainId, boolean eip1559, BigInteger nonce, BigInteger gasLimit, BigInteger maxFee, BigInteger priority, String to,
      String data) {
    RawTransaction raw = eip1559 ? RawTransaction.createTransaction(chainId, nonce, gasLimit, to, BigInteger.ZERO, data, priority, maxFee)
        : RawTransaction.createTransaction(nonce, maxFee, gasLimit, to, BigInteger.ZERO, data);
    byte[] signed = eip1559 ? TransactionEncoder.signMessage(raw, c) : TransactionEncoder.signMessage(raw, chainId, c);
    return Numeric.toHexString(signed);
  }

  private static String word(String address) {
    return "0".repeat(24) + strip(address).toLowerCase();
  }

  private static String word(BigInteger v) {
    String h = v.toString(16);
    return "0".repeat(64 - h.length()) + h;
  }

  private static String strip(String h) {
    return h.startsWith("0x") || h.startsWith("0X") ? h.substring(2) : h;
  }
}
