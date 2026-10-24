package com.forecast.app.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在客户端的交易和资金写接口上（下单、改单、撤单、平仓、改止盈止损、追加保证金、提现提交、白名单增删）：
 * 同一客户每分钟合计最多 {@code forecast.rate-limit.user-write-per-minute} 次（默认 60），超过返回 429 {@code RATE_LIMITED}。
 * 按客户计数（换 IP 也算同一个额度），由 {@link UserWriteLimitInterceptor} 在登录检查之后执行；{@code RateLimitTest} 检查哪些接口带了它。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface UserWriteLimited {}
