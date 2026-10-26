package com.forecast.app.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.growth.api.TelegramAdminApi;
import com.forecast.app.growth.board.BoardProperties;
import com.forecast.app.growth.board.BtcBoard;
import com.forecast.app.growth.telegram.TelegramBot;
import com.forecast.app.growth.telegram.TelegramClient;
import com.forecast.app.growth.telegram.TelegramProperties;
import com.forecast.app.growth.telegram.TelegramPublisher;
import com.forecast.app.market.api.PublicMarketsApi;
import com.forecast.app.settle.SettleTestSupport;
import com.forecast.app.support.StubHttp;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * forecast S16 官方 Telegram 机器人（MF-7；需求第 6.3 节、AC-89、AC-90～AC-92 里和开奖卡有关的部分；SQ-22）。Telegram 用本机的 HTTP 替身（StubHttp），
 * 不连真实的 Telegram。频道、群、模板、紧急停止是全局状态：每个测试结束复原。
 * <ul>
 *   <li>没有 token：机器人不取消息、不发帖、不调用 Telegram；健康检查 alerts 提示"未配置"，status 仍是 UP</li>
 *   <li>命令：/btc（BTC 现价、每小时涨跌、当前这场 BTC 市场的概率和链接）、/today、/start 参数记来源（MF-1）；没有下注</li>
 *   <li>群：拉进群登记；只有群管理员能开启 / 关闭播报</li>
 *   <li>开奖卡自动发：模板草稿不发（记原因）；审定 + 开关打开后发到频道和开了播报的群、每个渠道一次；没有"今日场次"标记的不发；
 *       紧急停止记"已停止"；模板文字改了按草稿处理；429 按 retry_after 推迟、不算失败；连续 3 次失败自动关频道并邮件</li>
 *   <li>客户端：5xx 重试、熔断、不在事务里调用、错误信息里没有 token</li>
 * </ul>
 */
class S16TelegramTest extends SettleTestSupport {

  @Autowired TelegramProperties props;
  @Autowired TelegramBot bot;
  @Autowired TelegramPublisher publisher;
  @Autowired TelegramClient client;
  @Autowired TelegramAdminApi tgAdmin;
  @Autowired PublicMarketsApi markets;
  @Autowired BtcBoard board;
  @Autowired BoardProperties boardProps;
  @Autowired List<AlertCheck> alerts;

  StubHttp stub;
  /** 替身要回给 getUpdates 的消息（取一次就清空） */
  final List<String> updates = new CopyOnWriteArrayList<>();
  long nextUpdate = System.nanoTime() / 1000;
  /** 替身对 sendPhoto 的回答（默认成功） */
  volatile Function<URI, StubHttp.Reply> photoReply = u -> StubHttp.Reply.ok("{\"ok\":true,\"result\":{\"message_id\":77,\"chat\":{\"id\":-1001}}}");
  final List<String> admins = new CopyOnWriteArrayList<>();

  @BeforeEach
  void stub() {
    stub = new StubHttp();
    stub.handle(u -> {
      String path = u.getPath();
      String method = path.substring(path.lastIndexOf('/') + 1);
      return switch (method) {
        case "getMe" -> StubHttp.Reply.ok("{\"ok\":true,\"result\":{\"id\":777,\"is_bot\":true,\"username\":\"fc_test_bot\"}}");
        case "getUpdates" -> {
          String r = "{\"ok\":true,\"result\":[" + String.join(",", updates) + "]}";
          updates.clear();
          yield StubHttp.Reply.ok(r);
        }
        case "sendMessage" -> StubHttp.Reply.ok("{\"ok\":true,\"result\":{\"message_id\":5,\"chat\":{\"id\":1}}}");
        case "sendPhoto" -> photoReply.apply(u);
        case "getChatMember" -> StubHttp.Reply.ok("{\"ok\":true,\"result\":{\"status\":\"" + (admins.isEmpty() ? "member" : "administrator") + "\"}}");
        default -> StubHttp.Reply.status(404, "{\"ok\":false,\"error_code\":404,\"description\":\"Not Found\"}");
      };
    });
    props.setApiUrl(stub.url());
    props.setChatInterval(Duration.ZERO);
    props.setSendInterval(Duration.ZERO);
  }

  @AfterEach
  void restore() {
    stub.close();
    props.setApiUrl("http://127.0.0.1:9");
    props.setToken("ci_only_test_telegram");
    jdbc.update("UPDATE growth_tg_target SET enabled = 0, chat_id = NULL, fail_streak = 0, disabled_reason = NULL, enabled_at = NULL");
    jdbc.update("UPDATE growth_tg_template SET status = 'DRAFT', fingerprint = NULL");
    jdbc.update("UPDATE growth_tg_state SET emergency_stop = 0");
    jdbc.update("UPDATE growth_tg_chat SET broadcast = 0");
    // 测试里留下的待发记录不让下一个测试去发
    jdbc.update("UPDATE growth_tg_post SET status = 'SKIPPED', note = '测试结束' WHERE status = 'PENDING'");
  }

  // ---------------------------------------------------------------------

  private void update(String chatJson, String text, long fromId, String lang, String extra) {
    long id = nextUpdate++;
    updates.add("{\"update_id\":" + id + ",\"message\":{\"message_id\":1,\"chat\":" + chatJson + ",\"from\":{\"id\":" + fromId + ",\"is_bot\":false"
        + (lang == null ? "" : ",\"language_code\":\"" + lang + "\"") + "},\"text\":\"" + text + "\"" + (extra == null ? "" : "," + extra) + "}}");
  }

  private static String privateChat(long id) {
    return "{\"id\":" + id + ",\"type\":\"private\"}";
  }

  private static String group(long id) {
    return "{\"id\":" + id + ",\"type\":\"supergroup\",\"title\":\"测试群 " + id + "\"}";
  }

  /** 机器人发出去的 sendMessage 的文字（按顺序） */
  private List<String> replies() {
    List<String> out = new ArrayList<>();
    List<URI> rs = stub.requests();
    for (int i = 0; i < rs.size(); i++) {
      if (!rs.get(i).getPath().endsWith("/sendMessage")) continue;
      out.add(json.readTree(stub.bodyText(i)).path("text").asString());
    }
    return out;
  }

  /** sendPhoto 的 multipart 请求体（按顺序） */
  private List<String> photos() {
    List<String> out = new ArrayList<>();
    List<URI> rs = stub.requests();
    for (int i = 0; i < rs.size(); i++) if (rs.get(i).getPath().endsWith("/sendPhoto")) out.add(stub.bodyText(i));
    return out;
  }

  /** 一个带"今日场次"标记、有下注的市场，结算掉；返回市场 */
  private Market endedSlateMarket(boolean slate) throws Exception {
    Market m = shortMarket(2);
    if (slate) jdbc.update("UPDATE market_question SET today_slate = 1 WHERE id = ?", m.id());
    User a = user("1000");
    User b = user("1000");
    bet(a.id(), m, 0, "30");
    bet(b.id(), m, 1, "20");
    closeAndEnd(m);
    settle(m, 0);
    return m;
  }

  private void approve(String lang) {
    TelegramAdminApi.Preview p = tgAdmin.preview("RESULT", lang).orElseThrow();
    tgAdmin.approve("RESULT", lang, p.fingerprint(), 1);
  }

  private List<Map<String, Object>> posts(Market m) {
    return jdbc.queryForList("SELECT target, status, attempts, note, post_url, lang FROM growth_tg_post WHERE market_no = ? ORDER BY target", Long.parseLong(m.no()));
  }

  // =====================================================================

  @Test
  void 没有token_机器人不启动_不调用Telegram_健康检查只在alerts里提示_status仍是UP() throws Exception {
    props.setToken("");
    update(privateChat(1), "/btc", 1, "zh-hans", null);
    assertThat(bot.pollOnce(Duration.ZERO)).isZero();
    assertThat(publisher.publishDue()).isZero();
    assertThat(stub.hits()).as("一次都没有调用 Telegram").isZero();
    // 健康检查的告警项：后端测试里 alert-enabled 关着（告警项的内容在 telegram/TelegramAlertTest 里测）
    assertThat(alerts.stream().flatMap(a -> a.evaluate().stream()).filter(i -> i.name().equals("telegram.bot")).findFirst()).isEmpty();
    // 应用照常：健康检查 status 是 UP
    assertThat(body(call(get("/api/health"), null, null)).get("status").asString()).isEqualTo("UP");
    // 后台能看到"没有配置"
    assertThat(tgAdmin.overview().status().configured()).isFalse();
  }

  @Test
  void AC89_btc命令_回BTC现价和每小时涨跌_当前这场BTC市场的概率和链接_没有下注() throws Exception {
    // 行情：先让数据页取一次（替身）
    try (StubHttp binance = new StubHttp()) {
      Instant hour = Instant.now(clock).truncatedTo(ChronoUnit.HOURS);
      StringBuilder k = new StringBuilder("[");
      for (int i = 3; i >= 1; i--) {
        long o = hour.minus(i, ChronoUnit.HOURS).toEpochMilli();
        k.append("[").append(o).append(",\"100\",\"0\",\"0\",\"").append(i == 2 ? "99" : "101").append("\",\"1\",").append(o + 3_599_999).append(",\"1\",1,\"0\",\"0\",\"0\"],");
      }
      k.append("[").append(hour.toEpochMilli()).append(",\"100\",\"0\",\"0\",\"67000.5\",\"1\",").append(hour.toEpochMilli() + 3_599_999).append(",\"1\",1,\"0\",\"0\",\"0\"]]");
      binance.handle(u -> StubHttp.Reply.ok(k.toString()));
      boardProps.setBinanceUrl(binance.url());
      boardProps.setHours(3);
      assertThat(board.refresh()).isTrue();
    } finally {
      boardProps.setBinanceUrl("http://127.0.0.1:9");
      boardProps.setHours(24);
    }
    // 一个 BTC 自动生成模板下的市场（默认模板 BTCUSDT）
    Market m = market(2, Instant.now(clock).plus(Duration.ofMinutes(30)), null);
    jdbc.update("UPDATE market_question SET autogen_id = (SELECT id FROM market_autogen WHERE symbol = 'BTCUSDT' ORDER BY id LIMIT 1) WHERE id = ?", m.id());
    bet(user("1000").id(), m, 0, "30");
    bet(user("1000").id(), m, 1, "10");
    PublicMarketsApi.MarketStat current = markets.currentAutogen("BTCUSDT").orElseThrow();

    update(privateChat(501), "/btc", 501, "zh-hans", null);
    assertThat(bot.pollOnce(Duration.ZERO)).isEqualTo(1);
    List<String> r = replies();
    assertThat(r).hasSize(1);
    String text = r.get(0);
    assertThat(text).contains("BTC 现价 67000.50 USDT").contains("过去 3 小时：2 小时涨、1 小时跌（涨 66.7%）")
        .contains("当前场次：").contains("/markets/" + current.publicNo() + "?src=telegram&cmp=bot")
        .contains(current.options().get(0).pct()).contains("概率随奖池变化");
    if (current.publicNo() == Long.parseLong(m.no())) assertThat(text).contains("75.0%").contains("25.0%");
    assertThat(text).doesNotContainIgnoringCase("bet now").doesNotContain("下注吧");
    // 已处理的消息不再取（offset 往前推了）
    assertThat(jdbc.queryForObject("SELECT update_offset FROM growth_tg_state WHERE id = 1", Long.class)).isEqualTo(nextUpdate);
    // 英文
    update(privateChat(502), "/btc@fc_test_bot", 502, "en", null);
    bot.pollOnce(Duration.ZERO);
    assertThat(replies().get(1)).contains("BTC 67000.50 USDT").contains("Last 3 hours: 2 up, 1 down (66.7% up)");
    // 给别的机器人的命令不理
    update(group(-100501), "/btc@other_bot", 503, "en", null);
    bot.pollOnce(Duration.ZERO);
    assertThat(replies()).hasSize(2);
  }

  @Test
  void MF1_start参数记来源_today命令_未知命令私聊回说明() throws Exception {
    update(privateChat(601), "/start s-tg__c-w1", 601, "zh-hans", null);
    update(privateChat(601), "/today", 601, "zh-hans", null);
    update(privateChat(601), "/whatever", 601, "zh-hans", null);
    update(privateChat(601), "hello", 601, "zh-hans", null);
    assertThat(bot.pollOnce(Duration.ZERO)).isEqualTo(4);
    List<String> r = replies();
    assertThat(r).hasSize(3);
    assertThat(r.get(0)).contains("官方机器人").contains("不能在 Telegram 里下注").contains("?src=telegram&cmp=bot&start=s-tg__c-w1");
    assertThat(r.get(1)).containsAnyOf("今天的场次", "今天没有场次");
    assertThat(r.get(2)).contains("/btc");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM growth_visit WHERE src = 'telegram' AND cmp = 'bot' AND start_param = 's-tg__c-w1'", Integer.class))
        .isPositive();
  }

  @Test
  void 群_拉进群登记_只有管理员能开关播报() throws Exception {
    long g = -1000000000000L - (System.nanoTime() % 1_000_000);
    updates.add("{\"update_id\":" + (nextUpdate++) + ",\"my_chat_member\":{\"chat\":" + group(g)
        + ",\"new_chat_member\":{\"status\":\"member\",\"user\":{\"id\":777,\"is_bot\":true}}}}");
    update(group(g), "/broadcast_on", 11, "zh-hans", null);
    bot.pollOnce(Duration.ZERO);
    assertThat(jdbc.queryForMap("SELECT status, broadcast FROM growth_tg_chat WHERE chat_id = ?", g)).containsEntry("status", "ACTIVE").containsEntry("broadcast", false);
    assertThat(replies().get(0)).contains("只有群管理员");
    admins.add("x");
    update(group(g), "/broadcast_on en", 11, "zh-hans", null);
    bot.pollOnce(Duration.ZERO);
    assertThat(jdbc.queryForMap("SELECT broadcast, lang FROM growth_tg_chat WHERE chat_id = ?", g)).containsEntry("broadcast", true).containsEntry("lang", "en");
    assertThat(replies().get(1)).contains("Result broadcast is on (English)");
    update(group(g), "/broadcast_off", 11, "zh-hans", null);
    bot.pollOnce(Duration.ZERO);
    assertThat(jdbc.queryForObject("SELECT broadcast FROM growth_tg_chat WHERE chat_id = ?", Boolean.class, g)).isFalse();
    // 被移出群
    updates.add("{\"update_id\":" + (nextUpdate++) + ",\"my_chat_member\":{\"chat\":" + group(g)
        + ",\"new_chat_member\":{\"status\":\"kicked\",\"user\":{\"id\":777,\"is_bot\":true}}}}");
    bot.pollOnce(Duration.ZERO);
    assertThat(jdbc.queryForObject("SELECT status FROM growth_tg_chat WHERE chat_id = ?", String.class, g)).isEqualTo("LEFT");
    // 私聊里不能用
    update(privateChat(12), "/broadcast_on", 12, "en", null);
    bot.pollOnce(Duration.ZERO);
    assertThat(replies().get(replies().size() - 1)).contains("only works in groups");
  }

  @Test
  void AC90_开奖卡_模板草稿不发_审定并打开开关后发到频道和群_每个渠道一次_没有标记的不发() throws Exception {
    tgAdmin.updateTarget("CHANNEL_ZH", "@fc_test_cn", true, 1);
    tgAdmin.updateTarget("CHANNEL_EN", "-1009876543210", true, 1);
    long g = -2000000000000L - (System.nanoTime() % 1_000_000);
    jdbc.update("INSERT INTO growth_tg_chat (chat_id, chat_type, title, status, broadcast, broadcast_at, lang, fail_streak, added_at, updated_at) "
        + "VALUES (?, 'supergroup', 'g', 'ACTIVE', 1, ?, 'zh-CN', 0, ?, ?)", g, utcNow(), utcNow(), utcNow());
    advance(Duration.ofMinutes(1));
    // 模板还是草稿：不发，记原因
    Market draft = endedSlateMarket(true);
    publisher.publishDue();
    assertThat(posts(draft)).hasSize(3).allSatisfy(p -> {
      assertThat(p.get("status")).isEqualTo("SKIPPED");
      assertThat(String.valueOf(p.get("note"))).contains("草稿");
    });
    assertThat(photos()).isEmpty();
    // 审定中英文，之后结束的市场发出去
    approve("zh-CN");
    approve("en");
    Market m = endedSlateMarket(true);
    Market noFlag = endedSlateMarket(false);
    publisher.publishDue();
    List<Map<String, Object>> ps = posts(m);
    assertThat(ps).extracting(p -> p.get("target") + " " + p.get("status") + " " + p.get("lang"))
        .containsExactly("CHANNEL_EN SENT en", "CHANNEL_ZH SENT zh-CN", "G:" + g + " SENT zh-CN");
    assertThat(ps.get(1).get("post_url")).isEqualTo("https://t.me/fc_test_cn/77");
    assertThat(ps.get(0).get("post_url")).as("用数字编号填的频道没有公开链接").isNull();
    assertThat(posts(noFlag)).as("没有今日场次标记的一张都不发").isEmpty();
    List<String> mine = photos().stream().filter(b -> b.contains("/markets/" + m.no() + "?")).toList();
    assertThat(mine).hasSize(3);
    assertThat(mine).anySatisfy(b -> assertThat(b).contains("name=\"chat_id\"\r\n\r\n@fc_test_cn").contains("结果：")
        .contains("?src=telegram&cmp=channel&ref=zh").contains("Content-Type: image/png"));
    assertThat(mine).anySatisfy(b -> assertThat(b).contains("-1009876543210").contains("Result:").contains("cmp=channel&ref=en").doesNotContain("结果"));
    assertThat(mine).anySatisfy(b -> assertThat(b).contains("cmp=group&ref=" + g));
    // 再跑一轮不重发
    int before = photos().size();
    publisher.publishDue();
    assertThat(photos()).hasSize(before);
  }

  @Test
  void AC92_紧急停止记已停止_模板文字改了按草稿处理_429推迟不算失败_连续3次失败自动关频道并邮件() throws Exception {
    tgAdmin.updateTarget("CHANNEL_ZH", "@fc_test_cn2", true, 1);
    advance(Duration.ofMinutes(1));
    // 紧急停止
    tgAdmin.setStop(true, "测试停止", 1);
    Market stopped = endedSlateMarket(true);
    publisher.publishDue();
    assertThat(posts(stopped)).extracting(p -> p.get("status")).containsExactly("STOPPED");
    tgAdmin.setStop(false, "恢复", 1);
    approve("zh-CN");
    // 模板文字审定以后改过（指纹不同）：不发
    jdbc.update("UPDATE growth_tg_template SET fingerprint = REPEAT('0', 64) WHERE kind = 'RESULT' AND lang = 'zh-CN'");
    clearInvocations(staff);
    Market changed = endedSlateMarket(true);
    publisher.publishDue();
    assertThat(posts(changed)).extracting(p -> String.valueOf(p.get("note"))).allMatch(n -> n.contains("重新审定"));
    verify(staff, atLeastOnce()).notifyStaff(eq("TELEGRAM_ALERT"), anyMap());
    assertThat(tgAdmin.overview().templates()).anySatisfy(t -> {
      assertThat(t.lang()).isEqualTo("zh-CN");
      assertThat(t.changed()).isTrue();
    });
    approve("zh-CN");
    // 429：推迟到 retry_after 之后，不算一次失败
    photoReply = u -> StubHttp.Reply.status(429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 30\",\"parameters\":{\"retry_after\":30}}");
    Market limited = endedSlateMarket(true);
    publisher.publishDue();
    Map<String, Object> p = posts(limited).get(0);
    assertThat(p.get("status")).isEqualTo("PENDING");
    assertThat(p.get("attempts")).isEqualTo(0);
    assertThat(jdbc.queryForObject("SELECT fail_streak FROM growth_tg_target WHERE target_key = 'CHANNEL_ZH'", Integer.class)).isZero();
    // 之后一直 500：每次都重试，第 1、2 次失败留在待发，第 3 次失败 FAILED；连续 3 次失败后频道自动关掉、邮件通知。
    // 库是几个测试类共用的：别的测试结束的"今日场次"市场也会排进这个频道，先把它们记成没发，只看这一张
    jdbc.update("UPDATE growth_tg_post SET status = 'SKIPPED', note = '测试：只看这一张' WHERE status = 'PENDING' AND market_no <> ?", Long.parseLong(limited.no()));
    photoReply = u -> StubHttp.Reply.status(500, "{\"ok\":false,\"error_code\":500,\"description\":\"Internal\"}");
    clearInvocations(staff);
    for (int i = 0; i < 3; i++) {
      advance(Duration.ofMinutes(16));
      publisher.publishDue();
    }
    assertThat(posts(limited).get(0)).containsEntry("status", "FAILED").containsEntry("attempts", 3);
    Map<String, Object> t = jdbc.queryForMap("SELECT enabled, fail_streak, disabled_reason FROM growth_tg_target WHERE target_key = 'CHANNEL_ZH'");
    assertThat(t.get("enabled")).isEqualTo(false);
    assertThat(String.valueOf(t.get("disabled_reason"))).contains("连续 3 次发送失败");
    verify(staff, atLeastOnce()).notifyStaff(eq("TELEGRAM_ALERT"), anyMap());
    // 错误信息里没有 token
    assertThat(String.valueOf(posts(limited).get(0).get("note"))).doesNotContain("ci_only_test_telegram");
  }

  @Test
  void C81_客户端_5xx重试后成功_连续失败熔断_不能在事务里调用_错误里没有token() throws Exception {
    int[] n = {0};
    stub.handle(u -> ++n[0] <= 2 ? StubHttp.Reply.status(502, "bad") : StubHttp.Reply.ok("{\"ok\":true,\"result\":{\"username\":\"x\"}}"));
    assertThat(client.call("getMe", Map.of()).path("username").asString()).isEqualTo("x");
    assertThat(n[0]).isEqualTo(3);
    assertThat(stub.requests().get(0).getPath()).isEqualTo("/botci_only_test_telegram/getMe");
    // 一直 500：重试完失败；连续 5 次失败后熔断
    stub.handle(u -> StubHttp.Reply.status(500, "{\"ok\":false,\"description\":\"boom\"}"));
    props.setRetries(0);
    try {
      for (int i = 0; i < 5; i++) {
        try {
          client.call("getMe", Map.of());
        } catch (TelegramClient.Failure f) {
          assertThat(f.kind()).isEqualTo(TelegramClient.Kind.UNAVAILABLE);
          assertThat(f.getMessage()).doesNotContain("ci_only_test_telegram");
        }
      }
      int hits = stub.hits();
      try {
        client.call("getMe", Map.of());
        throw new AssertionError("应该熔断");
      } catch (TelegramClient.Failure f) {
        assertThat(f.kind()).isEqualTo(TelegramClient.Kind.CIRCUIT_OPEN);
      }
      assertThat(stub.hits()).isEqualTo(hits);
      assertThat(tgAdmin.overview().status().healthy()).isFalse();
    } finally {
      props.setRetries(2);
      advance(Duration.ofMinutes(2));
      stub.handle(u -> StubHttp.Reply.ok("{\"ok\":true,\"result\":{}}"));
      client.call("getMe", Map.of());
    }
    // 4xx 不重试
    stub.clear();
    stub.handle(u -> StubHttp.Reply.status(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}"));
    try {
      client.call("sendMessage", Map.of("chat_id", 1, "text", "x"));
    } catch (TelegramClient.Failure f) {
      assertThat(f.kind()).isEqualTo(TelegramClient.Kind.REJECTED);
      assertThat(f.getMessage()).contains("chat not found");
    }
    assertThat(stub.hits()).isEqualTo(1);
    // 不能在事务里调用
    org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(
        applicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    try {
      tx.executeWithoutResult(st -> {
        jdbc.queryForObject("SELECT 1", Integer.class);
        try {
          client.call("getMe", Map.of());
        } catch (TelegramClient.Failure f) {
          throw new AssertionError(f);
        }
      });
      throw new AssertionError("应该拒绝");
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).contains("不能在数据库事务里调用 Telegram");
    }
  }

  @Autowired org.springframework.context.ApplicationContext applicationContext;

  private java.time.LocalDateTime utcNow() {
    return java.time.LocalDateTime.ofInstant(Instant.now(clock), java.time.ZoneOffset.UTC);
  }
}
