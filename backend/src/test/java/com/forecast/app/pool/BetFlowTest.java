package com.forecast.app.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.error.BizException;
import com.forecast.app.ledger.api.LedgerQueryApi;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.pool.api.PoolApi;
import com.forecast.app.pool.bet.BetService;
import com.forecast.app.pool.bet.PoolViews;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * 下注事务（forecast S4，需求说明书第 3.3 节，技术方案第 4.2 节），真实 MySQL：记账、下注记录和快照、持仓合并、奖池、幂等、
 * 截止判定（AC-15）、11 种拒单原因（AC-16）、暂停（AC-14）、通知 #8、客户端资金流水里的下注（AC-39 的下注部分）、R8 的数据。
 */
class BetFlowTest extends PoolTestSupport {

  @Autowired LedgerQueryApi ledgerQuery;
  @Autowired PoolApi poolApi;

  @Test
  void 下注_一个事务_记账_下注记录_持仓_奖池_快照_通知() throws Exception {
    User u = user("500");
    Market m = market(3);
    PoolViews.BetResult r = bet(u.id(), m, 0, "60");
    assertThat(r.duplicate()).isFalse();
    assertThat(r.status()).isEqualTo("active");
    assertThat(r.amount()).isEqualTo("60.00");
    assertThat(r.available()).isEqualTo("440.00");
    assertThat(r.betLocked()).isEqualTo("60.00");
    assertThat(r.receivedAt()).isEqualTo(NOW.toString());
    assertThat(r.pool().total()).isEqualTo("60.00");
    assertThat(r.pool().options().get(0).amount()).isEqualTo("60.00");
    assertThat(r.pool().options().get(0).pctList()).isEqualTo("100%");
    assertThat(r.pool().options().get(1).mult()).as("没人押的选项赔率显示 —").isEqualTo("—");
    // 账本：可用 −60、下注锁定 +60，同一个幂等键 BET : 下注编号，记了市场
    assertThat(available(u.id())).isEqualByComparingTo("440");
    assertThat(betLocked(u.id())).isEqualByComparingTo("60");
    List<String> entries = jdbc.queryForList("SELECT CONCAT(l.entry_type, ' ', l.bucket, ' ', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM l.amount)), ' ', l.biz_type, ':', l.biz_id, ' ', l.ref_market_id) "
        + "FROM ledger_entry l JOIN ledger_account a ON a.id = l.account_id WHERE a.user_id = ? AND l.entry_type = 'BET_LOCK' ORDER BY l.id", String.class, u.id());
    assertThat(entries).containsExactly("BET_LOCK AVAILABLE -60 BET:" + r.betNo() + " " + m.id(), "BET_LOCK BET_LOCKED 60 BET:" + r.betNo() + " " + m.id());

    // 同一选项再押 40：一个持仓 100（结算按持仓算，只舍入一次），两笔下注
    bet(u.id(), m, 0, "40");
    assertThat(jdbc.queryForList("SELECT CONCAT(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM amount)), '/', bet_count) FROM pool_position WHERE market_id = ? AND user_id = ?", String.class, m.id(), u.id()))
        .containsExactly("100/2");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pool_bet WHERE market_id = ? AND user_id = ?", Integer.class, m.id(), u.id())).isEqualTo(2);
    // 押另一个选项（RQ-12：同一市场可以押多个选项，每个选项一个持仓）
    bet(u.id(), m, 2, "25.5");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pool_position WHERE market_id = ? AND user_id = ?", Integer.class, m.id(), u.id())).isEqualTo(2);
    assertThat(optionPool(m, 0)).isEqualByComparingTo("100");
    assertThat(optionPool(m, 2)).isEqualByComparingTo("25.5");
    assertThat(jdbc.queryForObject("SELECT bettors FROM pool_option_total WHERE option_no = ?", Integer.class, Long.parseLong(m.options().get(0)))).as("人数只算一次")
        .isEqualTo(1);
    // 守恒（需求第 3.9 节第 2 条）：Σ有效下注 = 选项奖池之和 = 持仓之和 = 账本里这个市场的锁定金额
    assertThat(sumBets(m)).isEqualByComparingTo("125.5").isEqualByComparingTo(sumPools(m)).isEqualByComparingTo(sumPositions(m)).isEqualByComparingTo(ledgerLocked(m));
    assertThat(ledgerQuery.mismatches()).as("账本合计 = 余额").isEmpty();

    // 快照：下注后各选项的奖池和当时的费率（只做展示）
    JsonNode snap = json.readTree(jdbc.queryForObject("SELECT snapshot FROM pool_bet WHERE public_no = ?", String.class, Long.parseLong(r.betNo())));
    assertThat(snap.get("feeRate").asString()).isEqualTo("0.05");
    assertThat(snap.get("total").asString()).isEqualTo("60.00");
    assertThat(snap.get("options").get(0).get("amount").asString()).isEqualTo("60.00");
    assertThat(snap.get("options").get(0).get("optionNo").asString()).isEqualTo(m.options().get(0));

    // 通知 #8 下注成功：只发站内信（和下注同一个事务）
    List<String> inbox = jdbc.queryForList("SELECT params FROM notify_message WHERE user_id = ? AND template_code = 'BET_PLACED' ORDER BY id", String.class, u.id());
    assertThat(inbox).hasSize(3);
    JsonNode p = json.readTree(inbox.get(0));
    assertThat(p.get("market").asString()).isEqualTo("曼城 vs 利物浦 简");
    assertThat(p.get("optionEn").asString()).isEqualTo("选项1 en");
    assertThat(p.get("amount").asString()).isEqualTo("60.00");
    assertThat(p.get("betNo").asString()).isEqualTo(r.betNo());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE user_id = ? AND template_code = 'BET_PLACED' AND channel <> 'INBOX'", Integer.class, u.id()))
        .as("不发推送和邮件").isZero();
  }

  @Test
  void 幂等_同一个请求号只下一次注_返回第一次的结果_换了内容409() throws Exception {
    User u = user("100");
    Market m = market(2);
    String req = UUID.randomUUID().toString();
    PoolViews.BetResult first = bet(u.id(), m, 0, "30", req, NOW);
    PoolViews.BetResult again = bet(u.id(), m, 0, "30", req, NOW.plusSeconds(1));
    assertThat(again.betNo()).isEqualTo(first.betNo());
    assertThat(again.duplicate()).isTrue();
    assertThat(again.receivedAt()).as("第一次的收到时间").isEqualTo(first.receivedAt());
    assertThat(available(u.id())).as("只扣一次").isEqualByComparingTo("70");
    assertThat(sumBets(m)).isEqualByComparingTo("30");
    // 换了金额 / 选项：不是重复提交，是客户端用错了请求号
    assertThat(code(() -> bet(u.id(), m, 0, "31", req, NOW))).isEqualTo("BET_REQUEST_REUSED");
    assertThat(code(() -> bet(u.id(), m, 1, "30", req, NOW))).isEqualTo("BET_REQUEST_REUSED");
    // 接口：同样的请求号重复 POST 返回同一个下注编号；换内容 409
    MockHttpServletResponse h1 = postBet(u.cookie(), m.no(), m.options().get(1), "5", "req-http-" + u.id());
    MockHttpServletResponse h2 = postBet(u.cookie(), m.no(), m.options().get(1), "5", "req-http-" + u.id());
    assertThat(h1.getStatus()).as(h1.getContentAsString()).isEqualTo(200);
    assertThat(body(h2).get("betNo").asString()).isEqualTo(body(h1).get("betNo").asString());
    assertThat(body(h2).get("duplicate").asBoolean()).isTrue();
    assertThat(postBet(u.cookie(), m.no(), m.options().get(1), "6", "req-http-" + u.id()).getStatus()).isEqualTo(409);
    // 截止以后用同一个请求号重试：仍然返回第一次的结果（网络重试不应该变成"已截止"）
    advance(Duration.ofDays(2));
    assertThat(bet(u.id(), m, 0, "30", req, Instant.now(clock)).betNo()).isEqualTo(first.betNo());
    // 另一个客户用同一个请求号互不影响
    User other = user("100");
    advance(Duration.ofDays(-2));
    assertThat(bet(other.id(), m, 0, "30", req, NOW).betNo()).isNotEqualTo(first.betNo());
  }

  @Test
  void AC15_截止判定只看收到时间_T减1毫秒成功_T拒绝() throws Exception {
    User u = user("100");
    Instant close = NOW.plus(Duration.ofHours(1));
    Market m = market(2, close, null);
    assertThat(bet(u.id(), m, 0, "1", UUID.randomUUID().toString(), close.minusMillis(1)).receivedAt()).isEqualTo(close.minusMillis(1).toString());
    assertThat(code(() -> bet(u.id(), m, 0, "1", UUID.randomUUID().toString(), close))).isEqualTo("MARKET_CLOSED");
    assertThat(code(() -> bet(u.id(), m, 0, "1", UUID.randomUUID().toString(), close.plusMillis(1)))).isEqualTo("MARKET_CLOSED");
    assertThat(jdbc.queryForObject("SELECT received_at FROM pool_bet WHERE market_id = ?", LocalDateTime.class, m.id()))
        .as("收到时间精确到毫秒（E14 退回按它判断，S5）").isEqualTo(LocalDateTime.ofInstant(close.minusMillis(1), ZoneOffset.UTC));
  }

  @Test
  void AC16_11种拒单原因各触发一次_接口返回对应的错误码() throws Exception {
    Map<String, String> got = new LinkedHashMap<>();
    // 市场未开放：开放时间在将来
    User u = user("20000", true);
    Instant future = NOW.plus(Duration.ofHours(2));
    MarketAdminApi.Detail d = admin.createDraft(new MarketAdminApi.DraftInput("FOOTBALL", t("未开放"), t("r"), "AUTO", "a", "b", null, future.toString(),
        future.plus(Duration.ofHours(1)).toString(), future.plus(Duration.ofHours(1)).toString(), future.plus(Duration.ofHours(3)).toString(), null, null, null,
        List.of(new MarketAdminApi.OptionInput(t("是"), t("是")), new MarketAdminApi.OptionInput(t("否"), t("否")))), 1);
    admin.publish(Long.parseLong(d.publicNo()), true, 1);
    got.put("MARKET_NOT_OPEN", httpCode(u, d.publicNo(), d.options().get(0).optionNo(), "1"));
    // 已截止
    Market closed = market(2, NOW.plus(Duration.ofHours(1)), null);
    advance(Duration.ofHours(1));
    got.put("MARKET_CLOSED", httpCode(u, closed.no(), closed.options().get(0), "1"));
    advance(Duration.ofHours(-1));
    // 已暂停（AC-14）
    Market paused = market(2);
    admin.pause(Long.parseLong(paused.no()), 1, "测试");
    got.put("MARKET_PAUSED", httpCode(u, paused.no(), paused.options().get(0), "1"));
    // 已取消
    Market cancelled = market(2);
    admin.cancel(Long.parseLong(cancelled.no()), 1, "测试");
    got.put("MARKET_CANCELLED", httpCode(u, cancelled.no(), cancelled.options().get(0), "1"));
    // 金额低于最小 / 高于最大（默认 1、5,000）
    Market m = market(2, NOW.plus(Duration.ofDays(1)), "8000");
    got.put("BET_BELOW_MIN", httpCode(u, m.no(), m.options().get(0), "0.99"));
    got.put("BET_ABOVE_MAX", httpCode(u, m.no(), m.options().get(0), "5000.01"));
    // 超过本市场的累计上限（这个市场调低到 8,000，跨选项合计）
    bet(u.id(), m, 0, "5000");
    bet(u.id(), m, 1, "2999");
    got.put("BET_OVER_USER_CAP", httpCode(u, m.no(), m.options().get(1), "1.01"));
    assertThat(httpCode(u, m.no(), m.options().get(0), "1")).as("正好到上限可以").isEqualTo("OK");
    // 余额不足
    User poor = user("10");
    got.put("INSUFFICIENT_FUNDS", httpCode(poor, m.no(), m.options().get(0), "10.01"));
    // 需要先完成身份认证：累计充值（扣冲正）超过阈值 1,000、没通过 KYC（AC-62：1,000 还能下注，1,000.01 不能）
    User at = user("1000");
    assertThat(httpCode(at, m.no(), m.options().get(0), "1")).isEqualTo("OK");
    User over = user("1000.01");
    got.put("KYC_REQUIRED", httpCode(over, m.no(), m.options().get(0), "1"));
    // 账户已冻结（冻结后登录会被作废，接口进不来；这里直接调业务，冻结发生在登录之后的情况）
    User frozen = user("100");
    jdbc.update("UPDATE account_user SET status = 'FROZEN' WHERE id = ?", frozen.id());
    got.put("USER_FROZEN", code(() -> bet(frozen.id(), m, 0, "1")));
    // 员工账户（AC-67）
    User staff = user("100");
    jdbc.update("UPDATE account_user SET is_staff = 1 WHERE id = ?", staff.id());
    got.put("BET_STAFF_ACCOUNT", httpCode(staff, m.no(), m.options().get(0), "1"));

    assertThat(got.keySet()).containsExactlyElementsOf(BetService.REJECT_CODES);
    got.forEach((expected, actual) -> assertThat(actual).as(expected).isEqualTo(expected));
    // 被拒的都没有动钱、没有写下注
    assertThat(available(poor.id())).isEqualByComparingTo("10");
    assertThat(available(over.id())).isEqualByComparingTo("1000.01");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pool_bet WHERE user_id IN (?, ?, ?, ?)", Integer.class, poor.id(), over.id(), frozen.id(), staff.id())).isZero();
    // HTTP 状态：冻结 403，其他 400
    MockHttpServletResponse r = postBet(poor.cookie(), m.no(), m.options().get(0), "10.01", UUID.randomUUID().toString());
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(body(r).get("message").asString()).isEqualTo("可用余额不足");
  }

  @Test
  void AC14_暂停后被拒_恢复后能下注() throws Exception {
    User u = user("100");
    Market m = market(2);
    admin.pause(Long.parseLong(m.no()), 1, "突发");
    assertThat(tryBet(u.id(), m, 0, "1")).isEqualTo("MARKET_PAUSED");
    admin.resume(Long.parseLong(m.no()), 1, "恢复");
    assertThat(tryBet(u.id(), m, 0, "1")).isEqualTo("OK");
  }

  @Test
  void 请求格式_一次列出全部字段错误_市场或选项不存在() throws Exception {
    User u = user("100");
    Market m = market(2);
    MockHttpServletResponse r = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/markets/" + m.no() + "/bets"), u.cookie(),
        "{\"optionNo\":\"x\",\"amount\":\"1.001\",\"clientReqId\":\"short\"}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(body(r).get("fields").toString()).isEqualTo("[{\"field\":\"optionNo\",\"code\":\"invalid\"},{\"field\":\"amount\",\"code\":\"amountDigits\"},"
        + "{\"field\":\"clientReqId\",\"code\":\"invalid\"}]");
    r = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/markets/" + m.no() + "/bets"), u.cookie(), "{}");
    assertThat(body(r).get("fields").toString()).isEqualTo("[{\"field\":\"optionNo\",\"code\":\"required\"},{\"field\":\"amount\",\"code\":\"required\"},"
        + "{\"field\":\"clientReqId\",\"code\":\"required\"}]");
    assertThat(body(postBet(u.cookie(), m.no(), m.options().get(0), "-5", UUID.randomUUID().toString())).get("fields").get(0).get("code").asString())
        .isEqualTo("positiveNumber");
    assertThat(postBet(u.cookie(), "999999", m.options().get(0), "1", UUID.randomUUID().toString()).getStatus()).isEqualTo(404);
    assertThat(postBet(u.cookie(), "abc", m.options().get(0), "1", UUID.randomUUID().toString()).getStatus()).isEqualTo(404);
    Market other = market(2);
    assertThat(body(postBet(u.cookie(), m.no(), other.options().get(0), "1", UUID.randomUUID().toString())).get("code").asString()).as("别的市场的选项")
        .isEqualTo("OPTION_NOT_FOUND");
    assertThat(postBet(null, m.no(), m.options().get(0), "1", UUID.randomUUID().toString()).getStatus()).as("要登录").isEqualTo(401);
    assertThat(available(u.id())).isEqualByComparingTo("100");
  }

  @Test
  void 草稿市场不能下注_S4以前上架没有奖池行的市场第一次下注时补上() throws Exception {
    User u = user("100");
    MarketAdminApi.Detail draft = admin.createDraft(new MarketAdminApi.DraftInput("FOOTBALL", t("草稿"), t("r"), "AUTO", "a", "b", null, NOW.toString(),
        NOW.plus(Duration.ofDays(1)).toString(), NOW.plus(Duration.ofDays(1)).toString(), NOW.plus(Duration.ofDays(2)).toString(), null, null, null,
        List.of(new MarketAdminApi.OptionInput(t("是"), t("是")), new MarketAdminApi.OptionInput(t("否"), t("否")))), 1);
    assertThat(code(() -> bets.place(new BetService.Place(u.id(), draft.publicNo(), draft.options().get(0).optionNo(), "1", UUID.randomUUID().toString(), NOW))))
        .isEqualTo("NOT_FOUND");
    // 直接在库里改成已上架（模拟 S4 之前上架的市场：彩池里没有登记）
    jdbc.update("UPDATE market_question SET status = 'LISTED' WHERE public_no = ?", Long.parseLong(draft.publicNo()));
    long id = jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, Long.parseLong(draft.publicNo()));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pool_option_total WHERE market_id = ?", Integer.class, id)).isZero();
    Market m = new Market(id, draft.publicNo(), draft.options().stream().map(MarketAdminApi.OptionView::optionNo).toList());
    bet(u.id(), m, 1, "7");
    assertThat(optionPool(m, 1)).isEqualByComparingTo("7");
    assertThat(jdbc.queryForObject("SELECT status FROM pool_market WHERE market_id = ?", String.class, id)).isEqualTo("LISTED");
  }

  @Test
  void 参数设置改了下注限额_立即生效() throws Exception {
    User u = user("100");
    Market m = market(2);
    jdbc.update("UPDATE common_config SET value = '5' WHERE config_key = ?", ConfigKey.BET_MIN_AMOUNT.key);
    jdbc.update("UPDATE common_config SET value = '50' WHERE config_key = ?", ConfigKey.BET_MAX_AMOUNT.key);
    config.refresh();
    try {
      assertThat(tryBet(u.id(), m, 0, "4.99")).isEqualTo("BET_BELOW_MIN");
      assertThat(tryBet(u.id(), m, 0, "50.01")).isEqualTo("BET_ABOVE_MAX");
      assertThat(tryBet(u.id(), m, 0, "50")).isEqualTo("OK");
    } finally {
      jdbc.update("UPDATE common_config SET value = '1' WHERE config_key = ?", ConfigKey.BET_MIN_AMOUNT.key);
      jdbc.update("UPDATE common_config SET value = '5000' WHERE config_key = ?", ConfigKey.BET_MAX_AMOUNT.key);
      config.refresh();
    }
  }

  @Test
  void 客户端资金流水_下注一条_带市场编号和题目() throws Exception {
    User u = user("100");
    Market m = market(2);
    bet(u.id(), m, 0, "12.34");
    JsonNode page = getJson("/api/ledger/entries?type=bet&range=all", u.cookie());
    assertThat(page.get("total").asLong()).isEqualTo(1);
    JsonNode e = page.get("items").get(0);
    assertThat(keys(e)).containsExactly("id", "type", "amount", "ref", "time", "market");
    assertThat(e.get("type").asString()).isEqualTo("bet");
    assertThat(e.get("amount").asString()).isEqualTo("-12.34");
    assertThat(e.get("market").get("publicNo").asString()).isEqualTo(m.no());
    assertThat(e.get("market").get("title").get("zhCN").asString()).isEqualTo("曼城 vs 利物浦 简");
    assertThat(getJson("/api/ledger/entries?range=all", u.cookie()).get("total").asLong()).as("充值 + 下注").isEqualTo(2);
  }

  @Test
  void R8的数据_有效下注本金_退回的和取消作废市场里的不算() throws Exception {
    User u = user("1000");
    Market a = market(2);
    Market b = market(2);
    bet(u.id(), a, 0, "100");
    bet(u.id(), b, 0, "30");
    LocalDateTime since = LocalDateTime.ofInstant(NOW.minus(Duration.ofDays(30)), ZoneOffset.UTC);
    assertThat(poolApi.effectiveStakeSince(u.id(), since)).isEqualByComparingTo("130");
    admin.cancel(Long.parseLong(b.no()), 1, "取消");
    assertThat(poolApi.effectiveStakeSince(u.id(), since)).as("取消的市场（S5 退款前）也不算").isEqualByComparingTo("100");
    // S5 退回下注时写的状态（这里直接改库模拟）
    jdbc.update("UPDATE pool_bet SET status = 'REFUNDED', refund_reason = 'LATE_BET', refunded_at = UTC_TIMESTAMP(3) WHERE market_id = ?", a.id());
    assertThat(poolApi.effectiveStakeSince(u.id(), since)).isEqualByComparingTo("0");
    assertThat(poolApi.effectiveStakeSince(u.id(), LocalDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC))).as("按收到时间").isEqualByComparingTo("0");
    // 数据库兜底：ACTIVE 不能有退回原因、REFUNDED 必须有
    assertThatThrownBy(() -> jdbc.update("UPDATE pool_bet SET status = 'REFUNDED', refund_reason = NULL WHERE market_id = ?", a.id())).isNotNull();
  }

  /** 下注接口返回的错误码（成功是 OK） */
  String httpCode(User u, String marketNo, String optionNo, String amount) throws Exception {
    MockHttpServletResponse r = postBet(u.cookie(), marketNo, optionNo, amount, UUID.randomUUID().toString());
    if (r.getStatus() == 200) return "OK";
    return body(r).get("code").asString();
  }

  static String code(Runnable r) {
    try {
      r.run();
      return "OK";
    } catch (BizException e) {
      return e.code();
    }
  }

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }
}
