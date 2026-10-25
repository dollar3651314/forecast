package com.forecast.app.common.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 端到端测试专用接口（S19）只在 profile e2e 下存在；e2e profile 的启动保护。
 */
class E2eProfileTest {

  static HttpResponse<String> get(int port, String path) throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
  }

  static List<String> e2ePaths(RequestMappingHandlerMapping mapping) {
    return mapping.getHandlerMethods().keySet().stream()
        .flatMap(info -> info.getPatternValues().stream())
        .filter(p -> p.startsWith("/api/e2e"))
        .toList();
  }

  static List<String> e2eBeans(ApplicationContext ctx) {
    return Arrays.stream(ctx.getBeanDefinitionNames())
        .map(ctx::getType)
        .filter(t -> t != null && (t.getSimpleName().startsWith("E2e") || (t.getEnclosingClass() != null && t.getEnclosingClass().getSimpleName().startsWith("E2e"))))
        .map(Class::getName)
        .toList();
  }

  /** 默认配置（不带 e2e profile）：没有任何 /api/e2e/** 接口和 e2e 的 bean，访问照常要求登录 */
  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @ActiveProfiles("test")
  class DefaultProfile {

    @Autowired
    ApplicationContext ctx;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @LocalServerPort
    int port;

    @Test
    void 没有e2e接口和bean() throws Exception {
      assertThat(e2ePaths(mapping)).isEmpty();
      assertThat(e2eBeans(ctx)).isEmpty();
      assertThat(get(port, "/api/e2e/mail/code?email=a@example.com").statusCode()).as("不在公开接口里，照常要求登录").isEqualTo(401);
    }
  }

  /**
   * e2e profile + forecast.e2e.enabled=true：接口都在，不需要登录。
   * 测试结束就关掉这个上下文（不让它的定时任务影响后面的测试）。
   */
  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {"forecast.e2e.enabled=true", "forecast.notify.mail.host="})
  @ActiveProfiles({"test", "e2e"})
  @DirtiesContext
  class WithE2eProfile {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Autowired
    java.time.Clock clock;

    @LocalServerPort
    int port;

    @Test
    void 接口都在_不需要登录() throws Exception {
      assertThat(e2ePaths(mapping)).contains("/api/e2e/mail/code", "/api/e2e/wallet/deposit", "/api/e2e/wallet/mine",
          "/api/e2e/wallet/withdrawals/advance", "/api/e2e/wallet/withdrawals/{id}/confirm", "/api/e2e/clock", "/api/e2e/clock/advance", "/api/e2e/clock/reset",
          "/api/e2e/markets/tick", "/api/e2e/settle/tick", "/api/e2e/settle/recon", "/api/e2e/autogen/tick", "/api/e2e/resolve/tick", "/api/e2e/candles",
          "/api/e2e/candles/binance/api/v3/klines", "/api/e2e/candles/okx/api/v5/market/history-candles");
      // 替身数据源（S6）：设一个窗口的价格，照币安、OKX 的格式回出来，真实的适配器能解析
      HttpResponse<String> set = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/e2e/candles"))
          .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"symbol\":\"BTCUSDT\",\"windowStart\":\"2026-11-01T13:00:00Z\","
              + "\"windowEnd\":\"2026-11-01T14:00:00Z\",\"open\":\"100\",\"close\":\"100\"}")).build(), HttpResponse.BodyHandlers.ofString());
      assertThat(set.statusCode()).isEqualTo(200);
      com.forecast.app.resolve.source.CandleQuery q = new com.forecast.app.resolve.source.CandleQuery("BTCUSDT",
          java.time.Instant.parse("2026-11-01T13:00:00Z"), java.time.Instant.parse("2026-11-01T14:00:00Z"));
      String base = "http://localhost:" + port + "/api/e2e/candles";
      com.forecast.app.resolve.source.BinanceSpot bn = new com.forecast.app.resolve.source.BinanceSpot();
      com.forecast.app.resolve.source.OkxSpot ok = new com.forecast.app.resolve.source.OkxSpot();
      String bBody = HttpClient.newHttpClient().send(HttpRequest.newBuilder(bn.uri(base + "/binance", q)).build(), HttpResponse.BodyHandlers.ofString()).body();
      String oBody = HttpClient.newHttpClient().send(HttpRequest.newBuilder(ok.uri(base + "/okx", q)).build(), HttpResponse.BodyHandlers.ofString()).body();
      assertThat(bn.parse(bBody, q).direction()).isEqualTo("UP");
      assertThat(bn.parse(bBody, q).count()).isEqualTo(60);
      assertThat(ok.parse(oBody, q).open()).isEqualByComparingTo("100");
      // 可调时钟（S3）：往后拨，业务时钟跟着走；reset 回到系统时间
      assertThat(clock.getClass().getSimpleName()).as("e2e 下业务时钟是可调时钟").isEqualTo("OffsetClock");
      java.time.Instant before = clock.instant();
      HttpResponse<String> adv = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/e2e/clock/advance"))
          .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"millis\":3600000}")).build(), HttpResponse.BodyHandlers.ofString());
      assertThat(adv.statusCode()).isEqualTo(200);
      assertThat(java.time.Duration.between(before, clock.instant()).toMinutes()).isBetween(59L, 61L);
      HttpResponse<String> reset = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/e2e/clock/reset"))
          .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
      assertThat(reset.body()).contains("\"offsetMillis\":0");
      assertThat(java.time.Duration.between(java.time.Instant.now(), clock.instant()).abs().toSeconds()).isLessThan(5);
      assertThat(get(port, "/api/e2e/mail/code?email=nobody@example.com").statusCode()).as("没有邮件返回 404，不是 401").isEqualTo(404);
      assertThat(get(port, "/api/me/session").statusCode()).as("其他接口照常要求登录").isEqualTo(401);
    }
  }

  @Test
  void 启动保护_必须显式打开() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("e2e");
    assertThat(E2eGuard.problems(env)).singleElement().asString().contains("forecast.e2e.enabled");
    env.setProperty("forecast.e2e.enabled", "true");
    assertThat(E2eGuard.problems(env)).isEmpty();
  }

  @Test
  void 启动保护_不能和生产配置同时出现() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("forecast.e2e.enabled", "true");
    env.setActiveProfiles("e2e", "prod");
    assertThat(E2eGuard.problems(env)).singleElement().asString().contains("prod");

    MockEnvironment real = new MockEnvironment();
    real.setActiveProfiles("e2e");
    real.setProperty("forecast.e2e.enabled", "true");
    real.setProperty("forecast.wallet.hot.eth-private-key", "x");
    real.setProperty("forecast.wallet.chains.TRC20.rpc-url", "https://node.example");
    real.setProperty("forecast.notify.mail.host", "smtp.example");
    assertThat(E2eGuard.problems(real)).hasSize(3);
  }
}
