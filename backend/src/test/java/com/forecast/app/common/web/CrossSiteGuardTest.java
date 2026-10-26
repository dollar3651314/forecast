package com.forecast.app.common.web;

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
import java.util.Map;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 安全审查（测试报告/v1-安全审查.md）：真实启动服务、用 HTTP 客户端发请求（实际执行），不经过 MockMvc，路径和请求头和浏览器一样原样到达 Tomcat。
 * <ul>
 *   <li>SEC-01 跨站写请求：带登录 Cookie 的写请求如果来自别的网站（{@code Sec-Fetch-Site} 不是 same-origin，或 {@code Origin} 和本站不同）一律 403。
 *       SameSite=Strict 挡不住同一主域名下的其他子域名（例如 test-admin 和 test-app、或者同一主域名下的其他网站），这里补上。</li>
 *   <li>SEC-02 路径里的分号：Spring 匹配路径时忽略分号后面的内容，{@code /api/admin;x/...} 会进到后台接口，Nginx 的 {@code location /api/admin/} 却拦不住；
 *       后端一律拒绝带分号的 /api/ 路径。</li>
 *   <li>WebSocket 只接受同源（Spring 默认，确认没有被放开）；{@code ..} 不能绕过登录检查。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CrossSiteGuardTest {

  @LocalServerPort int port;
  @MockitoBean MailApi mail;

  final HttpClient http = HttpClient.newHttpClient();
  String cookie;

  HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(b::header);
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void register() throws Exception {
    String email = "csrf" + System.nanoTime() + "@example.com";
    send("POST", "/api/auth/email-code", "{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}", Map.of());
    ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(mail, atLeastOnce()).send(eq(email), eq("EMAIL_CODE"), p.capture());
    HttpResponse<String> r = send("POST", "/api/auth/register", "{\"email\":\"" + email + "\",\"code\":\"" + p.getValue().get("code")
        + "\",\"password\":\"password123\",\"firstName\":\"A\",\"lastName\":\"B\",\"birthDate\":\"1990-05-01\",\"nationality\":\"CN\",\"adultConfirmed\":true}", Map.of());
    assertThat(r.statusCode()).isEqualTo(200);
    String setCookie = r.headers().firstValue("set-cookie").orElseThrow();
    cookie = setCookie.substring(0, setCookie.indexOf(';'));
  }

  @Test
  void SEC01_其他网站带着Cookie发来的写请求一律403_同源和非浏览器照常() throws Exception {
    String self = "http://localhost:" + port;
    // 别的网站（包括同一主域名下的其他子域名）发来的写请求
    for (Map<String, String> h : new Map[] {
        Map.of("Cookie", cookie, "Origin", "https://evil.example"),
        Map.of("Cookie", cookie, "Origin", "null"),
        Map.of("Cookie", cookie, "Sec-Fetch-Site", "cross-site"),
        Map.of("Cookie", cookie, "Sec-Fetch-Site", "same-site", "Origin", "https://other.localhost"),
    }) {
      HttpResponse<String> r = send("POST", "/api/notifications/read-all", null, h);
      assertThat(r.statusCode()).as(h.toString()).isEqualTo(403);
      assertThat(r.body()).contains("\"code\":\"FORBIDDEN\"");
      assertThat(send("PUT", "/api/me/notify-prefs", "{\"push\":false,\"email\":false}", h).statusCode()).as(h.toString()).isEqualTo(403);
    }
    // 不需要登录的写接口也一样（例如别的网站替客户登录到攻击者的账号）
    assertThat(send("POST", "/api/auth/login", "{\"email\":\"a@example.com\",\"password\":\"x\"}", Map.of("Origin", "https://evil.example")).statusCode())
        .isEqualTo(403);
    // 后台接口也一样
    assertThat(send("POST", "/api/admin/auth/login", "{\"username\":\"x\",\"password\":\"y\"}", Map.of("Sec-Fetch-Site", "cross-site")).statusCode())
        .isEqualTo(403);

    // 同源：浏览器带 Sec-Fetch-Site: same-origin 和本站的 Origin
    assertThat(send("POST", "/api/notifications/read-all", null, Map.of("Cookie", cookie, "Origin", self, "Sec-Fetch-Site", "same-origin")).statusCode())
        .isBetween(200, 299);
    // 老浏览器没有 Sec-Fetch-Site，只有 Origin
    assertThat(send("POST", "/api/notifications/read-all", null, Map.of("Cookie", cookie, "Origin", self)).statusCode()).isBetween(200, 299);
    // 非浏览器（没有 Origin，也没有 Sec-Fetch-Site），例如部署脚本、测试
    assertThat(send("POST", "/api/notifications/read-all", null, Map.of("Cookie", cookie)).statusCode()).isBetween(200, 299);
    // 读接口不受影响（跨站读不到响应内容，由浏览器的同源策略保证）
    assertThat(send("GET", "/api/me/session", null, Map.of("Cookie", cookie, "Sec-Fetch-Site", "cross-site")).statusCode()).isEqualTo(200);
  }

  /** 用原始 HTTP 请求模拟 Nginx 转发（Host 是公网域名、X-Forwarded-Proto: https），Java 的 HTTP 客户端不让改 Host 头 */
  int viaNginx(String origin) throws Exception {
    try (java.net.Socket s = new java.net.Socket("localhost", port)) {
      String req = "POST /api/notifications/read-all HTTP/1.1\r\nHost: test-app.example\r\nX-Forwarded-For: 203.0.113.9\r\nX-Forwarded-Proto: https\r\n"
          + "Origin: " + origin + "\r\nCookie: " + cookie + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
      s.getOutputStream().write(req.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      String status = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII)).readLine();
      return Integer.parseInt(status.split(" ")[1]);
    }
  }

  @Test
  void SEC01_经过Nginx转发时按Nginx传来的域名和https判断同源() throws Exception {
    // 测试环境的真实情况：页面在 https://test-app.example，Nginx 用 http 转发到后端，Host 原样，X-Forwarded-Proto: https
    assertThat(viaNginx("https://test-app.example")).isBetween(200, 299);
    assertThat(viaNginx("https://test-admin.example")).as("同一主域名下的其他子域名").isEqualTo(403);
    assertThat(viaNginx("http://test-app.example")).as("协议不同也不是同源").isEqualTo(403);
  }

  @Test
  void SEC02_路径里带分号的请求一律400_不会进到后台接口() throws Exception {
    // 修复前：带着客户的 Cookie（客户登录检查只看原始路径，不以 /api/admin/ 开头就要求客户登录），Spring 忽略 ;x，请求进到后台登录接口，返回 INVALID_CREDENTIALS
    for (String path : new String[] {"/api/admin;x/auth/login", "/api/admin/auth/login;x", "/api/auth/login;jsessionid=1"}) {
      HttpResponse<String> r = send("POST", path, "{\"username\":\"x\",\"password\":\"y\",\"email\":\"a@example.com\"}", Map.of("Cookie", cookie));
      assertThat(r.statusCode()).as(path).isEqualTo(400);
      assertThat(r.body()).as(path).contains("\"code\":\"BAD_REQUEST\"").doesNotContain("INVALID_CREDENTIALS");
    }
  }

  String raw(String requestLine) throws Exception {
    try (java.net.Socket s = new java.net.Socket("localhost", port)) {
      String req = requestLine + "\r\nHost: localhost\r\nCookie: " + cookie + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
      s.getOutputStream().write(req.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      return new String(s.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @Test
  void SEC05_错误响应不带堆栈和服务器版本_参数类型不对返回400而不是500() throws Exception {
    // 路径参数类型不对：原来是 500 INTERNAL_ERROR，并在日志里记一条带堆栈的 ERROR（可以被用来刷日志）
    HttpResponse<String> r = send("POST", "/api/notifications/abc/read", null, Map.of("Cookie", cookie));
    assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
    assertThat(r.body()).contains("\"code\":\"BAD_REQUEST\"");
    // Tomcat 自己拒绝的请求（非法的百分号编码、非法字符）：不暴露服务器版本和堆栈
    for (String line : new String[] {"GET /api/%zz HTTP/1.1", "GET /api/a%00b HTTP/1.1", "GET /api/a\\b HTTP/1.1"}) {
      String resp = raw(line);
      assertThat(resp).as(line).doesNotContainIgnoringCase("tomcat").doesNotContain("Exception").doesNotContain("at org.");
    }
  }

  @Test
  void 路径里的点点不能绕过登录检查() throws Exception {
    HttpResponse<String> r = send("GET", "/api/admin/../ledger/account", null, Map.of());
    assertThat(r.statusCode()).isNotEqualTo(200);
    assertThat(r.body()).doesNotContain("balance");
  }

  @Test
  void WebSocket只接受同源_其他网站的页面连不上() throws Exception {
    assertThat(handshakeStatus("/api/ws/user", "https://evil.example")).isEqualTo(403);
    // S4 的 /api/ws/markets 建好后在这里加同样的检查
    WebSocket ok = http.newWebSocketBuilder().header("Cookie", cookie).header("Origin", "http://localhost:" + port)
        .buildAsync(URI.create("ws://localhost:" + port + "/api/ws/user"), new WebSocket.Listener() {}).get(5, TimeUnit.SECONDS);
    ok.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
  }

  int handshakeStatus(String path, String origin) throws Exception {
    try {
      http.newWebSocketBuilder().header("Cookie", cookie).header("Origin", origin)
          .buildAsync(URI.create("ws://localhost:" + port + path), new WebSocket.Listener() {}).get(5, TimeUnit.SECONDS)
          .sendClose(WebSocket.NORMAL_CLOSURE, "");
      return 101;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof WebSocketHandshakeException h) return h.getResponse().statusCode();
      throw e;
    }
  }
}
