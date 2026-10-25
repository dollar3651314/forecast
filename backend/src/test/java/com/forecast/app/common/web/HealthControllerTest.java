package com.forecast.app.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** 用真实的 MySQL 和 Redis 启动整个应用（C37），检查健康检查接口和管理端点的暴露范围。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HealthControllerTest {

  @LocalServerPort
  int port;

  private HttpResponse<String> get(String path) throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void 健康检查报告应用数据库和Redis都可用() throws Exception {
    HttpResponse<String> r = get("/api/health");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("\"status\":\"UP\"").contains("\"db\":\"UP\"").contains("\"redis\":\"UP\"").contains("\"version\":\"0.1.0-SNAPSHOT\"")
        .as("没有设置 FC_ENV_LABEL 时不返回 env").doesNotContain("\"env\"");
  }

  @Test
  void 应用管理端点不在对外端口上() throws Exception {
    assertThat(get("/actuator/health").statusCode()).isEqualTo(404);
    HttpResponse<String> env = get("/actuator/env");
    assertThat(env.statusCode()).isEqualTo(404);
    assertThat(env.body()).as("统一错误格式，不暴露内部信息").contains("\"code\":\"NOT_FOUND\"");
  }
}
