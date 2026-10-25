package com.forecast.app.common.trace;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * 追踪号（traceId，功能取舍对照表 P-07，需求 v2 E9）：把同一个请求、同一条 WebSocket 连接、同一次定时任务的日志串起来。
 * <ul>
 *   <li>HTTP 请求：{@link TraceIdFilter} 优先用 Nginx 传来的 {@code X-Request-Id}（Nginx 的 {@code $request_id}，32 位十六进制；
 *       Nginx 用自己生成的值覆盖客户端带的同名头），没有或格式不对时自己生成；写进日志（MDC {@code traceId}）、响应头 {@code X-Request-Id}、
 *       错误响应体的 {@code traceId}。客户报问题时给出这个号，运维按它在日志里查。</li>
 *   <li>WebSocket：握手请求的追踪号记在连接上，之后这个连接收到消息等处理的日志都带它（{@link TraceWebSocket}）。</li>
 *   <li>定时任务：每执行一次生成一个新的，前缀 {@code job-}（{@link TraceTaskDecorator}）。</li>
 * </ul>
 * 日志格式在 application.yml 的 {@code logging.pattern.level}：级别后面是 {@code [追踪号]}，没有时是 {@code [-]}。
 */
public final class TraceId {

  /** MDC 里的键 */
  public static final String MDC_KEY = "traceId";
  /** 请求头和响应头 */
  public static final String HEADER = "X-Request-Id";
  /** 请求属性：过滤器之后的代码（例如 WebSocket 握手）从这里取 */
  public static final String ATTRIBUTE = TraceId.class.getName();

  /** 只接受这种格式的外来追踪号（防止往日志里写换行、超长内容） */
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

  private TraceId() {}

  /** 新的追踪号：32 位十六进制（和 Nginx 的 $request_id 一样长） */
  public static String generate() {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    return String.format("%016x%016x", r.nextLong(), r.nextLong());
  }

  /** 外来的追踪号格式对就用它，否则生成一个新的 */
  public static String acceptOrGenerate(String incoming) {
    return valid(incoming) ? incoming : generate();
  }

  public static boolean valid(String id) {
    return id != null && VALID.matcher(id).matches();
  }

  /** 当前线程的追踪号；没有时为 null */
  public static String current() {
    return MDC.get(MDC_KEY);
  }

  /** 在指定追踪号下运行，结束后恢复原来的值 */
  public static void run(String id, Runnable r) {
    call(id, () -> {
      r.run();
      return null;
    });
  }

  public static <T> T call(String id, Supplier<T> s) {
    String old = MDC.get(MDC_KEY);
    MDC.put(MDC_KEY, id);
    try {
      return s.get();
    } finally {
      if (old == null) MDC.remove(MDC_KEY);
      else MDC.put(MDC_KEY, old);
    }
  }
}
