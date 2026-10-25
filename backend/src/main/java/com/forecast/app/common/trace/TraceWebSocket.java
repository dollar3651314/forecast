package com.forecast.app.common.trace;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WebSocket 的追踪号：握手请求的追踪号（{@link TraceIdFilter} 设的，握手响应头里也有）记在连接属性里；之后这个连接的连上、收到消息、出错、断开的处理
 * 都在这个追踪号下运行，日志能按连接串起来。注册 WebSocket 时用 {@link #wrap(WebSocketHandler)} 和 {@link #handshake()}。
 * 服务器主动推送（定时推报价、交易事件）不属于某个连接的处理，不带连接的追踪号。
 */
public final class TraceWebSocket {

  static final String ATTR = "fc.traceId";

  private TraceWebSocket() {}

  /** 握手时记下追踪号 */
  public static HandshakeInterceptor handshake() {
    return new HandshakeInterceptor() {
      @Override
      public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Map<String, Object> attrs) {
        String id = null;
        if (req instanceof ServletServerHttpRequest sr) {
          HttpServletRequest r = sr.getServletRequest();
          if (r.getAttribute(TraceId.ATTRIBUTE) instanceof String s) id = s;
        }
        attrs.put(ATTR, id != null ? id : TraceId.generate());
        return true;
      }

      @Override
      public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Exception e) {}
    };
  }

  /** 连接上的每个回调都在这个连接的追踪号下运行 */
  public static WebSocketHandler wrap(WebSocketHandler handler) {
    return new WebSocketHandlerDecorator(handler) {
      @Override
      public void afterConnectionEstablished(WebSocketSession s) throws Exception {
        run(s, () -> super.afterConnectionEstablished(s));
      }

      @Override
      public void handleMessage(WebSocketSession s, WebSocketMessage<?> m) throws Exception {
        run(s, () -> super.handleMessage(s, m));
      }

      @Override
      public void handleTransportError(WebSocketSession s, Throwable e) throws Exception {
        run(s, () -> super.handleTransportError(s, e));
      }

      @Override
      public void afterConnectionClosed(WebSocketSession s, CloseStatus st) throws Exception {
        run(s, () -> super.afterConnectionClosed(s, st));
      }
    };
  }

  /** 连接的追踪号；没有时为 null */
  public static String of(WebSocketSession s) {
    return s.getAttributes().get(ATTR) instanceof String id ? id : null;
  }

  @FunctionalInterface
  private interface Body {
    void run() throws Exception;
  }

  private static void run(WebSocketSession s, Body body) throws Exception {
    String id = of(s);
    String old = MDC.get(TraceId.MDC_KEY);
    MDC.put(TraceId.MDC_KEY, id != null ? id : TraceId.generate());
    try {
      body.run();
    } finally {
      if (old == null) MDC.remove(TraceId.MDC_KEY);
      else MDC.put(TraceId.MDC_KEY, old);
    }
  }
}
