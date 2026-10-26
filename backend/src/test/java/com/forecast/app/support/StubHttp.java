package com.forecast.app.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 本机的 HTTP 替身（forecast S6，外部调用测试用，C81：慢响应、断开、5xx、格式不对）。监听 127.0.0.1 的随机端口，每个请求按 {@link #handle} 设的函数回复；
 * 记下收到的请求。测试里不连任何外网。
 */
public final class StubHttp implements AutoCloseable {

  /**
   * 一次回复。
   *
   * @param delayMs 回复前等多久（模拟慢响应 / 读取超时）
   * @param disconnect true：不回复直接断开连接
   * @param trickleMs &gt; 0：先发响应头，再每隔这么久发 1 个字节（模拟"慢慢吐字节"）
   */
  public record Reply(int status, String body, long delayMs, boolean disconnect, long trickleMs) {

    public static Reply ok(String body) {
      return new Reply(200, body, 0, false, 0);
    }

    public static Reply status(int status, String body) {
      return new Reply(status, body, 0, false, 0);
    }

    public static Reply slow(long delayMs, String body) {
      return new Reply(200, body, delayMs, false, 0);
    }

    public static Reply drop() {
      return new Reply(0, null, 0, true, 0);
    }

    public static Reply trickle(long everyMs, String body) {
      return new Reply(200, body, 0, false, everyMs);
    }
  }

  private final HttpServer server;
  private final ExecutorService pool = Executors.newCachedThreadPool();
  private final List<URI> requests = new CopyOnWriteArrayList<>();
  /** 每个请求的请求头（名字小写，取第一个值；forecast S8：核对 key 有没有带） */
  private final List<java.util.Map<String, String>> headers = new CopyOnWriteArrayList<>();
  /** 每个请求的请求体（forecast S16：POST 的 JSON / multipart，和 {@link #requests()} 一一对应） */
  private final List<byte[]> bodies = new CopyOnWriteArrayList<>();
  private volatile Function<URI, Reply> handler = u -> Reply.status(404, "{}");

  public StubHttp() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.setExecutor(pool);
    server.createContext("/", this::serve);
    server.start();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void handle(Function<URI, Reply> h) {
    handler = h;
  }

  /** 收到的请求（含路径和查询串） */
  public List<URI> requests() {
    return List.copyOf(requests);
  }

  /** 收到的请求头（和 {@link #requests()} 一一对应） */
  public List<java.util.Map<String, String>> headers() {
    return List.copyOf(headers);
  }

  /** 收到的请求体（和 {@link #requests()} 一一对应；GET 是空的） */
  public List<byte[]> bodies() {
    return List.copyOf(bodies);
  }

  /** 第 i 个请求体按 UTF-8 解成文字 */
  public String bodyText(int i) {
    return new String(bodies.get(i), StandardCharsets.UTF_8);
  }

  public int hits() {
    return requests.size();
  }

  public void clear() {
    requests.clear();
    headers.clear();
    bodies.clear();
  }

  private void serve(HttpExchange ex) throws IOException {
    URI u = ex.getRequestURI();
    java.util.Map<String, String> h = new java.util.HashMap<>();
    ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v.isEmpty() ? "" : v.get(0)));
    headers.add(h);
    bodies.add(ex.getRequestBody().readAllBytes());
    requests.add(u);
    Reply r;
    try {
      r = handler.apply(u);
    } catch (RuntimeException e) {
      r = Reply.status(500, "stub error: " + e.getMessage());
    }
    try {
      if (r.delayMs() > 0) Thread.sleep(r.delayMs());
      if (r.disconnect()) {
        ex.close(); // 不发响应头，直接断开
        return;
      }
      byte[] b = (r.body() == null ? "" : r.body()).getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().set("Content-Type", "application/json");
      if (r.trickleMs() > 0) {
        ex.sendResponseHeaders(r.status(), b.length);
        try (OutputStream out = ex.getResponseBody()) {
          for (byte x : b) {
            out.write(x);
            out.flush();
            Thread.sleep(r.trickleMs());
          }
        }
        return;
      }
      ex.sendResponseHeaders(r.status(), b.length == 0 ? -1 : b.length);
      try (OutputStream out = ex.getResponseBody()) {
        out.write(b);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      // 客户端已经超时断开
    } finally {
      ex.close();
    }
  }

  @Override
  public void close() {
    server.stop(0);
    pool.shutdownNow();
  }
}
