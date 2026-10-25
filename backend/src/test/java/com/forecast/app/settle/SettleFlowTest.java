package com.forecast.app.settle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.forecast.app.common.error.BizException;
import com.forecast.app.settle.api.SettleAdminApi;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 结算和退款的流程（真实 MySQL 8.4，C37）。每个用例两种事务边界都跑（{@code forecast.settle.batch-size} = 0：计算和派彩同一个事务，需求原文；
 * = 500 或更小：分批派彩，架构方案建议 B），结果必须相同：
 * <ul>
 *   <li>AC-20：需求第 3.5 节的例子（Alice 分 2 笔）在库里结算，逐分相等；平台多 200.01；账本守恒；通知 #11、#12；AC-39 流水；费率结算后锁定。</li>
 *   <li>AC-37 后半：结算前把费率改成 8%，按 8% 结算（Alice 161.33）。</li>
 *   <li>AC-24：E1 没人押赢方作废退款；E3 截止时只有一个选项有人押、E4 奖池 0 截止时作废（1 分钟内，截止任务一轮）。</li>
 *   <li>AC-25：截止前取消、截止后人工作废（E5）：每个客户退回 = 他的有效下注合计，不收费，通知 #13 每人一条。</li>
 *   <li>AC-26：E14 实际开赛早于截止时间，之后收到的下注全额退回（通知 #14），其余按公式结算；提前截止后退回新截止时间之后的下注。</li>
 *   <li>E15：员工的下注退回、不参与结算。</li>
 * </ul>
 */
class SettleFlowTest extends SettleTestSupport {

  @Autowired SettleAdminApi settleAdmin;

  record Example(Market m, User alice, User bob, User carol, User dave, User eve, User frank) {}

  /** 需求第 3.5 节的市场：主胜 Alice 60 + 40、Bob 2,500、Carol 3,400；平 Dave 1,500；客胜 Eve 2,000、Frank 500 */
  Example example() throws Exception {
    Market m = shortMarket(3);
    User alice = user("100");
    User bob = user("2500", true);
    User carol = user("3400", true);
    User dave = user("1500", true);
    User eve = user("2000", true);
    User frank = user("500");
    bet(alice.id(), m, 0, "60");
    bet(alice.id(), m, 0, "40");
    bet(bob.id(), m, 0, "2500");
    bet(carol.id(), m, 0, "3400");
    bet(dave.id(), m, 1, "1500");
    bet(eve.id(), m, 2, "2000");
    bet(frank.id(), m, 2, "500");
    return new Example(m, alice, bob, carol, dave, eve, frank);
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 2, 500})
  void AC20_第35节例子在库里结算_逐分相等_守恒_通知_流水(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Example x = example();
    closeAndEnd(x.m());
    assertThat(status(x.m())).isEqualTo("PENDING_RESOLUTION");
    BigDecimal revenueBefore = revenue();
    settle(x.m(), 0);
    // 派彩回到可用余额（逐分照需求原文）
    assertThat(available(x.alice().id())).isEqualByComparingTo("163.33");
    assertThat(available(x.bob().id())).isEqualByComparingTo("4083.33");
    assertThat(available(x.carol().id())).isEqualByComparingTo("5553.33");
    for (User u : List.of(x.dave(), x.eve(), x.frank())) assertThat(available(u.id())).as("输方派彩 0").isEqualByComparingTo("0");
    for (User u : List.of(x.alice(), x.bob(), x.carol(), x.dave(), x.eve(), x.frank())) assertThat(betLocked(u.id())).as("下注中归零").isEqualByComparingTo("0");
    // 平台收入：抽成 199.99 + 尾差 0.02 = 200.01（RQ-7）
    assertThat(revenue().subtract(revenueBefore)).isEqualByComparingTo("200.01");
    assertThat(ledgerSum(x.m(), "FEE_INCOME", "AVAILABLE")).isEqualByComparingTo("199.99");
    assertThat(ledgerSum(x.m(), "SETTLE_DUST", "AVAILABLE")).isEqualByComparingTo("0.02");
    settledConserved(x.m(), "10000");
    // 结算单
    Map<String, Object> run = run(x.m(), "PAYOUT");
    assertThat(run.get("status")).isEqualTo("DONE");
    assertThat((BigDecimal) run.get("sum_payout")).isEqualByComparingTo("9799.99");
    assertThat((BigDecimal) run.get("sum_fee")).isEqualByComparingTo("199.99");
    assertThat((BigDecimal) run.get("dust")).isEqualByComparingTo("0.02");
    assertThat((BigDecimal) run.get("pool_total")).isEqualByComparingTo("10000");
    assertThat((BigDecimal) run.get("fee_rate")).isEqualByComparingTo("0.05");
    assertThat(run.get("winner_positions")).isEqualTo(3);
    assertThat(run.get("loser_positions")).isEqualTo(3);
    assertThat(((Number) run.get("batch_size")).intValue()).isEqualTo(batch);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settle_item WHERE run_id = ? AND status = 'DONE'", Integer.class, run.get("id"))).as("每个持仓一行（Alice 两笔合一个）")
        .isEqualTo(6);
    assertThat(status(x.m())).isEqualTo("SETTLED");
    assertThat(phase(x.m())).isEqualTo("DONE");
    // 通知 #11 赢了（派彩、抽成写明）、#12 输了，每人一条
    assertThat(inbox(x.alice().id(), "SETTLE_WON")).isEqualTo(1);
    assertThat(inbox(x.dave().id(), "SETTLE_LOST")).isEqualTo(1);
    assertThat(inbox(x.alice().id(), "SETTLE_LOST")).isZero();
    JsonNode p = json.readTree(jdbc.queryForObject("SELECT params FROM notify_message WHERE user_id = ? AND template_code = 'SETTLE_WON'", String.class, x.alice().id()));
    assertThat(p.get("payout").asString()).isEqualTo("163.33");
    assertThat(p.get("fee").asString()).isEqualTo("3.33");
    assertThat(p.get("gross").asString()).isEqualTo("66.66");
    assertThat(p.get("feeRate").asString()).isEqualTo("5");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE user_id = ? AND template_code = 'SETTLE_WON' AND channel = 'EMAIL'", Integer.class,
        x.alice().id())).as("#11 不发邮件").isZero();
    // AC-39：资金流水里有派彩、抽成，带市场
    JsonNode flow = getJson("/api/ledger/entries?range=all", x.alice().cookie());
    List<String> lines = new java.util.ArrayList<>();
    flow.get("items").forEach(i -> lines.add(i.get("type").asString() + " " + i.get("amount").asString() + " " + i.path("market").path("publicNo").asString()));
    assertThat(lines).contains("payout 166.66 " + x.m().no(), "fee -3.33 " + x.m().no(), "bet -60 " + x.m().no());
    // 我的下注：已结算分组；客户端详情公开结果
    JsonNode mine = getJson("/api/me/bets?tab=settled", x.alice().cookie());
    assertThat(mine.get("total").asInt()).isEqualTo(2);
    JsonNode d = getJson("/api/markets/" + x.m().no(), x.alice().cookie());
    assertThat(d.get("status").asString()).isEqualTo("settled");
    assertThat(d.get("winningOptionNo").asString()).isEqualTo(x.m().options().get(0));
    assertThat(d.get("settling").asBoolean()).isFalse();
    // 结算开始后不能改费率（需求第 3.8 节第 3 条）
    assertThatThrownBy(() -> admin.changeFee(Long.parseLong(x.m().no()), "8", 1, "结算后改")).isInstanceOf(BizException.class).hasMessageContaining("结算");
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void AC37_结算前改费率_按新费率8结算(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Example x = example();
    closeAndEnd(x.m());
    admin.changeFee(Long.parseLong(x.m().no()), "8", 1, "AC-37");
    settle(x.m(), 0);
    assertThat(available(x.alice().id())).isEqualByComparingTo("161.33");
    assertThat(available(x.bob().id())).isEqualByComparingTo("4033.33");
    assertThat(available(x.carol().id())).isEqualByComparingTo("5485.33");
    assertThat(ledgerSum(x.m(), "FEE_INCOME", "AVAILABLE")).isEqualByComparingTo("319.99");
    assertThat((BigDecimal) run(x.m(), "PAYOUT").get("fee_rate")).isEqualByComparingTo("0.08");
    settledConserved(x.m(), "10000");
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void AC24_E1_没人押赢方_作废全额退款不收费(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Market m = shortMarket(3);
    User a = user("100");
    User b = user("80");
    bet(a.id(), m, 0, "30");
    bet(a.id(), m, 1, "20");
    bet(b.id(), m, 1, "50");
    closeAndEnd(m);
    BigDecimal revenueBefore = revenue();
    settle(m, 2); // 结果是没人押的选项 3
    if (batch > 0) settle.drainAll();
    assertThat(status(m)).isEqualTo("VOIDED");
    assertThat(jdbc.queryForObject("SELECT void_reason FROM market_question WHERE id = ?", String.class, m.id())).isEqualTo("NO_WINNER");
    assertThat(run(m, "PAYOUT")).as("没有结算单，不派彩").isNull();
    assertThat(run(m, "REFUND").get("status")).isEqualTo("DONE");
    assertThat(available(a.id())).isEqualByComparingTo("100");
    assertThat(available(b.id())).isEqualByComparingTo("80");
    assertThat(betLocked(a.id())).isEqualByComparingTo("0");
    assertThat(revenue()).as("不收费").isEqualByComparingTo(revenueBefore);
    assertThat(stillLocked(m)).isEqualByComparingTo("0");
    assertThat(sumBets(m)).as("下注都已退回").isEqualByComparingTo("0");
    assertThat(sumPools(m)).as("奖池同步扣掉").isEqualByComparingTo("0");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pool_bet WHERE market_id = ? AND status = 'REFUNDED' AND refund_reason = 'VOIDED'", Integer.class, m.id()))
        .isEqualTo(3);
    assertThat(inbox(a.id(), "MARKET_REFUNDED")).as("押两个选项的客户也只发一条 #13").isEqualTo(1);
    JsonNode p = json.readTree(jdbc.queryForObject("SELECT params FROM notify_message WHERE user_id = ? AND template_code = 'MARKET_REFUNDED'", String.class, a.id()));
    assertThat(p.get("amount").asString()).isEqualTo("50.00");
    assertThat(p.get("refundReason").asString()).isEqualTo("NO_WINNER");
    assertThat(p.get("refundKind").asString()).isEqualTo("VOIDED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE user_id = ? AND template_code = 'MARKET_REFUNDED' AND channel = 'EMAIL'", Integer.class,
        a.id())).as("#13 发邮件").isEqualTo(1);
    assertThat(ledgerQuery.mismatches()).isEmpty();
    JsonNode flow = getJson("/api/ledger/entries?type=refund&range=all", a.cookie());
    assertThat(flow.get("items").size()).as("AC-39：资金流水里的退款").isEqualTo(2);
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void AC24_E3_截止时只有一个选项有人押_E4_奖池为0_截止任务一轮就作废退款(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Market one = shortMarket(2);
    Market empty = shortMarket(2);
    User a = user("100");
    User b = user("100");
    bet(a.id(), one, 0, "40");
    bet(b.id(), one, 0, "10");
    advance(Duration.ofMinutes(1));
    marketService.closeDue(); // 截止任务每 5 秒一轮：截止后 1 分钟内作废退款（AC-24）
    if (batch > 0) settle.drainAll();
    assertThat(status(one)).isEqualTo("VOIDED");
    assertThat(available(a.id())).isEqualByComparingTo("100");
    assertThat(available(b.id())).isEqualByComparingTo("100");
    assertThat(run(one, "REFUND").get("status")).isEqualTo("DONE");
    assertThat(inbox(a.id(), "MARKET_REFUNDED")).isEqualTo(1);
    // E4：奖池为 0，作废，没有资金变动
    assertThat(status(empty)).isEqualTo("VOIDED");
    Map<String, Object> r = run(empty, "REFUND");
    assertThat(r.get("status")).isEqualTo("DONE");
    assertThat(r.get("item_count")).isEqualTo(0);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE ref_market_id = ?", Integer.class, empty.id())).isZero();
    assertThat(ledgerQuery.mismatches()).isEmpty();
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 2})
  void AC25_截止前取消_每人退回他的有效下注合计_不收费_通知13(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Market m = shortMarket(3);
    User a = user("300");
    User b = user("200");
    User c = user("50");
    bet(a.id(), m, 0, "100");
    bet(a.id(), m, 1, "50.50");
    bet(a.id(), m, 1, "1.01");
    bet(b.id(), m, 2, "199.99");
    bet(c.id(), m, 0, "50");
    admin.cancel(Long.parseLong(m.no()), 1, "比赛取消（测试）");
    if (batch > 0) {
      assertThat(run(m, "REFUND").get("status")).as("分批：取消的事务里只建退款单").isEqualTo("PAYING");
      assertThat(available(a.id())).isEqualByComparingTo("148.49");
      settle.drainAll();
    }
    assertThat(status(m)).isEqualTo("CANCELLED");
    assertThat(available(a.id())).isEqualByComparingTo("300");
    assertThat(available(b.id())).isEqualByComparingTo("200");
    assertThat(available(c.id())).isEqualByComparingTo("50");
    Map<String, Object> r = run(m, "REFUND");
    assertThat(r.get("status")).isEqualTo("DONE");
    assertThat((BigDecimal) r.get("pool_total")).isEqualByComparingTo("401.50");
    assertThat(r.get("reason")).isEqualTo("比赛取消（测试）");
    for (User u : List.of(a, b, c)) assertThat(inbox(u.id(), "MARKET_REFUNDED")).isEqualTo(1);
    JsonNode p = json.readTree(jdbc.queryForObject("SELECT params FROM notify_message WHERE user_id = ? AND template_code = 'MARKET_REFUNDED'", String.class, a.id()));
    assertThat(p.get("amount").asString()).isEqualTo("151.51");
    assertThat(p.get("refundKind").asString()).isEqualTo("CANCELLED");
    assertThat(stillLocked(m)).isEqualByComparingTo("0");
    assertThat(sumPositions(m)).isEqualByComparingTo("0");
    assertThat(ledgerQuery.mismatches()).isEmpty();
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void E5_截止后人工作废_全额退款(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Market m = shortMarket(2);
    User a = user("100");
    User b = user("100");
    bet(a.id(), m, 0, "60");
    bet(b.id(), m, 1, "40");
    closeAndEnd(m);
    settleAdmin.voidManually(Long.parseLong(m.no()), "比赛取消（E5）", "1");
    if (batch > 0) settle.drainAll();
    assertThat(status(m)).isEqualTo("VOIDED");
    assertThat(available(a.id())).isEqualByComparingTo("100");
    assertThat(available(b.id())).isEqualByComparingTo("100");
    assertThat(run(m, "REFUND").get("reason")).isEqualTo("比赛取消（E5）");
    assertThat(inbox(b.id(), "MARKET_REFUNDED")).isEqualTo(1);
    // 已作废的市场不能再结算
    assertThatThrownBy(() -> settleAdmin.settleManually(Long.parseLong(m.no()), m.options().get(0), null, "1")).isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).code()).isEqualTo("MARKET_NOT_SETTLEABLE");
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void AC26_E14_实际开赛早于截止_之后收到的下注全额退回_其余按公式结算(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Market m = shortMarket(2);
    Instant t0 = Instant.now(clock);
    User a = user("200");
    User b = user("200");
    User late = user("200");
    bet(a.id(), m, 0, "100", UUID.randomUUID().toString(), t0);
    bet(b.id(), m, 1, "100", UUID.randomUUID().toString(), t0.plusSeconds(10));
    bet(late.id(), m, 0, "50", UUID.randomUUID().toString(), t0.plusSeconds(30));
    bet(a.id(), m, 1, "20", UUID.randomUUID().toString(), t0.plusSeconds(40)); // a 晚于开赛的另一笔
    closeAndEnd(m);
    Instant actualStart = t0.plusSeconds(30); // 正好等于开赛时间收到的也算"之后"
    settle(m, 0, actualStart);
    // 退回 late 的 50、a 的 20；剩下 T = 200（a 押选项 1 的 100、b 押选项 2 的 100），结果选项 1：a 派彩 100 + 100 − 5 = 195
    assertThat(available(late.id())).isEqualByComparingTo("200");
    assertThat(available(a.id())).isEqualByComparingTo("295"); // 200 − 120 + 20（退回）+ 195（派彩）
    assertThat(available(b.id())).isEqualByComparingTo("100");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pool_bet WHERE market_id = ? AND refund_reason = 'LATE_BET'", Integer.class, m.id())).isEqualTo(2);
    assertThat(inbox(late.id(), "LATE_BET_REFUNDED")).isEqualTo(1);
    assertThat(inbox(a.id(), "LATE_BET_REFUNDED")).isEqualTo(1);
    JsonNode p = json.readTree(jdbc.queryForObject("SELECT params FROM notify_message WHERE user_id = ? AND template_code = 'LATE_BET_REFUNDED'", String.class, late.id()));
    assertThat(p.get("amount").asString()).isEqualTo("50.00");
    assertThat(p.get("at").asString()).endsWith("UTC");
    assertThat((BigDecimal) run(m, "PAYOUT").get("pool_total")).as("T 不含退回的").isEqualByComparingTo("200");
    assertThat(jdbc.queryForObject("SELECT actual_start_at FROM market_question WHERE id = ?", java.sql.Timestamp.class, m.id())).isNotNull();
    settledConserved(m, "200");
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void E15_员工账户的下注退回_不参与结算_不发通知(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Market m = shortMarket(2);
    User a = user("100");
    User b = user("100");
    User staffUser = user("100");
    bet(a.id(), m, 0, "50");
    bet(b.id(), m, 1, "50");
    bet(staffUser.id(), m, 1, "100");
    jdbc.update("UPDATE account_user SET is_staff = 1 WHERE id = ?", staffUser.id()); // 漏登记后补登
    closeAndEnd(m);
    settle(m, 0);
    assertThat(available(staffUser.id())).as("员工的下注全额退回").isEqualByComparingTo("100");
    assertThat(available(a.id())).isEqualByComparingTo("147.50"); // 50 + 50 × 95%
    assertThat(jdbc.queryForObject("SELECT refund_reason FROM pool_bet WHERE user_id = ?", String.class, staffUser.id())).isEqualTo("STAFF");
    assertThat(inbox(staffUser.id(), "LATE_BET_REFUNDED") + inbox(staffUser.id(), "MARKET_REFUNDED") + inbox(staffUser.id(), "SETTLE_LOST")).isZero();
    settledConserved(m, "100");
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void 提前截止_新截止时间之后收到的下注全额退回(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Market m = market(2, Instant.now(clock).plus(Duration.ofHours(2)), null);
    Instant t0 = Instant.now(clock);
    User a = user("100");
    User b = user("100");
    bet(a.id(), m, 0, "30", UUID.randomUUID().toString(), t0);
    bet(b.id(), m, 1, "20", UUID.randomUUID().toString(), t0.plusSeconds(60));
    bet(a.id(), m, 1, "10", UUID.randomUUID().toString(), t0.plusSeconds(90));
    advance(Duration.ofSeconds(120));
    admin.advanceClose(Long.parseLong(m.no()), t0.plusSeconds(60).toString(), 1, "比赛提前开始");
    assertThat(available(b.id())).as("新截止时间（含）之后收到的退回").isEqualByComparingTo("100");
    assertThat(available(a.id())).isEqualByComparingTo("70");
    assertThat(inbox(b.id(), "LATE_BET_REFUNDED")).isEqualTo(1);
    assertThat(sumBets(m)).isEqualByComparingTo("30");
    assertThat(sumPools(m)).isEqualByComparingTo("30");
    assertThat(sumPositions(m)).isEqualByComparingTo("30");
    assertThat(stillLocked(m)).isEqualByComparingTo("30");
    marketService.closeDue();
    assertThat(status(m)).as("退回以后只剩一个选项有人押：截止时作废（E3）").isEqualTo("VOIDED");
    if (batch > 0) settle.drainAll();
    assertThat(available(a.id())).isEqualByComparingTo("100");
    assertThat(ledgerQuery.mismatches()).isEmpty();
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void 只有一边有人押的赢方_拿回本金_平台不收费(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Market m = shortMarket(2);
    User a = user("100");
    User b = user("100");
    bet(a.id(), m, 0, "40");
    bet(b.id(), m, 1, "10");
    jdbc.update("UPDATE account_user SET is_staff = 1 WHERE id = ?", b.id());
    closeAndEnd(m);
    settle(m, 0);
    assertThat(available(a.id())).as("员工的下注退回后只剩赢方：L = 0，拿回本金").isEqualByComparingTo("100");
    assertThat(status(m)).isEqualTo("SETTLED");
    verify(staff, never()).notifyStaff(eq("SETTLE_ABNORMAL"), argThat(x -> m.no().equals(x.get("marketNo"))));
    settledConserved(m, "40");
  }
}
