package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.forecast.app.common.config.ConfigKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 管理员、角色、菜单、审计、系统配置（AC-33、AC-34、AC-35），后端和后台原型的权限点、菜单一致。 */
class AdminSystemTest extends AdminTestSupport {

  final ObjectMapper json = new ObjectMapper();

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  String adminJson(String username, long... roleIds) {
    StringBuilder ids = new StringBuilder();
    for (long id : roleIds) ids.append(ids.isEmpty() ? "" : ",").append('"').append(id).append('"');
    return "{\"username\":\"" + username + "\",\"displayName\":\"" + username + "\",\"email\":\"" + username + "@example.com\",\"roleIds\":[" + ids + "]}";
  }

  @Test
  void AC33_非超级管理员不能通过任何方式加超级管理员_不能改自己的角色_不能分配超出自己的权限() throws Exception {
    String ops = role(Permission.ADMIN_VIEW, Permission.ADMIN_EDIT, Permission.ROLE_VIEW, Permission.ROLE_EDIT);
    Admin a = withRoles(ops);
    long superRole = roleId("super_admin");
    assertThat(call(post("/api/admin/admins"), a.cookie(), adminJson(unique("x_"), superRole)).getContentAsString()).contains("CANNOT_ASSIGN_SUPER");
    long other = createAdmin(unique("o_"), false);
    assertThat(call(put("/api/admin/admins/" + other), a.cookie(), adminJson("ignored", superRole)).getContentAsString()).contains("CANNOT_ASSIGN_SUPER");
    assertThat(call(put("/api/admin/admins/" + a.id()), a.cookie(), adminJson("ignored", roleId(ops), superRole)).getContentAsString())
        .contains("CANNOT_EDIT_OWN_ROLES");
    assertThat(call(put("/api/admin/roles/" + superRole), a.cookie(),
        "{\"nameZh\":\"x\",\"nameEn\":\"x\",\"permissions\":[]}").getContentAsString()).contains("BUILT_IN_ROLE");
    // 不能把自己没有的权限点放进角色，也不能给自己的角色加权限
    assertThat(call(post("/api/admin/roles"), a.cookie(), "{\"code\":\"" + unique("r_") + "\",\"nameZh\":\"x\",\"nameEn\":\"x\",\"permissions\":[\"config:edit\"]}")
        .getContentAsString()).contains("PERMISSION_EXCEEDS_OWN");
    assertThat(call(put("/api/admin/roles/" + roleId(ops)), a.cookie(),
        "{\"nameZh\":\"x\",\"nameEn\":\"x\",\"permissions\":[\"admin:view\",\"admin:edit\",\"role:view\",\"role:edit\",\"config:edit\"]}").getContentAsString())
        .contains("PERMISSION_EXCEEDS_OWN");
    String big = role(Permission.CONFIG_EDIT);
    assertThat(call(post("/api/admin/admins"), a.cookie(), adminJson(unique("y_"), roleId(big))).getContentAsString()).contains("PERMISSION_EXCEEDS_OWN");
    // 不能修改、停用、重置超级管理员
    Admin s = superAdmin();
    assertThat(call(put("/api/admin/admins/" + s.id()), a.cookie(), adminJson("ignored", superRole)).getContentAsString()).contains("FORBIDDEN");
    assertThat(call(post("/api/admin/admins/" + s.id() + "/status"), a.cookie(), "{\"active\":false,\"reason\":\"试试\"}").getContentAsString()).contains("FORBIDDEN");
    assertThat(call(post("/api/admin/admins/" + s.id() + "/reset-password"), a.cookie(), "{\"reason\":\"试试\"}").getContentAsString()).contains("FORBIDDEN");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_user_role WHERE role_id = ? AND admin_id IN (?, ?)", Long.class, superRole, a.id(), other)).isZero();
    // 超级管理员可以分配
    MockHttpServletResponse ok = call(put("/api/admin/admins/" + other), s.cookie(), adminJson("ignored", superRole));
    assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
    assertThat(audits("admin.update")).anyMatch(l -> l.startsWith("FAILURE " + a.username() + " CANNOT_ASSIGN_SUPER"));
  }

  /**
   * SQ-19（Amos 2026-10-25："只创建一个角色，我自己登录用，我要什么都可以做，未来再由我根据需要创建其他角色"）：
   * 内置角色只有超级管理员一个；超管的权限不存表，按 {@code Permission} 枚举全部给（RoleService.permissionsOf、AdminContext.has），
   * 所以以后新加的权限点（S3～S9 的 market、resolve、dispute、settle、fee、betting_params、pool_recon、report 等）自动归超管。
   */
  @Test
  void SQ19_内置角色只有超级管理员_超管拥有后端声明的全部权限点() throws Exception {
    assertThat(jdbc.queryForList("SELECT code FROM admin_role WHERE built_in = 1", String.class)).containsExactly("super_admin");
    Admin s = superAdmin();
    JsonNode me = body(call(get("/api/admin/me"), s.cookie(), null));
    assertThat(me.get("isSuperAdmin").asBoolean()).isTrue();
    Set<String> granted = new LinkedHashSet<>();
    me.get("permissions").forEach(p -> granted.add(p.asString()));
    for (Permission p : Permission.values()) assertThat(granted).as("超管要有 %s", p.code).contains(p.code);
    assertThat(granted).hasSize(Permission.values().length);
    JsonNode roles = body(call(get("/api/admin/roles"), s.cookie(), null));
    for (JsonNode role : roles) {
      if (!"super_admin".equals(role.get("code").asString())) continue;
      Set<String> rp = new LinkedHashSet<>();
      role.get("permissions").forEach(p -> rp.add(p.asString()));
      assertThat(rp).isEqualTo(granted);
    }
  }

  /**
   * 安全审查 SEC-04：原来只拦"重置超级管理员的密码"。有"编辑管理员"权限的普通管理员可以重置权限比自己大的管理员的密码，
   * 拿到临时密码后登录成对方，绕过"不能分配超出自己权限的角色"（后台方案第 9 节补充）。现在要求对方的权限点都在自己的范围内。
   */
  @Test
  void SEC04_非超级管理员不能重置权限超出自己的管理员的密码() throws Exception {
    String ops = role(Permission.ADMIN_VIEW, Permission.ADMIN_EDIT, Permission.AUDIT_VIEW);
    Admin a = withRoles(ops);
    long bigger = createAdmin(unique("bg_"), false, role(Permission.CONFIG_VIEW, Permission.CONFIG_EDIT, Permission.AUDIT_VIEW));
    MockHttpServletResponse r = call(post("/api/admin/admins/" + bigger + "/reset-password"), a.cookie(), "{\"reason\":\"试试\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("PERMISSION_EXCEEDS_OWN").doesNotContain("temporaryPassword");
    assertThat(jdbc.queryForObject("SELECT must_change_password FROM admin_user WHERE id = ?", Boolean.class, bigger)).as("密码没有被改").isFalse();
    assertThat(audits("admin.reset_password")).anyMatch(l -> l.startsWith("FAILURE " + a.username() + " "));
    // 权限在自己范围内的可以重置；超级管理员可以重置任何人
    long smaller = createAdmin(unique("sm_"), false, role(Permission.AUDIT_VIEW));
    assertThat(call(post("/api/admin/admins/" + smaller + "/reset-password"), a.cookie(), "{\"reason\":\"忘记密码\"}").getStatus()).isEqualTo(200);
    assertThat(call(post("/api/admin/admins/" + bigger + "/reset-password"), superAdmin().cookie(), "{\"reason\":\"忘记密码\"}").getStatus()).isEqualTo(200);
  }

  @Test
  void 新建管理员返回一次临时密码_首次登录要改_重置密码同理_审计里没有密码_AC34() throws Exception {
    Admin s = superAdmin();
    String op = role(Permission.AUDIT_VIEW);
    String username = unique("nm_");
    MockHttpServletResponse r = call(post("/api/admin/admins"), s.cookie(), adminJson(username, roleId(op)));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    String temp = body(r).get("temporaryPassword").asString();
    long id = body(r).get("id").asLong();
    assertThat(temp).hasSize(16);
    assertThat(call(post("/api/admin/admins"), s.cookie(), adminJson(username)).getContentAsString()).contains("USERNAME_TAKEN");
    MockHttpServletResponse login = call(post("/api/admin/auth/login"), null, "{\"username\":\"" + username + "\",\"password\":\"" + temp + "\"}");
    assertThat(body(login).get("mustChangePassword").asBoolean()).isTrue();

    MockHttpServletResponse reset = call(post("/api/admin/admins/" + id + "/reset-password"), s.cookie(), "{\"reason\":\"忘记密码\"}");
    String temp2 = body(reset).get("temporaryPassword").asString();
    assertThat(temp2).isNotEqualTo(temp);
    assertThat(call(get("/api/admin/me"), login.getCookie("fc_admin_session"), null).getStatus()).as("重置后已有登录失效").isEqualTo(401);
    assertThat(call(post("/api/admin/admins/" + id + "/reset-password"), s.cookie(), "{\"reason\":\" \"}").getContentAsString()).contains("\"field\":\"reason\"");

    for (String p : List.of(temp, temp2, PASSWORD)) {
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log WHERE CONCAT_WS('|', target, reason, before_json, after_json) LIKE ?", Long.class,
          "%" + p + "%")).as("审计里没有密码").isZero();
    }
    assertThat(audits("admin.reset_password")).anyMatch(l -> l.equals("SUCCESS " + s.username() + " 忘记密码"));
    JsonNode logs = body(call(get("/api/admin/audit-logs?action=admin.create&admin=" + s.username()), s.cookie(), null));
    assertThat(logs.get("total").asLong()).isEqualTo(2);
    JsonNode created = logs.get("items").get(logs.get("items").size() - 1);
    assertThat(created.get("highRisk").asBoolean()).isTrue();
    assertThat(created.get("result").asString()).isEqualTo("success");
    assertThat(created.get("after").get("password").asString()).isEqualTo("***");
    assertThat(created.get("ip").asString()).isEqualTo(IP);
    assertThat(body(call(get("/api/admin/audit-logs/actions"), s.cookie(), null)).toString()).contains("admin.create").contains("admin.login");
  }

  @Test
  void 停用_不能停用自己_停用后退出登录_原因必填() throws Exception {
    Admin s = superAdmin();
    Admin other = withRoles(role(Permission.AUDIT_VIEW));
    assertThat(call(post("/api/admin/admins/" + s.id() + "/status"), s.cookie(), "{\"active\":false,\"reason\":\"x\"}").getContentAsString())
        .contains("CANNOT_DISABLE_SELF");
    assertThat(call(post("/api/admin/admins/" + other.id() + "/status"), s.cookie(), "{\"active\":false}").getStatus()).isEqualTo(400);
    MockHttpServletResponse r = call(post("/api/admin/admins/" + other.id() + "/status"), s.cookie(), "{\"active\":false,\"reason\":\"离职\"}");
    assertThat(body(r).get("status").asString()).isEqualTo("disabled");
    assertThat(call(get("/api/admin/me"), other.cookie(), null).getStatus()).isEqualTo(401);
    assertThat(body(call(post("/api/admin/admins/" + other.id() + "/status"), s.cookie(), "{\"active\":true,\"reason\":\"回来了\"}")).get("status").asString())
        .isEqualTo("active");
  }

  @Test
  void 角色_新建修改删除_有成员不能删_超级管理员角色列出全部权限() throws Exception {
    Admin s = superAdmin();
    String code = unique("r_");
    MockHttpServletResponse r = call(post("/api/admin/roles"), s.cookie(),
        "{\"code\":\"" + code + "\",\"nameZh\":\"出金审核\",\"nameEn\":\"Withdraw review\",\"description\":\"\",\"permissions\":[\"withdrawal:view\",\"withdrawal:review\"]}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    String id = body(r).get("id").asString();
    assertThat(call(post("/api/admin/roles"), s.cookie(), "{\"code\":\"" + code + "\",\"nameZh\":\"x\",\"nameEn\":\"x\",\"permissions\":[]}").getContentAsString())
        .contains("ROLE_CODE_TAKEN");
    assertThat(call(post("/api/admin/roles"), s.cookie(), "{\"code\":\"super_admin\",\"nameZh\":\"x\",\"nameEn\":\"x\",\"permissions\":[]}").getContentAsString())
        .contains("ROLE_CODE_TAKEN");
    assertThat(call(post("/api/admin/roles"), s.cookie(), "{\"code\":\"" + unique("r_") + "\",\"nameZh\":\"x\",\"nameEn\":\"x\",\"permissions\":[\"nope:view\"]}")
        .getContentAsString()).contains("\"field\":\"permissions\"");
    MockHttpServletResponse u = call(put("/api/admin/roles/" + id), s.cookie(), "{\"nameZh\":\"出金审核员\",\"nameEn\":\"Reviewer\",\"permissions\":[\"withdrawal:view\"]}");
    assertThat(body(u).get("permissions").toString()).isEqualTo("[\"withdrawal:view\"]");
    createAdmin(unique("m_"), false, code);
    assertThat(call(delete("/api/admin/roles/" + id), s.cookie(), null).getContentAsString()).contains("ROLE_IN_USE");
    jdbc.update("DELETE FROM admin_user_role WHERE role_id = ?", Long.parseLong(id));
    assertThat(call(delete("/api/admin/roles/" + id), s.cookie(), null).getStatus()).isEqualTo(200);
    JsonNode roles = body(call(get("/api/admin/roles"), s.cookie(), null));
    for (JsonNode role : roles) {
      if ("super_admin".equals(role.get("code").asString())) {
        assertThat(role.get("permissions").size()).isEqualTo(Permission.values().length);
        assertThat(role.get("builtIn").asBoolean()).isTrue();
      }
      assertThat(role.get("id").asString()).isNotEqualTo(id);
    }
    assertThat(audits("role.delete")).anyMatch(l -> l.startsWith("FAILURE " + s.username() + " ROLE_IN_USE"));
  }

  @Test
  void 管理员列表分页和搜索() throws Exception {
    Admin s = superAdmin();
    String prefix = unique("pg");
    for (int i = 0; i < 5; i++) createAdmin(prefix + "_" + i, false);
    JsonNode p2 = body(call(get("/api/admin/admins?keyword=" + prefix + "&page=2&pageSize=2"), s.cookie(), null));
    assertThat(p2.get("total").asLong()).isEqualTo(5);
    assertThat(p2.get("items").size()).isEqualTo(2);
    assertThat(p2.get("items").get(0).get("username").asString()).isEqualTo(prefix + "_2");
    assertThat(p2.get("items").get(0).has("passwordHash")).isFalse();
    JsonNode big = body(call(get("/api/admin/admins?pageSize=1000"), s.cookie(), null));
    assertThat(big.get("pageSize").asInt()).isEqualTo(100);
  }

  @Test
  void 菜单_改顺序和隐藏_隐藏后不出现在可见菜单_权限不受影响() throws Exception {
    Admin s = superAdmin();
    try {
      MockHttpServletResponse r = call(put("/api/admin/menus/audit"), s.cookie(), "{\"order\":5,\"visible\":false}");
      assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
      String me = call(get("/api/admin/me"), s.cookie(), null).getContentAsString();
      assertThat(me.substring(me.indexOf("\"menus\""))).doesNotContain("\"key\":\"audit\"").contains("\"key\":\"config\"");
      assertThat(call(get("/api/admin/audit-logs"), s.cookie(), null).getStatus()).as("隐藏菜单不影响权限").isEqualTo(200);
      JsonNode menus = body(call(get("/api/admin/menus"), s.cookie(), null));
      JsonNode system = null;
      for (JsonNode m : menus) if ("system".equals(m.get("key").asString())) system = m;
      assertThat(system.get("children").get(0).get("key").asString()).as("按顺序排").isEqualTo("audit");
      assertThat(call(put("/api/admin/menus/nope"), s.cookie(), "{\"order\":1}").getStatus()).isEqualTo(404);
    } finally {
      jdbc.update("DELETE FROM admin_menu WHERE menu_key = 'audit'");
    }
  }

  @Test
  void 系统配置_按单位校验_确认数只能调高_改了各模块立即生效() throws Exception {
    Admin s = superAdmin();
    try {
      JsonNode list = body(call(get("/api/admin/configs"), s.cookie(), null));
      assertThat(list.size()).as("自动审批、归集、参数设置的项不在系统配置页").isEqualTo(java.util.Arrays.stream(ConfigKey.values()).filter(k -> k.configPage).count());
      assertThat(list.toString()).as("单笔最小提现 S3 起在参数设置页（betting_params:edit）").doesNotContain("withdraw.minAmount");
      assertThat(call(put("/api/admin/configs/deposit.confirmations.ERC20"), s.cookie(), "{\"value\":\"11\",\"reason\":\"加快到账\"}").getContentAsString())
          .contains("\"field\":\"value\"");
      assertThat(call(put("/api/admin/configs/withdraw.coolingHours"), s.cookie(), "{\"value\":\"1.5\",\"reason\":\"x\"}").getStatus()).isEqualTo(400);
      assertThat(call(put("/api/admin/params/betting/withdraw.minAmount"), s.cookie(), "{\"value\":\"-1\",\"reason\":\"x\"}").getStatus()).isEqualTo(400);
      assertThat(call(put("/api/admin/params/betting/withdraw.minAmount"), s.cookie(), "{\"value\":\"20\"}").getContentAsString()).contains("\"field\":\"reason\"");
      assertThat(call(put("/api/admin/configs/nope"), s.cookie(), "{\"value\":\"1\",\"reason\":\"x\"}").getStatus()).isEqualTo(404);
      MockHttpServletResponse ok = call(put("/api/admin/configs/deposit.confirmations.ERC20"), s.cookie(), "{\"value\":\"20\",\"reason\":\"链上拥堵\"}");
      assertThat(body(ok).get("value").asString()).isEqualTo("20");
      assertThat(confirmations.of(com.forecast.app.wallet.Chain.ERC20)).isEqualTo(20);
      call(put("/api/admin/configs/withdraw.fee.TRC20"), s.cookie(), "{\"value\":\"1.50\",\"reason\":\"手续费调整\"}");
      assertThat(config.decimal(ConfigKey.WITHDRAW_FEE_TRC20, java.math.BigDecimal.ZERO)).isEqualByComparingTo("1.5");
      assertThat(audits("config.update")).anyMatch(l -> l.equals("SUCCESS " + s.username() + " 链上拥堵"));
      assertThat(jdbc.queryForObject("SELECT CONCAT(before_json, after_json) FROM admin_audit_log WHERE action = 'config.update' AND reason = '链上拥堵' ORDER BY id DESC LIMIT 1", String.class))
          .isEqualTo("{\"value\":\"12\"}{\"value\":\"20\"}");
    } finally {
      jdbc.update("UPDATE common_config SET value = ? WHERE config_key = ?", "12", ConfigKey.DEPOSIT_CONFIRMATIONS_ERC20.key);
      jdbc.update("UPDATE common_config SET value = ? WHERE config_key = ?", "1", ConfigKey.WITHDRAW_FEE_TRC20.key);
      config.refresh();
    }
  }

  /**
   * 单笔最小提现必须大于每条链的提现手续费（需求 RQ-16、第 3.11 节、AC-61）：改最小金额、改手续费都检查，并指出是哪条链。
   * S3 起最小金额在参数设置页改（{@code /api/admin/params/betting/withdraw.minAmount}，betting_params:edit），手续费仍在系统配置页。
   */
  @Test
  void 系统配置_单笔最小提现必须大于每条链的手续费_指出是哪条链_AC61() throws Exception {
    Admin s = superAdmin();
    try {
      MockHttpServletResponse r = call(put("/api/admin/params/betting/withdraw.minAmount"), s.cookie(), "{\"value\":\"5\",\"reason\":\"测试\"}");
      assertThat(r.getStatus()).isEqualTo(400);
      assertThat(body(r).get("fields").toString()).as("ERC20 手续费 5，不能等于").isEqualTo("[{\"field\":\"ERC20\",\"code\":\"minNotAboveFee\"}]");
      r = call(put("/api/admin/params/betting/withdraw.minAmount"), s.cookie(), "{\"value\":\"1\",\"reason\":\"测试\"}");
      assertThat(body(r).get("fields").toString()).as("一次列出全部不满足的链")
          .isEqualTo("[{\"field\":\"ERC20\",\"code\":\"minNotAboveFee\"},{\"field\":\"TRC20\",\"code\":\"minNotAboveFee\"}]");
      assertThat(config.decimal(ConfigKey.WITHDRAW_MIN_AMOUNT, null)).as("被拒时没有保存").isEqualByComparingTo("100");
      assertThat(body(call(put("/api/admin/params/betting/withdraw.minAmount"), s.cookie(), "{\"value\":\"5.01\",\"reason\":\"测试\"}")).get("value").asString()).isEqualTo("5.01");
      // 反过来：手续费不能改到大于等于最小金额
      r = call(put("/api/admin/configs/withdraw.fee.ERC20"), s.cookie(), "{\"value\":\"5.01\",\"reason\":\"测试\"}");
      assertThat(r.getStatus()).isEqualTo(400);
      assertThat(body(r).get("fields").toString()).isEqualTo("[{\"field\":\"ERC20\",\"code\":\"feeNotBelowMin\"}]");
      assertThat(call(put("/api/admin/configs/withdraw.fee.BSC"), s.cookie(), "{\"value\":\"5\",\"reason\":\"测试\"}").getStatus()).isEqualTo(200);
      assertThat(config.decimal(ConfigKey.WITHDRAW_FEE_BSC, null)).isEqualByComparingTo("5");
      assertThat(call(put("/api/admin/params/betting/withdraw.minAmount"), s.cookie(), "{\"value\":\"100\",\"reason\":\"恢复\"}").getStatus()).isEqualTo(200);
    } finally {
      jdbc.update("UPDATE common_config SET value = ? WHERE config_key = ?", "100", ConfigKey.WITHDRAW_MIN_AMOUNT.key);
      jdbc.update("UPDATE common_config SET value = ? WHERE config_key = ?", "5", ConfigKey.WITHDRAW_FEE_ERC20.key);
      jdbc.update("UPDATE common_config SET value = ? WHERE config_key = ?", "0.5", ConfigKey.WITHDRAW_FEE_BSC.key);
      config.refresh();
    }
  }

  @org.springframework.beans.factory.annotation.Autowired com.forecast.app.wallet.Confirmations confirmations;

  static Path web(String rel) {
    Path p = Path.of("../web/admin/src").resolve(rel);
    return Files.exists(p) ? p : Path.of("web/admin/src").resolve(rel);
  }

  @Test
  void 权限点和后台原型一致_高危标记一致() throws Exception {
    String ts = Files.readString(web("config/permissions.ts"));
    Matcher m = Pattern.compile("\\{ code: '([^']+)', module: '([^']+)', highRisk: (true|false) }").matcher(ts);
    Set<String> front = new LinkedHashSet<>();
    while (m.find()) front.add(m.group(1) + "|" + m.group(2) + "|" + m.group(3));
    Set<String> back = new LinkedHashSet<>();
    for (Permission p : Permission.values()) back.add(p.code + "|" + p.module + "|" + p.highRisk);
    assertThat(back).containsExactlyElementsOf(front);
  }

  @Test
  void AC35_菜单和后台原型一致_每个页面都有菜单入口() throws Exception {
    String menu = Files.readString(web("config/menu.tsx"));
    Matcher m = Pattern.compile("\\{ key: '([^']+)', labelKey: '[^']+'(?:, icon: <\\w+ />)?, path: '([^']+)', permission: '([^']+)'").matcher(menu);
    List<String> front = new ArrayList<>();
    while (m.find()) front.add(m.group(1) + "|" + m.group(2) + "|" + m.group(3));
    List<String> back = new ArrayList<>();
    collect(Menus.TREE, back);
    assertThat(back).containsExactlyElementsOf(front);
    String routes = Files.readString(web("config/routes.tsx"));
    Matcher r = Pattern.compile("menuKey: '([^']+)'").matcher(routes);
    Set<String> keys = new LinkedHashSet<>();
    for (String b : back) keys.add(b.split("\\|")[0]);
    int n = 0;
    while (r.find()) {
      assertThat(keys).as("页面 " + r.group(1) + " 有菜单入口").contains(r.group(1));
      n++;
    }
    assertThat(n).isGreaterThan(20);
  }

  private static void collect(List<Menus.Item> items, List<String> out) {
    for (Menus.Item it : items) {
      if (it.path() != null) out.add(it.key() + "|" + it.path() + "|" + it.permission().code);
      collect(it.children(), out);
    }
  }
}
