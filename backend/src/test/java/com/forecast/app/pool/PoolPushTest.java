package com.forecast.app.pool;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.market.core.MarketService;
import com.forecast.app.pool.bet.BetService;
import com.forecast.app.pool.push.PoolChanges;
import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.support.MutableClock;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 奖池推送 {@code WS /api/ws/markets}（技术方案第 7 节，AC-17、NF-02）：真实启动服务、真实握手、真实 Redis（实际执行）。
 * 订阅时先推一次现在的奖池；下注提交后 2 秒内推到；同一个市场每秒最多推一次（合并）；截止、改费率也推；其他实例经 Redis 广播的变化也推；没登录握手被拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class PoolPushTest {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired LedgerApi ledger;
  @Autowired MarketAdminApi admin;
  @Autowired MarketService marketService;
  @Autowired BetService bets;
  @Autowired StringRedisTemplate redis;
  @Autowired Clock clock;
  final ObjectMapper json = new ObjectMapper();
  final HttpClient http = HttpClient.newHttpClient();
  final List<WebSocket> sockets = new ArrayList<>();

  @AfterEach
  void close() {
    for (WebSocket w : sockets) w.abort();
    ((MutableClock) clock).reset();
  }

  /** 收到的一条推送和收到的时刻 */
  record Msg(JsonNode body, long atNanos) {}

  long user(String deposit) {
    String email = "push" + System.nanoTime() + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    ledger.openAccount(id);
    ledger.apply(new LedgerCommand(id, Bucket.AVAILABLE, new BigDecimal(deposit), LedgerEntryType.DEPOSIT, "TEST", "dep-" + id, "测试入金"));
    return id;
  }

  String cookie(long uid) throws Exception {
    String token = UUID.randomUUID().toString();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    jdbc.update("INSERT INTO account_session (user_id, token_hash, created_at, last_seen_at, expires_at) VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), "
        + "UTC_TIMESTAMP(3) + INTERVAL 1 DAY)", uid, hash);
    return "fc_session=" + token;
  }

  record Market(long id, String no, List<String> options) {}

  Market market(Instant close) {
    I18nText t = new I18nText("Push", "推送", "推送");
    Instant open = Instant.now(clock).minus(Duration.ofMinutes(1));
    MarketAdminApi.Detail d = admin.createDraft(new MarketAdminApi.DraftInput("FOOTBALL", t, t, "AUTO", "a", "b", null, open.toString(), close.toString(),
        close.toString(), close.plus(Duration.ofHours(2)).toString(), null, null, null,
        List.of(new MarketAdminApi.OptionInput(t, t), new MarketAdminApi.OptionInput(t, t))), 1);
    admin.publish(Long.parseLong(d.publicNo()), true, 1);
    long id = jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, Long.parseLong(d.publicNo()));
    return new Market(id, d.publicNo(), d.options().stream().map(MarketAdminApi.OptionView::optionNo).toList());
  }

  void bet(long uid, Market m, int option, String amount) {
    bets.place(new BetService.Place(uid, m.no(), m.options().get(option), amount, UUID.randomUUID().toString(), Instant.now(clock)));
  }

  BlockingQueue<Msg> subscribe(String cookie, String... marketNos) throws Exception {
    BlockingQueue<Msg> q = new LinkedBlockingQueue<>();
    WebSocket ws = http.newWebSocketBuilder().header("Cookie", cookie).buildAsync(URI.create("ws://localhost:" + port + "/api/ws/markets"), new WebSocket.Listener() {
      final StringBuilder buf = new StringBuilder();

      @Override
      public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
        buf.append(data);
        if (last) {
          q.add(new Msg(json.readTree(buf.toString()), System.nanoTime()));
          buf.setLength(0);
        }
        w.request(1);
        return null;
      }
    }).get(5, TimeUnit.SECONDS);
    sockets.add(ws);
    ws.sendText(json.writeValueAsString(java.util.Map.of("op", "sub", "markets", List.of(marketNos))), true).get(5, TimeUnit.SECONDS);
    return q;
  }

  /** 订阅之前的变化（上架、准备数据时的下注）标记的推送可能在订阅后的第一轮推出来：等它推完、清掉，后面只看测试动作引起的推送 */
  static void settle(BlockingQueue<Msg> q) throws InterruptedException {
    Thread.sleep(1300);
    q.clear();
  }

  @Test
  void AC17_订阅时先推一次_下注后2秒内推到新的奖池和赔率() throws Exception {
    long u = user("1000");
    Market m = market(Instant.now(clock).plus(Duration.ofDays(1)));
    BlockingQueue<Msg> q = subscribe(cookie(user("0.01")), m.no());
    Msg first = q.poll(5, TimeUnit.SECONDS);
    assertThat(first).as("订阅时立即推一次现在的奖池").isNotNull();
    assertThat(first.body().get("type").asString()).isEqualTo("pool");
    assertThat(first.body().get("marketNo").asString()).isEqualTo(m.no());
    assertThat(first.body().get("status").asString()).isEqualTo("listed");
    assertThat(first.body().get("pool").get("total").asString()).isEqualTo("0.00");
    assertThat(first.body().get("pool").get("options").get(0).get("pct").asString()).isEqualTo("—");
    settle(q);

    bet(u, m, 0, "60");
    long committed = System.nanoTime();
    Msg after = q.poll(2, TimeUnit.SECONDS);
    assertThat(after).as("下注提交后 2 秒内推到").isNotNull();
    assertThat((after.atNanos() - committed) / 1_000_000).as("毫秒").isLessThan(2000);
    JsonNode pool = after.body().get("pool");
    assertThat(pool.get("total").asString()).isEqualTo("60.00");
    assertThat(pool.get("bettors").asInt()).isEqualTo(1);
    assertThat(pool.get("options").get(0).get("pct").asString()).isEqualTo("100.0%");
    assertThat(pool.has("mine")).as("推送不含本人的持仓").isTrue();
    assertThat(pool.get("mine").isNull()).isTrue();

    bet(u, m, 1, "40");
    Msg third = q.poll(2, TimeUnit.SECONDS);
    assertThat(third).isNotNull();
    assertThat(third.body().get("pool").get("options").get(0).get("mult").asString()).as("赔率按新奖池算：(60 + 40 × 0.95) ÷ 60").isEqualTo("1.63");
  }

  @Test
  void 每个市场每秒最多推一次_连续下注合并推送_最后一次是最新的奖池() throws Exception {
    long u = user("1000");
    Market m = market(Instant.now(clock).plus(Duration.ofDays(1)));
    BlockingQueue<Msg> q = subscribe(cookie(u), m.no());
    assertThat(q.poll(5, TimeUnit.SECONDS)).isNotNull();
    settle(q);
    long start = System.nanoTime();
    for (int i = 0; i < 40; i++) bet(u, m, i % 2, "1");
    long took = (System.nanoTime() - start) / 1_000_000;
    // 等最后一次合并推送（最多 1 秒 + 一轮）
    List<Msg> got = new ArrayList<>();
    Msg x;
    while ((x = q.poll(2, TimeUnit.SECONDS)) != null) {
      got.add(x);
      if (x.body().get("pool").get("total").asString().equals("40.00")) break;
    }
    assertThat(got).isNotEmpty();
    assertThat(got.get(got.size() - 1).body().get("pool").get("total").asString()).as("最后一次是最新的奖池").isEqualTo("40.00");
    assertThat(got.size()).as("40 笔下注用了 " + took + " 毫秒，合并后推了 " + got.size() + " 次").isLessThanOrEqualTo((int) (took / 1000) + 2).isLessThan(40);
    for (int i = 1; i < got.size(); i++) {
      assertThat((got.get(i).atNanos() - got.get(i - 1).atNanos()) / 1_000_000).as("两次推送间隔不少于 1 秒（容差 100 毫秒）").isGreaterThanOrEqualTo(900);
    }
  }

  @Test
  void 截止时推一次_奖池定格_改费率也推() throws Exception {
    long a = user("1000");
    long b = user("1000");
    Instant close = Instant.now(clock).plus(Duration.ofMinutes(30));
    Market m = market(close);
    bet(a, m, 0, "60");
    bet(b, m, 1, "40");
    BlockingQueue<Msg> q = subscribe(cookie(a), m.no());
    assertThat(q.poll(5, TimeUnit.SECONDS).body().get("pool").get("frozen").asBoolean()).isFalse();
    settle(q);
    admin.changeFee(Long.parseLong(m.no()), "8", 1, "推送测试");
    Msg fee = q.poll(2, TimeUnit.SECONDS);
    assertThat(fee).as("改费率后推一次（赔率按新费率）").isNotNull();
    assertThat(fee.body().get("pool").get("feeRate").asString()).isEqualTo("8");
    assertThat(fee.body().get("pool").get("options").get(0).get("mult").asString()).isEqualTo("1.61");
    Thread.sleep(1100);
    ((MutableClock) clock).advance(Duration.ofMinutes(30));
    marketService.closeDue();
    Msg closed = q.poll(2, TimeUnit.SECONDS);
    assertThat(closed).as("截止后推一次").isNotNull();
    assertThat(closed.body().get("status").asString()).isEqualTo("closed");
    assertThat(closed.body().get("pool").get("frozen").asBoolean()).as("奖池定格").isTrue();
    assertThat(closed.body().get("pool").get("total").asString()).isEqualTo("100.00");
  }

  @Test
  void 其他实例经Redis广播的变化也推() throws Exception {
    Market m = market(Instant.now(clock).plus(Duration.ofDays(1)));
    BlockingQueue<Msg> q = subscribe(cookie(user("1")), m.no());
    assertThat(q.poll(5, TimeUnit.SECONDS)).isNotNull();
    settle(q);
    // 另一个实例（另一个实例名）发布"这个市场有变化"；本机没有任何变化
    redis.convertAndSend(PoolChanges.CHANNEL, "another-instance:" + m.id());
    Msg x = q.poll(3, TimeUnit.SECONDS);
    assertThat(x).as("收到其他实例的广播后推送").isNotNull();
    assertThat(x.body().get("marketNo").asString()).isEqualTo(m.no());
  }

  @Test
  void 没登录也能连_S13公开浏览_草稿和不存在的市场订阅不到() throws Exception {
    // S13（MF-4）：不登录浏览打开时（默认）没有 Cookie 也能握手（推送不含个人数据）；关掉时握手被登录检查拦下（S13PublicBrowseOffTest）
    WebSocket guest = http.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + port + "/api/ws/markets"), new WebSocket.Listener() {})
        .get(5, TimeUnit.SECONDS);
    guest.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
    I18nText t = new I18nText("d", "d", "d");
    MarketAdminApi.Detail draft = admin.createDraft(new MarketAdminApi.DraftInput("FOOTBALL", t, t, "AUTO", "a", "b", null, Instant.now(clock).toString(),
        Instant.now(clock).plus(Duration.ofDays(1)).toString(), Instant.now(clock).plus(Duration.ofDays(1)).toString(),
        Instant.now(clock).plus(Duration.ofDays(2)).toString(), null, null, null, List.of(new MarketAdminApi.OptionInput(t, t), new MarketAdminApi.OptionInput(t, t))),
        1);
    BlockingQueue<Msg> q = subscribe(cookie(user("1")), draft.publicNo(), "123", "not-a-number");
    assertThat(q.poll(1500, TimeUnit.MILLISECONDS)).as("草稿、不存在的、格式不对的都不推").isNull();
  }
}
