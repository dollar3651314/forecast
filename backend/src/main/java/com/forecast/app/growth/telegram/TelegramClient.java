package com.forecast.app.growth.telegram;

import com.forecast.app.common.http.ExternalHttp;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Telegram Bot API 的客户端（forecast S16，MF-7；外部调用按 agents C81、约定 40）。
 * <ul>
 *   <li>走 {@link ExternalHttp}：连接超时、读取超时（读完响应体的总时长）、响应体上限、不跟随跳转。</li>
 *   <li><b>重试</b>：只重试超时、连不上、断开、5xx、408，最多 {@code retries} 次、间隔递增；4xx（chat 不存在、机器人被踢出、没有权限）不重试。</li>
 *   <li><b>429 限流</b>：读响应里的 {@code parameters.retry_after}（没有时读 Retry-After 头）：不超过 {@code max-inline-wait} 就当场等完再试，
 *       否则抛 {@link Kind#RATE_LIMITED} 带等待秒数，由调用方把这一张改到那之后再发（发帖任务同时暂停这个群 / 全部发帖）。429 不算熔断的失败。</li>
 *   <li><b>熔断</b>：连续 {@code breaker-threshold} 次失败（重试完仍失败）后 {@code breaker-open} 内直接失败，不再调用。</li>
 *   <li><b>不能在数据库事务里调用</b>（直接报错，和数据源网关一样）：先在事务外发，再开事务记结果。</li>
 *   <li><b>token 只在请求地址里</b>：异常、日志里只写方法名和 Telegram 的错误描述，不写地址（地址里有 token）。</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramClient {

  private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);
  static final int MAX_BYTES = 2 * 1024 * 1024;

  public enum Kind {
    /** 没配置 token */
    NOT_CONFIGURED,
    /** 熔断中 */
    CIRCUIT_OPEN,
    /** 超时、断开、5xx：重试完仍失败 */
    UNAVAILABLE,
    /** 429，要等 retryAfter 秒 */
    RATE_LIMITED,
    /** 4xx：Telegram 拒绝（chat 不存在、机器人不在群里、没有发言权限、内容不合法） */
    REJECTED
  }

  /** 调用失败（消息里没有 token） */
  public static final class Failure extends Exception {
    private final Kind kind;
    private final int status;
    private final long retryAfter;

    Failure(Kind kind, int status, long retryAfter, String message) {
      super(message);
      this.kind = kind;
      this.status = status;
      this.retryAfter = retryAfter;
    }

    public Kind kind() {
      return kind;
    }

    public int status() {
      return status;
    }

    /** 429 时要等多少秒 */
    public long retryAfter() {
      return retryAfter;
    }
  }

  private final TelegramProperties props;
  private final ExternalHttp http;
  private final ObjectMapper json;
  private final Clock clock;
  private int consecutiveFailures;
  private Instant openUntil;
  private volatile String lastError;
  private volatile Instant lastOkAt;

  @Autowired
  TelegramClient(TelegramProperties props, ObjectMapper json, Clock clock) {
    this(props, new ExternalHttp(), json, clock);
  }

  TelegramClient(TelegramProperties props, ExternalHttp http, ObjectMapper json, Clock clock) {
    this.props = props;
    this.http = http;
    this.json = json;
    this.clock = clock;
  }

  /** JSON 参数的方法（getMe、getUpdates、sendMessage、getChatMember）；返回 {@code result} */
  public JsonNode call(String method, Map<String, Object> params) throws Failure {
    return call(method, params, props.getReadTimeout());
  }

  public JsonNode call(String method, Map<String, Object> params, Duration readTimeout) throws Failure {
    byte[] body = json.writeValueAsBytes(params == null ? Map.of() : params);
    return invoke(method, "application/json", body, readTimeout);
  }

  /** 发一张图片（multipart 上传 PNG，不用 Telegram 来抓我们的地址）；caption 最多 1024 个字符 */
  public JsonNode sendPhoto(String chatId, byte[] png, String caption) throws Failure {
    String boundary = "fc" + UUID.randomUUID().toString().replace("-", "");
    ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 2048);
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("chat_id", chatId);
    fields.put("caption", caption);
    for (Map.Entry<String, String> f : fields.entrySet()) {
      out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      out.writeBytes(f.getValue().getBytes(StandardCharsets.UTF_8));
      out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }
    out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"card.png\"\r\nContent-Type: image/png\r\n\r\n")
        .getBytes(StandardCharsets.UTF_8));
    out.writeBytes(png);
    out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return invoke("sendPhoto", "multipart/form-data; boundary=" + boundary, out.toByteArray(), props.getReadTimeout());
  }

  private JsonNode invoke(String method, String contentType, byte[] body, Duration readTimeout) throws Failure {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("不能在数据库事务里调用 Telegram（" + method + "）：先在事务外调用，再开事务记结果");
    }
    if (!props.configured()) throw new Failure(Kind.NOT_CONFIGURED, 0, 0, "没有配置 FC_TELEGRAM_BOT_TOKEN");
    synchronized (this) {
      if (openUntil != null && clock.instant().isBefore(openUntil)) throw new Failure(Kind.CIRCUIT_OPEN, 0, 0, "Telegram 连续失败，熔断中");
    }
    URI uri = URI.create(props.getApiUrl() + "/bot" + props.getToken() + "/" + method);
    int attempts = props.getRetries() + 1;
    Failure lastFailure = null;
    for (int i = 1; i <= attempts; i++) {
      ExternalHttp.Response r;
      try {
        r = http.post(uri, props.getConnectTimeout(), readTimeout, MAX_BYTES, Map.of(), contentType, body);
      } catch (ExternalHttp.Failure e) {
        lastFailure = new Failure(Kind.UNAVAILABLE, 0, 0, method + "：" + e.kind() + " " + e.getMessage());
        if (e.kind() == ExternalHttp.Kind.TOO_LARGE) break;
        backoff(i, attempts);
        continue;
      }
      JsonNode root = parse(r.body());
      if (r.status() == 200 && root != null && root.path("ok").asBoolean(false)) {
        succeeded();
        return root.path("result");
      }
      String desc = root == null ? "HTTP " + r.status() : root.path("description").asString("HTTP " + r.status());
      if (r.status() == 429) {
        long wait = root == null ? 0 : root.path("parameters").path("retry_after").asLong(0);
        if (wait <= 0) wait = parseLong(r.header("Retry-After"), 1);
        if (wait <= props.getMaxInlineWait().toSeconds() && i < attempts) {
          log.info("Telegram {} 被限流，等 {} 秒再试", method, wait);
          sleep(wait * 1000);
          continue;
        }
        throw new Failure(Kind.RATE_LIMITED, 429, wait, method + "：限流，" + wait + " 秒后再试（" + desc + "）");
      }
      if (r.status() >= 500 || r.status() == 408) {
        lastFailure = new Failure(Kind.UNAVAILABLE, r.status(), 0, method + "：HTTP " + r.status() + " " + desc);
        backoff(i, attempts);
        continue;
      }
      // 其余 4xx：不重试、不算熔断（是请求本身的问题，例如 chat 不存在、机器人被踢出）
      succeeded();
      throw new Failure(Kind.REJECTED, r.status(), 0, method + "：HTTP " + r.status() + " " + desc);
    }
    failed(lastFailure == null ? method + "：失败" : lastFailure.getMessage());
    throw lastFailure == null ? new Failure(Kind.UNAVAILABLE, 0, 0, method + "：失败") : lastFailure;
  }

  private synchronized void succeeded() {
    consecutiveFailures = 0;
    openUntil = null;
    lastOkAt = clock.instant();
  }

  private synchronized void failed(String message) {
    consecutiveFailures++;
    lastError = message;
    if (consecutiveFailures >= props.getBreakerThreshold()) {
      openUntil = clock.instant().plus(props.getBreakerOpen());
      log.warn("Telegram 连续 {} 次失败，{} 内不再调用：{}", consecutiveFailures, props.getBreakerOpen(), message);
    } else {
      log.info("Telegram 调用失败（连续第 {} 次）：{}", consecutiveFailures, message);
    }
  }

  /** 熔断中（健康检查、后台显示） */
  public synchronized boolean circuitOpen() {
    return openUntil != null && clock.instant().isBefore(openUntil);
  }

  public synchronized int consecutiveFailures() {
    return consecutiveFailures;
  }

  public String lastError() {
    return lastError;
  }

  public Instant lastOkAt() {
    return lastOkAt;
  }

  private JsonNode parse(byte[] body) {
    try {
      return body == null || body.length == 0 ? null : json.readTree(body);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static long parseLong(String s, long fallback) {
    try {
      return s == null ? fallback : Long.parseLong(s.strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static void backoff(int attempt, int attempts) {
    if (attempt < attempts) sleep(400L * attempt);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
