package com.forecast.app.settle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.market.remind.ReminderService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * forecast S11 客户端页面要的接口（开发记录/S11-客户端.md 第 2 节）：真实 MySQL 8.4、Redis 7（C37），测试数据各自新建（C80）。
 * <ul>
 *   <li>市场列表：搜索（题目三种语言、市场编号；%、_ 不当通配符）、按奖池排序、分页、已结算带结果；</li>
 *   <li>市场详情的 {@code settle}（结算合计和本人的派彩、抽成、退款）；我的下注的最终赔率、按持仓的派彩和抽成、顶部汇总；</li>
 *   <li>#23 开赛提醒：只给登录过的客户（SQ-20；S16 起注册后的自动登录也算，SQ-27，见 AuthFlowTest；这里的测试客户是直接写库建的，没有登录过）、只能订还没开放的市场、到开放时间发一次邮件（遵守邮件开关）、
 *       市场先结束的不发、邮件里的退订链接不用登录。</li>
 * </ul>
 */
class S11ClientTest extends SettleTestSupport {

  @Autowired ReminderService reminders;

  private static I18nText tx(String s) {
    return new I18nText(s + " en", s + " 简", s + " 繁");
  }

  /** 题目带 tag 的市场：open 开放、close 截止（事件开始 = 截止 + 5 分钟，结束 = 截止 + 2 小时） */
  private Market titled(String title, int options, Instant open, Instant close) {
    List<MarketAdminApi.OptionInput> opts = new ArrayList<>();
    for (int i = 1; i <= options; i++) opts.add(new MarketAdminApi.OptionInput(tx("选项" + i), tx("条件" + i)));
    MarketAdminApi.DraftInput in = new MarketAdminApi.DraftInput("FOOTBALL", tx(title), tx("规则"), "AUTO", "premierleague.com", "API-Football", null,
        open.toString(), close.toString(), close.plus(Duration.ofMinutes(5)).toString(), close.plus(Duration.ofHours(2)).toString(), null, null, null, opts);
    MarketAdminApi.Detail d = admin.createDraft(in, 1);
    admin.publish(Long.parseLong(d.publicNo()), true, 1);
    long id = jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, Long.parseLong(d.publicNo()));
    return new Market(id, d.publicNo(), d.options().stream().map(MarketAdminApi.OptionView::optionNo).toList());
  }

  private Market titled(String title, int options) {
    Instant now = Instant.now(clock);
    return titled(title, options, now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(1)));
  }

  /** GET /api/markets，参数成对给（按原样编码） */
  private JsonNode list(User u, String... kv) throws Exception {
    var b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/markets");
    for (int i = 0; i < kv.length; i += 2) b = b.param(kv[i], kv[i + 1]);
    MockHttpServletResponse r = call(b, u.cookie(), null);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return body(r);
  }

  private static List<String> nos(JsonNode page) {
    List<String> out = new ArrayList<>();
    page.get("items").forEach(i -> out.add(i.get("publicNo").asString()));
    return out;
  }

  // =====================================================================
  // 市场列表
  // =====================================================================

  @Test
  void 市场列表_搜索三种语言和编号_按奖池排序_分页_已结算带结果() throws Exception {
    String tag = "S11K" + System.nanoTime();
    User u = user("1000");
    Market a = titled(tag + " 阿森纳", 2);
    Market b = titled(tag + " 拜仁", 2);
    Market c = titled(tag + " 切尔西", 2);
    bet(u.id(), a, 0, "10");
    bet(u.id(), a, 1, "5");
    bet(u.id(), b, 0, "30");
    bet(u.id(), c, 1, "20");

    // 题目包含（简体、英文、繁体都能搜到）
    assertThat(nos(list(u, "status", "active", "q", tag))).containsExactly(a.no(), b.no(), c.no());
    assertThat(nos(list(u, "status", "active", "q", tag + " 拜仁 简"))).as("简体").containsExactly(b.no());
    assertThat(nos(list(u, "status", "active", "q", tag + " 拜仁 en"))).as("英文").containsExactly(b.no());
    assertThat(nos(list(u, "status", "active", "q", tag + " 拜仁 繁"))).as("繁体").containsExactly(b.no());
    // 市场编号
    assertThat(nos(list(u, "status", "active", "q", c.no()))).containsExactly(c.no());
    // % 和 _ 是普通字符，不当通配符
    assertThat(list(u, "status", "active", "q", tag + "%").get("total").asLong()).isZero();
    assertThat(list(u, "status", "active", "q", "S11K_").get("total").asLong()).isZero();

    // 按奖池排序：拜仁 30 > 切尔西 20 > 阿森纳 15；分页
    JsonNode p1 = list(u, "status", "active", "sort", "pool", "q", tag, "pageSize", "2", "page", "1");
    assertThat(nos(p1)).containsExactly(b.no(), c.no());
    assertThat(p1.get("total").asLong()).isEqualTo(3);
    assertThat(nos(list(u, "status", "active", "sort", "pool", "q", tag, "pageSize", "2", "page", "2"))).containsExactly(a.no());
    // 关键字太长、排序不认识：一次指出
    MockHttpServletResponse bad = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/markets?sort=hot&q=" + "x".repeat(101)),
        u.cookie(), null);
    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat(bad.getContentAsString()).contains("\"field\":\"sort\"").contains("\"field\":\"keyword\"");

    // 已结算的市场在列表里带结果（另外两个市场只有一个选项有人押，截止时作废，没有结果）
    closeAndEnd(a);
    settle(a, 0);
    JsonNode ended = list(u, "status", "ended", "q", tag);
    assertThat(nos(ended)).contains(a.no(), b.no(), c.no());
    for (JsonNode i : ended.get("items")) {
      if (i.get("publicNo").asString().equals(a.no())) assertThat(i.get("winningOptionNo").asString()).isEqualTo(a.options().get(0));
      else assertThat(i.get("winningOptionNo").isNull()).as("作废的没有结果").isTrue();
    }
  }

  // =====================================================================
  // 详情的结算信息、我的下注
  // =====================================================================

  @Test
  void 详情的结算信息_我的下注的最终赔率派彩抽成和汇总() throws Exception {
    User win = user("1000");
    User lose = user("1000");
    Market m = titled("S11 结算 " + System.nanoTime(), 2);
    bet(win.id(), m, 0, "60");
    bet(win.id(), m, 0, "40");
    bet(lose.id(), m, 1, "100");
    // 还没截止：没有最终赔率、没有结果，汇总的总下注算进行中的
    JsonNode before = getJson("/api/me/bets", win.cookie());
    assertThat(before.get("items").get(0).get("finalMult").isNull()).isTrue();
    assertThat(before.get("items").get(0).get("result").isNull()).isTrue();
    assertThat(before.get("items").get(0).get("category").asString()).isEqualTo("FOOTBALL");
    assertThat(before.get("totals").toString()).isEqualTo("{\"staked\":\"100.00\",\"payout\":\"0.00\",\"net\":\"0.00\"}");
    assertThat(getJson("/api/markets/" + m.no(), win.cookie()).get("settle").isNull()).as("还没结算").isTrue();

    closeAndEnd(m);
    JsonNode pending = getJson("/api/me/bets?tab=pending", win.cookie());
    // 截止后最终赔率：1 + (200 ÷ 100 − 1) × (1 − 5%) = 1.95
    assertThat(pending.get("items").get(0).get("finalMult").asString()).isEqualTo("1.95");

    settle(m, 0);
    // 详情：结算合计（T = 200，赢方 100：G = 100、F = 5、派彩 195，尾差 0）和本人的结果
    JsonNode s = getJson("/api/markets/" + m.no(), win.cookie()).get("settle");
    assertThat(keys(s)).containsExactly("kind", "status", "feeRate", "poolTotal", "payoutTotal", "feeTotal", "dust", "winnerPositions", "loserPositions", "doneAt", "mine");
    assertThat(s.get("kind").asString()).isEqualTo("payout");
    assertThat(s.get("feeRate").asString()).isEqualTo("5");
    assertThat(s.get("poolTotal").asString()).isEqualTo("200.00");
    assertThat(s.get("payoutTotal").asString()).isEqualTo("195.00");
    assertThat(s.get("feeTotal").asString()).isEqualTo("5.00");
    assertThat(s.get("dust").asString()).isEqualTo("0.00");
    assertThat(s.get("winnerPositions").asInt()).isEqualTo(1);
    assertThat(s.get("mine").toString()).isEqualTo("{\"stake\":\"100.00\",\"payout\":\"195.00\",\"fee\":\"5.00\",\"refund\":\"0.00\",\"compensation\":\"0.00\"}");
    assertThat(getJson("/api/markets/" + m.no(), lose.cookie()).get("settle").get("mine").get("payout").asString()).isEqualTo("0.00");
    User none = user("0");
    assertThat(getJson("/api/markets/" + m.no(), none.cookie()).get("settle").get("mine").isNull()).as("没下注的客户").isTrue();

    // 我的下注：两笔下注是同一个持仓，显示持仓合计的派彩和抽成
    JsonNode settled = getJson("/api/me/bets?tab=settled", win.cookie());
    assertThat(settled.get("items").size()).isEqualTo(2);
    for (JsonNode i : settled.get("items")) {
      assertThat(i.get("finalMult").asString()).isEqualTo("1.95");
      assertThat(i.get("result").toString()).isEqualTo("{\"won\":true,\"positionAmount\":\"100.00\",\"positionBets\":2,\"payout\":\"195.00\",\"fee\":\"5.00\"}");
    }
    assertThat(getJson("/api/me/bets?tab=settled", lose.cookie()).get("items").get(0).get("result").toString())
        .isEqualTo("{\"won\":false,\"positionAmount\":\"100.00\",\"positionBets\":1,\"payout\":\"0.00\",\"fee\":\"0.00\"}");
    // 汇总：总下注 100；赢家派彩 195、净 +95；输家净 −100
    assertThat(getJson("/api/me/bets", win.cookie()).get("totals").toString()).isEqualTo("{\"staked\":\"100.00\",\"payout\":\"195.00\",\"net\":\"95.00\"}");
    assertThat(getJson("/api/me/bets", lose.cookie()).get("totals").toString()).isEqualTo("{\"staked\":\"100.00\",\"payout\":\"0.00\",\"net\":\"-100.00\"}");
    // 汇总和账本一致：派彩 = 账本 PAYOUT − FEE（客户的可用余额变动）
    assertThat(ledgerSum(m, "PAYOUT", "AVAILABLE").add(ledgerSum(m, "FEE", "AVAILABLE"))).isEqualByComparingTo("195");
    // 通知中心：结算、下注的站内信能点进这个市场（需求 P6）
    JsonNode inbox = getJson("/api/notifications?lang=zh-CN&page=1&pageSize=20", win.cookie()).get("items");
    assertThat(inbox.size()).isGreaterThanOrEqualTo(3);
    for (JsonNode i : inbox) assertThat(i.get("link").asString()).as(i.get("title").asString()).isEqualTo("/markets/" + m.no());
  }

  @Test
  void 取消的市场_详情给退款合计和本人的退款_我的下注在已退回() throws Exception {
    User u = user("1000");
    Market m = titled("S11 取消 " + System.nanoTime(), 2, Instant.now(clock).minus(Duration.ofMinutes(1)), Instant.now(clock).plus(Duration.ofDays(1)));
    bet(u.id(), m, 0, "25");
    admin.cancel(Long.parseLong(m.no()), 1, "测试取消");
    settle.drainAll();
    JsonNode s = getJson("/api/markets/" + m.no(), u.cookie()).get("settle");
    assertThat(s.get("kind").asString()).isEqualTo("refund");
    assertThat(s.get("payoutTotal").asString()).isEqualTo("25.00");
    assertThat(s.get("mine").get("refund").asString()).isEqualTo("25.00");
    JsonNode bets = getJson("/api/me/bets?tab=refunded", u.cookie());
    assertThat(bets.get("items").get(0).get("refundReason").asString()).isEqualTo("CANCELLED");
    assertThat(bets.get("items").get(0).get("finalMult").isNull()).as("退回的不显示最终赔率").isTrue();
    assertThat(getJson("/api/me/bets", u.cookie()).get("totals").get("staked").asString()).as("已退回的不算总下注").isEqualTo("0.00");
  }

  // =====================================================================
  // #23 开赛提醒
  // =====================================================================

  private Market upcoming() {
    Instant now = Instant.now(clock);
    return titled("S11 提醒 " + System.nanoTime(), 2, now.plus(Duration.ofHours(2)), now.plus(Duration.ofHours(5)));
  }

  private void loggedIn(User u) {
    jdbc.update("UPDATE account_user SET last_login_at = UTC_TIMESTAMP(3) WHERE id = ?", u.id());
  }

  private MockHttpServletResponse subscribe(User u, Market m) throws Exception {
    return call(post("/api/markets/" + m.no() + "/reminder"), u.cookie(), null);
  }

  private List<Map<String, Object>> mails(User u) {
    return jdbc.queryForList("SELECT status, error FROM notify_record WHERE user_id = ? AND template_code = 'MATCH_REMINDER' AND channel = 'EMAIL'", u.id());
  }

  private String status(User u, Market m) {
    return jdbc.queryForObject("SELECT status FROM market_reminder WHERE user_id = ? AND market_id = ?", String.class, u.id(), m.id());
  }

  @Test
  void 开赛提醒_只给登录过的客户_只能订还没开放的市场_可以退订再订() throws Exception {
    User u = user("0");
    Market m = upcoming();
    // 没有登录过的账户（SQ-20；测试客户是直接写库建的，没经过注册接口的自动登录，SQ-27）：详情里能看到"可以订阅、但你还不能订"，订阅被拒（403）
    JsonNode r0 = getJson("/api/markets/" + m.no(), u.cookie()).get("reminder");
    assertThat(r0.toString()).isEqualTo("{\"available\":true,\"eligible\":false,\"status\":\"none\"}");
    MockHttpServletResponse denied = subscribe(u, m);
    assertThat(denied.getStatus()).isEqualTo(403);
    assertThat(denied.getContentAsString()).contains("REMINDER_LOGIN_REQUIRED");
    assertThat(getJson("/api/me/reminders", u.cookie()).get("eligible").asBoolean()).isFalse();

    loggedIn(u);
    assertThat(body(subscribe(u, m)).toString()).isEqualTo("{\"available\":true,\"eligible\":true,\"status\":\"active\"}");
    assertThat(body(subscribe(u, m)).get("status").asString()).as("重复订阅没有效果").isEqualTo("active");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_reminder WHERE user_id = ?", Integer.class, u.id())).isEqualTo(1);
    JsonNode list = getJson("/api/me/reminders", u.cookie());
    assertThat(list.get("eligible").asBoolean()).isTrue();
    assertThat(keys(list.get("items").get(0))).containsExactly("marketNo", "title", "marketStatus", "openAt", "closeAt", "status", "sentAt");
    assertThat(list.get("items").get(0).get("marketNo").asString()).isEqualTo(m.no());
    assertThat(list.get("items").get(0).get("status").asString()).isEqualTo("active");

    // 退订、再订
    assertThat(body(call(delete("/api/markets/" + m.no() + "/reminder"), u.cookie(), null)).get("status").asString()).isEqualTo("cancelled");
    assertThat(getJson("/api/markets/" + m.no(), u.cookie()).get("reminder").get("status").asString()).isEqualTo("cancelled");
    assertThat(body(subscribe(u, m)).get("status").asString()).isEqualTo("active");

    // 已经开放的市场不能订；详情里不显示（没订过）
    Market open = titled("S11 已开放 " + System.nanoTime(), 2);
    MockHttpServletResponse late = subscribe(u, open);
    assertThat(late.getStatus()).isEqualTo(409);
    assertThat(late.getContentAsString()).contains("REMINDER_NOT_AVAILABLE");
    assertThat(getJson("/api/markets/" + open.no(), u.cookie()).get("reminder").isNull()).isTrue();
    // 不存在的市场
    assertThat(call(post("/api/markets/123/reminder"), u.cookie(), null).getStatus()).isEqualTo(404);
    // 没登录不能订
    assertThat(call(post("/api/markets/" + m.no() + "/reminder"), null, null).getStatus()).isEqualTo(401);
  }

  @Test
  void 开赛提醒_到开放时间发一封邮件_只发一次_链接完整_退订链接不用登录() throws Exception {
    User u = user("0");
    loggedIn(u);
    Market m = upcoming();
    subscribe(u, m);
    reminders.sendDue();
    assertThat(mails(u)).as("还没到开放时间").isEmpty();

    advance(Duration.ofHours(2));
    reminders.sendDue();
    reminders.sendDue();
    assertThat(mails(u)).as("只发一次").hasSize(1);
    assertThat(mails(u).get(0).get("status")).isEqualTo("PENDING");
    assertThat(status(u, m)).isEqualTo("SENT");
    String token = jdbc.queryForObject("SELECT token FROM market_reminder WHERE user_id = ? AND market_id = ?", String.class, u.id(), m.id());
    long record = jdbc.queryForObject("SELECT id FROM notify_record WHERE user_id = ? AND template_code = 'MATCH_REMINDER'", Long.class, u.id());
    String payload = jdbc.queryForObject("SELECT JSON_EXTRACT(payload, '$.params') FROM common_task WHERE task_type = 'MAIL_SEND' AND JSON_EXTRACT(payload, '$.recordId') = ?",
        String.class, record);
    assertThat(payload).contains("https://app.forecast.test/markets/" + m.no()).contains("https://app.forecast.test/unsubscribe?t=" + token)
        .contains("\"matchEn\": \"S11 提醒").contains("UTC");
    assertThat(getJson("/api/me/reminders", u.cookie()).get("items").get(0).get("sentAt").isNull()).isFalse();
    // 已经开放：不能再订，详情里显示"已发"
    assertThat(getJson("/api/markets/" + m.no(), u.cookie()).get("reminder").toString()).isEqualTo("{\"available\":false,\"eligible\":true,\"status\":\"sent\"}");

    // 邮件里的退订链接：不用登录；随机串不对 404
    Market next = upcoming();
    subscribe(u, next);
    String t2 = jdbc.queryForObject("SELECT token FROM market_reminder WHERE user_id = ? AND market_id = ?", String.class, u.id(), next.id());
    MockHttpServletResponse out = call(post("/api/reminders/unsubscribe"), null, "{\"token\":\"" + t2 + "\"}");
    assertThat(out.getStatus()).as(out.getContentAsString()).isEqualTo(200);
    assertThat(body(out).get("status").asString()).isEqualTo("cancelled");
    assertThat(body(out).get("marketNo").asString()).isEqualTo(next.no());
    assertThat(status(u, next)).isEqualTo("CANCELLED");
    assertThat(call(post("/api/reminders/unsubscribe"), null, "{\"token\":\"0123456789abcdef0123456789abcdef\"}").getStatus()).isEqualTo(404);
    assertThat(call(post("/api/reminders/unsubscribe"), null, "{\"token\":\"x' OR 1=1\"}").getStatus()).isEqualTo(404);
    advance(Duration.ofHours(2));
    reminders.sendDue();
    assertThat(mails(u)).as("退订的不发").hasSize(1);
  }

  @Test
  void 开赛提醒_关了邮件的记跳过_开放前市场取消的不发() throws Exception {
    User off = user("0");
    loggedIn(off);
    User other = user("0");
    loggedIn(other);
    Market m = upcoming();
    Market cancelled = upcoming();
    subscribe(off, m);
    subscribe(other, cancelled);
    jdbc.update("INSERT INTO notify_pref (user_id, push_enabled, email_enabled, lang) VALUES (?, 1, 0, 'en') ON DUPLICATE KEY UPDATE email_enabled = 0", off.id());
    admin.cancel(Long.parseLong(cancelled.no()), 1, "测试取消");
    advance(Duration.ofHours(2));
    reminders.sendDue();
    assertThat(mails(off)).hasSize(1);
    assertThat(mails(off).get(0).get("status")).as("客户关了邮件：通知记录写跳过").isEqualTo("SKIPPED");
    assertThat(status(off, m)).isEqualTo("SENT");
    assertThat(mails(other)).as("市场在开放前取消了：不发").isEmpty();
    assertThat(status(other, cancelled)).isEqualTo("MISSED");
  }
}
