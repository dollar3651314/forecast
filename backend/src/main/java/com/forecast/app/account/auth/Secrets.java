package com.forecast.app.account.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 令牌、验证码的生成和哈希。库里只存哈希，不存原值。 */
final class Secrets {

  private static final SecureRandom RANDOM = new SecureRandom();

  private Secrets() {}

  /** 32 字节随机令牌（URL 安全的 Base64） */
  static String newToken() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  /** 6 位数字验证码 */
  static String newCode() {
    return String.format("%06d", RANDOM.nextInt(1_000_000));
  }

  static String sha256(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
