package com.forecast.app.settle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.forecast.app.common.error.BizException;
import com.forecast.app.settle.api.SettleAdminApi;
import com.forecast.app.settle.api.SettleApi;
import com.forecast.app.settle.run.SettleService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 彩池对账（需求说明书第 3.9 节、AC-38；技术方案/v1-架构方案.md 第 4.4 节）：每日对账发现差异 → 记下、告警财务、后台列出、R12 让提现转人工；
 * 结算前对账不平 → 停在"结算异常"、不动钱；派到一半发现差异 → 停止派彩；处理差异后重试 → 照常结算。对账是全库扫描，断言只看本测试自己的市场（C80）。
 */
class PoolReconTest extends SettleTestSupport {

  @Autowired SettleAdminApi settleAdmin;
  @Autowired SettleApi settleApi;

  Market twoSided() throws Exception {
    Market m = shortMarket(2);
    User a = user("100");
    User b = user("100");
    bet(a.id(), m, 0, "60");
    bet(b.id(), m, 1, "40");
    return m;
  }

  long issueId(Market m, String kind) {
    return jdbc.queryForObject("SELECT id FROM settle_pool_recon_issue WHERE market_id = ? AND kind = ? AND status = 'OPEN'", Long.class, m.id(), kind);
  }

  @Test
  void AC38_人为改动奖池_每日对账列出差异_邮件告警_后台能看到_R12转人工_同一差异不重复记() throws Exception {
    Market m = twoSided();
    assertThat(settle.check(m.id())).as("没改之前对得上").isEmpty();
    jdbc.update("UPDATE pool_option_total SET amount = amount + 1 WHERE option_id = ?", optionId(m, 0)); // 人为改动奖池记录
    recon.run();
    assertThat(openIssueKinds(m)).containsExactly("POOL_VS_BETS");
    Map<String, Object> i = jdbc.queryForMap("SELECT expected, actual, diff, source FROM settle_pool_recon_issue WHERE market_id = ?", m.id());
    assertThat((BigDecimal) i.get("expected")).isEqualByComparingTo("100");
    assertThat((BigDecimal) i.get("actual")).isEqualByComparingTo("101");
    assertThat((BigDecimal) i.get("diff")).isEqualByComparingTo("1");
    assertThat(i.get("source")).isEqualTo("DAILY");
    verify(staff, atLeastOnce()).notifyStaff(eq("POOL_RECON_DIFF"), any());
    assertThat(settleApi.openPoolReconIssues()).as("R12：有待处理的差异，提现转人工（规则本身见 AutoApproveEngineTest、AutoApproveFlowTest）").isPositive();
    SettleAdminApi.Page<SettleAdminApi.Issue> page = settleAdmin.issues("open", 1, 100);
    assertThat(page.items()).anySatisfy(x -> {
      assertThat(x.marketNo()).isEqualTo(m.no());
      assertThat(x.kind()).isEqualTo("POOL_VS_BETS");
      assertThat(x.status()).isEqualTo("open");
    });
    // 第二天再跑：同一个差异还没处理，不重复记
    recon.run();
    assertThat(openIssueKinds(m)).containsExactly("POOL_VS_BETS");
    // 处理：改回来、标记已处理（处理说明必填）
    jdbc.update("UPDATE pool_option_total SET amount = amount - 1 WHERE option_id = ?", optionId(m, 0));
    long id = issueId(m, "POOL_VS_BETS");
    assertThatThrownBy(() -> settleAdmin.resolveIssue(id, 1, " ")).isInstanceOf(com.forecast.app.common.error.ValidationException.class);
    SettleAdminApi.Issue done = settleAdmin.resolveIssue(id, 1, "核对后改回");
    assertThat(done.status()).isEqualTo("resolved");
    assertThat(done.note()).isEqualTo("核对后改回");
    assertThatThrownBy(() -> settleAdmin.resolveIssue(id, 1, "再来一次")).isInstanceOf(BizException.class);
    recon.run();
    assertThat(openIssueKinds(m)).isEmpty();
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 500})
  void 结算前对账不平_停在结算异常_不动钱_告警_处理后重试照常结算(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Market m = twoSided();
    closeAndEnd(m);
    jdbc.update("UPDATE pool_option_total SET amount = amount + 5, final_amount = final_amount + 5 WHERE option_id = ?", optionId(m, 1));
    BigDecimal locked = stillLocked(m);
    settle.settle(m.id(), optionId(m, 0), null, "1");
    Map<String, Object> run = run(m, "PAYOUT");
    assertThat(run.get("status")).isEqualTo("ABNORMAL");
    assertThat(run.get("item_count")).isEqualTo(0);
    assertThat((String) run.get("abnormal_reason")).contains("Σ选项奖池 ≠ Σ有效下注");
    assertThat(phase(m)).isEqualTo("ABNORMAL");
    assertThat(status(m)).as("市场停在待裁决（结算异常）").isEqualTo("PENDING_RESOLUTION");
    assertThat(stillLocked(m)).as("不动钱").isEqualByComparingTo(locked);
    assertThat(openIssueKinds(m)).contains("POOL_VS_BETS");
    verify(staff).notifyStaff(eq("SETTLE_ABNORMAL"), argThat(p -> m.no().equals(p.get("marketNo"))));
    // 结算异常时不能改费率（结算已经开始）、再触发结算什么都不做
    assertThatThrownBy(() -> admin.changeFee(Long.parseLong(m.no()), "8", 1, "x")).isInstanceOf(BizException.class);
    settle.settle(m.id(), optionId(m, 0), null, "1");
    assertThat(run(m, "PAYOUT").get("status")).isEqualTo("ABNORMAL");
    // 差异没处理就重试：拒绝
    long runNo = ((Number) run.get("public_no")).longValue();
    assertThatThrownBy(() -> settleAdmin.retry(runNo, "1")).isInstanceOf(BizException.class).extracting(e -> ((BizException) e).code()).isEqualTo("POOL_RECON_OPEN");
    // 改回来、标记已处理、重试：照常结算
    jdbc.update("UPDATE pool_option_total SET amount = amount - 5, final_amount = final_amount - 5 WHERE option_id = ?", optionId(m, 1));
    for (Map<String, Object> x : jdbc.queryForList("SELECT id FROM settle_pool_recon_issue WHERE market_id = ? AND status = 'OPEN'", m.id())) {
      settleAdmin.resolveIssue(((Number) x.get("id")).longValue(), 1, "改回");
    }
    SettleAdminApi.Run after = settleAdmin.retry(runNo, "1");
    if (batch > 0) {
      assertThat(after.status()).isEqualTo("paying");
      settle.drainAll();
    }
    assertThat(run(m, "PAYOUT").get("status")).isEqualTo("DONE");
    assertThat(status(m)).isEqualTo("SETTLED");
    assertThat(((Number) run(m, "PAYOUT").get("public_no")).longValue()).as("还是同一张结算单").isEqualTo(runNo);
    settledConserved(m, "100");
  }

  @Test
  void 派到一半对账发现差异_停止派彩_处理后重试接着派() throws Exception {
    settleProps.setBatchSize(1);
    Market m = shortMarket(2);
    for (int i = 0; i < 4; i++) {
      User u = user("100");
      bet(u.id(), m, i % 2, "10");
    }
    closeAndEnd(m);
    settle.settle(m.id(), optionId(m, 0), null, "1");
    long runId = ((Number) run(m, "PAYOUT").get("id")).longValue();
    assertThat(settle.payOneBatch(runId)).isEqualTo(1);
    // 对账发现了差异（例如账本被人改了）：下一批开始前停下
    settle.recordIssuesForRecon(m.id(), List.of(new SettleService.Finding("POOL_VS_LEDGER", BigDecimal.ONE, BigDecimal.TEN, "测试：派彩中发现差异")),
        LocalDateTime.now(clock.withZone(ZoneOffset.UTC)));
    settle.drainAll();
    assertThat(run(m, "PAYOUT").get("status")).isEqualTo("ABNORMAL");
    assertThat(phase(m)).isEqualTo("ABNORMAL");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settle_item WHERE run_id = ? AND status = 'DONE'", Integer.class, runId)).as("停下前派了 1 个").isEqualTo(1);
    verify(staff).notifyStaff(eq("SETTLE_ABNORMAL"), argThat(p -> m.no().equals(p.get("marketNo"))));
    settleAdmin.resolveIssue(issueId(m, "POOL_VS_LEDGER"), 1, "核对过，是测试数据");
    settleAdmin.retry(((Number) run(m, "PAYOUT").get("public_no")).longValue(), "1");
    settle.drainAll();
    assertThat(run(m, "PAYOUT").get("status")).isEqualTo("DONE");
    settledConserved(m, "40");
  }

  @Test
  void 已结算市场的复核_结算单数字被改了_对账发现守恒差异() throws Exception {
    Market m = twoSided();
    closeAndEnd(m);
    settle(m, 0);
    recon.run();
    assertThat(openIssueKinds(m)).as("刚结算完：对得上").isEmpty();
    jdbc.update("UPDATE settle_run SET sum_fee = sum_fee + 1 WHERE market_id = ? AND kind = 'PAYOUT'", m.id());
    recon.run();
    assertThat(openIssueKinds(m)).containsExactly("CONSERVATION");
    jdbc.update("UPDATE settle_run SET sum_fee = sum_fee - 1 WHERE market_id = ? AND kind = 'PAYOUT'", m.id());
  }

  @ParameterizedTest(name = "batch-size = {0}")
  @ValueSource(ints = {0, 2})
  void 取消时对账不平_退款单停在结算异常_钱不动_处理后重试退完(int batch) throws Exception {
    settleProps.setBatchSize(batch);
    Market m = twoSided();
    jdbc.update("UPDATE pool_position SET amount = amount + 1 WHERE market_id = ? AND option_id = ?", m.id(), optionId(m, 0));
    admin.cancel(Long.parseLong(m.no()), 1, "测试");
    assertThat(status(m)).isEqualTo("CANCELLED");
    Map<String, Object> run = run(m, "REFUND");
    assertThat(run.get("status")).isEqualTo("ABNORMAL");
    assertThat(stillLocked(m)).as("钱不动").isEqualByComparingTo("100");
    assertThat(openIssueKinds(m)).contains("POOL_VS_POSITIONS");
    jdbc.update("UPDATE pool_position SET amount = amount - 1 WHERE market_id = ? AND option_id = ?", m.id(), optionId(m, 0));
    for (Map<String, Object> x : jdbc.queryForList("SELECT id FROM settle_pool_recon_issue WHERE market_id = ? AND status = 'OPEN'", m.id())) {
      settleAdmin.resolveIssue(((Number) x.get("id")).longValue(), 1, "改回");
    }
    settleAdmin.retry(((Number) run.get("public_no")).longValue(), "1");
    if (batch > 0) settle.drainAll();
    assertThat(run(m, "REFUND").get("status")).isEqualTo("DONE");
    assertThat(stillLocked(m)).isEqualByComparingTo("0");
    assertThat(ledgerQuery.mismatches()).isEmpty();
  }
}
