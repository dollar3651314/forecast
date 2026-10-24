package com.forecast.app.admin.security;

import com.forecast.app.common.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 管理员密码（后台方案 G2）：至少 10 位，含字母和数字，最多 72 字节（BCrypt 的上限）；临时密码 16 位随机。 */
public final class Passwords {

  private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(10);
  private static final SecureRandom RANDOM = new SecureRandom();
  /** 去掉容易看错的 0 O 1 l I */
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
  /** 用户名不存在时也做一次比对，耗时一样，不能靠响应时间猜用户名 */
  static final String DUMMY_HASH = ENCODER.encode("dummy-password-for-timing");

  private Passwords() {}

  public static boolean strong(String p) {
    return p != null && p.length() >= 10 && p.getBytes(StandardCharsets.UTF_8).length <= 72 && p.chars().anyMatch(Character::isLetter)
        && p.chars().anyMatch(Character::isDigit);
  }

  /** 不合格时抛字段错误 {@code field: weak} */
  public static void check(String field, String p) {
    if (!strong(p)) throw new ValidationException(List.of(new ValidationException.Field(field, "weak")));
  }

  public static String hash(String p) {
    return ENCODER.encode(p);
  }

  public static boolean matches(String raw, String hash) {
    return raw != null && hash != null && ENCODER.matches(raw, hash);
  }

  /** 临时密码：16 位，保证有字母和数字 */
  public static String temporary() {
    while (true) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 16; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
      if (strong(sb.toString())) return sb.toString();
    }
  }
}
