package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.support.MarketTestDoubles;
import com.forecast.app.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * forecast S13 后台部分（开发记录/S13-推广功能1.md）：真实 MySQL。
 * <ul>
 *   <li>MF-3 同时开放上限（AC-81）：超过时不勾"仍要上架"接口拒绝，勾了成功并写审计；上限 0 不提示；"列入今日场次"在编辑草稿和上架时勾选；
 *       后台列表按今日场次筛选；仪表盘的"同时开放""今日场次"（SQ-21）；参数设置页"场次"一组。</li>
 *   <li>MF-1、MF-10 来源统计（AC-94 的权限和导出）：没有 growth:view 的 403；导出写审计；导出的文件里没有邮箱。</li>
 *   <li>菜单"来源统计"和权限点 growth:view（54 个权限点，超管自动有）。</li>
 * </ul>
 * 和 {@link AdminMarketTest} 共用一个 Spring 上下文（同样的替身）。
 */
@Import({FixedClockConfig.class, MarketTestDoubles.class})
class AdminGrowthTest extends AdminTestSupport {

  static final Instant NOW = FixedClockConfig.NOW;

  @Autowired Clock clock;
  @Autowired MarketTestDoubles.Pool pool;
  final ObjectMapper json = new ObjectMapper();

  @AfterEach
  void reset() {
    ((MutableClock) clock).reset();
    jdbc.update("UPDATE common_config SET value = '2' WHERE config_key = ?", ConfigKey.MARKET_MAX_OPEN.key);
    config.refresh();
  }

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  List<String> fields(MockHttpServletResponse r) throws Exception {
    List<String> out = new ArrayList<>();
    for (JsonNode f : body(r).get("fields")) out.add(f.get("field").asString() + ":" + f.get("code").asString());
    return out;
  }

  String create(Admin a, MarketAdminApi.DraftInput d) throws Exception {
    MockHttpServletResponse r = call(post("/api/admin/markets"), a.cookie(), json.writeValueAsString(d));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return body(r).get("publicNo").asString();
  }

  /** 现在同时开放的市场数（库里别的测试留下的也算） */
  long openNow(Admin a) throws Exception {
    JsonNode l = body(call(get("/api/admin/markets/open-limit"), a.cookie(), null));
    return l.get("open").asLong();
  }

  void setLimit(long v) {
    jdbc.update("UPDATE common_config SET value = ? WHERE config_key = ?", String.valueOf(v), ConfigKey.MARKET_MAX_OPEN.key);
    config.refresh();
  }

  @Test
  void AC81_同时开放上限_超过时要勾选仍要上架_写审计_上限0不提示() throws Exception {
    Admin s = superAdmin();
    long open = openNow(s);
    // 上限 = 现在的数量 + 2：再上两个不超，第三个超
    setLimit(open + 2);
    JsonNode limit = body(call(get("/api/admin/markets/open-limit"), s.cookie(), null));
    assertThat(limit.get("open").asLong()).isEqualTo(open);
    assertThat(limit.get("limit").asLong()).isEqualTo(open + 2);
    for (int i = 0; i < 2; i++) {
      String no = create(s, AdminMarketTest.draft());
      assertThat(call(post("/api/admin/markets/" + no + "/publish"), s.cookie(), "{\"checked\":true}").getStatus()).isEqualTo(200);
    }
    String third = create(s, AdminMarketTest.draft());
    MockHttpServletResponse r = call(post("/api/admin/markets/" + third + "/publish"), s.cookie(), "{\"checked\":true}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(fields(r)).containsExactly("overLimit:required");
    assertThat(jdbc.queryForObject("SELECT status FROM market_question WHERE public_no = ?", String.class, Long.parseLong(third))).isEqualTo("DRAFT");
    // 勾选"仍要上架"：成功，审计记下当时的数量和上限
    JsonNode ok = body(call(post("/api/admin/markets/" + third + "/publish"), s.cookie(), "{\"checked\":true,\"overLimit\":true}"));
    assertThat(ok.get("status").asString()).isEqualTo("listed");
    String after = jdbc.queryForObject("SELECT after_json FROM admin_audit_log WHERE action = 'market.publish' AND target = ? AND result = 'SUCCESS' ORDER BY id DESC LIMIT 1",
        String.class, third);
    assertThat(json.readTree(after).get("overLimit").asBoolean()).isTrue();
    assertThat(json.readTree(after).get("openBefore").asLong()).isEqualTo(open + 2);
    assertThat(json.readTree(after).get("openLimit").asLong()).isEqualTo(open + 2);
    // 截止时间已过的不算"同时开放"
    ((MutableClock) clock).advance(Duration.ofDays(2));
    Admin s2 = login(s.id());
    assertThat(body(call(get("/api/admin/markets/open-limit"), s2.cookie(), null)).get("open").asLong()).as("截止时间都过了").isLessThan(open + 3);
    ((MutableClock) clock).reset();
    // 上限 0 = 不限：不提示
    setLimit(0);
    String fourth = create(s, AdminMarketTest.draft());
    assertThat(call(post("/api/admin/markets/" + fourth + "/publish"), s.cookie(), "{\"checked\":true}").getStatus()).isEqualTo(200);
  }

  @Test
  void 列入今日场次_编辑草稿和上架时勾选_列表筛选_仪表盘的同时开放和今日场次() throws Exception {
    Admin s = superAdmin();
    // 今天（UTC）13:00 开始的场次
    Instant start = NOW.truncatedTo(java.time.temporal.ChronoUnit.DAYS).plus(Duration.ofHours(13));
    MarketAdminApi.DraftInput base = AdminMarketTest.draft("FOOTBALL", 2, NOW, start.minus(Duration.ofMinutes(5)));
    MarketAdminApi.DraftInput slate = new MarketAdminApi.DraftInput(base.category(), base.title(), base.rules(), base.resolutionMode(), base.sourcePrimary(),
        base.sourceBackup(), base.sourceRef(), base.openAt(), base.closeAt(), start.toString(), start.plus(Duration.ofHours(2)).toString(), null, null, null,
        base.options(), true);
    JsonNode before = body(call(get("/api/admin/dashboard/markets"), s.cookie(), null)).get("slate");
    String a = create(s, slate);
    assertThat(body(call(get("/api/admin/markets/" + a), s.cookie(), null)).get("todaySlate").asBoolean()).as("编辑草稿时勾选").isTrue();
    // 上架时勾选（草稿没勾）
    MarketAdminApi.DraftInput plain = new MarketAdminApi.DraftInput(base.category(), base.title(), base.rules(), base.resolutionMode(), base.sourcePrimary(),
        base.sourceBackup(), base.sourceRef(), base.openAt(), base.closeAt(), start.toString(), start.plus(Duration.ofHours(2)).toString(), null, null, null,
        base.options());
    String b = create(s, plain);
    assertThat(body(call(get("/api/admin/markets/" + b), s.cookie(), null)).get("todaySlate").asBoolean()).isFalse();
    JsonNode pb = body(call(post("/api/admin/markets/" + b + "/publish"), s.cookie(), "{\"checked\":true,\"overLimit\":true,\"todaySlate\":true}"));
    assertThat(pb.get("todaySlate").asBoolean()).isTrue();
    assertThat(call(post("/api/admin/markets/" + a + "/publish"), s.cookie(), "{\"checked\":true,\"overLimit\":true}").getStatus()).isEqualTo(200);
    // 没勾的
    String c = create(s, plain);
    assertThat(call(post("/api/admin/markets/" + c + "/publish"), s.cookie(), "{\"checked\":true,\"overLimit\":true}").getStatus()).isEqualTo(200);
    // 列表筛选
    List<String> slateNos = new ArrayList<>();
    for (JsonNode i : body(call(get("/api/admin/markets?todaySlate=true&pageSize=100"), s.cookie(), null)).get("items")) {
      assertThat(i.get("todaySlate").asBoolean()).isTrue();
      slateNos.add(i.get("publicNo").asString());
    }
    assertThat(slateNos).contains(a, b).doesNotContain(c);
    // 仪表盘：今日场次 +2，同时开放 +3，上限 2
    JsonNode after = body(call(get("/api/admin/dashboard/markets"), s.cookie(), null)).get("slate");
    assertThat(after.get("today").asLong() - before.get("today").asLong()).isEqualTo(2);
    assertThat(after.get("open").asLong() - before.get("open").asLong()).isEqualTo(3);
    assertThat(after.get("limit").asInt()).isEqualTo(2);
    // 上架后改：加进今日场次、拿掉；写审计；只有 market:publish 能改；结束了的市场不能改
    JsonNode on = body(call(post("/api/admin/markets/" + c + "/today-slate"), s.cookie(), "{\"todaySlate\":true}"));
    assertThat(on.get("todaySlate").asBoolean()).isTrue();
    String audit = jdbc.queryForObject("SELECT after_json FROM admin_audit_log WHERE action = 'market.todaySlate' AND target = ? ORDER BY id DESC LIMIT 1", String.class, c);
    assertThat(json.readTree(audit).get("todaySlate").asBoolean()).isTrue();
    assertThat(body(call(post("/api/admin/markets/" + c + "/today-slate"), s.cookie(), "{\"todaySlate\":false}")).get("todaySlate").asBoolean()).isFalse();
    assertThat(call(post("/api/admin/markets/" + c + "/today-slate"), s.cookie(), "{}").getStatus()).isEqualTo(400);
    Admin viewer = withRoles(role(Permission.MARKET_VIEW));
    assertThat(call(post("/api/admin/markets/" + c + "/today-slate"), viewer.cookie(), "{\"todaySlate\":true}").getStatus()).isEqualTo(403);
    assertThat(call(post("/api/admin/markets/" + c + "/cancel"), s.cookie(), "{\"reason\":\"测试取消\"}").getStatus()).isEqualTo(200);
    assertThat(call(post("/api/admin/markets/" + c + "/today-slate"), s.cookie(), "{\"todaySlate\":true}").getStatus()).isEqualTo(409);
  }

  @Test
  void 参数设置_场次一组_同时开放上限默认2_要betting_params_edit() throws Exception {
    Admin s = superAdmin();
    JsonNode item = null;
    for (JsonNode p : body(call(get("/api/admin/params/betting"), s.cookie(), null))) if (p.get("key").asString().equals("market.maxOpen")) item = p;
    assertThat(item).as("参数设置里有同时开放上限").isNotNull();
    assertThat(item.get("group").asString()).isEqualTo("slate");
    assertThat(item.get("value").asString()).isEqualTo("2");
    Admin fee = withRoles(role(Permission.FEE_EDIT));
    assertThat(call(put("/api/admin/params/betting/market.maxOpen"), fee.cookie(), "{\"value\":\"3\",\"reason\":\"x\"}").getStatus()).as("只有 fee:edit 改不了")
        .isEqualTo(403);
    Admin params = withRoles(role(Permission.BETTING_PARAMS_EDIT));
    MockHttpServletResponse r = call(put("/api/admin/params/betting/market.maxOpen"), params.cookie(), "{\"value\":\"3\",\"reason\":\"加一场焦点战\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(body(call(get("/api/admin/markets/open-limit"), s.cookie(), null)).get("limit").asInt()).isEqualTo(3);
    assertThat(call(put("/api/admin/params/betting/market.maxOpen"), params.cookie(), "{\"value\":\"-1\",\"reason\":\"x\"}").getStatus()).isEqualTo(400);
  }

  @Test
  void 来源统计_要growth_view_导出写审计_文件里没有邮箱() throws Exception {
    Admin s = superAdmin();
    Admin other = withRoles(role(Permission.MARKET_VIEW));
    assertThat(call(get("/api/admin/growth/report"), other.cookie(), null).getStatus()).isEqualTo(403);
    assertThat(call(get("/api/admin/growth/export?kind=customers"), other.cookie(), null).getStatus()).isEqualTo(403);
    Admin viewer = withRoles(role(Permission.GROWTH_VIEW));
    JsonNode rep = body(call(get("/api/admin/growth/report"), viewer.cookie(), null));
    assertThat(rep.get("basis").asString()).isEqualTo("first");
    assertThat(rep.get("cohorts").isArray()).isTrue();
    // 有一个客户（带邮箱）这周注册
    String email = "growth" + System.nanoTime() + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status, created_at) VALUES (?, 'ACTIVE', ?)", email, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
    for (String kind : List.of("sources", "markets", "customers")) {
      MockHttpServletResponse r = call(get("/api/admin/growth/export?kind=" + kind + "&lang=en"), viewer.cookie(), null);
      assertThat(r.getStatus()).as(kind + " " + r.getContentAsString()).isEqualTo(200);
      assertThat(r.getContentType()).startsWith("text/csv");
      assertThat(r.getHeader("Content-Disposition")).contains("attachment").contains("growth-" + kind);
      String csv = new String(r.getContentAsByteArray(), StandardCharsets.UTF_8);
      assertThat(csv).startsWith("﻿").doesNotContain("@").doesNotContainIgnoringCase("email");
    }
    // 库是几个测试共用的：只看这个管理员的
    assertThat(audits("growth.export").stream().filter(x -> x.contains(" " + viewer.username() + " ")).toList()).hasSize(3)
        .allMatch(x -> x.startsWith("SUCCESS " + viewer.username()));
    // 参数不对一次指出
    MockHttpServletResponse bad = call(get("/api/admin/growth/export?kind=all&from=x"), viewer.cookie(), null);
    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat(fields(call(get("/api/admin/growth/report?from=2026-13-01&basis=x"), s.cookie(), null))).containsExactlyInAnyOrder("from:invalid", "basis:invalid");
  }

  @Test
  void 菜单有来源统计_超管自动有growth_view() throws Exception {
    Admin s = superAdmin();
    JsonNode me = body(call(get("/api/admin/me"), s.cookie(), null));
    assertThat(me.toString()).contains("\"growth\"").contains("growth:view");
  }
}
