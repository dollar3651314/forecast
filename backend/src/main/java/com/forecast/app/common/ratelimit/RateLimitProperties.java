package com.forecast.app.common.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 通用限流参数（application.yml 的 forecast.rate-limit，功能取舍对照表 P-05）。上限填 0 表示这一层不限。
 * 后端测试、端到端测试（e2e profile）把上限调大，限流本身在 {@code RateLimitTest} 里用很小的上限单独测。
 *
 * @param enabled 总开关（关掉后所有限流都不生效，只用于排查）
 * @param keyPrefix Redis 计数 key 的前缀（测试用随机前缀，测试后只删自己的 key）
 * @param ipPerMinute 同一 IP 每分钟最多请求几次 /api/**（不含 /api/health 和 WebSocket 握手）
 * @param wsHandshakePerMinute 同一 IP 每分钟最多发起几次 WebSocket 握手（行情、交易推送；防止反复重连刷连接，已建立的连接不受影响）
 * @param userWritePerMinute 同一客户每分钟最多几次交易和资金写操作（标了 {@link UserWriteLimited} 的接口，合计）
 * @param adminLoginPerIp 同一 IP 在 {@code adminLoginWindow} 内最多请求几次后台登录（成功失败都算，安全审查 SEC-15）
 * @param adminLoginWindow 后台登录按 IP 计数的时间窗
 * @param redisDownSkip Redis 出错后多长时间内不再访问 Redis、直接放行（避免 Redis 卡住时每个请求都等超时）
 */
@ConfigurationProperties("forecast.rate-limit")
public record RateLimitProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("fc:rl:") String keyPrefix,
    @DefaultValue("600") int ipPerMinute,
    @DefaultValue("120") int wsHandshakePerMinute,
    @DefaultValue("60") int userWritePerMinute,
    @DefaultValue("20") int adminLoginPerIp,
    @DefaultValue("15m") Duration adminLoginWindow,
    @DefaultValue("30s") Duration redisDownSkip) {}
