package com.forecast.app.notification.service;

import com.forecast.app.common.health.HealthCheck;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Web Push 发送（S15b，通知方案 N2、N3）：
 * <ul>
 *   <li>VAPID 密钥（RFC 8292）由 Amos 生成并填进服务器；启动时用私钥算出公钥和配置的公钥对一遍，对不上就不发推送、健康检查 {@code push} 报错。
 *       没有配置时不发推送，健康检查通过（站内信和邮件照常）。私钥不进日志和 toString。</li>
 *   <li>内容按 RFC 8291 加密，只放标题和正文。</li>
 *   <li>只发到浏览器推送服务的地址（Google、Apple、Mozilla、微软），防止被当成跳板访问别的地址。</li>
 * </ul>
 */
@Component
class WebPush implements HealthCheck {

  private static final Logger log = LoggerFactory.getLogger(WebPush.class);

  /** 发送结果 */
  sealed interface Result permits Delivered, Gone, Rejected, Retry {}

  record Delivered() implements Result {}

  /** 订阅已失效（404、410），要删除订阅 */
  record Gone(int status) implements Result {}

  /** 推送服务明确拒绝（其他 4xx），重试也没用 */
  record Rejected(int status, String reason) implements Result {}

  /** 暂时失败（429、5xx、连不上），按任务表重试 */
  record Retry(String reason) implements Result {}

  private final NotifyProperties.Push cfg;
  private final Clock clock;
  private final HttpClient http;
  private final ECPrivateKey key;
  private final String publicKey;
  private final boolean healthy;

  WebPush(NotifyProperties props, Clock clock) {
    this.cfg = props.push();
    this.clock = clock;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    ECPrivateKey k = null;
    String pub = null;
    boolean ok = true;
    if (!cfg.anySet()) {
      log.warn("Web Push 未配置（FC_PUSH_VAPID_*），不发推送；站内信和邮件照常");
    } else if (!cfg.allSet()) {
      log.error("Web Push 配置不完整：公钥、私钥、联系方式三个都要填");
      ok = false;
    } else {
      try {
        byte[] d = WebPushCrypto.b64(cfg.vapidPrivateKey());
        byte[] derived = WebPushCrypto.derivePublic(d);
        if (!Arrays.equals(derived, WebPushCrypto.b64(cfg.vapidPublicKey()))) {
          log.error("Web Push 私钥算出的公钥和 FC_PUSH_VAPID_PUBLIC_KEY 对不上，不发推送");
          ok = false;
        } else if (!cfg.vapidSubject().startsWith("mailto:") && !cfg.vapidSubject().startsWith("https://")) {
          log.error("FC_PUSH_VAPID_SUBJECT 必须是 mailto: 或 https:// 开头");
          ok = false;
        } else {
          k = WebPushCrypto.privateKey(d);
          pub = WebPushCrypto.b64(derived);
          log.info("Web Push 已配置，公钥 {}", pub);
        }
      } catch (RuntimeException | GeneralSecurityException e) {
        log.error("Web Push 密钥格式不对：{}", e.getClass().getSimpleName());
        ok = false;
      }
    }
    this.key = k;
    this.publicKey = pub;
    this.healthy = ok;
  }

  @Override
  public String name() {
    return "push";
  }

  /** 配置错误（不完整、公私钥对不上、格式不对）时不通过；没有配置时通过 */
  @Override
  public boolean ok() {
    return healthy;
  }

  boolean enabled() {
    return key != null;
  }

  /** 客户端订阅时用的公钥（applicationServerKey）；没有配置时为空 */
  String publicKey() {
    return publicKey;
  }

  /** 订阅地址是否允许：https、主机是浏览器推送服务（测试配置可以放行本机 http） */
  boolean allowedEndpoint(String endpoint) {
    try {
      URI u = URI.create(endpoint);
      String host = u.getHost();
      if (host == null || u.getUserInfo() != null) return false;
      host = host.toLowerCase(Locale.ROOT);
      if ("localhost".equals(host) && cfg.allowLocalhost()) return "http".equals(u.getScheme()) || "https".equals(u.getScheme());
      if (!"https".equals(u.getScheme()) || (u.getPort() != -1 && u.getPort() != 443)) return false;
      for (String allowed : cfg.allowedHosts()) {
        String a = allowed.toLowerCase(Locale.ROOT).strip();
        if (a.startsWith("*.") ? host.endsWith(a.substring(1)) : host.equals(a)) return true;
      }
      return false;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  Result send(String endpoint, byte[] p256dh, byte[] auth, byte[] plaintext, boolean urgent) {
    if (key == null) return new Rejected(0, "推送未配置");
    if (!allowedEndpoint(endpoint)) return new Rejected(0, "推送地址不允许");
    byte[] body;
    try {
      body = WebPushCrypto.encrypt(plaintext, p256dh, auth);
    } catch (RuntimeException e) {
      return new Rejected(0, "订阅的公钥不对：" + e.getMessage());
    }
    HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(15))
        .header("TTL", String.valueOf(cfg.ttl().toSeconds()))
        .header("Urgency", urgent ? "high" : "normal")
        .header("Content-Encoding", "aes128gcm")
        .header("Content-Type", "application/octet-stream")
        .header("Authorization", authorization(endpoint))
        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    try {
      HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
      int s = r.statusCode();
      if (s >= 200 && s < 300) return new Delivered();
      if (s == 404 || s == 410) return new Gone(s);
      String text = r.body() == null ? "" : r.body().length() > 200 ? r.body().substring(0, 200) : r.body();
      String reason = ("推送服务返回 " + s + " " + text).strip();
      if (s == 429 || s >= 500) return new Retry(reason);
      return new Rejected(s, reason);
    } catch (java.io.IOException e) {
      return new Retry("连不上推送服务：" + e.getClass().getSimpleName() + " " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Retry("被中断");
    }
  }

  /** VAPID（RFC 8292）：{@code vapid t=<JWT>, k=<公钥>}；JWT 的 aud 是推送服务的源，12 小时有效，ES256 签名 */
  String authorization(String endpoint) {
    URI u = URI.create(endpoint);
    String aud = u.getScheme() + "://" + u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort());
    long exp = clock.instant().plus(Duration.ofHours(12)).getEpochSecond();
    String header = WebPushCrypto.b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
    String claims = WebPushCrypto.b64(("{\"aud\":\"" + aud + "\",\"exp\":" + exp + ",\"sub\":\"" + cfg.vapidSubject().replace("\"", "") + "\"}")
        .getBytes(StandardCharsets.UTF_8));
    String input = header + "." + claims;
    try {
      Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
      s.initSign(key);
      s.update(input.getBytes(StandardCharsets.US_ASCII));
      return "vapid t=" + input + "." + WebPushCrypto.b64(s.sign()) + ", k=" + publicKey;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("VAPID 签名失败", e);
    }
  }
}
