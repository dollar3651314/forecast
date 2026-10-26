package com.forecast.app.settle;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.account.api.CustomerAdminApi;
import com.forecast.app.settle.api.SettleAdminApi;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 员工账户（forecast S10，需求说明书第 3.3 节、第 3.6 节 E15、第 8.1 节、AC-67）：登记以后不能下注；漏登记事后补登的，结算还没开始的市场里已有的下注
 * 当场退回（同步扣持仓、奖池、截止后定格的奖池，对账仍然平），已经在派彩中、已结算的市场不动；取消登记以后可以下注，已退回的不恢复。
 * 审计和权限在后台的测试（{@code admin/security/AdminRiskTest}）。
 */
class StaffAccountTest extends SettleTestSupport {

  @Autowired CustomerAdminApi customers;
  @Autowired SettleAdminApi settleAdmin;

  int activeBets(long uid, Market m) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM pool_bet WHERE user_id = ? AND market_id = ? AND status = 'ACTIVE'", Integer.class, uid, m.id());
  }

  @Test
  void AC67_E15_补登员工_结算没开始的市场当场退回_已结算和派彩中的不动_不能再下注_取消登记后可以() throws Exception {
    User staffUser = user("2000", true);
    User other = user("2000", true);
    Market settled = market(2);
    Market paying = market(2);
    bet(staffUser.id(), settled, 0, "10");
    bet(other.id(), settled, 1, "10");
    bet(staffUser.id(), paying, 0, "20");
    bet(other.id(), paying, 1, "20");
    closeAndEnd(settled);
    settle(settled, 0);
    closeAndEnd(paying);
    settleProps.setBatchSize(500);
    settle.settle(paying.id(), optionId(paying, 0), null, "1"); // 不派完：停在派彩中
    assertThat(phase(paying)).isEqualTo("PAYING");
    // closed 1 分钟后截止（奖池定格）；open 1 天后截止，还开着
    Market open = market(2);
    Market closed = shortMarket(2);
    bet(staffUser.id(), open, 0, "100");
    bet(staffUser.id(), open, 1, "50");
    bet(other.id(), open, 1, "200");
    bet(staffUser.id(), closed, 0, "300");
    bet(other.id(), closed, 1, "300");
    advance(java.time.Duration.ofMinutes(2));
    marketService.closeDue();
    assertThat(status(closed)).isIn("CLOSED", "PENDING_RESOLUTION");
    assertThat(status(open)).isEqualTo("LISTED");
    java.math.BigDecimal availBefore = available(staffUser.id());

    assertThat(customers.setStaff(staffUser.id(), true)).as("改之前不是员工").isFalse();
    SettleAdminApi.StaffRefund r = settleAdmin.refundStaffBets(staffUser.id());
    assertThat(r.markets()).isEqualTo(2);
    assertThat(r.bets()).isEqualTo(3);
    assertThat(r.amount()).isEqualTo("450.00");
    assertThat(available(staffUser.id())).isEqualByComparingTo(availBefore.add(new java.math.BigDecimal("450")));
    assertThat(activeBets(staffUser.id(), open)).isZero();
    assertThat(activeBets(staffUser.id(), closed)).isZero();
    assertThat(activeBets(staffUser.id(), paying)).as("派彩中的不动").isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT DISTINCT refund_reason FROM pool_bet WHERE user_id = ? AND status = 'REFUNDED'", String.class, staffUser.id()))
        .containsExactly("STAFF");
    // 奖池、持仓、账本锁定、定格的奖池同步扣掉：结算前对账仍然平
    assertThat(settle.check(open.id())).isEmpty();
    assertThat(settle.check(closed.id())).isEmpty();
    assertThat(optionPool(open, 0)).isEqualByComparingTo("0");
    assertThat(optionPool(open, 1)).isEqualByComparingTo("200");
    assertThat(jdbc.queryForObject("SELECT SUM(final_amount) FROM pool_option_total WHERE market_id = ?", java.math.BigDecimal.class, closed.id()))
        .isEqualByComparingTo("300");
    assertThat(ledgerQuery.mismatches()).isEmpty();
    assertThat(inbox(staffUser.id(), "MARKET_REFUNDED") + inbox(staffUser.id(), "LATE_BET_REFUNDED")).as("员工的退回没有客户通知").isZero();

    // AC-67：登记后下注被拒
    assertThat(tryBet(staffUser.id(), open, 0, "1")).isEqualTo("BET_STAFF_ACCOUNT");
    // 再登记一次：没有可退的
    assertThat(customers.setStaff(staffUser.id(), true)).isTrue();
    assertThat(settleAdmin.refundStaffBets(staffUser.id()).bets()).isZero();

    // 派彩中的市场派完；已结算的不回滚
    settle.drainAll();
    assertThat(phase(paying)).isEqualTo("DONE");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pool_bet WHERE user_id = ? AND market_id = ? AND status = 'REFUNDED'", Integer.class, staffUser.id(),
        settled.id())).isZero();

    // 取消登记：可以下注，已退回的不恢复
    assertThat(customers.setStaff(staffUser.id(), false)).isTrue();
    assertThat(tryBet(staffUser.id(), open, 0, "1")).isEqualTo("OK");
    assertThat(activeBets(staffUser.id(), open)).isEqualTo(1);

    // open 结算：只按剩下的下注算，守恒
    closeAndEnd(open);
    settleProps.setBatchSize(0);
    settle(open, 1);
    settledConserved(open, "201");
    assertThat(List.of(status(open))).containsExactly("SETTLED");
  }
}
