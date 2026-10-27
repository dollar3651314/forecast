package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.health.AlertMailSwitch;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * forecast S12b：后台"参数设置 → 告警邮件"（Amos 2026-10-27："告警邮件要能控制是否发送"）：总开关 {@code alert.mail.enabled}、不发邮件的告警项
 * {@code alert.mail.muted}。权限点 {@code alert_params:edit}（高危，超管自动拥有），原因必填、写审计（params.update），改了以后告警任务读到的值立即变。
 */
class AdminAlertMailTest extends AdminTestSupport {

  @Autowired AlertMailSwitch mailSwitch;
  final ObjectMapper json = new ObjectMapper();

  @AfterEach
  void restore() {
    jdbc.update("UPDATE common_config SET value = 'true', updated_by = NULL WHERE config_key = ?", ConfigKey.ALERT_MAIL_ENABLED.key);
    jdbc.update("UPDATE common_config SET value = '', updated_by = NULL WHERE config_key = ?", ConfigKey.ALERT_MAIL_MUTED.key);
    config.refresh();
  }

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  @Test
  void 默认开_没有静音_超管能看能改_写审计_告警任务立即按新的值() throws Exception {
    Admin s = superAdmin();
    MockHttpServletResponse r = call(get("/api/admin/params/alert"), s.cookie(), null);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode list = body(r);
    assertThat(list).hasSize(2);
    assertThat(list.get(0).get("key").asString()).isEqualTo("alert.mail.enabled");
    assertThat(list.get(0).get("value").asString()).as("迁移 V28：默认开").isEqualTo("true");
    assertThat(list.get(0).get("unit").asString()).isEqualTo("bool");
    assertThat(list.get(0).get("section").asString()).isEqualTo("alert");
    assertThat(list.get(1).get("key").asString()).isEqualTo("alert.mail.muted");
    assertThat(list.get(1).get("value").asString()).isEmpty();
    assertThat(list.get(1).get("unit").asString()).isEqualTo("text");
    assertThat(mailSwitch.mailEnabled()).isTrue();

    // 原因必填
    assertThat(call(put("/api/admin/params/alert/alert.mail.enabled"), s.cookie(), "{\"value\":\"false\"}").getContentAsString()).contains("\"field\":\"reason\"");
    // 只接受 true / false
    assertThat(call(put("/api/admin/params/alert/alert.mail.enabled"), s.cookie(), "{\"value\":\"off\",\"reason\":\"测试\"}").getStatus()).isEqualTo(400);
    r = call(put("/api/admin/params/alert/alert.mail.enabled"), s.cookie(), "{\"value\":\"false\",\"reason\":\"测试阶段不收告警邮件\",\"expected\":\"true\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).get("value").asString()).isEqualTo("false");
    assertThat(mailSwitch.mailEnabled()).as("改了以后告警任务立即按新的值（本机清缓存）").isFalse();
    assertThat(audits("params.update")).anyMatch(l -> l.equals("SUCCESS " + s.username() + " 测试阶段不收告警邮件"));
    assertThat(jdbc.queryForObject("SELECT before_json FROM admin_audit_log WHERE action = 'params.update' AND target = 'alert.mail.enabled' ORDER BY id DESC LIMIT 1", String.class))
        .contains("true");

    // 别人刚改过：409
    r = call(put("/api/admin/params/alert/alert.mail.enabled"), s.cookie(), "{\"value\":\"true\",\"reason\":\"x\",\"expected\":\"true\"}");
    assertThat(r.getStatus()).isEqualTo(409);

    // 不发邮件的告警项：去重、排序；名字不合法拒绝
    assertThat(call(put("/api/admin/params/alert/alert.mail.muted"), s.cookie(), "{\"value\":\"telegram.bot,有空格 的名字\",\"reason\":\"测试\"}").getStatus()).isEqualTo(400);
    r = call(put("/api/admin/params/alert/alert.mail.muted"), s.cookie(),
        "{\"value\":\" source.thesportsdb, telegram.bot ,source.balldontlie,telegram.bot\",\"reason\":\"测试环境没配 key\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(r).get("value").asString()).isEqualTo("source.balldontlie,source.thesportsdb,telegram.bot");
    assertThat(mailSwitch.muted()).containsExactly("source.balldontlie", "source.thesportsdb", "telegram.bot");
    // 清空
    r = call(put("/api/admin/params/alert/alert.mail.muted"), s.cookie(), "{\"value\":\"\",\"reason\":\"恢复\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(mailSwitch.muted()).isEmpty();

    // 不能借别的组改：告警邮件的键不属于 betting 组
    assertThat(call(put("/api/admin/params/betting/alert.mail.enabled"), s.cookie(), "{\"value\":\"false\",\"reason\":\"x\"}").getStatus()).isEqualTo(404);
    // 系统配置页没有这两项
    assertThat(call(get("/api/admin/configs"), s.cookie(), null).getContentAsString()).doesNotContain("alert.mail");
  }

  @Test
  void 告警项列表_现在的各项加上已静音但不出现的项() throws Exception {
    Admin s = superAdmin();
    jdbc.update("UPDATE common_config SET value = 'node.NOPE' WHERE config_key = ?", ConfigKey.ALERT_MAIL_MUTED.key);
    config.refresh();
    MockHttpServletResponse r = call(get("/api/admin/params/alert/items"), s.cookie(), null);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    List<String> names = new ArrayList<>();
    JsonNode nope = null;
    for (JsonNode n : body(r)) {
      names.add(n.get("name").asString());
      if ("node.NOPE".equals(n.get("name").asString())) nope = n;
      else {
        assertThat(n.get("titleZh").asString()).as("现在的项带中文说明").isNotBlank();
        assertThat(n.get("status").asString()).isIn("UP", "DOWN", "NOT_CONFIGURED");
      }
    }
    assertThat(names).as("按名字排序").isSorted();
    assertThat(nope).as("已静音、现在不检查的项也列出来，方便取消").isNotNull();
    assertThat(nope.get("muted").asBoolean()).isTrue();
    assertThat(nope.get("status").isNull()).isTrue();
  }

  @Test
  void 权限点alert_params_edit_没有时403_有时可以改() throws Exception {
    Admin other = withRoles(role(Permission.BETTING_PARAMS_EDIT, Permission.FEE_EDIT));
    assertThat(call(get("/api/admin/params/alert"), other.cookie(), null).getStatus()).isEqualTo(403);
    assertThat(call(get("/api/admin/params/alert/items"), other.cookie(), null).getStatus()).isEqualTo(403);
    assertThat(call(put("/api/admin/params/alert/alert.mail.enabled"), other.cookie(), "{\"value\":\"false\",\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    Admin ops = withRoles(role(Permission.ALERT_PARAMS_EDIT));
    assertThat(call(get("/api/admin/params/alert"), ops.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(call(put("/api/admin/params/alert/alert.mail.enabled"), ops.cookie(), "{\"value\":\"false\",\"reason\":\"x\"}").getStatus()).isEqualTo(200);
    assertThat(Permission.ALERT_PARAMS_EDIT.highRisk).isTrue();
  }
}
