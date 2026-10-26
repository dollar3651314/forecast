package com.forecast.app.common.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 调用外部 HTTP 接口的统一客户端（agents C81；技术方案/v1-架构方案.md 第 6.3 节"所有外部调用走 common 里统一的 HTTP 客户端"；forecast S6 起用于数据源）。
 * <ul>
 *   <li><b>连接超时</b>：HttpClient 的 connectTimeout（每种超时一个 client，复用连接）。</li>
 *   <li><b>读取超时</b>：从发出请求到读完响应体的总时长上限（不只是等到响应头：对方慢慢吐字节也会超时），到时取消这次请求。</li>
 *   <li><b>响应体上限</b>：超过 maxBytes 立即断开，不把大响应读进内存。</li>
 *   <li>不跟随跳转；不重试（重试、熔断由调用方的网关按业务决定）。</li>
 * </ul>
 * 失败一律抛 {@link Failure}，{@link Failure#kind} 区分超时、连不上、断开、太大。代理用 JDK 默认的 ProxySelector（系统属性）。
 */
public final class ExternalHttp {

  /**
   * 一次请求的结果（任何 HTTP 状态码都算拿到了响应，由调用方判断）。
   *
   * @param headers 响应头（forecast S8：限频时读 Retry-After 等），键是小写
   */
  public record Response(int status, byte[] body, Map<String, List<String>> headers) {

    public Response(int status, byte[] body) {
      this(status, body, Map.of());
    }

    /** 某个响应头的第一个值（名字不分大小写），没有为 null */
    public String header(String name) {
      List<String> v = headers == null ? null : headers.get(name.toLowerCase(java.util.Locale.ROOT));
      return v == null || v.isEmpty() ? null : v.get(0);
    }
  }

  public enum Kind {
    /** 连接超时 */
    CONNECT_TIMEOUT,
    /** 连不上（拒绝连接、DNS 解析失败等） */
    CONNECT_FAILED,
    /** 读取超时（总时长超过读取超时） */
    READ_TIMEOUT,
    /** 连接中途断开、协议错误 */
    IO,
    /** 响应体超过上限 */
    TOO_LARGE
  }

  public static final class Failure extends Exception {
    private final Kind kind;

    public Failure(Kind kind, String message, Throwable cause) {
      super(message, cause);
      this.kind = kind;
    }

    public Kind kind() {
      return kind;
    }
  }

  private final Map<Duration, HttpClient> clients = new ConcurrentHashMap<>();

  private HttpClient client(Duration connectTimeout) {
    return clients.computeIfAbsent(connectTimeout, ct -> HttpClient.newBuilder().connectTimeout(ct).followRedirects(HttpClient.Redirect.NEVER).build());
  }

  /**
   * GET 一个地址。
   *
   * @param headers 额外的请求头（可以为空）
   */
  public Response get(URI uri, Duration connectTimeout, Duration readTimeout, int maxBytes, Map<String, String> headers) throws Failure {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(readTimeout).header("Accept", "application/json").GET();
    if (headers != null) headers.forEach(b::header);
    return send(b, connectTimeout, readTimeout, maxBytes);
  }

  /**
   * POST 一个请求体（forecast S16：Telegram Bot API 的 sendMessage（JSON）、sendPhoto（multipart））。超时、响应体上限、不跟随跳转、不重试，和 {@link #get} 一样。
   *
   * @param contentType 请求体的类型（{@code application/json}、{@code multipart/form-data; boundary=…}）
   */
  public Response post(URI uri, Duration connectTimeout, Duration readTimeout, int maxBytes, Map<String, String> headers, String contentType, byte[] body)
      throws Failure {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(readTimeout).header("Accept", "application/json").header("Content-Type", contentType)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
    if (headers != null) headers.forEach(b::header);
    return send(b, connectTimeout, readTimeout, maxBytes);
  }

  private Response send(HttpRequest.Builder b, Duration connectTimeout, Duration readTimeout, int maxBytes) throws Failure {
    CompletableFuture<HttpResponse<byte[]>> f = client(connectTimeout).sendAsync(b.build(), info -> new Capped(maxBytes));
    try {
      // 总时长 = 连接超时 + 读取超时（连接阶段由 connectTimeout 自己报错，这里是兜底）
      HttpResponse<byte[]> r = f.get(connectTimeout.plus(readTimeout).toMillis(), TimeUnit.MILLISECONDS);
      Map<String, List<String>> h = new java.util.HashMap<>();
      r.headers().map().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v));
      return new Response(r.statusCode(), r.body(), h);
    } catch (TimeoutException e) {
      f.cancel(true);
      throw new Failure(Kind.READ_TIMEOUT, "读取超时（" + readTimeout.toMillis() + " 毫秒）", e);
    } catch (InterruptedException e) {
      f.cancel(true);
      Thread.currentThread().interrupt();
      throw new Failure(Kind.IO, "请求被中断", e);
    } catch (ExecutionException e) {
      throw classify(e.getCause() == null ? e : e.getCause(), readTimeout);
    }
  }

  private static Failure classify(Throwable t, Duration readTimeout) {
    Throwable c = t;
    // 找到最里层的原因（CompletionException / IOException 包了几层）
    for (int i = 0; i < 5 && c.getCause() != null && !(c instanceof TooLarge) && !(c instanceof HttpTimeoutException) && !(c instanceof ConnectException); i++) {
      c = c.getCause();
    }
    if (t instanceof TooLarge || c instanceof TooLarge) return new Failure(Kind.TOO_LARGE, "响应超过上限", t);
    if (t instanceof HttpConnectTimeoutException || c instanceof HttpConnectTimeoutException) return new Failure(Kind.CONNECT_TIMEOUT, "连接超时", t);
    if (t instanceof HttpTimeoutException || c instanceof HttpTimeoutException) {
      return new Failure(Kind.READ_TIMEOUT, "读取超时（" + readTimeout.toMillis() + " 毫秒）", t);
    }
    if (t instanceof ConnectException || c instanceof ConnectException || c instanceof java.nio.channels.UnresolvedAddressException
        || c instanceof java.net.UnknownHostException) {
      return new Failure(Kind.CONNECT_FAILED, "连不上：" + c.getClass().getSimpleName(), t);
    }
    String msg = c.getMessage() == null ? c.getClass().getSimpleName() : c.getClass().getSimpleName() + ": " + c.getMessage();
    return new Failure(Kind.IO, msg.length() > 300 ? msg.substring(0, 300) : msg, t);
  }

  /** 响应体超过上限 */
  static final class TooLarge extends IOException {
    TooLarge(int max) {
      super("响应体超过 " + max + " 字节");
    }
  }

  /** 有上限的响应体读取：超过上限立即取消订阅（断开），以异常结束 */
  static final class Capped implements HttpResponse.BodySubscriber<byte[]> {
    private final int max;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription sub;

    Capped(int max) {
      this.max = max;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
      sub = s;
      s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
      if (result.isDone()) return;
      for (ByteBuffer b : items) {
        if (out.size() + b.remaining() > max) {
          sub.cancel();
          result.completeExceptionally(new TooLarge(max));
          return;
        }
        byte[] chunk = new byte[b.remaining()];
        b.get(chunk);
        out.write(chunk, 0, chunk.length);
      }
    }

    @Override
    public void onError(Throwable t) {
      result.completeExceptionally(t);
    }

    @Override
    public void onComplete() {
      result.complete(out.toByteArray());
    }
  }
}
