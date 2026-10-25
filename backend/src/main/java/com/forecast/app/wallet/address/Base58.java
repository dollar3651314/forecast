package com.forecast.app.wallet.address;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Base58Check 编码解码（xpub、TRON 地址用）。 */
public final class Base58 {

  private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  private static final BigInteger BASE = BigInteger.valueOf(58);

  private Base58() {}

  public static String encodeChecked(byte[] payload) {
    byte[] sum = Arrays.copyOf(sha256(sha256(payload)), 4);
    byte[] all = Arrays.copyOf(payload, payload.length + 4);
    System.arraycopy(sum, 0, all, payload.length, 4);
    return encode(all);
  }

  public static byte[] decodeChecked(String s) {
    byte[] all = decode(s);
    if (all.length < 5) throw new IllegalArgumentException("Base58Check 太短");
    byte[] payload = Arrays.copyOf(all, all.length - 4);
    byte[] sum = Arrays.copyOfRange(all, all.length - 4, all.length);
    if (!Arrays.equals(sum, Arrays.copyOf(sha256(sha256(payload)), 4))) throw new IllegalArgumentException("Base58Check 校验和不对");
    return payload;
  }

  static String encode(byte[] input) {
    BigInteger n = new BigInteger(1, input);
    StringBuilder sb = new StringBuilder();
    while (n.signum() > 0) {
      BigInteger[] qr = n.divideAndRemainder(BASE);
      sb.append(ALPHABET.charAt(qr[1].intValue()));
      n = qr[0];
    }
    for (int i = 0; i < input.length && input[i] == 0; i++) sb.append('1');
    return sb.reverse().toString();
  }

  static byte[] decode(String s) {
    BigInteger n = BigInteger.ZERO;
    for (char c : s.toCharArray()) {
      int d = ALPHABET.indexOf(c);
      if (d < 0) throw new IllegalArgumentException("不是 Base58 字符");
      n = n.multiply(BASE).add(BigInteger.valueOf(d));
    }
    byte[] raw = n.toByteArray();
    int strip = raw.length > 1 && raw[0] == 0 ? 1 : 0;
    int zeros = 0;
    while (zeros < s.length() && s.charAt(zeros) == '1') zeros++;
    byte[] out = new byte[zeros + raw.length - strip];
    System.arraycopy(raw, strip, out, zeros, raw.length - strip);
    return out;
  }

  static byte[] sha256(byte[] b) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(b);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
