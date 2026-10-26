package com.forecast.app.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.forecast.app.notification.api.MailApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * WebSocket 握手限流（P-05）：真实启动服务、真实的 WebSocket 握手（实际执行）。上限设成 2：
 * 第 3 次握手返回 429；已经建立的两条长连接不受影响（还开着、能收发）；同一个 IP 的普通接口单独计数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "forecast.rate-limit.ws-handshake-per-minute=2")
@ActiveProfiles("test")
class RateLimitWebSocketTest {

  static final String PREFIX = "fc:rl-test-" + UUID.randomUUID() + ":";

  @DynamicPropertySource
  static void prefix(DynamicPropertyRegistry r) {
    r.add("forecast.rate-limit.key-prefix", () -> PREFIX);
  }

  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @MockitoBean MailApi mail;

  final HttpClient http = HttpClient.newHttpClient();

  @AfterEach
  void cleanOwnKeys() {
    Set<String> keys = redis.keys(PREFIX + "*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  String cookie;

  HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  /** 推送要登录：先注册一个客户 */
  @SuppressWarnings("unchecked")
  void register() throws Exception {
    String email = "rlws" + System.nanoTime() + "@example.com";
    post("/api/auth/email-code", "{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}");
    ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(mail, atLeastOnce()).send(eq(email), eq("EMAIL_CODE"), p.capture());
    HttpResponse<String> r = post("/api/auth/register", "{\"email\":\"" + email + "\",\"code\":\"" + p.getValue().get("code")
        + "\",\"password\":\"password123\",\"firstName\":\"A\",\"lastName\":\"B\",\"birthDate\":\"1990-05-01\",\"nationality\":\"CN\",\"adultConfirmed\":true}");
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    cookie = r.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
  }

  WebSocket open() throws Exception {
    return http.newWebSocketBuilder().header("Origin", "http://localhost:" + port).header("Cookie", cookie)
        .buildAsync(URI.create("ws://localhost:" + port + "/api/ws/user"), new WebSocket.Listener() {}).get(5, TimeUnit.SECONDS);
  }

  @Test
  void 第3次握手返回429_已建立的连接不受影响_普通接口单独计数() throws Exception {
    register();
    WebSocket a = open();
    WebSocket b = open();
    try {
      open();
      throw new AssertionError("第 3 次握手应该被拒绝");
    } catch (ExecutionException e) {
      assertThat(e.getCause()).isInstanceOf(WebSocketHandshakeException.class);
      HttpResponse<?> r = ((WebSocketHandshakeException) e.getCause()).getResponse();
      assertThat(r.statusCode()).isEqualTo(429);
      assertThat(r.headers().firstValue("Retry-After")).isPresent();
    }
    // 已建立的两条连接还开着，可以正常发消息和关闭
    assertThat(a.isOutputClosed()).isFalse();
    assertThat(b.isInputClosed()).isFalse();
    a.sendPing(java.nio.ByteBuffer.allocate(0)).get(5, TimeUnit.SECONDS);
    a.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
    b.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
    // 同一个 IP 的普通接口照常（没登录是 401，不是 429）
    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me/session")).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(401);
  }
}
