package com.forecast.app.common.push;

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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 推给本人的 WebSocket {@code /api/ws/user}：真实启动服务、真实握手（实际执行）。只推给这个客户自己的连接；没登录握手被拒绝。
 * 复制源的同类测试（交易推送）靠下单、止盈触发事件；forecast S1 删除了交易，直接调 {@link UserEventsApi}（充值、提现、通知用的就是它）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserPushTest {

  @LocalServerPort int port;
  @Autowired UserEventsApi events;
  @MockitoBean MailApi mail;

  final HttpClient http = HttpClient.newHttpClient();

  HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  record Session(long userId, String cookie) {}

  @SuppressWarnings("unchecked")
  Session register() throws Exception {
    String email = "push" + System.nanoTime() + "@example.com";
    post("/api/auth/email-code", "{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}");
    ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(mail, atLeastOnce()).send(eq(email), eq("EMAIL_CODE"), p.capture());
    HttpResponse<String> r = post("/api/auth/register", "{\"email\":\"" + email + "\",\"code\":\"" + p.getValue().get("code")
        + "\",\"password\":\"password123\",\"firstName\":\"A\",\"lastName\":\"B\",\"birthDate\":\"1990-05-01\",\"nationality\":\"CN\"}");
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    String cookie = r.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    HttpResponse<String> me = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me/session")).header("Cookie", cookie).build(),
        HttpResponse.BodyHandlers.ofString());
    long id = Long.parseLong(me.body().replaceAll(".*\"userId\":\"(\\d+)\".*", "$1"));
    return new Session(id, cookie);
  }

  WebSocket open(String cookie, BlockingQueue<String> inbox) throws Exception {
    var b = http.newWebSocketBuilder().header("Origin", "http://localhost:" + port);
    if (cookie != null) b = b.header("Cookie", cookie);
    return b.buildAsync(URI.create("ws://localhost:" + port + "/api/ws/user"), new WebSocket.Listener() {
      @Override
      public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        inbox.add(data.toString());
        ws.request(1);
        return null;
      }
    }).get(5, TimeUnit.SECONDS);
  }

  @Test
  void 只推给本人的所有连接_别人收不到_没登录不能握手() throws Exception {
    Session a = register();
    Session b = register();
    BlockingQueue<String> phone = new LinkedBlockingQueue<>();
    BlockingQueue<String> laptop = new LinkedBlockingQueue<>();
    BlockingQueue<String> others = new LinkedBlockingQueue<>();
    WebSocket w1 = open(a.cookie(), phone);
    WebSocket w2 = open(a.cookie(), laptop);
    WebSocket w3 = open(b.cookie(), others);
    // 握手完成到连接登记之间有极短的间隔，等一下再推
    Thread.sleep(200);
    events.send(a.userId(), Map.of("type", "withdraw", "status", "cooling", "amount", "100"));
    String got = phone.poll(5, TimeUnit.SECONDS);
    assertThat(got).contains("\"type\":\"withdraw\"").contains("\"status\":\"cooling\"").contains("\"amount\":\"100\"");
    assertThat(laptop.poll(5, TimeUnit.SECONDS)).isEqualTo(got);
    assertThat(others.poll(500, TimeUnit.MILLISECONDS)).as("别的客户收不到").isNull();
    for (WebSocket w : new WebSocket[] {w1, w2, w3}) w.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);

    try {
      open(null, new LinkedBlockingQueue<>());
      throw new AssertionError("没登录应该握手失败");
    } catch (ExecutionException e) {
      assertThat(e.getCause()).isInstanceOf(WebSocketHandshakeException.class);
    }
  }
}
