package com.forecast.app.admin.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.support.FixedClockConfig;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 后台测试的公共部分：直接在库里建管理员和角色，经接口登录；默认 IP 10.0.0.5。每个测试后清空 IP 白名单。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
abstract class AdminTestSupport {

  static final String IP = "10.0.0.5";
  static final String PASSWORD = "Passw0rd-for-test";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired SystemConfig config;

  record Admin(long id, String username, Cookie cookie) {}

  @AfterEach
  void clearWhitelist() {
    jdbc.update("UPDATE common_config SET value = '' WHERE config_key = ?", com.forecast.app.common.config.ConfigKey.ADMIN_IP_WHITELIST.key);
    config.refresh();
  }

  static String unique(String prefix) {
    return prefix + Long.toString(System.nanoTime(), 36);
  }

  long createAdmin(String username, boolean mustChange, String... roleCodes) {
    jdbc.update("INSERT INTO admin_user (username, display_name, email, password_hash, status, must_change_password, created_at) "
        + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, UTC_TIMESTAMP(3))", username, username, username + "@example.com", Passwords.hash(PASSWORD), mustChange);
    long id = jdbc.queryForObject("SELECT id FROM admin_user WHERE username = ?", Long.class, username);
    for (String code : roleCodes) {
      jdbc.update("INSERT INTO admin_user_role (admin_id, role_id) SELECT ?, id FROM admin_role WHERE code = ?", id, code);
    }
    return id;
  }

  /** 建一个只有这些权限点的角色，返回角色编码 */
  String role(Permission... perms) {
    String code = unique("r_");
    jdbc.update("INSERT INTO admin_role (code, name_zh, name_en, description, built_in) VALUES (?, ?, ?, '', 0)", code, code, code);
    long id = jdbc.queryForObject("SELECT id FROM admin_role WHERE code = ?", Long.class, code);
    for (Permission p : perms) jdbc.update("INSERT INTO admin_role_permission (role_id, permission) VALUES (?, ?)", id, p.code);
    return code;
  }

  long roleId(String code) {
    return jdbc.queryForObject("SELECT id FROM admin_role WHERE code = ?", Long.class, code);
  }

  Admin superAdmin() throws Exception {
    return login(createAdmin(unique("su_"), false, "super_admin"));
  }

  Admin withRoles(String... roleCodes) throws Exception {
    return login(createAdmin(unique("op_"), false, roleCodes));
  }

  Admin login(long id) throws Exception {
    String username = jdbc.queryForObject("SELECT username FROM admin_user WHERE id = ?", String.class, id);
    MockHttpServletResponse r = call(post("/api/admin/auth/login"), null, "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
    if (r.getStatus() != 200) throw new AssertionError(r.getContentAsString());
    return new Admin(id, username, r.getCookie("fc_admin_session"));
  }

  MockHttpServletResponse call(MockHttpServletRequestBuilder b, Cookie c, String body) throws Exception {
    return call(b, c, body, IP);
  }

  MockHttpServletResponse call(MockHttpServletRequestBuilder b, Cookie c, String body, String ip) throws Exception {
    b = b.with(r -> {
      r.setRemoteAddr(ip);
      return r;
    });
    if (c != null) b = b.cookie(c);
    if (body != null) b = b.contentType(MediaType.APPLICATION_JSON).content(body);
    return mvc.perform(b).andReturn().getResponse();
  }

  List<String> audits(String action) {
    return jdbc.queryForList("SELECT CONCAT(result, ' ', admin_name, ' ', COALESCE(reason, '-')) FROM admin_audit_log WHERE action = ? ORDER BY id", String.class, action);
  }
}
