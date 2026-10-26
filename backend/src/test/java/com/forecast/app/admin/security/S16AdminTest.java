package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * forecast S16 后台：等候页订阅（growth:view；发送上线通知 content:publish ★，只能一次）、Telegram 机器人（content:view；改动 content:publish ★）。
 * 权限点、原因必填、勾选、审计（项目地图约定 12、47）。
 */
class S16AdminTest extends AdminTestSupport {

  @AfterEach
  void restore() {
    jdbc.update("DELETE FROM growth_launch");
    jdbc.update("UPDATE growth_tg_target SET enabled = 0, chat_id = NULL, enabled_at = NULL");
    jdbc.update("UPDATE growth_tg_state SET emergency_stop = 0");
    jdbc.update("UPDATE growth_tg_template SET status = 'DRAFT', fingerprint = NULL");
  }

  @Test
  void 等候页订阅_查看要growth_view_发送上线通知要content_publish_原因和勾选必填_只能一次_写审计() throws Exception {
    Admin viewer = withRoles(role(Permission.GROWTH_VIEW));
    Admin noView = withRoles(role(Permission.DASHBOARD_VIEW));
    assertThat(call(get("/api/admin/waitlist"), noView.cookie(), null).getStatus()).isEqualTo(403);
    MockHttpServletResponse r = call(get("/api/admin/waitlist?status=pending"), viewer.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentAsString()).contains("\"counts\"").contains("\"launch\":{\"status\":\"none\"");
    assertThat(call(get("/api/admin/waitlist?status=bad"), viewer.cookie(), null).getStatus()).isEqualTo(400);
    // 只有 growth:view 不能发
    assertThat(call(post("/api/admin/waitlist/launch"), viewer.cookie(), "{\"checked\":true,\"reason\":\"上线\"}").getStatus()).isEqualTo(403);
    Admin publisher = withRoles(role(Permission.CONTENT_PUBLISH));
    MockHttpServletResponse missing = call(post("/api/admin/waitlist/launch"), publisher.cookie(), "{}");
    assertThat(missing.getStatus()).isEqualTo(400);
    assertThat(missing.getContentAsString()).contains("\"field\":\"reason\"").contains("\"field\":\"checked\"");
    MockHttpServletResponse ok = call(post("/api/admin/waitlist/launch"), publisher.cookie(), "{\"checked\":true,\"reason\":\"正式上线\"}");
    assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
    assertThat(ok.getContentAsString()).contains("\"status\":\"sending\"");
    MockHttpServletResponse again = call(post("/api/admin/waitlist/launch"), publisher.cookie(), "{\"checked\":true,\"reason\":\"再按一次\"}");
    assertThat(again.getStatus()).isEqualTo(409);
    assertThat(again.getContentAsString()).contains("LAUNCH_ALREADY_SENT");
    assertThat(audits("waitlist.launch")).containsExactly("SUCCESS " + publisher.username() + " 正式上线",
        "FAILURE " + publisher.username() + " 再按一次（失败：LAUNCH_ALREADY_SENT）");
  }

  @Test
  void Telegram_查看要content_view_改动要content_publish_原因必填_频道编号校验_写审计() throws Exception {
    Admin viewer = withRoles(role(Permission.CONTENT_VIEW));
    Admin other = withRoles(role(Permission.GROWTH_VIEW));
    assertThat(call(get("/api/admin/telegram"), other.cookie(), null).getStatus()).isEqualTo(403);
    MockHttpServletResponse o = call(get("/api/admin/telegram"), viewer.cookie(), null);
    assertThat(o.getStatus()).isEqualTo(200);
    assertThat(o.getContentAsString()).contains("\"configured\":true").contains("\"key\":\"CHANNEL_ZH\"").contains("\"key\":\"CHANNEL_EN\"")
        .contains("\"status\":\"draft\"").doesNotContain("ci_only_test_telegram");
    assertThat(call(get("/api/admin/telegram/posts"), viewer.cookie(), null).getStatus()).isEqualTo(200);
    assertThat(call(put("/api/admin/telegram/targets/CHANNEL_ZH"), viewer.cookie(), "{\"chatId\":\"@qc_test_cn\",\"enabled\":true,\"reason\":\"x\"}").getStatus())
        .isEqualTo(403);
    Admin pub = withRoles(role(Permission.CONTENT_PUBLISH, Permission.CONTENT_VIEW));
    assertThat(call(put("/api/admin/telegram/targets/CHANNEL_ZH"), pub.cookie(), "{\"chatId\":\"@qc_test_cn\",\"enabled\":true}").getStatus()).as("原因必填")
        .isEqualTo(400);
    assertThat(call(put("/api/admin/telegram/targets/CHANNEL_ZH"), pub.cookie(), "{\"chatId\":\"not valid\",\"enabled\":true,\"reason\":\"x\"}").getContentAsString())
        .contains("\"field\":\"chatId\"");
    assertThat(call(put("/api/admin/telegram/targets/CHANNEL_ZH"), pub.cookie(), "{\"enabled\":true,\"reason\":\"x\"}").getContentAsString()).as("打开时编号必填")
        .contains("\"field\":\"chatId\"");
    MockHttpServletResponse t = call(put("/api/admin/telegram/targets/CHANNEL_ZH"), pub.cookie(), "{\"chatId\":\"@qc_test_cn\",\"enabled\":true,\"reason\":\"建好频道\"}");
    assertThat(t.getStatus()).as(t.getContentAsString()).isEqualTo(200);
    assertThat(t.getContentAsString()).contains("\"chatId\":\"@qc_test_cn\"").contains("\"enabled\":true");
    assertThat(call(put("/api/admin/telegram/targets/NOPE"), pub.cookie(), "{\"enabled\":false,\"reason\":\"x\"}").getStatus()).isEqualTo(404);
    assertThat(audits("telegram.target")).anyMatch(a -> a.startsWith("SUCCESS " + pub.username() + " 建好频道"));
    // 紧急停止 / 恢复
    assertThat(call(post("/api/admin/telegram/stop"), pub.cookie(), "{\"stop\":true}").getStatus()).isEqualTo(400);
    MockHttpServletResponse s = call(post("/api/admin/telegram/stop"), pub.cookie(), "{\"stop\":true,\"reason\":\"发错了\"}");
    assertThat(s.getContentAsString()).contains("\"emergencyStop\":true");
    assertThat(call(post("/api/admin/telegram/stop"), pub.cookie(), "{\"stop\":false,\"reason\":\"恢复\"}").getContentAsString()).contains("\"emergencyStop\":false");
    assertThat(audits("telegram.stop")).containsExactly("SUCCESS " + pub.username() + " 发错了");
    assertThat(audits("telegram.resume")).hasSize(1);
    // 审定：要勾选、指纹要和现在的模板一致
    MockHttpServletResponse bad = call(post("/api/admin/telegram/templates/RESULT/zh-CN/approve"), pub.cookie(), "{\"fingerprint\":\"x\",\"reason\":\"审定\"}");
    assertThat(bad.getContentAsString()).contains("\"field\":\"checked\"");
    MockHttpServletResponse changed = call(post("/api/admin/telegram/templates/RESULT/zh-CN/approve"), pub.cookie(),
        "{\"fingerprint\":\"x\",\"checked\":true,\"reason\":\"审定\"}");
    assertThat(changed.getStatus()).isEqualTo(409);
    assertThat(changed.getContentAsString()).contains("TELEGRAM_TEMPLATE_CHANGED");
    assertThat(call(post("/api/admin/telegram/templates/RESULT/fr/approve"), pub.cookie(), "{\"fingerprint\":\"x\",\"checked\":true,\"reason\":\"x\"}").getStatus())
        .isEqualTo(404);
  }
}
