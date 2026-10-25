package com.forecast.app.common.error;

/**
 * 操作太频繁：带上还要等多少秒。接口返回 429，响应体多一个 {@code cooldownSeconds}，并带 {@code Retry-After} 头，
 * 客户端按它倒计时，不用自己写死。
 */
public class RetryLaterException extends BizException {

  private final long cooldownSeconds;

  public RetryLaterException(String code, String message, long cooldownSeconds) {
    super(code, message);
    this.cooldownSeconds = Math.max(1, cooldownSeconds);
  }

  public long cooldownSeconds() {
    return cooldownSeconds;
  }
}
