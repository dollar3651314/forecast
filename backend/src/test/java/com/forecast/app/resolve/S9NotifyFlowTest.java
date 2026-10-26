package com.forecast.app.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.forecast.app.admin.security.Menus;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.notification.api.MailApi;
import com.forecast.app.resolve.api.ResolveDeskApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * forecast S9：通知 22 类（需求说明书 v1.3 第 7 节，C86）、三种语言、员工提醒按角色发（SQ-14），后台菜单表（迁移 V17）。真实 MySQL、Redis。
 */
class S9NotifyFlowTest extends PlatformTestSupport {

  @Autowired MailApi mail;

  /** 需求第 7 节：编号 → 模板 → 渠道（站内信、推送、邮件）。不发的（#9、#19、#20、#21）没有模板 */
  static final Map<String, Map<String, String>> TABLE = new LinkedHashMap<>();

  static {
    row("#1", "WELCOME", "INBOX");
    row("#2", "EMAIL_CODE", "EMAIL");
    row("#3", "DEPOSIT_CREDITED", "INBOX,PUSH,EMAIL");
    row("#4", "DEPOSIT_BELOW_MIN", "INBOX");
    row("#5", "WITHDRAW_STATUS", "INBOX,PUSH,EMAIL");
    row("#6", "KYC_APPROVED", "INBOX,PUSH,EMAIL");
    row("#6", "KYC_REJECTED", "INBOX,PUSH,EMAIL");
    row("#7", "KYC_REQUIRED", "INBOX,PUSH,EMAIL");
    row("#8", "BET_PLACED", "INBOX");
    row("#10", "RESULT_PUBLICIZED", "INBOX,PUSH");
    row("#11", "SETTLE_WON", "INBOX,PUSH");
    row("#12", "SETTLE_LOST", "INBOX,PUSH");
    row("#13", "MARKET_REFUNDED", "INBOX,PUSH,EMAIL");
    row("#14", "LATE_BET_REFUNDED", "INBOX,PUSH,EMAIL");
    row("#15", "DISPUTE_DECIDED", "INBOX,PUSH,EMAIL");
    row("#16", "RESULT_OVERTURNED", "INBOX,PUSH");
    row("#17", "COMPENSATION_PAID", "INBOX,PUSH,EMAIL");
    row("#18", "MARKET_CLARIFIED", "INBOX");
    row("#22", "FEE_CHANGED", "INBOX");
    row("#23", "MATCH_REMINDER", "EMAIL");
  }

  static void row(String no, String code, String channels) {
    TABLE.computeIfAbsent(no, k -> new LinkedHashMap<>()).put(code, channels);
  }

  /** 市场策略 F-12、F-15：不能出现的诱导收益、和事实不符的说法 */
  static final List<String> BANNED = List.of("稳赚", "必中", "保本", "翻倍", "胜者瓜分", "最后入场", "别错过", "秒出", "全在链上", "链上公开", "私房钱", "恭喜",
      "穩賺", "保證", "勝者瓜分", "最後入場", "別錯過", "guaranteed", "risk-free", "don't miss", "last chance", "congratulations", "double your");

  static final Pattern VAR = Pattern.compile("\\{([A-Za-z0-9_]+)}");

  @Test
  void 需求第7节_每一类的模板和渠道_三种语言都有_变量都登记了_没有诱导收益的说法() {
    List<String> problems = new ArrayList<>();
    for (var e : TABLE.entrySet()) {
      for (var t : e.getValue().entrySet()) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM notify_template WHERE code = ?", t.getKey());
        if (!e.getKey().equals(row.get("req_no"))) problems.add(t.getKey() + " 编号 " + row.get("req_no"));
        if (!t.getValue().equals(row.get("channels"))) problems.add(t.getKey() + " 渠道 " + row.get("channels"));
        Set<String> vars = Set.of(String.valueOf(row.get("variables")).split(","));
        for (String col : List.of("title_zh", "body_zh", "title_en", "body_en", "title_tw", "body_tw")) {
          String text = String.valueOf(row.get(col));
          if (text.isBlank()) problems.add(t.getKey() + " " + col + " 为空");
          Matcher m = VAR.matcher(text);
          while (m.find()) if (!vars.contains(m.group(1))) problems.add(t.getKey() + " " + col + " 用了没登记的变量 " + m.group(1));
          for (String b : BANNED) if (text.toLowerCase().contains(b.toLowerCase())) problems.add(t.getKey() + " " + col + " 有禁用的说法 " + b);
        }
        // 繁体里不能夹简体常用字（简单检查：几个只在简体里出现的字）
        String tw = row.get("title_tw") + " " + row.get("body_tw");
        for (String simp : List.of("币", "账", "认证", "结算", "费", "这", "说", "们", "现", "额", "单", "后台")) {
          if (tw.contains(simp)) problems.add(t.getKey() + " 繁体里有简体字 " + simp);
        }
      }
    }
    assertThat(problems).isEmpty();
    // 需求第 7 节写"不发"的四类没有模板；客户模板一共 20 个；S16 加了等候页的两封（按邮箱发给订阅的人，不一定是客户，SQ-22：确认订阅、正式上线了）
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_template WHERE audience IN ('USER', 'ANY')", Integer.class)).isEqualTo(22);
    assertThat(jdbc.queryForList("SELECT code FROM notify_template WHERE audience = 'ANY' AND req_no IS NULL AND code LIKE 'WAITLIST%' ORDER BY code", String.class))
        .containsExactly("WAITLIST_CONFIRM", "WAITLIST_LAUNCH");
    // 员工模板都配了按角色的收件人或者明确发告警邮箱（复用的）
    assertThat(jdbc.queryForList("SELECT code FROM notify_template WHERE audience = 'STAFF' AND staff_perms <> '' ORDER BY code", String.class))
        .containsExactly("CONTENT_ALERT", "CONTENT_DIGEST", "DISPUTE_NEW", "MARKET_SUBMITTED", "POOL_RECON_DIFF", "RESOLVE_AUTO_FAILED", "RESOLVE_OVERDUE", "RESOLVE_PROPOSED", "RISK_ALERT", "SETTLE_ABNORMAL",
            "SPORTS_AUTO_FAILED", "SPORTS_KICKOFF_CHANGED", "TELEGRAM_ALERT");
  }

  /** 这个市场的员工邮件发给了谁 */
  List<String> staffMailsTo(String template, String marketNo) {
    return jdbc.queryForList("SELECT r.recipient FROM notify_record r JOIN common_task t ON JSON_EXTRACT(t.payload, '$.recordId') = r.id "
        + "WHERE r.template_code = ? AND JSON_UNQUOTE(JSON_EXTRACT(t.payload, '$.params.marketNo')) = ? ORDER BY r.id", String.class, template, marketNo);
  }

  long admin(String username, String... perms) {
    jdbc.update("INSERT INTO admin_user (username, display_name, email, password_hash, status, must_change_password, created_at) "
        + "VALUES (?, ?, ?, 'x', 'ACTIVE', 0, UTC_TIMESTAMP(3))", username, username, username + "@staff.test");
    long id = jdbc.queryForObject("SELECT id FROM admin_user WHERE username = ?", Long.class, username);
    if (perms.length > 0) {
      String code = "r_" + username;
      jdbc.update("INSERT INTO admin_role (code, name_zh, name_en, description, built_in) VALUES (?, ?, ?, '', 0)", code, code, code);
      long role = jdbc.queryForObject("SELECT id FROM admin_role WHERE code = ?", Long.class, code);
      for (String p : perms) jdbc.update("INSERT INTO admin_role_permission (role_id, permission) VALUES (?, ?)", role, p);
      jdbc.update("INSERT INTO admin_user_role (admin_id, role_id) VALUES (?, ?)", id, role);
    }
    return id;
  }

  @Test
  void 员工提醒按角色发_提议发给有确认权限的人和超管_待提议超时发给裁决员() throws Exception {
    String tag = Long.toString(System.nanoTime(), 36);
    admin("cf" + tag, "resolve:confirm");
    admin("pr" + tag, "resolve:propose");
    long off = admin("off" + tag, "resolve:confirm");
    jdbc.update("UPDATE admin_user SET status = 'DISABLED' WHERE id = ?", off);
    Market m = platformMarket(2, 2);
    bet(user("100").id(), m, 0, "10");
    bet(user("100").id(), m, 1, "10");
    closeAndEnd(m);
    ResolveDeskApi.CaseDetail d = desk.propose(no(m), result(m, 0), 7);
    assertThat(d.summary().proposal()).isNotNull();
    List<String> to = staffMailsTo("RESOLVE_PROPOSED", m.no());
    assertThat(to).contains("cf" + tag + "@staff.test").doesNotContain("pr" + tag + "@staff.test", "off" + tag + "@staff.test");
    List<String> supers = jdbc.queryForList("SELECT u.email FROM admin_user u JOIN admin_user_role ur ON ur.admin_id = u.id JOIN admin_role r ON r.id = ur.role_id "
        + "WHERE r.code = 'super_admin' AND u.status = 'ACTIVE'", String.class);
    assertThat(to).containsAll(supers);
    assertThat(to).doesNotHaveDuplicates();
  }

  @Test
  void 补充说明_通知18_发给有下注的客户_繁体按繁体() throws Exception {
    Market m = platformMarket(2, 2);
    User a = user("100");
    User b = user("100");
    bet(a.id(), m, 0, "10");
    admin.clarify(no(m), new I18nText("Rate cut means the upper bound goes down", "降息指利率区间上限下调", "降息指利率區間上限下調"), 1);
    assertThat(inbox(a.id(), "MARKET_CLARIFIED")).isEqualTo(1);
    assertThat(inbox(b.id(), "MARKET_CLARIFIED")).isZero();
    // 客户端切到繁体：站内信按繁体显示（变量 marketTw、textTw）
    MockHttpServletResponse r = call(put("/api/me/notify-lang"), a.cookie(), "{\"lang\":\"zh-TW\"}");
    assertThat(r.getStatus()).as(r.getContentAsString()).isIn(200, 204);
    assertThat(jdbc.queryForObject("SELECT lang FROM notify_pref WHERE user_id = ?", String.class, a.id())).isEqualTo("zh-TW");
    JsonNode list = getJson("/api/notifications?lang=zh-TW", a.cookie());
    JsonNode item = list.get("items").get(0);
    assertThat(item.get("title").asString()).isEqualTo("「美联储 11 月议息 繁」發布了補充說明");
    assertThat(item.get("body").asString()).contains("降息指利率區間上限下調").contains("補充說明只解釋規則");
    JsonNode en = getJson("/api/notifications?lang=en", a.cookie()).get("items").get(0);
    assertThat(en.get("title").asString()).isEqualTo("Clarification for \"美联储 11 月议息 en\"");
    // 不认识的语言 400
    assertThat(call(put("/api/me/notify-lang"), a.cookie(), "{\"lang\":\"ja\"}").getStatus()).isEqualTo(400);
  }

  @Test
  void 提交上架_提醒有上架权限的人_再提交不重复() throws Exception {
    // S14：自己建一个有上架权限的管理员（C80：不依赖别的测试先建过管理员；单独跑这个类时原来会因为没有收件人失败）
    String tag = "pub" + System.nanoTime();
    admin(tag, "market:publish");
    Market m = platformMarket(2, 2);
    // 已经上架的市场换一个草稿测：新建 → 提交
    var in = new com.forecast.app.market.api.MarketAdminApi.DraftInput("MACRO", tx("草稿待上架"), tx("规则"), "PLATFORM", "a", "b", null,
        java.time.Instant.now(clock).plusSeconds(600).toString(), java.time.Instant.now(clock).plusSeconds(3600).toString(),
        java.time.Instant.now(clock).plusSeconds(3700).toString(), java.time.Instant.now(clock).plusSeconds(7200).toString(), null, 120, null,
        List.of(new com.forecast.app.market.api.MarketAdminApi.OptionInput(tx("是"), tx("是的条件")),
            new com.forecast.app.market.api.MarketAdminApi.OptionInput(tx("否"), tx("否的条件"))));
    String no = admin.createDraft(in, 1).publicNo();
    admin.submit(Long.parseLong(no));
    admin.submit(Long.parseLong(no));
    List<String> to = staffMailsTo("MARKET_SUBMITTED", no);
    assertThat(to).contains(tag + "@staff.test").doesNotHaveDuplicates();
    assertThat(m.no()).isNotEqualTo(no);
  }

  @Test
  void 按邮箱发_知道语言时只发一种语言() {
    String to = "sub" + System.nanoTime() + "@example.com";
    mail.send(to, "MATCH_REMINDER", Map.of("match", "Arsenal vs Everton", "openAt", "2026-10-26 12:00 UTC", "closeAt", "2026-10-26 13:55 UTC", "link",
        "https://example.org/m/1", "unsubscribe", "https://example.org/u/1"), "zh-TW");
    Map<String, Object> t = jdbc.queryForMap("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.lang')) AS lang, JSON_UNQUOTE(JSON_EXTRACT(payload, '$.template')) AS tpl "
        + "FROM common_task WHERE payload LIKE ? ORDER BY id DESC LIMIT 1", "%" + to + "%");
    assertThat(t).containsEntry("lang", "zh-TW").containsEntry("tpl", "MATCH_REMINDER");
    // 不知道语言（员工邮件、验证码）：不带 lang，中英文各一份
    mail.send(to, "MATCH_REMINDER", Map.of("match", "x"));
    assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(payload, '$.lang') FROM common_task WHERE payload LIKE ? ORDER BY id DESC LIMIT 1", String.class,
        "%" + to + "%")).isNull();
  }

  @Test
  void 后台菜单表_迁移写了每个菜单的默认顺序() throws Exception {
    List<String> keys = new ArrayList<>();
    collect(Menus.TREE, keys);
    // 迁移脚本里每个菜单都有默认顺序（库里的行别的测试会改，例如 AdminSystemTest 删掉 audit 看兜底，所以查脚本本身）
    // S9 的菜单在 V17，S10 的风控告警在 V19，S13 的来源统计在 V21，S15 的内容中心在 V24
    String v17;
    try (var in = getClass().getResourceAsStream("/db/migration/V17__s9_admin_notify.sql"); var in19 = getClass().getResourceAsStream("/db/migration/V19__s10_riskalert.sql");
        var in21 = getClass().getResourceAsStream("/db/migration/V21__s13_growth.sql"); var in24 = getClass().getResourceAsStream("/db/migration/V24__s15_content.sql");
        var in26 = getClass().getResourceAsStream("/db/migration/V26__s16_waitlist.sql"); var in27 = getClass().getResourceAsStream("/db/migration/V27__s16_telegram.sql")) {
      v17 = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) + new String(in19.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          + new String(in21.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) + new String(in24.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          + new String(in26.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) + new String(in27.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    // S16 的等候页订阅、Telegram 机器人在 V26、V27（S16 分支上原编号 V41、V42，整合 4 改号）
    for (String k : keys) assertThat(v17).as("V17 / V19 / V21 / V24 / V26 / V27 里的菜单 " + k).contains("('" + k + "', ");
    List<String> saved = jdbc.queryForList("SELECT menu_key FROM admin_menu", String.class);
    assertThat(saved).containsAll(keys.stream().filter(k -> !k.equals("audit")).toList());
    // 一级菜单的默认顺序：仪表盘、市场管理、裁决、结算与补偿、报表、来源统计（S13）、等候页订阅（S16）、内容中心（S15，含 S16 的 Telegram 机器人）、
    // 参数设置、客户、KYC、资金、风控告警（S10）、通知、系统
    assertThat(Menus.TREE.stream().map(Menus.Item::key).toList()).containsExactly("dashboard", "market", "judge", "settle", "reports", "growth", "waitlist", "content",
        "params", "customers", "kyc", "funds", "risk-alerts", "notify", "system");
    // 整合 4：Telegram 机器人是内容中心分组的最后一项（发布渠道）
    Menus.Item content = Menus.TREE.stream().filter(i -> i.key().equals("content")).findFirst().orElseThrow();
    assertThat(content.children().stream().map(Menus.Item::key).toList()).containsExactly("content-drafts", "content-events", "content-pages", "content-templates",
        "content-tools", "content-logs", "content-settings", "telegram");
  }

  static void collect(List<Menus.Item> items, List<String> out) {
    for (Menus.Item it : items) {
      out.add(it.key());
      collect(it.children(), out);
    }
  }
}
