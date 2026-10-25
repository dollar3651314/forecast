package com.forecast.app.common.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 追踪号（E9，AC2-21）和指标端点（E10），用真实端口：
 * <ul>
 *   <li>每个响应头都有 {@code X-Request-Id}；Nginx 传来的合法追踪号原样沿用，不合法的（带换行、太长）换成新的；</li>
 *   <li>错误响应体带同一个 {@code traceId}（过滤器直接写的 401/403/400、框架的 404、业务错误）；</li>
 *   <li>日志里能按追踪号查到这个请求；定时任务每次执行有自己的 {@code job-} 追踪号；</li>
 *   <li>{@code /actuator/prometheus} 只在内网管理端口，有自定义指标；对外端口没有。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TraceAndMetricsTest.Probe.class)
@ExtendWith(OutputCaptureExtension.class)
class TraceAndMetricsTest {

  /** 测试用：一个会写日志的接口，和一个记录追踪号的定时任务 */
  @TestConfiguration
  static class Probe {
    static final List<String> JOB_IDS = new CopyOnWriteArrayList<>();

    @Bean
    ProbeController traceProbeController() {
      return new ProbeController();
    }

    @Bean
    ProbeJob traceProbeJob() {
      return new ProbeJob();
    }
  }

  @RestController
  static class ProbeController {
    @GetMapping("/trace-probe")
    String probe() {
      LoggerFactory.getLogger(ProbeController.class).warn("追踪号探针被调用");
      return "ok";
    }
  }

  static class ProbeJob {
    @Scheduled(fixedDelay = 200)
    void run() {
      if (Probe.JOB_IDS.size() < 5) Probe.JOB_IDS.add(String.valueOf(MDC.get(TraceId.MDC_KEY)));
    }
  }

  @LocalServerPort int port;
  @LocalManagementPort int managementPort;
  @Autowired TraceIdFilter filter;

  private HttpResponse<String> get(int p, String path, String requestId) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + p + path)).timeout(Duration.ofSeconds(10));
    if (requestId != null) b.header(TraceId.HEADER, requestId);
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String origin) throws Exception {
    HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Origin", origin).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
    return HttpClient.newHttpClient().send(r, HttpResponse.BodyHandlers.ofString());
  }

  static String header(HttpResponse<?> r) {
    return r.headers().firstValue(TraceId.HEADER).orElse(null);
  }

  @Test
  void 每个响应头都带追踪号_沿用Nginx传来的合法值_不合法的换成新的() throws Exception {
    HttpResponse<String> a = get(port, "/api/health", null);
    assertThat(header(a)).matches("[0-9a-f]{32}");
    HttpResponse<String> b = get(port, "/api/health", null);
    assertThat(header(b)).as("每个请求不同").isNotEqualTo(header(a));
    String nginx = "0123456789abcdef0123456789abcdef";
    assertThat(header(get(port, "/api/health", nginx))).as("Nginx 的 $request_id 原样沿用").isEqualTo(nginx);
    assertThat(header(get(port, "/api/health", "x".repeat(65)))).as("太长").matches("[0-9a-f]{32}");
    assertThat(header(get(port, "/api/health", "abc def ghi"))).as("带空格（可能往日志里注入内容）").matches("[0-9a-f]{32}");
    assertThat(header(get(port, "/", null))).as("不是 /api/ 的请求也有").isNotNull();
  }

  @Test
  void 错误响应体带同一个追踪号() throws Exception {
    HttpResponse<String> unauthorized = get(port, "/api/ledger/account", "trace-unauth-0001");
    assertThat(unauthorized.statusCode()).isEqualTo(401);
    assertThat(unauthorized.body()).contains("\"code\":\"UNAUTHORIZED\"").contains("\"traceId\":\"trace-unauth-0001\"");
    HttpResponse<String> notFound = get(port, "/no-such-page", "trace-404-0001");
    assertThat(notFound.statusCode()).isEqualTo(404);
    assertThat(notFound.body()).contains("\"code\":\"NOT_FOUND\"").contains("\"traceId\":\"trace-404-0001\"");
    HttpResponse<String> semicolon = get(port, "/api/health;x", "trace-400-0001");
    assertThat(semicolon.statusCode()).isEqualTo(400);
    assertThat(semicolon.body()).contains("\"traceId\":\"trace-400-0001\"");
    HttpResponse<String> crossSite = post("/api/auth/login", "https://evil.example");
    assertThat(crossSite.statusCode()).isEqualTo(403);
    assertThat(crossSite.body()).contains("\"traceId\":\"" + header(crossSite) + "\"");
    HttpResponse<String> biz = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
        .header("Content-Type", "application/json").header(TraceId.HEADER, "trace-biz-0001").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(biz.statusCode()).isEqualTo(400);
    assertThat(biz.body()).contains("\"traceId\":\"trace-biz-0001\"");
    assertThat(get(port, "/api/health", null).body()).as("成功的响应体不加追踪号").doesNotContain("traceId");
  }

  @Test
  void 日志里能按追踪号查到这个请求(CapturedOutput out) throws Exception {
    String id = "trace-log-" + System.nanoTime();
    assertThat(get(port, "/trace-probe", id).statusCode()).isEqualTo(200);
    assertThat(out.getAll().lines().filter(l -> l.contains("[" + id + "]"))).as("日志格式里带追踪号").anyMatch(l -> l.contains("追踪号探针被调用"));
  }

  @Test
  void 定时任务每次执行有自己的追踪号() throws Exception {
    long deadline = System.currentTimeMillis() + 10_000;
    while (Probe.JOB_IDS.size() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(100);
    assertThat(Probe.JOB_IDS).hasSizeGreaterThanOrEqualTo(3).allMatch(s -> s.matches("job-[0-9a-f]{16}"));
    assertThat(Probe.JOB_IDS.subList(0, 3)).doesNotHaveDuplicates();
  }

  @Test
  void 指标只在内网管理端口_有自定义指标_对外端口没有() throws Exception {
    HttpResponse<String> prom = get(managementPort, "/actuator/prometheus", null);
    assertThat(prom.statusCode()).isEqualTo(200);
    assertThat(prom.body()).contains("fc_tasks_backlog").contains("fc_chain_scan_lag_blocks{chain=\"ERC20\"")
        .contains("fc_chain_node_failures{chain=\"TRC20\"");
    assertThat(get(managementPort, "/actuator/metrics/fc.tasks.backlog", null).statusCode()).isEqualTo(200);
    assertThat(get(port, "/actuator/prometheus", null).statusCode()).isEqualTo(404);
    assertThat(get(port, "/actuator/metrics", null).statusCode()).isEqualTo(404);
  }
}
