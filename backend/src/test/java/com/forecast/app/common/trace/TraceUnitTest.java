package com.forecast.app.common.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** 追踪号的格式检查、定时任务和 WebSocket 的追踪号（E9）。 */
class TraceUnitTest {

  @Test
  void 外来的追踪号只接受字母数字和点横线下划线_8到64位() {
    assertThat(TraceId.valid("0123456789abcdef0123456789abcdef")).isTrue();
    assertThat(TraceId.valid("job-1234abcd")).isTrue();
    assertThat(TraceId.valid("short")).isFalse();
    assertThat(TraceId.valid("a".repeat(65))).isFalse();
    assertThat(TraceId.valid("abc\ndef12345")).as("不能往日志里写换行").isFalse();
    assertThat(TraceId.valid(null)).isFalse();
    assertThat(TraceId.generate()).matches("[0-9a-f]{32}");
  }

  @Test
  void 定时任务每次执行一个新的job追踪号_请求里提交的异步任务沿用请求的追踪号() {
    List<String> seen = new ArrayList<>();
    Runnable job = new TraceTaskDecorator().decorate(() -> seen.add(MDC.get(TraceId.MDC_KEY)));
    job.run();
    job.run();
    assertThat(seen).hasSize(2).allMatch(s -> s.matches("job-[0-9a-f]{16}"));
    assertThat(seen.get(0)).isNotEqualTo(seen.get(1));
    assertThat(MDC.get(TraceId.MDC_KEY)).as("执行完清掉").isNull();

    TraceId.run("req-12345678", () -> {
      Runnable async = new TraceTaskDecorator().decorate(() -> seen.add(MDC.get(TraceId.MDC_KEY)));
      TraceId.run("other-123456", async);
    });
    assertThat(seen.get(2)).isEqualTo("req-12345678");
  }

  @Test
  void WebSocket连接的处理带握手请求的追踪号() throws Exception {
    MockHttpServletRequest http = new MockHttpServletRequest("GET", "/api/ws/user");
    http.setAttribute(TraceId.ATTRIBUTE, "ws-trace-0001");
    Map<String, Object> attrs = new HashMap<>();
    assertThat(TraceWebSocket.handshake().beforeHandshake(new ServletServerHttpRequest(http), null, null, attrs)).isTrue();

    WebSocketSession s = mock(WebSocketSession.class);
    when(s.getAttributes()).thenReturn(attrs);
    List<String> seen = new ArrayList<>();
    var handler = TraceWebSocket.wrap(new TextWebSocketHandler() {
      @Override
      public void afterConnectionEstablished(WebSocketSession session) {
        seen.add("open " + MDC.get(TraceId.MDC_KEY));
      }

      @Override
      protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        seen.add("msg " + MDC.get(TraceId.MDC_KEY));
      }

      @Override
      public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        seen.add("close " + MDC.get(TraceId.MDC_KEY));
      }
    });
    handler.afterConnectionEstablished(s);
    handler.handleMessage(s, new TextMessage("{\"op\":\"subKline\"}"));
    handler.afterConnectionClosed(s, CloseStatus.NORMAL);
    assertThat(seen).containsExactly("open ws-trace-0001", "msg ws-trace-0001", "close ws-trace-0001");
    assertThat(MDC.get(TraceId.MDC_KEY)).isNull();

    Map<String, Object> other = new HashMap<>();
    TraceWebSocket.handshake().beforeHandshake(new ServletServerHttpRequest(new MockHttpServletRequest()), null, null, other);
    assertThat((String) other.get(TraceWebSocket.ATTR)).as("没有经过过滤器时自己生成").matches("[0-9a-f]{32}");
  }
}
