package com.forecast.app.pool;

import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.error.BizException;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.pool.bet.BetService;
import com.forecast.app.pool.bet.PoolViews;
import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.support.MutableClock;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * pool 模块测试的公共部分（真实 MySQL 8.4、真实 Redis 7，C37）。用真实的 market、ledger、account（<b>不</b> import market 测试的替身 MarketTestDoubles），
 * 固定时钟 2026-10-14 10:00 UTC（可以往前拨，测完复原）。测试数据各自新建（雪花编号、新客户），互不影响；不清库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
public abstract class PoolTestSupport {

  public static final Instant NOW = FixedClockConfig.NOW;

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected MockMvc mvc;
  @Autowired protected LedgerApi ledger;
  @Autowired protected MarketAdminApi admin;
  @Autowired protected BetService bets;
  @Autowired protected Clock clock;
  @Autowired protected SystemConfig config;
  protected final ObjectMapper json = new ObjectMapper();

  @AfterEach
  void resetClock() {
    ((MutableClock) clock).reset();
  }

  protected void advance(Duration d) {
    ((MutableClock) clock).advance(d);
  }

  // ---------------------------------------------------------------------
  // 客户
  // ---------------------------------------------------------------------

  /** 一个有登录会话的客户 */
  public record User(long id, Cookie cookie) {}

  /** 新客户：开户、充值 deposit（账本 DEPOSIT）；kyc = true 时 KYC 已通过（充值超过 1,000 也能下注） */
  protected User user(String deposit, boolean kyc) throws Exception {
    String email = "pool" + System.nanoTime() + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    ledger.openAccount(id);
    if (new BigDecimal(deposit).signum() > 0) deposit(id, deposit);
    if (kyc) {
      jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', ?, 'f', 'b', 's', 'APPROVED')",
          id, "P" + id);
    }
    String token = UUID.randomUUID().toString();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    jdbc.update("INSERT INTO account_session (user_id, token_hash, created_at, last_seen_at, expires_at) VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), "
        + "UTC_TIMESTAMP(3) + INTERVAL 1 DAY)", id, hash);
    return new User(id, new Cookie("fc_session", token));
  }

  protected User user(String deposit) throws Exception {
    return user(deposit, false);
  }

  protected void deposit(long uid, String amount) {
    ledger.apply(new LedgerCommand(uid, Bucket.AVAILABLE, new BigDecimal(amount), LedgerEntryType.DEPOSIT, "TEST", "dep-" + uid + "-" + UUID.randomUUID(), "测试入金"));
  }

  protected BigDecimal available(long uid) {
    return ledger.snapshot(uid).available();
  }

  protected BigDecimal betLocked(long uid) {
    return ledger.snapshot(uid).betLocked();
  }

  // ---------------------------------------------------------------------
  // 市场（经 market 的后台接口建草稿、上架：上架时 pool 建奖池行）
  // ---------------------------------------------------------------------

  /** 一个上架的市场：对外编号和各选项的对外编号（按顺序） */
  public record Market(long id, String no, List<String> options) {}

  static I18nText t(String s) {
    return new I18nText(s + " en", s + " 简", s + " 繁");
  }

  /** 上架一个市场：开放 = 现在 − 1 分钟，截止 = close，optionCount 个选项；userCap 为空时用品类默认（足球 10,000） */
  protected Market market(int optionCount, Instant close, String userCap) {
    Instant open = Instant.now(clock).minus(Duration.ofMinutes(1));
    List<MarketAdminApi.OptionInput> opts = new ArrayList<>();
    for (int i = 1; i <= optionCount; i++) opts.add(new MarketAdminApi.OptionInput(t("选项" + i), t("条件" + i)));
    MarketAdminApi.DraftInput in = new MarketAdminApi.DraftInput("FOOTBALL", t("曼城 vs 利物浦"), t("以 90 分钟比分为准"), "AUTO", "premierleague.com",
        "API-Football", null, open.toString(), close.toString(), close.plus(Duration.ofMinutes(5)).toString(), close.plus(Duration.ofHours(2)).toString(), userCap,
        null, null, opts);
    MarketAdminApi.Detail d = admin.createDraft(in, 1);
    admin.publish(Long.parseLong(d.publicNo()), true, 1);
    long id = jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, Long.parseLong(d.publicNo()));
    return new Market(id, d.publicNo(), d.options().stream().map(MarketAdminApi.OptionView::optionNo).toList());
  }

  protected Market market(int optionCount) {
    return market(optionCount, Instant.now(clock).plus(Duration.ofDays(1)), null);
  }

  // ---------------------------------------------------------------------
  // 下注
  // ---------------------------------------------------------------------

  protected PoolViews.BetResult bet(long uid, Market m, int option, String amount) {
    return bet(uid, m, option, amount, UUID.randomUUID().toString(), Instant.now(clock));
  }

  protected PoolViews.BetResult bet(long uid, Market m, int option, String amount, String reqId, Instant receivedAt) {
    return bets.place(new BetService.Place(uid, m.no(), m.options().get(option), amount, reqId, receivedAt));
  }

  /** 下注，返回 OK 或拒单的错误码 */
  protected String tryBet(long uid, Market m, int option, String amount) {
    try {
      bet(uid, m, option, amount);
      return "OK";
    } catch (BizException e) {
      return e.code();
    }
  }

  // ---------------------------------------------------------------------
  // 接口
  // ---------------------------------------------------------------------

  protected MockHttpServletResponse call(MockHttpServletRequestBuilder b, Cookie c, String body) throws Exception {
    if (c != null) b = b.cookie(c);
    if (body != null) b = b.contentType(MediaType.APPLICATION_JSON).content(body);
    return mvc.perform(b).andReturn().getResponse();
  }

  protected MockHttpServletResponse postBet(Cookie c, String marketNo, String optionNo, String amount, String reqId) throws Exception {
    String body = json.writeValueAsString(java.util.Map.of("optionNo", optionNo, "amount", amount, "clientReqId", reqId));
    return call(MockMvcRequestBuilders.post("/api/markets/" + marketNo + "/bets"), c, body);
  }

  protected JsonNode getJson(String url, Cookie c) throws Exception {
    MockHttpServletResponse r = call(MockMvcRequestBuilders.get(url), c, null);
    if (r.getStatus() != 200) throw new AssertionError(url + " → " + r.getStatus() + " " + r.getContentAsString());
    return json.readTree(r.getContentAsString());
  }

  protected JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  protected static List<String> keys(JsonNode n) {
    List<String> out = new ArrayList<>();
    n.propertyNames().forEach(out::add);
    return out;
  }

  // ---------------------------------------------------------------------
  // 库里的数
  // ---------------------------------------------------------------------

  protected BigDecimal optionPool(Market m, int option) {
    return jdbc.queryForObject("SELECT amount FROM pool_option_total WHERE option_no = ?", BigDecimal.class, Long.parseLong(m.options().get(option)));
  }

  protected BigDecimal sumBets(Market m) {
    return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM pool_bet WHERE market_id = ? AND status = 'ACTIVE'", BigDecimal.class, m.id());
  }

  protected BigDecimal sumPools(Market m) {
    return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM pool_option_total WHERE market_id = ?", BigDecimal.class, m.id());
  }

  protected BigDecimal sumPositions(Market m) {
    return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM pool_position WHERE market_id = ?", BigDecimal.class, m.id());
  }

  /** 账本里这个市场的下注锁定合计（BET_LOCK 的 BET_LOCKED 一项） */
  protected BigDecimal ledgerLocked(Market m) {
    return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_entry WHERE ref_market_id = ? AND entry_type = 'BET_LOCK' AND bucket = 'BET_LOCKED'",
        BigDecimal.class, m.id());
  }

  protected String status(Market m) {
    return jdbc.queryForObject("SELECT status FROM market_question WHERE id = ?", String.class, m.id());
  }
}
