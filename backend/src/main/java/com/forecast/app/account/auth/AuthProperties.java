package com.forecast.app.account.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录相关的参数（application.yml 的 forecast.auth）。默认值沿用现有系统（功能地图 A-02）：
 * 连续失败 5 次锁定 15 分钟；现有系统只按 IP 计数，这里按账号和 IP 同时计数（需求 4.1-2、AC-02）。
 *
 * @param codeDailyLimit 同一邮箱同一用途 24 小时内最多发几次验证码（安全审查 SEC-03：防止反复换新验证码猜重置密码的验证码）
 * @param codeIpHourlyLimit 同一 IP 每小时最多请求几次发验证码（安全审查 SEC-03：防止用一个 IP 给大量邮箱发信、探测哪些邮箱注册过）
 */
@ConfigurationProperties(prefix = "forecast.auth")
record AuthProperties(
    int loginFailureLimit,
    int lockMinutes,
    int sessionTtlHours,
    int codeTtlMinutes,
    int codeResendSeconds,
    int codeMaxAttempts,
    String cookieName,
    boolean cookieSecure,
    int codeDailyLimit,
    int codeIpHourlyLimit) {}
