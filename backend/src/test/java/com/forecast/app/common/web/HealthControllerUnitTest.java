package com.forecast.app.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** 健康检查的失败分支：数据库或 Redis 不可用时返回 503，且不带错误详情。 */
class HealthControllerUnitTest {

  @Test
  void 数据库不可用时返回503() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(Class.class))).thenThrow(new DataAccessResourceFailureException("connection refused 10.0.0.5:3306"));
    RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
    when(redis.getConnection()).thenReturn(mock(RedisConnection.class));
    var r = new HealthController(jdbc, redis, "test", "", java.util.List.of()).health();
    assertThat(r.getStatusCode().value()).isEqualTo(503);
    assertThat(r.getBody()).containsEntry("status", "DOWN").containsEntry("db", "DOWN").containsEntry("redis", "UP");
    assertThat(r.getBody().toString()).doesNotContain("10.0.0.5");
  }

  @Test
  void Redis不可用时返回503() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(1);
    RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
    when(redis.getConnection()).thenThrow(new RedisConnectionFailureException("down"));
    var r = new HealthController(jdbc, redis, "test", "", java.util.List.of()).health();
    assertThat(r.getStatusCode().value()).isEqualTo(503);
    assertThat(r.getBody()).containsEntry("db", "UP").containsEntry("redis", "DOWN");
  }

  @Test
  void 模块检查不通过时返回503_例如钱包配了节点没配USDT合约() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(1);
    RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
    when(redis.getConnection()).thenReturn(mock(RedisConnection.class));
    com.forecast.app.common.health.HealthCheck wallet = new com.forecast.app.common.health.HealthCheck() {
      public String name() { return "wallet"; }
      public boolean ok() { return false; }
    };
    var r = new HealthController(jdbc, redis, "test", "", java.util.List.of(wallet)).health();
    assertThat(r.getStatusCode().value()).isEqualTo(503);
    assertThat(r.getBody()).containsEntry("status", "DOWN").containsEntry("wallet", "DOWN").containsEntry("db", "UP");
  }

  @Test
  void 设置了测试环境标识时返回env字段_没设置时不返回() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(1);
    RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
    when(redis.getConnection()).thenReturn(mock(RedisConnection.class));
    var test = new HealthController(jdbc, redis, "a1b2c3d", " test ", java.util.List.of()).health();
    assertThat(test.getStatusCode().value()).isEqualTo(200);
    assertThat(test.getBody()).containsEntry("version", "a1b2c3d").containsEntry("env", "test");
    assertThat(test.getBody().keySet()).containsExactly("status", "version", "env", "db", "redis", "alert", "alerts");
    var prod = new HealthController(jdbc, redis, "a1b2c3d", "", java.util.List.of()).health();
    assertThat(prod.getBody()).as("生产不设，返回和以前一样").doesNotContainKey("env");
    assertThat(prod.getBody().keySet()).as("生产不设 env；告警汇总和各项总是返回（E8）").containsExactly("status", "version", "db", "redis", "alert", "alerts");
    assertThat(new HealthController(jdbc, redis, "x", null, java.util.List.of()).health().getBody()).doesNotContainKey("env");
  }

  @Test
  void 对外预热域名不返回env_其他域名照旧_S18() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(1);
    RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
    when(redis.getConnection()).thenReturn(mock(RedisConnection.class));
    var c = new HealthController(jdbc, redis, "v", "test", java.util.List.of());
    var www = new org.springframework.mock.web.MockHttpServletRequest();
    www.addHeader("X-Real-IP", "203.0.113.9");
    www.addHeader(PrelaunchSite.HEADER, PrelaunchSite.PRELAUNCH);
    assertThat(c.health(www).getBody()).as("预热域名（Nginx 设 X-Fc-Site: prelaunch）").doesNotContainKey("env").containsEntry("status", "UP")
        .doesNotContainKey("alerts");
    var app = new org.springframework.mock.web.MockHttpServletRequest();
    app.addHeader("X-Real-IP", "203.0.113.9");
    assertThat(c.health(app).getBody()).as("客户端域名照常有 env").containsEntry("env", "test");
    var other = new org.springframework.mock.web.MockHttpServletRequest();
    other.addHeader("X-Real-IP", "203.0.113.9");
    other.addHeader(PrelaunchSite.HEADER, "something-else");
    assertThat(c.health(other).getBody()).as("只认 prelaunch 这个值").containsEntry("env", "test");
  }

  @Test
  void 业务告警只出现在alerts里_status和HTTP状态码不变_部署脚本不会回滚_E8() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(1);
    RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
    when(redis.getConnection()).thenReturn(mock(RedisConnection.class));
    com.forecast.app.common.health.AlertCheck market = () -> java.util.List.of(
        new com.forecast.app.common.health.AlertCheck.Item("market", "行情全部断流", "Market data stopped", false, "开市的 3 个品种已经 45 秒没有收到任何报价 10.0.0.9"));
    com.forecast.app.common.health.AlertCheck chain = () -> java.util.List.of(
        new com.forecast.app.common.health.AlertCheck.Item("node.TRC20", "x", "x", true, "正常"),
        new com.forecast.app.common.health.AlertCheck.Item("scan.TRC20", "x", "x", true, "正常"));
    com.forecast.app.common.health.AlertCheck broken = () -> {
      throw new IllegalStateException("检查本身出错");
    };
    var r = new HealthController(jdbc, redis, "v", "", java.util.List.of(), java.util.List.of(market, chain, broken)).health();
    assertThat(r.getStatusCode().value()).as("断流不能让健康检查 503（deploy.sh 会回滚）").isEqualTo(200);
    assertThat(r.getBody()).containsEntry("status", "UP").containsEntry("alert", "ALERT");
    assertThat(r.getBody().get("alerts")).isEqualTo(java.util.Map.of("market", "DOWN", "node.TRC20", "UP", "scan.TRC20", "UP"));
    assertThat(r.getBody().toString()).as("不返回说明和内部信息").doesNotContain("45 秒").doesNotContain("10.0.0.9");

    var ok = new HealthController(jdbc, redis, "v", "", java.util.List.of(), java.util.List.of(chain)).health();
    assertThat(ok.getBody()).containsEntry("alert", "OK");
    var none = new HealthController(jdbc, redis, "v", "", java.util.List.of()).health();
    assertThat(none.getBody()).as("没有配置任何告警项（测试环境没配链、没有报价源）").containsEntry("alert", "OK").containsEntry("alerts", java.util.Map.of());
  }

  @Test
  void 应用本身的检查不通过时仍然是503_和告警无关() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(Class.class))).thenThrow(new DataAccessResourceFailureException("down"));
    RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
    when(redis.getConnection()).thenReturn(mock(RedisConnection.class));
    var r = new HealthController(jdbc, redis, "v", "", java.util.List.of(), java.util.List.of()).health();
    assertThat(r.getStatusCode().value()).isEqualTo(503);
    assertThat(r.getBody()).containsEntry("status", "DOWN").containsEntry("alert", "OK");
  }

  @Test
  void 业务告警只给后台域名和服务器本机_客户端域名和官网带什么请求头都拿不到() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(1);
    RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
    when(redis.getConnection()).thenReturn(mock(RedisConnection.class));
    com.forecast.app.common.health.AlertCheck market = () -> java.util.List.of(
        new com.forecast.app.common.health.AlertCheck.Item("market", "x", "x", false, "x"));
    var h = new HealthController(jdbc, redis, "v", "test", java.util.List.of(), java.util.List.of(market));

    var direct = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/health");
    assertThat(h.health(direct).getBody()).as("服务器本机直连后端（部署脚本）").containsEntry("alert", "ALERT").containsKey("alerts");

    var admin = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/health");
    admin.addHeader("X-Real-IP", "203.0.113.5");
    admin.addHeader("X-Fc-Health-Alerts", "1");
    assertThat(h.health(admin).getBody()).as("后台域名（Nginx 设了头）").containsEntry("alert", "ALERT");

    var client = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/health");
    client.addHeader("X-Real-IP", "203.0.113.5");
    var r = h.health(client);
    assertThat(r.getStatusCode().value()).isEqualTo(200);
    assertThat(r.getBody()).as("客户端域名、官网：只有存活信息").containsEntry("status", "UP").containsEntry("env", "test")
        .doesNotContainKey("alert").doesNotContainKey("alerts");

    var forged = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/health");
    forged.addHeader("X-Real-IP", "203.0.113.5");
    forged.addHeader("X-Fc-Health-Alerts", "yes");
    assertThat(h.health(forged).getBody()).as("只认 Nginx 设的值 1（客户端带的同名头会被 Nginx 清空）").doesNotContainKey("alerts");
  }
}
