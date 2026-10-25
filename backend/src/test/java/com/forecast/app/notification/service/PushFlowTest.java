package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.forecast.app.notification.api.NotifyChannel;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Web Push（S15b）：订阅、加密发送、失效订阅清理、重试、偏好。推送服务用本机的 HTTP 替身（测试配置放行 localhost），
 * 替身收到的内容由测试扮演浏览器按 RFC 8291 解密核对。
 */
class PushFlowTest extends NotifyTestSupport {

  record Received(String path, Map<String, String> headers, byte[] body) {}

  static HttpServer service;
  static final List<Received> received = new CopyOnWriteArrayList<>();
  /** 路径 → 返回的状态码（默认 201） */
  static final Map<String, Integer> replies = new ConcurrentHashMap<>();

  @BeforeAll
  static void startPushService() throws Exception {
    service = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    service.createContext("/", ex -> {
      Map<String, String> h = new java.util.HashMap<>();
      ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.getFirst()));
      String path = ex.getRequestURI().getPath();
      received.add(new Received(path, h, ex.getRequestBody().readAllBytes()));
      int status = replies.getOrDefault(path, 201);
      ex.sendResponseHeaders(status, -1);
      ex.close();
    });
    service.start();
  }

  @AfterAll
  static void stopPushService() {
    service.stop(0);
  }

  record Device(String endpoint, KeyPair keys, byte[] auth) {
    String json() {
      return "{\"endpoint\":\"" + endpoint + "\",\"expirationTime\":null,\"keys\":{\"p256dh\":\""
          + WebPushCrypto.b64(WebPushCrypto.publicBytes((ECPublicKey) keys.getPublic())) + "\",\"auth\":\"" + WebPushCrypto.b64(auth) + "\"}}";
    }

    String path() {
      return java.net.URI.create(endpoint).getPath();
    }
  }

  Device device() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    byte[] auth = new byte[16];
    ThreadLocalRandom.current().nextBytes(auth);
    return new Device("http://localhost:" + service.getAddress().getPort() + "/push/" + System.nanoTime(), g.generateKeyPair(), auth);
  }

  Device subscribed(User u) throws Exception {
    Device d = device();
    MockHttpServletResponse r = call(post("/api/notifications/push/subscriptions"), u.cookie(), d.json());
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return d;
  }

  List<Received> to(Device d) {
    return received.stream().filter(r -> r.path().equals(d.path())).toList();
  }

  JsonNode open(Received r, Device d) throws Exception {
    return json.readTree(new String(PushTestSupport.decrypt(r.body(), d.keys(), d.auth()), StandardCharsets.UTF_8));
  }

  @Test
  void 公钥接口_订阅_每台设备一条记录_加密发出_浏览器能解密_带VAPID签名() throws Exception {
    User u = registerClean();
    assertThat(call(get("/api/notifications/push/key"), u.cookie(), null).getContentAsString())
        .isEqualTo("{\"publicKey\":\"" + WebPushCryptoTest.TEST_PUBLIC + "\"}");
    Device phone = subscribed(u);
    Device laptop = subscribed(u);
    // 复制源用的是强平模板（高优先级）；forecast 第一期没有高优先级的推送（S1 删除），这里用认证通过（三个渠道都发）
    notify.toUser(u.id(), "KYC_APPROVED", Map.of());
    assertThat(records(u.id())).containsExactly("INBOX SENT -", "PUSH PENDING -", "PUSH PENDING -", "EMAIL PENDING -");
    worker.drain();
    assertThat(records(u.id())).containsExactly("INBOX SENT -", "PUSH SENT -", "PUSH SENT -", "EMAIL SENT -");

    Received r = to(phone).getFirst();
    assertThat(r.headers()).containsEntry("content-encoding", "aes128gcm").containsEntry("ttl", "86400").containsEntry("urgency", "normal")
        .containsEntry("content-type", "application/octet-stream");
    assertThat(r.headers().get("authorization")).startsWith("vapid t=").endsWith(", k=" + WebPushCryptoTest.TEST_PUBLIC);
    JsonNode msg = open(r, phone);
    assertThat(msg.get("title").asString()).isEqualTo("身份认证已通过");
    assertThat(msg.get("body").asString()).isEqualTo("你的身份认证已通过，现在可以提现，下注也不再受累计充值的认证门槛限制。");
    assertThat(msg.get("kind").asString()).isEqualTo("kyc");
    long messageId = jdbc.queryForObject("SELECT id FROM notify_message WHERE user_id = ?", Long.class, u.id());
    assertThat(msg.get("id").asString()).isEqualTo(String.valueOf(messageId));
    assertThat(open(to(laptop).getFirst(), laptop).get("title").asString()).isEqualTo("身份认证已通过");
  }

  @Test
  void 按客户语言推送_普通通知不加急_手动发送原样推送() throws Exception {
    User u = registerClean();
    Device d = subscribed(u);
    call(put("/api/me/notify-lang"), u.cookie(), "{\"lang\":\"en\"}");
    notify.toUser(u.id(), "DEPOSIT_CREDITED", Map.of("amount", "100", "chain", "TRC20"));
    admin.manual(u.id(), Set.of(NotifyChannel.PUSH), "维护通知", "今晚维护", 9);
    worker.drain();
    List<Received> got = to(d);
    assertThat(got).hasSize(2);
    assertThat(got.getFirst().headers()).containsEntry("urgency", "normal");
    assertThat(open(got.getFirst(), d).get("body").asString()).isEqualTo("Your deposit of 100 USDT via TRC20 has been credited to your available balance.");
    JsonNode manual = open(got.get(1), d);
    assertThat(manual.get("title").asString()).isEqualTo("维护通知");
    assertThat(manual.get("kind").asString()).isEqualTo("system");
    assertThat(manual.has("id")).as("只推送没有站内信").isFalse();
  }

  @Test
  void 订阅失效410就删除_推送服务500按任务表重试_400不重试() throws Exception {
    User u = registerClean();
    Device gone = subscribed(u);
    Device flaky = subscribed(u);
    Device bad = subscribed(u);
    replies.put(gone.path(), 410);
    replies.put(flaky.path(), 503);
    replies.put(bad.path(), 400);
    notify.toUser(u.id(), "KYC_APPROVED", Map.of());
    worker.drain();
    assertThat(records(u.id())).containsExactly("INBOX SENT -", "PUSH FAILED 订阅已失效（410），已删除", "PUSH PENDING 推送服务返回 503", "PUSH FAILED 推送服务返回 400",
        "EMAIL SENT -");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_push_sub WHERE user_id = ?", Long.class, u.id())).as("410 的订阅删掉了").isEqualTo(2);

    // 推送服务恢复后，任务表到时间重试（这里直接把重试时间提前）
    replies.remove(flaky.path());
    jdbc.update("UPDATE common_task SET next_run_at = UTC_TIMESTAMP(3) WHERE task_type = 'PUSH_SEND' AND status = 'PENDING' AND JSON_EXTRACT(payload, '$.userId') = ?", u.id());
    worker.drain();
    assertThat(records(u.id()).get(2)).isEqualTo("PUSH SENT -");
    assertThat(to(flaky)).hasSize(2);
    assertThat(to(bad)).as("400 不重试").hasSize(1);
  }

  @Test
  void 关闭推送记已跳过_没有设备记已跳过_取消订阅() throws Exception {
    User u = registerClean();
    notify.toUser(u.id(), "KYC_APPROVED", Map.of());
    assertThat(records(u.id()).get(1)).isEqualTo("PUSH SKIPPED 没有订阅推送的设备");
    Device d = subscribed(u);
    call(put("/api/me/notify-prefs"), u.cookie(), "{\"push\":false,\"email\":true}");
    notify.toUser(u.id(), "KYC_APPROVED", Map.of());
    assertThat(records(u.id()).get(4)).isEqualTo("PUSH SKIPPED 客户关闭了推送");
    call(put("/api/me/notify-prefs"), u.cookie(), "{\"push\":true,\"email\":true}");
    assertThat(call(post("/api/notifications/push/unsubscribe"), u.cookie(), "{\"endpoint\":\"" + d.endpoint() + "\"}").getStatus()).isEqualTo(200);
    notify.toUser(u.id(), "KYC_APPROVED", Map.of());
    assertThat(records(u.id()).get(7)).isEqualTo("PUSH SKIPPED 没有订阅推送的设备");
    worker.drain();
    assertThat(to(d)).isEmpty();
  }

  @Test
  void 订阅校验_只接受推送服务地址和合法密钥_同一地址换账号归新账号_最多10台设备() throws Exception {
    User u = registerClean();
    User other = registerClean();
    Device d = device();
    String evil = d.json().replace(d.endpoint(), "https://169.254.169.254/latest/meta-data");
    MockHttpServletResponse r = call(post("/api/notifications/push/subscriptions"), u.cookie(), evil);
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("\"field\":\"endpoint\"");
    r = call(post("/api/notifications/push/subscriptions"), u.cookie(), "{\"endpoint\":\"" + d.endpoint() + "\",\"keys\":{\"p256dh\":\"AAAA\",\"auth\":\"x\"}}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("\"field\":\"p256dh\"").contains("\"field\":\"auth\"");
    assertThat(call(post("/api/notifications/push/subscriptions"), u.cookie(), "{}").getContentAsString()).contains("\"field\":\"endpoint\"");

    call(post("/api/notifications/push/subscriptions"), u.cookie(), d.json());
    call(post("/api/notifications/push/subscriptions"), u.cookie(), d.json());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_push_sub WHERE user_id = ?", Long.class, u.id())).as("重复提交只保留一条").isEqualTo(1);
    call(post("/api/notifications/push/subscriptions"), other.cookie(), d.json());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_push_sub WHERE user_id = ?", Long.class, u.id())).as("同一台设备换了账号").isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_push_sub WHERE user_id = ?", Long.class, other.id())).isEqualTo(1);

    for (int i = 0; i < 12; i++) subscribed(u);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_push_sub WHERE user_id = ?", Long.class, u.id())).isEqualTo(10);
    assertThat(call(post("/api/notifications/push/subscriptions"), null, d.json()).getStatus()).isEqualTo(401);
  }

  @Test
  void 超长内容截短正文_加密后不超过一条记录() {
    String longBody = "很长的正文".repeat(1000);
    byte[] p = worker.pushPayload(new Render.Text("标题", longBody), "system", 7L);
    assertThat(p.length).isLessThanOrEqualTo(WebPushCrypto.MAX_PLAINTEXT);
    String s = new String(p, StandardCharsets.UTF_8);
    assertThat(s).startsWith("{\"title\":\"标题\",\"body\":\"很长的正文").contains("…\",\"kind\":\"system\",\"id\":\"7\"}");
  }
}
