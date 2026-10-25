package com.forecast.app.pool.push;

import com.forecast.app.common.trace.TraceWebSocket;
import com.forecast.app.common.web.CurrentUser;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 注册 {@code /api/ws/markets}（要登录，和市场列表一样；不登录浏览待 MF 结论）和 Redis 订阅（多实例时收其他实例的奖池变化）。
 * 只接受同源握手（Spring 默认）；握手按 IP 限流（{@code IpRateLimitFilter} 对 {@code /api/ws/**} 单独计数）。
 */
@Configuration
@EnableWebSocket
class PoolPushConfig implements WebSocketConfigurer {

  private final PoolPush push;

  PoolPushConfig(PoolPush push) {
    this.push = push;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(TraceWebSocket.wrap(push), "/api/ws/markets").addInterceptors(TraceWebSocket.handshake(), new HandshakeInterceptor() {
      @Override
      public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Map<String, Object> attrs) {
        if (!(req instanceof ServletServerHttpRequest sr)) return false;
        var uid = CurrentUser.of(sr.getServletRequest());
        uid.ifPresent(id -> attrs.put(PoolPush.USER, id));
        return uid.isPresent();
      }

      @Override
      public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Exception e) {}
    });
  }

  /**
   * 订阅其他实例的"奖池有变化"。Redis 断开时监听容器自己按退避重连（最长 30 秒一次），不影响启动和下注；断开期间只推本机的变化。
   * 开关 {@code forecast.pool.push.redis-enabled}（默认开）。
   */
  @Bean
  @ConditionalOnProperty(name = "forecast.pool.push.redis-enabled", havingValue = "true", matchIfMissing = true)
  RedisMessageListenerContainer poolChangesListener(RedisConnectionFactory redis, PoolChanges changes) {
    RedisMessageListenerContainer c = new RedisMessageListenerContainer();
    c.setConnectionFactory(redis);
    ExponentialBackOff backoff = new ExponentialBackOff(1_000, 2);
    backoff.setMaxInterval(30_000);
    c.setRecoveryBackoff(backoff);
    c.addMessageListener((message, pattern) -> changes.onMessage(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8)),
        new ChannelTopic(PoolChanges.CHANNEL));
    return c;
  }
}
