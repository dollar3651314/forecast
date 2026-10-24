package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 后台登录、IP 白名单、权限点（AC-32）、第一个超级管理员（G1）。 */
class AdminSecurityTest extends AdminTestSupport {

  @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
  @Autowired SecurityMapper securityMapper;
  @Autowired java.time.Clock clock;

  record Endpoint(HttpMethod method, String path, HandlerMethod handler) {}

  /** 全部后台接口（从 Spring 的路由表里读，不靠手写清单） */
  List<Endpoint> adminEndpoints() {
    List<Endpoint> out = new ArrayList<>();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
      for (String path : e.getKey().getPatternValues()) {
        if (!path.startsWith("/api/admin/")) continue;
        for (var m : e.getKey().getMethodsCondition().getMethods()) out.add(new Endpoint(HttpMethod.valueOf(m.name()), path, e.getValue()));
      }
    }
    return out;
  }

  @Test
  void 每个后台接口都声明了权限点或只要登录_没有遗漏() {
    List<Endpoint> all = adminEndpoints();
    assertThat(all).hasSizeGreaterThan(15);
    for (Endpoint e : all) {
      HandlerMethod h = e.handler();
      int n = (h.hasMethodAnnotation(RequiresPermission.class) ? 1 : 0) + (h.hasMethodAnnotation(AdminLoginOnly.class) ? 1 : 0)
          + (h.hasMethodAnnotation(AdminPublic.class) ? 1 : 0);
      assertThat(n).as(e.method() + " " + e.path()).isEqualTo(1);
      if (h.hasMethodAnnotation(AdminPublic.class)) assertThat(e.path()).isEqualTo("/api/admin/auth/login");
    }
  }

  @Test
  void AC32_只有出金审核查看权限_只看到对应菜单_其他后台接口都返回403() throws Exception {
    Admin a = withRoles(role(Permission.WITHDRAWAL_VIEW));
    String me = call(get("/api/admin/me"), a.cookie(), null).getContentAsString();
    assertThat(me).contains("\"permissions\":[\"withdrawal:view\"]").contains("\"isSuperAdmin\":false");
    assertThat(me.substring(me.indexOf("\"menus\""))).contains("\"key\":\"funds\"").contains("\"key\":\"withdrawals\"").doesNotContain("\"key\":\"deposits\"")
        .doesNotContain("\"key\":\"dashboard\"").doesNotContain("\"key\":\"system\"");
    List<String> checked = new ArrayList<>();
    for (Endpoint e : adminEndpoints()) {
      HandlerMethod h = e.handler();
      if (!h.hasMethodAnnotation(RequiresPermission.class)) continue;
      boolean allowed = h.getMethodAnnotation(RequiresPermission.class).value() == Permission.WITHDRAWAL_VIEW;
      String path = e.path().replaceAll("\\{[^}]+}", "1");
      MockHttpServletResponse r = call(request(e.method(), path), a.cookie(), "{}");
      if (!allowed) assertThat(r.getStatus()).as(e.method() + " " + path).isEqualTo(403);
      checked.add(e.method() + " " + path);
    }
    assertThat(checked).hasSizeGreaterThan(15);
  }

  @Test
  void 没登录401_客户的Cookie不能用于后台() throws Exception {
    assertThat(call(get("/api/admin/me"), null, null).getStatus()).isEqualTo(401);
    assertThat(call(get("/api/admin/admins"), new jakarta.servlet.http.Cookie("fc_session", "x"), null).getStatus()).isEqualTo(401);
    assertThat(call(get("/api/admin/admins"), new jakarta.servlet.http.Cookie("fc_admin_session", "forged"), null).getStatus()).isEqualTo(401);
  }

  @Test
  void 连续失败5次锁30分钟_锁定期间密码对了也不能登录_用户名不存在同样的错误() throws Exception {
    long id = createAdmin(unique("lk_"), false, "super_admin");
    String username = jdbc.queryForObject("SELECT username FROM admin_user WHERE id = ?", String.class, id);
    for (int i = 0; i < 4; i++) {
      MockHttpServletResponse r = call(post("/api/admin/auth/login"), null, "{\"username\":\"" + username + "\",\"password\":\"wrong-password-1\"}");
      assertThat(r.getContentAsString()).contains("INVALID_CREDENTIALS");
    }
    assertThat(call(post("/api/admin/auth/login"), null, "{\"username\":\"" + username + "\",\"password\":\"wrong-password-1\"}").getStatus()).isEqualTo(429);
    assertThat(call(post("/api/admin/auth/login"), null, "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}").getContentAsString())
        .contains("LOGIN_LOCKED");
    assertThat(jdbc.queryForObject("SELECT locked_until FROM admin_user WHERE id = ?", java.time.LocalDateTime.class, id))
        .isEqualTo(java.time.LocalDateTime.now(clock).plusMinutes(30));
    assertThat(call(post("/api/admin/auth/login"), null, "{\"username\":\"nobody-here\",\"password\":\"x\"}").getContentAsString()).contains("INVALID_CREDENTIALS");
    assertThat(audits("admin.login")).contains("FAILURE " + username + " LOGIN_LOCKED", "FAILURE nobody-here INVALID_CREDENTIALS");
  }

  @Test
  void 首次登录必须先改密码_改完旧令牌失效_新密码要够强() throws Exception {
    long id = createAdmin(unique("nw_"), true, "super_admin");
    Admin a = login(id);
    assertThat(call(get("/api/admin/admins"), a.cookie(), null).getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");
    assertThat(call(get("/api/admin/me"), a.cookie(), null).getStatus()).isEqualTo(200);
    MockHttpServletResponse weak = call(post("/api/admin/auth/change-password"), a.cookie(), "{\"oldPassword\":\"" + PASSWORD + "\",\"newPassword\":\"short1\"}");
    assertThat(weak.getStatus()).isEqualTo(400);
    assertThat(weak.getContentAsString()).contains("\"field\":\"newPassword\",\"code\":\"weak\"");
    assertThat(call(post("/api/admin/auth/change-password"), a.cookie(), "{\"oldPassword\":\"bad\",\"newPassword\":\"NewPassw0rd-123\"}").getContentAsString())
        .contains("CURRENT_PASSWORD_WRONG");
    MockHttpServletResponse ok = call(post("/api/admin/auth/change-password"), a.cookie(), "{\"oldPassword\":\"" + PASSWORD + "\",\"newPassword\":\"NewPassw0rd-123\"}");
    assertThat(ok.getStatus()).isEqualTo(200);
    assertThat(call(get("/api/admin/admins"), a.cookie(), null).getStatus()).as("旧令牌失效").isEqualTo(401);
    assertThat(call(get("/api/admin/admins"), ok.getCookie("fc_admin_session"), null).getStatus()).isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log WHERE before_json LIKE '%NewPassw0rd%' OR after_json LIKE '%NewPassw0rd%' OR reason LIKE '%NewPassw0rd%'",
        Long.class)).isZero();
  }

  @Test
  void 退出后令牌失效_停用后立即失效_30分钟不操作失效() throws Exception {
    Admin a = superAdmin();
    call(post("/api/admin/auth/logout"), a.cookie(), null);
    assertThat(call(get("/api/admin/me"), a.cookie(), null).getStatus()).isEqualTo(401);
    Admin b = superAdmin();
    jdbc.update("UPDATE admin_user SET status = 'DISABLED' WHERE id = ?", b.id());
    assertThat(call(get("/api/admin/me"), b.cookie(), null).getStatus()).isEqualTo(401);
    assertThat(call(post("/api/admin/auth/login"), null, "{\"username\":\"" + b.username() + "\",\"password\":\"" + PASSWORD + "\"}").getContentAsString())
        .contains("ACCOUNT_DISABLED");
    Admin c = superAdmin();
    jdbc.update("UPDATE admin_session SET last_seen_at = last_seen_at - INTERVAL 31 MINUTE WHERE admin_id = ?", c.id());
    assertThat(call(get("/api/admin/me"), c.cookie(), null).getStatus()).isEqualTo(401);
  }

  @Test
  void IP白名单_不在名单403_伪造X_Forwarded_For不起作用_保存时不能把自己排除在外() throws Exception {
    Admin a = superAdmin();
    MockHttpServletResponse self = call(put("/api/admin/configs/admin.ipWhitelist"), a.cookie(), "{\"value\":\"192.168.9.0/24\",\"reason\":\"收紧\"}");
    assertThat(self.getContentAsString()).contains("WHITELIST_EXCLUDES_SELF");
    assertThat(call(put("/api/admin/configs/admin.ipWhitelist"), a.cookie(), "{\"value\":\"10.0.0.0/29, 2001:db8::/32 ,bad\",\"reason\":\"x\"}").getStatus())
        .isEqualTo(400);
    assertThat(call(put("/api/admin/configs/admin.ipWhitelist"), a.cookie(), "{\"value\":\"10.0.0.0/29, 2001:db8::/32\",\"reason\":\"只允许办公室\"}").getStatus())
        .isEqualTo(200);
    assertThat(call(get("/api/admin/me"), a.cookie(), null, "10.0.0.3").getStatus()).isEqualTo(200);
    MockHttpServletResponse out = call(get("/api/admin/me"), a.cookie(), null, "10.0.0.9");
    assertThat(out.getStatus()).isEqualTo(403);
    assertThat(out.getContentAsString()).contains("IP_NOT_ALLOWED");
    MockHttpServletResponse forged = mvc.perform(get("/api/admin/me").cookie(a.cookie()).header("X-Forwarded-For", "10.0.0.5").with(r -> {
      r.setRemoteAddr("203.0.113.9");
      return r;
    })).andReturn().getResponse();
    assertThat(forged.getStatus()).as("伪造的请求头不起作用").isEqualTo(403);
    assertThat(call(post("/api/admin/auth/login"), null, "{\"username\":\"x\",\"password\":\"y\"}", "203.0.113.9").getStatus()).as("登录也检查").isEqualTo(403);
    assertThat(call(get("/api/admin/me"), a.cookie(), null, "2001:db8::1").getStatus()).isEqualTo(200);
  }

  @Test
  void IP网段匹配() {
    IpRanges r = IpRanges.parse("203.0.113.0/24, 198.51.100.7，2001:db8::/32").orElseThrow();
    assertThat(r.contains("203.0.113.255")).isTrue();
    assertThat(r.contains("203.0.114.0")).isFalse();
    assertThat(r.contains("198.51.100.7")).isTrue();
    assertThat(r.contains("198.51.100.8")).isFalse();
    assertThat(r.contains("2001:db8:ffff::1")).isTrue();
    assertThat(r.contains("2001:db9::1")).isFalse();
    assertThat(r.contains("example.com")).isFalse();
    assertThat(IpRanges.parse("10.0.0.0/33")).isEmpty();
    assertThat(IpRanges.parse("example.com")).isEmpty();
    assertThat(IpRanges.parse("0.0.0.0/0").orElseThrow().contains("8.8.8.8")).isTrue();
    assertThat(IpRanges.parse("").orElseThrow().isEmpty()).isTrue();
  }

  @Test
  void 第一个超级管理员_只在没有管理员时建_密码太弱不建() {
    var props = new AdminProperties(java.time.Duration.ofHours(8), java.time.Duration.ofMinutes(30), "fc_admin_session", true, "amos", "Strong-Passw0rd-1");
    assertThat(new AdminBootstrap(securityMapper, props).create()).as("库里已经有管理员").isFalse();
    assertThat(props.toString()).doesNotContain("Strong-Passw0rd-1");
    // 在一个回滚的事务里模拟空库
    new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(st -> {
      jdbc.update("DELETE FROM admin_user_role");
      jdbc.update("DELETE FROM admin_user");
      var weak = new AdminProperties(java.time.Duration.ofHours(8), java.time.Duration.ofMinutes(30), "fc_admin_session", true, "amos", "weak");
      assertThat(new AdminBootstrap(securityMapper, weak).create()).isFalse();
      assertThat(new AdminBootstrap(securityMapper, props).create()).isTrue();
      assertThat(jdbc.queryForObject("SELECT must_change_password FROM admin_user WHERE username = 'amos'", Boolean.class)).isTrue();
      assertThat(securityMapper.isSuper(jdbc.queryForObject("SELECT id FROM admin_user WHERE username = 'amos'", Long.class))).isTrue();
      assertThat(jdbc.queryForObject("SELECT password_hash FROM admin_user WHERE username = 'amos'", String.class)).startsWith("$2a$").doesNotContain("Strong");
      st.setRollbackOnly();
    });
  }

  @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

  @Test
  void 审计过滤密码字段_任何层级() {
    Map<String, Object> m = Audit.scrub(Map.of("password", "p", "newPassword", "q", "name", "n", "nested", Map.of("apiToken", "t", "secretKey", "s", "ok", 1)));
    assertThat(m.get("password")).isEqualTo("***");
    assertThat(m.get("newPassword")).isEqualTo("***");
    assertThat(m.get("name")).isEqualTo("n");
    @SuppressWarnings("unchecked") Map<String, Object> nested = (Map<String, Object>) m.get("nested");
    assertThat(nested).containsEntry("apiToken", "***").containsEntry("secretKey", "***").containsEntry("ok", 1);
    assertThat(Set.of(Permission.values())).as("复制源 52 个，forecast S1 删掉差价合约的 20 个").hasSize(32);
  }
}
