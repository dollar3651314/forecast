package com.forecast.app.common.push;

import com.forecast.app.common.trace.TraceWebSocket;
import com.forecast.app.common.web.CurrentUser;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tools.jackson.databind.ObjectMapper;

/**
 * 推给本人的 WebSocket（{@code /api/ws/user}，需要登录）：{@link UserEventsApi} 的实现。握手时登录检查过滤器已经校验了 Cookie，
 * 这里把用户 ID 记到连接上；每个连接有发送超时和缓冲上限（慢客户端不会拖住别人）。单实例时只在本进程内推送；多实例经 Redis 广播在 S4 做
 * （技术方案/v1-架构方案.md 第 5.3、7 节）。
 * <p>复制源的 trading/order/TradingPush（{@code /api/ws/trading}）改名搬来（forecast S1）。
 */
@Component
class UserPush extends TextWebSocketHandler implements UserEventsApi {

  private static final Logger log = LoggerFactory.getLogger(UserPush.class);
  static final String USER = "fc.userId";

  private final ObjectMapper json;
  private final Map<Long, Set<WebSocketSession>> byUser = new ConcurrentHashMap<>();

  UserPush(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession s) {
    Long uid = (Long) s.getAttributes().get(USER);
    byUser.computeIfAbsent(uid, k -> ConcurrentHashMap.newKeySet()).add(new ConcurrentWebSocketSessionDecorator(s, 2000, 256 * 1024));
  }

  @Override
  public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
    Long uid = (Long) s.getAttributes().get(USER);
    Set<WebSocketSession> set = byUser.get(uid);
    if (set != null) set.removeIf(x -> x.getId().equals(s.getId()));
  }

  @Override
  public void send(long userId, Map<String, Object> event) {
    Set<WebSocketSession> set = byUser.get(userId);
    if (set == null || set.isEmpty()) return;
    String text = json.writeValueAsString(event);
    for (WebSocketSession s : set) {
      try {
        if (s.isOpen()) s.sendMessage(new TextMessage(text));
      } catch (IOException | RuntimeException e) {
        log.debug("推送失败，连接可能已断开");
      }
    }
  }

  /**
   * 注册 /api/ws/user，握手时把登录用户记到连接属性里；没登录的握手被拒绝。只接受同源握手（common/web/CrossSiteGuard），
   * 握手按 IP 限流（common/ratelimit/IpRateLimitFilter）。连接上的处理带握手请求的追踪号。
   * S4 的市场奖池推送 /api/ws/markets 也在这里之外另行注册（技术方案/v1-架构方案.md 第 7 节）。
   */
  @Configuration
  @EnableWebSocket
  static class Config implements WebSocketConfigurer {

    private final UserPush push;

    Config(UserPush push) {
      this.push = push;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
      registry.addHandler(TraceWebSocket.wrap(push), "/api/ws/user").addInterceptors(TraceWebSocket.handshake(), new HandshakeInterceptor() {
        @Override
        public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Map<String, Object> attrs) {
          if (!(req instanceof ServletServerHttpRequest sr)) return false;
          var uid = CurrentUser.of(sr.getServletRequest());
          uid.ifPresent(id -> attrs.put(USER, id));
          return uid.isPresent();
        }

        @Override
        public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Exception e) {}
      });
    }
  }
}
