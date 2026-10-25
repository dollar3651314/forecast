package com.forecast.app.account.auth;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 发验证码按 IP 限次（安全审查 SEC-03）：同一 IP 每小时最多 {@code codeIpHourlyLimit} 次，超过的请求返回"发送太频繁"。
 * 固定窗口：第一次请求开始计时 1 小时，到期自动清零。计数放在 Redis，和登录失败计数一样。
 * IP 是 Nginx 传来的真实客户端 IP（{@code request.getRemoteAddr()}），客户端自己带的 X-Forwarded-For 不起作用。
 */
@Component
class SendCodeLimiter {

  private final StringRedisTemplate redis;
  private final AuthProperties props;

  SendCodeLimiter(StringRedisTemplate redis, AuthProperties props) {
    this.redis = redis;
    this.props = props;
  }

  static String ipKey(String ip) {
    return "fc:code:ip:" + ip;
  }

  /** 记一次并返回是否还在限额内 */
  boolean allow(String ip) {
    String key = ipKey(ip == null ? "" : ip);
    Long n = redis.opsForValue().increment(key);
    if (n != null && n == 1) redis.expire(key, Duration.ofHours(1));
    return n == null || n <= props.codeIpHourlyLimit();
  }
}
