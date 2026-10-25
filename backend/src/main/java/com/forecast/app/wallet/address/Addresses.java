package com.forecast.app.wallet.address;

import java.util.Arrays;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.encoders.Hex;

/**
 * 地址格式工具。公钥 → TRON 地址：Keccak-256(未压缩公钥去掉 04) 的后 20 字节加 0x41。以太坊体系的充值地址不从公钥派生（只用 CREATE2 合约地址，{@link Create2}），
 * 这里只给它做 EIP-55 大小写校验和匹配键。
 */
final class Addresses {

  private Addresses() {}

  private static byte[] hash20(ECPoint p) {
    byte[] pub = p.getEncoded(false);
    Keccak.Digest256 k = new Keccak.Digest256();
    byte[] h = k.digest(Arrays.copyOfRange(pub, 1, pub.length));
    return Arrays.copyOfRange(h, 12, 32);
  }

  /** EIP-55：按小写地址的 Keccak 决定每一位字母大小写 */
  static String checksum(String hex40) {
    String lower = hex40.toLowerCase();
    String h = Hex.toHexString(new Keccak.Digest256().digest(lower.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    StringBuilder sb = new StringBuilder("0x");
    for (int i = 0; i < 40; i++) {
      char c = lower.charAt(i);
      sb.append(Character.digit(h.charAt(i), 16) >= 8 ? Character.toUpperCase(c) : c);
    }
    return sb.toString();
  }

  /** TRON：0x41 + 20 字节，Base58Check */
  static String tron(ECPoint p) {
    byte[] payload = new byte[21];
    payload[0] = 0x41;
    System.arraycopy(hash20(p), 0, payload, 1, 20);
    return Base58.encodeChecked(payload);
  }

  /** 用来匹配链上收款地址的键：以太坊地址统一小写，TRON 原样 */
  static String key(com.forecast.app.wallet.Chain.Family family, String address) {
    return family == com.forecast.app.wallet.Chain.Family.ETH ? address.toLowerCase() : address;
  }
}
