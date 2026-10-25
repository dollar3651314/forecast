package com.forecast.app.settle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.ledger.api.AccountKind;
import com.forecast.app.settle.api.SettleAdminApi;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 已结算后发现裁错的补偿（forecast S7；需求说明书第 3.6 节 E13、AC-35、RQ-8；真实 MySQL、Redis）。
 * 例子：三个选项，A 押选项 1 共 60、B 押选项 2 共 40、C 押选项 2 共 20、D 押选项 3 共 30，T = 150，费率 5%。按选项 1 结算（裁错了）：
 * A 盈利 90、抽成 4.50、派彩 145.50。正确结果是选项 2：B 应得 40 + 60 − 3 = 97，C 应得 20 + 30 − 1.50 = 48.50；A 多拿的不追回、D 本来就输。
 * 补偿：B 97.00、C 48.50，合计 145.50，平台补偿支出账户 −145.50，平台收入不变。
 */
class CompensationTest extends SettleTestSupport {

  @Autowired SettleAdminApi settleAdmin;

  record Setup(Market m, User a, User b, User c, User d) {}

  Setup settledWrong(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Market m = shortMarket(3);
    User a = user("100");
    User b = user("100");
    User c = user("100");
    User d = user("100");
    bet(a.id(), m, 0, "60");
    bet(b.id(), m, 1, "40");
    bet(c.id(), m, 1, "20");
    bet(d.id(), m, 2, "30");
    closeAndEnd(m);
    settle(m, 0);
    assertThat(status(m)).isEqualTo("SETTLED");
    assertThat(available(a.id())).isEqualByComparingTo("185.5");
    return new Setup(m, a, b, c, d);
  }

  long no(Market m) {
    return Long.parseLong(m.no());
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void AC35_补偿_应得减实际只取正数_平台补偿支出入账_不扣任何人_通知17(int batch) throws Exception {
    Setup s = settledWrong(batch);
    Market m = s.m();
    BigDecimal compBefore = platform.balance(AccountKind.PLATFORM_COMPENSATION);
    BigDecimal revenueBefore = revenue();
    SettleAdminApi.CompPlan p = settleAdmin.compensationPreview(no(m), m.options().get(1));
    assertThat(p.originalOptionNo()).isEqualTo(m.options().get(0));
    assertThat(p.correctOptionNo()).isEqualTo(m.options().get(1));
    assertThat(p.total()).isEqualTo("145.50");
    assertThat(p.lines()).extracting(SettleAdminApi.CompLine::userId, SettleAdminApi.CompLine::shouldPayout, SettleAdminApi.CompLine::actualPayout,
        SettleAdminApi.CompLine::amount).containsExactly(
            org.assertj.core.groups.Tuple.tuple(s.b().id(), "97.00", "0.00", "97.00"),
            org.assertj.core.groups.Tuple.tuple(s.c().id(), "48.50", "0.00", "48.50"));
    // 预览不改任何数据
    assertThat(available(s.b().id())).isEqualByComparingTo("60");
    SettleAdminApi.Compensation c = settleAdmin.compensate(no(m), m.options().get(1), "核对后发现裁错（E13）", 1);
    assertThat(c.total()).isEqualTo("145.50");
    assertThat(c.userCount()).isEqualTo(2);
    assertThat(available(s.b().id())).isEqualByComparingTo("157");
    assertThat(available(s.c().id())).isEqualByComparingTo("128.5");
    assertThat(available(s.a().id())).as("多拿的不追回").isEqualByComparingTo("185.5");
    assertThat(available(s.d().id())).isEqualByComparingTo("70");
    assertThat(platform.balance(AccountKind.PLATFORM_COMPENSATION).subtract(compBefore)).isEqualByComparingTo("-145.50");
    assertThat(revenue()).as("补偿不冲减平台收入").isEqualByComparingTo(revenueBefore);
    assertThat(ledgerSum(m, "COMPENSATION", "AVAILABLE")).isEqualByComparingTo("145.50");
    assertThat(ledgerSum(m, "COMPENSATION_EXPENSE", "AVAILABLE")).isEqualByComparingTo("-145.50");
    assertThat(ledgerQuery.mismatches()).isEmpty();
    assertThat(inbox(s.b().id(), "COMPENSATION_PAID")).isEqualTo(1);
    assertThat(inbox(s.c().id(), "COMPENSATION_PAID")).isEqualTo(1);
    assertThat(inbox(s.a().id(), "COMPENSATION_PAID")).isZero();
    assertThat(inbox(s.d().id(), "COMPENSATION_PAID")).isZero();
    // 每个市场只补偿一次
    assertThatThrownBy(() -> settleAdmin.compensate(no(m), m.options().get(2), "再补一次", 1)).extracting(e -> ((BizException) e).code())
        .isEqualTo("COMPENSATION_EXISTS");
    // 查补偿单（明细）
    SettleAdminApi.Compensation got = settleAdmin.compensation(Long.parseLong(c.compensationNo())).orElseThrow();
    assertThat(got.lines()).hasSize(2);
    assertThat(settleAdmin.compensations(1, 100).items()).anyMatch(x -> x.compensationNo().equals(c.compensationNo()) && x.lines().isEmpty());
  }

  @Test
  void 本应作废_应得是全额退款_正确选项没人押也按作废算() throws Exception {
    Setup s = settledWrong(0);
    SettleAdminApi.CompPlan p = settleAdmin.compensationPreview(no(s.m()), "void");
    assertThat(p.correctOptionNo()).isEqualTo("void");
    assertThat(p.total()).isEqualTo("90.00");
    assertThat(p.lines()).extracting(SettleAdminApi.CompLine::userId, SettleAdminApi.CompLine::amount).containsExactly(
        org.assertj.core.groups.Tuple.tuple(s.b().id(), "40.00"), org.assertj.core.groups.Tuple.tuple(s.c().id(), "20.00"),
        org.assertj.core.groups.Tuple.tuple(s.d().id(), "30.00"));
    // 正确结果是一个没人押的选项（4 选项的市场）：同样按作废（E1）算
    settleProps.setBatchSize(0);
    Market m = shortMarket(3);
    User a = user("100");
    User b = user("100");
    bet(a.id(), m, 0, "10");
    bet(b.id(), m, 1, "30");
    closeAndEnd(m);
    settle(m, 0);
    SettleAdminApi.CompPlan q = settleAdmin.compensationPreview(no(m), m.options().get(2));
    assertThat(q.lines()).extracting(SettleAdminApi.CompLine::userId, SettleAdminApi.CompLine::amount)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(b.id(), "30.00"));
  }

  @Test
  void 校验_结果相同_不是已结算_没人要补偿() throws Exception {
    Setup s = settledWrong(0);
    assertThat(settleAdmin.checkCompensation(no(s.m()), m0(s))).extracting(ValidationException.Field::code).containsExactly("sameAsResult");
    assertThat(settleAdmin.checkCompensation(no(s.m()), null)).extracting(ValidationException.Field::code).containsExactly("required");
    assertThat(settleAdmin.checkCompensation(no(s.m()), "123")).extracting(ValidationException.Field::code).containsExactly("invalid");
    Market open = market(2);
    assertThatThrownBy(() -> settleAdmin.compensationPreview(no(open), open.options().get(0))).extracting(e -> ((BizException) e).code())
        .isEqualTo("MARKET_NOT_COMPENSABLE");
    // 只有一个客户、两个选项各押 10：按哪个结果他都拿回 19.50，没有人少拿
    settleProps.setBatchSize(0);
    Market m = shortMarket(2);
    User a = user("100");
    bet(a.id(), m, 0, "10");
    bet(a.id(), m, 1, "10");
    closeAndEnd(m);
    settle(m, 0);
    assertThat(settleAdmin.compensationPreview(no(m), m.options().get(1)).lines()).isEmpty();
    assertThatThrownBy(() -> settleAdmin.compensate(no(m), m.options().get(1), "x", 1)).extracting(e -> ((BizException) e).code())
        .isEqualTo("COMPENSATION_NOTHING");
  }

  String m0(Setup s) {
    return s.m().options().get(0);
  }

  @Test
  void 同一个市场4个人同时确认补偿_只入账一次() throws Exception {
    Setup s = settledWrong(0);
    Market m = s.m();
    ExecutorService ex = Executors.newFixedThreadPool(4);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<String>> fs = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      long admin = 30 + i;
      fs.add(ex.submit(() -> {
        go.await();
        try {
          settleAdmin.compensate(no(m), m.options().get(1), "并发", admin);
          return "OK";
        } catch (BizException e) {
          return e.code();
        }
      }));
    }
    go.countDown();
    List<String> r = new ArrayList<>();
    for (Future<String> f : fs) r.add(f.get(60, TimeUnit.SECONDS));
    ex.shutdown();
    assertThat(Collections.frequency(r, "OK")).isEqualTo(1);
    assertThat(Collections.frequency(r, "COMPENSATION_EXISTS")).isEqualTo(3);
    assertThat(available(s.b().id())).isEqualByComparingTo("157");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE ref_market_id = ? AND entry_type = 'COMPENSATION'", Integer.class, m.id()))
        .isEqualTo(2);
    assertThat(ledgerQuery.mismatches()).isEmpty();
  }

  @Test
  void 报表_平台收入和补偿支出分开列() throws Exception {
    LocalDate today = LocalDate.ofInstant(java.time.Instant.now(clock), ZoneOffset.UTC);
    SettleAdminApi.Report before = settleAdmin.report(today.minusDays(1).toString(), today.plusDays(1).toString());
    Setup s = settledWrong(0);
    LocalDate settledDay = LocalDate.ofInstant(java.time.Instant.now(clock), ZoneOffset.UTC);
    settleAdmin.compensate(no(s.m()), s.m().options().get(1), "裁错", 1);
    SettleAdminApi.Report after = settleAdmin.report(today.minusDays(1).toString(), settledDay.plusDays(1).toString());
    assertThat(new BigDecimal(after.feeIncome()).subtract(new BigDecimal(before.feeIncome()))).isEqualByComparingTo("4.50");
    assertThat(new BigDecimal(after.revenue()).subtract(new BigDecimal(before.revenue()))).as("平台收入 = 抽成 + 尾差，不扣补偿").isEqualByComparingTo("4.50");
    assertThat(new BigDecimal(after.compensationExpense()).subtract(new BigDecimal(before.compensationExpense()))).isEqualByComparingTo("145.50");
    assertThat(after.compensations() - before.compensations()).isEqualTo(1);
    assertThat(after.days()).isNotEmpty();
    assertThatThrownBy(() -> settleAdmin.report("2026-13-01", null)).isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(((ValidationException) e).fields().toString()).contains("from").contains("to"));
    assertThatThrownBy(() -> settleAdmin.report("2026-01-01", "2025-01-01")).isInstanceOf(ValidationException.class);
  }
}
