package com.forecast.app.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.growth.api.WaitlistAdminApi;
import com.forecast.app.growth.waitlist.WaitlistService;
import com.forecast.app.settle.SettleTestSupport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * forecast S16 上线前等候页（MF-8；需求第 5.2 节 P13、AC-95；SQ-22：只发一封"正式上线了"，不发开赛提醒）。真实 MySQL、Redis（C37），
 * 每个测试用自己的邮箱和 IP（C80）；上线通知是全站唯一的一行，测试结束删掉，不影响别的测试。
 * <ul>
 *   <li>留邮箱 → 只发确认邮件（双重确认），回答不暴露邮箱是否留过；隐藏字段（防刷）；同一 IP 每小时上限；确认邮件间隔和封数上限</li>
 *   <li>确认（链接无效 404、过期 410、重复确认没有效果）；退订不用登录（任何状态），退订后再留邮箱要重新确认</li>
 *   <li>发送上线通知：只给已确认的、每人一封、按订阅时的语言；只能发一次（第二次 409）；重复跑任务不重发；上线以后等候页关闭</li>
 * </ul>
 */
class S16WaitlistTest extends SettleTestSupport {

  @Autowired WaitlistService waitlist;

  @AfterEach
  void clearLaunch() {
    jdbc.update("DELETE FROM growth_launch");
  }

  static String email(String tag) {
    return "wl-" + tag + "-" + System.nanoTime() + "@Example.com";
  }

  static String ip() {
    long n = System.nanoTime();
    return "198.51." + ((n >> 8) & 0xff) + "." + (n & 0xff);
  }

  private MockHttpServletResponse anon(MockHttpServletRequestBuilder b, String ipAddr, String body) throws Exception {
    b = b.with(r -> {
      r.setRemoteAddr(ipAddr);
      return r;
    });
    return call(b, null, body);
  }

  private MockHttpServletResponse subscribe(String email, String lang, String ipAddr) throws Exception {
    return anon(post("/api/public/waitlist"), ipAddr,
        "{\"email\":\"" + email + "\",\"lang\":\"" + lang + "\",\"website\":\"\",\"attribution\":{\"src\":\"x\",\"cmp\":\"wl\",\"ref\":\"r1\"}}");
  }

  /** 发给这个邮箱的某个模板的邮件：模板变量（确认、退订链接）、语言 */
  private List<Map<String, Object>> mails(String email, String template) {
    return jdbc.queryForList("SELECT JSON_UNQUOTE(JSON_EXTRACT(t.payload, '$.params.confirm')) AS confirm, JSON_UNQUOTE(JSON_EXTRACT(t.payload, '$.params.unsubscribe')) AS unsub, "
        + "JSON_UNQUOTE(JSON_EXTRACT(t.payload, '$.params.link')) AS link, JSON_UNQUOTE(JSON_EXTRACT(t.payload, '$.lang')) AS lang FROM notify_record r "
        + "JOIN common_task t ON JSON_EXTRACT(t.payload, '$.recordId') = r.id WHERE r.recipient = ? AND r.template_code = ? ORDER BY r.id", email.toLowerCase(), template);
  }

  private static String token(Object link) {
    String s = String.valueOf(link);
    return s.substring(s.indexOf("t=") + 2);
  }

  private String status(String email) {
    return jdbc.queryForObject("SELECT status FROM growth_waitlist WHERE email = ?", String.class, email.toLowerCase());
  }

  private MockHttpServletResponse confirm(String token) throws Exception {
    return anon(post("/api/public/waitlist/confirm"), ip(), "{\"token\":\"" + token + "\"}");
  }

  private MockHttpServletResponse unsubscribe(String token) throws Exception {
    return anon(post("/api/public/waitlist/unsubscribe"), ip(), "{\"token\":\"" + token + "\"}");
  }

  @Test
  void AC95_留邮箱只发确认邮件_确认以后才算订阅_回答不暴露邮箱是否留过_来源只记统计() throws Exception {
    String e = email("a");
    String addr = ip();
    MockHttpServletResponse r = subscribe(e, "zh-CN", addr);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(r.getContentAsString()).isEqualTo("{\"status\":\"check_email\"}");
    assertThat(status(e)).isEqualTo("PENDING");
    List<Map<String, Object>> m = mails(e, "WAITLIST_CONFIRM");
    assertThat(m).hasSize(1);
    assertThat(m.get(0).get("lang")).isEqualTo("zh-CN");
    assertThat(String.valueOf(m.get(0).get("confirm"))).matches("https://app\\.forecast\\.test/data/confirm\\?t=[0-9a-f]{32}");
    assertThat(String.valueOf(m.get(0).get("unsub"))).matches("https://app\\.forecast\\.test/data/unsubscribe\\?t=[0-9a-f]{32}");
    assertThat(jdbc.queryForMap("SELECT src, cmp, ref, LENGTH(ip_hash) AS h FROM growth_waitlist WHERE email = ?", e.toLowerCase()))
        .containsEntry("src", "x").containsEntry("cmp", "wl").containsEntry("ref", "r1").containsEntry("h", 64L);
    // 马上再留一次：同样的回答，不再发（10 分钟以内）
    assertThat(subscribe(e, "zh-CN", addr).getContentAsString()).isEqualTo("{\"status\":\"check_email\"}");
    assertThat(mails(e, "WAITLIST_CONFIRM")).hasSize(1);
    // 没确认之前不是订阅：确认
    MockHttpServletResponse c = confirm(token(m.get(0).get("confirm")));
    assertThat(c.getStatus()).isEqualTo(200);
    assertThat(body(c).get("status").asString()).isEqualTo("confirmed");
    assertThat(status(e)).isEqualTo("CONFIRMED");
    assertThat(body(confirm(token(m.get(0).get("confirm")))).get("status").asString()).as("重复确认没有效果").isEqualTo("confirmed");
    // 已确认的邮箱再留：同样的回答，什么都不发
    assertThat(subscribe(e, "en", addr).getContentAsString()).isEqualTo("{\"status\":\"check_email\"}");
    assertThat(mails(e, "WAITLIST_CONFIRM")).hasSize(1);
    // 链接无效
    assertThat(confirm("0123456789abcdef0123456789abcdef").getStatus()).isEqualTo(404);
    assertThat(confirm("not-a-token").getStatus()).isEqualTo(404);
  }

  @Test
  void 防刷_隐藏字段有值不处理_邮箱格式_同一IP每小时上限() throws Exception {
    String addr = ip();
    String bot = email("bot");
    MockHttpServletResponse r = anon(post("/api/public/waitlist"), addr, "{\"email\":\"" + bot + "\",\"lang\":\"en\",\"website\":\"http://spam\"}");
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM growth_waitlist WHERE email = ?", Integer.class, bot.toLowerCase())).as("隐藏字段有值：不记、不发").isZero();
    assertThat(anon(post("/api/public/waitlist"), addr, "{\"email\":\"not-an-email\",\"lang\":\"en\"}").getContentAsString()).contains("EMAIL_INVALID");
    // 同一个 IP：一小时 10 次（含上面那一次隐藏字段的），第 11 次 429
    for (int i = 0; i < 9; i++) assertThat(subscribe(email("ip" + i), "en", addr).getStatus()).isEqualTo(200);
    MockHttpServletResponse limited = subscribe(email("ip-over"), "en", addr);
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getContentAsString()).contains("RATE_LIMITED");
    assertThat(subscribe(email("other-ip"), "en", ip()).getStatus()).as("别的 IP 不受影响").isEqualTo(200);
  }

  @Test
  void 确认邮件_至少隔10分钟_最多5封_链接7天有效() throws Exception {
    String e = email("resend");
    subscribe(e, "en", ip());
    for (int i = 0; i < 6; i++) {
      advance(Duration.ofMinutes(11));
      subscribe(e, "en", ip());
    }
    List<Map<String, Object>> m = mails(e, "WAITLIST_CONFIRM");
    assertThat(m).as("最多 5 封").hasSize(5);
    assertThat(m.stream().map(x -> token(x.get("confirm"))).distinct().count()).as("同一个确认链接").isEqualTo(1);
    // 最近一封之后 7 天多：过期 410
    advance(Duration.ofDays(7).plusMinutes(1));
    MockHttpServletResponse c = confirm(token(m.get(0).get("confirm")));
    assertThat(c.getStatus()).isEqualTo(410);
    assertThat(c.getContentAsString()).contains("WAITLIST_LINK_EXPIRED");
    assertThat(status(e)).isEqualTo("PENDING");
  }

  @Test
  void 退订不用登录_任何状态都能退_退订后再留要重新确认() throws Exception {
    String e = email("unsub");
    subscribe(e, "zh-TW", ip());
    Map<String, Object> m = mails(e, "WAITLIST_CONFIRM").get(0);
    confirm(token(m.get("confirm")));
    MockHttpServletResponse u = unsubscribe(token(m.get("unsub")));
    assertThat(u.getStatus()).isEqualTo(200);
    assertThat(body(u).get("status").asString()).isEqualTo("unsubscribed");
    assertThat(body(u).get("lang").asString()).isEqualTo("zh-TW");
    assertThat(status(e)).isEqualTo("UNSUBSCRIBED");
    assertThat(unsubscribe(token(m.get("unsub"))).getStatus()).as("重复退订没有效果").isEqualTo(200);
    assertThat(unsubscribe("0123456789abcdef0123456789abcdef").getStatus()).isEqualTo(404);
    // 原来的确认链接：已退订
    assertThat(body(confirm(token(m.get("confirm")))).get("status").asString()).isEqualTo("unsubscribed");
    // 10 分钟后再留：回到待确认，新的确认链接
    advance(Duration.ofMinutes(11));
    subscribe(e, "zh-TW", ip());
    assertThat(status(e)).isEqualTo("PENDING");
    List<Map<String, Object>> all = mails(e, "WAITLIST_CONFIRM");
    assertThat(all).hasSize(2);
    assertThat(token(all.get(1).get("confirm"))).isNotEqualTo(token(m.get("confirm")));
  }

  @Test
  void SQ22_上线通知_只给已确认的_每人一封_按订阅时的语言_只能发一次_重复跑不重发_之后等候页关闭() throws Exception {
    String zh = email("zh");
    String en = email("en");
    String pending = email("pending");
    String gone = email("gone");
    for (String[] x : new String[][] {{zh, "zh-CN"}, {en, "en"}, {pending, "en"}, {gone, "en"}}) subscribe(x[0], x[1], ip());
    for (String x : List.of(zh, en, gone)) confirm(token(mails(x, "WAITLIST_CONFIRM").get(0).get("confirm")));
    unsubscribe(token(mails(gone, "WAITLIST_CONFIRM").get(0).get("unsub")));
    assertThat(waitlist.launched()).isFalse();
    long confirmed = jdbc.queryForObject("SELECT COUNT(*) FROM growth_waitlist WHERE status = 'CONFIRMED'", Long.class);

    WaitlistAdminApi.Launch l = waitlist.startLaunch(1, "正式上线");
    assertThat(l.status()).isEqualTo("sending");
    assertThat(l.recipients()).isEqualTo((int) confirmed);
    assertThat(waitlist.launched()).isTrue();
    try {
      waitlist.startLaunch(1, "再按一次");
      throw new AssertionError("第二次应该失败");
    } catch (com.forecast.app.common.error.BizException ex) {
      assertThat(ex.code()).isEqualTo("LAUNCH_ALREADY_SENT");
    }
    // 上线以后等候页关闭：不收邮箱、没确认的链接也不再生效
    MockHttpServletResponse closed = subscribe(email("late"), "en", ip());
    assertThat(closed.getStatus()).isEqualTo(409);
    assertThat(closed.getContentAsString()).contains("WAITLIST_CLOSED");
    assertThat(confirm(token(mails(pending, "WAITLIST_CONFIRM").get(0).get("confirm"))).getStatus()).isEqualTo(409);

    assertThat(waitlist.sendLaunchBatch()).isEqualTo((int) confirmed);
    assertThat(waitlist.sendLaunchBatch()).as("再跑一轮不重发").isZero();
    assertThat(mails(zh, "WAITLIST_LAUNCH")).hasSize(1);
    assertThat(mails(zh, "WAITLIST_LAUNCH").get(0).get("lang")).isEqualTo("zh-CN");
    assertThat(String.valueOf(mails(zh, "WAITLIST_LAUNCH").get(0).get("link"))).isEqualTo("https://app.forecast.test/markets?src=waitlist&cmp=launch");
    assertThat(mails(en, "WAITLIST_LAUNCH")).hasSize(1);
    assertThat(mails(en, "WAITLIST_LAUNCH").get(0).get("lang")).isEqualTo("en");
    assertThat(mails(pending, "WAITLIST_LAUNCH")).as("没确认的不发").isEmpty();
    assertThat(mails(gone, "WAITLIST_LAUNCH")).as("退订的不发").isEmpty();
    assertThat(status(zh)).isEqualTo("NOTIFIED");
    WaitlistAdminApi.Launch done = waitlist.launch();
    assertThat(done.status()).isEqualTo("done");
    assertThat(done.sent()).isEqualTo((int) confirmed);
    // 没有开赛提醒之类的其他邮件（SQ-22）
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE recipient = ? AND template_code NOT IN ('WAITLIST_CONFIRM', 'WAITLIST_LAUNCH')",
        Integer.class, zh.toLowerCase())).isZero();
    // 上线通知里也有退订链接，已通知的也能退订
    assertThat(body(unsubscribe(token(mails(en, "WAITLIST_CONFIRM").get(0).get("unsub")))).get("status").asString()).isEqualTo("unsubscribed");
    assertThat(status(en)).isEqualTo("UNSUBSCRIBED");
  }

  @Test
  void 后台列表_邮箱打码_各状态数量() throws Exception {
    String e = email("list");
    subscribe(e, "en", ip());
    WaitlistAdminApi.Page p = waitlist.list("pending", 1, 100);
    assertThat(p.items()).anySatisfy(i -> {
      assertThat(i.email()).isEqualTo("w***@example.com");
      assertThat(i.status()).isEqualTo("pending");
      assertThat(i.src()).isEqualTo("x");
    });
    assertThat(p.items()).noneMatch(i -> i.email().contains(e.toLowerCase().substring(1, 10)));
    assertThat(p.counts().pending()).isPositive();
    assertThat(p.launch().status()).isEqualTo("none");
  }

  @Test
  void 两封邮件模板_三种语言都有_没有诱导收益的说法_繁体没有简体字() {
    // 和 S9NotifyFlowTest.BANNED 同一份名单（市场策略 F-12、F-15）
    List<String> banned = List.of("稳赚", "必中", "保本", "翻倍", "胜者瓜分", "最后入场", "别错过", "秒出", "全在链上", "链上公开", "私房钱", "恭喜", "穩賺", "保證", "勝者瓜分",
        "最後入場", "別錯過", "guaranteed", "risk-free", "don't miss", "last chance", "congratulations", "double your");
    for (String code : List.of("WAITLIST_CONFIRM", "WAITLIST_LAUNCH")) {
      Map<String, Object> row = jdbc.queryForMap("SELECT * FROM notify_template WHERE code = ?", code);
      assertThat(row.get("audience")).isEqualTo("ANY");
      for (String col : List.of("title_zh", "body_zh", "title_en", "body_en", "title_tw", "body_tw")) {
        String text = String.valueOf(row.get(col));
        assertThat(text).as(code + " " + col).isNotBlank();
        for (String b : banned) assertThat(text.toLowerCase()).as(code + " " + col + " " + b).doesNotContain(b.toLowerCase());
      }
      assertThat(String.valueOf(row.get("body_zh"))).contains("{unsubscribe}");
      assertThat(String.valueOf(row.get("body_en"))).contains("{unsubscribe}");
      assertThat(String.valueOf(row.get("body_tw"))).contains("{unsubscribe}");
      String tw = row.get("title_tw") + " " + row.get("body_tw");
      for (String simp : List.of("币", "账", "认证", "结算", "费", "这", "说", "们", "现", "额", "单", "后台", "邮")) assertThat(tw).as(code + " 繁体 " + simp).doesNotContain(simp);
    }
  }
}
