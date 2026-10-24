package com.forecast.app.account.auth;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 登录失败计数：按账号和 IP 同时计数，任一达到上限就锁定（AC-02：换 IP 仍锁定）。
 * 每次失败重新计时，锁定期满自动解锁；登录成功清零。计数放在 Redis，应用重启不丢。
 */
@Component
class LoginLimiter {

  private final StringRedisTemplate redis;
  private final AuthProperties props;

  LoginLimiter(StringRedisTemplate redis, AuthProperties props) {
    this.redis = redis;
    this.props = props;
  }

  static String accountKey(String email) {
    return "fc:login:fail:acct:" + email;
  }

  static String ipKey(String ip) {
    return "fc:login:fail:ip:" + ip;
  }

  boolean isLocked(String email, String ip) {
    return count(accountKey(email)) >= props.loginFailureLimit() || count(ipKey(ip)) >= props.loginFailureLimit();
  }

  void recordFailure(String email, String ip) {
    Duration ttl = Duration.ofMinutes(props.lockMinutes());
    for (String k : new String[] {accountKey(email), ipKey(ip)}) {
      redis.opsForValue().increment(k);
      redis.expire(k, ttl);
    }
  }

  void clear(String email, String ip) {
    redis.delete(accountKey(email));
    redis.delete(ipKey(ip));
  }

  private long count(String key) {
    String v = redis.opsForValue().get(key);
    return v == null ? 0 : Long.parseLong(v);
  }
}
