package com.forecast.app.settle;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.ledger.api.AccountKind;
import com.forecast.app.ledger.api.LedgerQueryApi;
import com.forecast.app.ledger.api.PlatformLedgerApi;
import com.forecast.app.market.core.MarketService;
import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.pool.PoolTestSupport;
import com.forecast.app.settle.recon.PoolReconService;
import com.forecast.app.settle.run.SettleFaults;
import com.forecast.app.settle.run.SettleProperties;
import com.forecast.app.settle.run.SettleService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * settle 模块测试的公共部分（真实 MySQL 8.4、真实 Redis 7，C37）：沿用 pool 的客户、市场、下注（{@link PoolTestSupport}），
 * 加上截止、事件结束、结算、两种事务边界（{@code forecast.settle.batch-size}）的切换和守恒核对。
 * 告警邮件用 spy 核对（{@link StaffAlertApi}，其余行为是真的）。测试数据各自新建，查共享表时带市场或客户的条件（C80）。
 */
public abstract class SettleTestSupport extends PoolTestSupport {

  @MockitoSpyBean protected StaffAlertApi staff;
  /** 派彩每批提交前调用：测试里让它在第 N 批抛异常，模拟中途崩溃（每个测试结束自动复原） */
  @MockitoSpyBean protected SettleFaults faults;
  @Autowired protected SettleService settle;
  @Autowired protected SettleProperties settleProps;
  @Autowired protected MarketService marketService;
  @Autowired protected LedgerQueryApi ledgerQuery;
  @Autowired protected PlatformLedgerApi platform;
  @Autowired protected PoolReconService recon;

  @AfterEach
  void resetBatchSize() {
    settleProps.setBatchSize(0);
    // 待处理的彩池对账差异是全平台的（提现自动审批 R12 看它）：本类测试留下的标成已处理，不影响别的测试
    jdbc.update("UPDATE settle_pool_recon_issue SET status = 'RESOLVED', note = '测试结束清理', resolved_at = UTC_TIMESTAMP(3) WHERE status = 'OPEN'");
  }

  /** 这个市场待处理的差异种类 */
  protected List<String> openIssueKinds(Market m) {
    return jdbc.queryForList("SELECT kind FROM settle_pool_recon_issue WHERE market_id = ? AND status = 'OPEN' ORDER BY id", String.class, m.id());
  }

  /** 选项的内部 id */
  protected long optionId(Market m, int option) {
    return jdbc.queryForObject("SELECT id FROM market_option WHERE public_no = ?", Long.class, Long.parseLong(m.options().get(option)));
  }

  /** 一个 1 分钟后截止的市场（事件结束 = 截止 + 2 小时 5 分钟，见 PoolTestSupport.market） */
  protected Market shortMarket(int options) {
    return market(options, Instant.now(clock).plus(Duration.ofMinutes(1)), null);
  }

  /** 时钟拨到截止之后、跑截止任务；再拨到事件结束之后、跑事件结束任务（市场进入待裁决） */
  protected void closeAndEnd(Market m) {
    Instant close = closeAt(m);
    Instant now = Instant.now(clock);
    if (now.isBefore(close)) advance(Duration.between(now, close));
    marketService.closeDue();
    Instant end = jdbc.queryForObject("SELECT event_end_at FROM market_question WHERE id = ?", java.sql.Timestamp.class, m.id()).toLocalDateTime()
        .toInstant(java.time.ZoneOffset.UTC);
    now = Instant.now(clock);
    if (now.isBefore(end)) advance(Duration.between(now, end));
    marketService.endDue();
  }

  protected Instant closeAt(Market m) {
    return jdbc.queryForObject("SELECT close_at FROM market_question WHERE id = ?", java.sql.Timestamp.class, m.id()).toLocalDateTime()
        .toInstant(java.time.ZoneOffset.UTC);
  }

  /** 按结果结算（batch-size &gt; 0 时再把派彩派完） */
  protected void settle(Market m, int winningOption) {
    settle(m, winningOption, null);
  }

  protected void settle(Market m, int winningOption, Instant actualStartAt) {
    settle.settle(m.id(), optionId(m, winningOption), actualStartAt, "1");
    if (settleProps.getBatchSize() > 0) settle.drainAll();
  }

  protected String phase(Market m) {
    return jdbc.queryForObject("SELECT settle_phase FROM market_question WHERE id = ?", String.class, m.id());
  }

  protected Map<String, Object> run(Market m, String kind) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM settle_run WHERE market_id = ? AND kind = ?", m.id(), kind);
    return rows.isEmpty() ? null : rows.get(0);
  }

  protected BigDecimal revenue() {
    return platform.balance(AccountKind.PLATFORM_REVENUE);
  }

  /** 账本里这个市场的合计：类型:项 → 金额 */
  protected BigDecimal ledgerSum(Market m, String type, String bucket) {
    return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_entry WHERE ref_market_id = ? AND entry_type = ? AND bucket = ?", BigDecimal.class,
        m.id(), type, bucket);
  }

  /** 账本里这个市场还锁着的下注金额 */
  protected BigDecimal stillLocked(Market m) {
    return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_entry WHERE ref_market_id = ? AND bucket = 'BET_LOCKED'", BigDecimal.class, m.id());
  }

  /** 已结算市场的守恒（需求第 3.5 节，按账本核）：Σ解除锁定 = T，Σ(派彩 − 抽成) + 平台(抽成 + 尾差) = T，下注锁定归零，平台收入 = 抽成 + 尾差 ≥ 0 */
  protected void settledConserved(Market m, String total) {
    BigDecimal t = new BigDecimal(total);
    BigDecimal released = ledgerSum(m, "BET_SETTLE", "BET_LOCKED").add(ledgerSum(m, "BET_LOST", "BET_LOCKED")).negate();
    BigDecimal paid = ledgerSum(m, "PAYOUT", "AVAILABLE").add(ledgerSum(m, "FEE", "AVAILABLE"));
    BigDecimal income = ledgerSum(m, "FEE_INCOME", "AVAILABLE").add(ledgerSum(m, "SETTLE_DUST", "AVAILABLE"));
    assertThat(released).as("Σ结算解除的下注锁定 = T").isEqualByComparingTo(t);
    assertThat(paid.add(income)).as("Σ(派彩 − 抽成) + 平台(抽成 + 尾差) = T").isEqualByComparingTo(t);
    assertThat(income.signum()).as("平台收入 ≥ 0").isGreaterThanOrEqualTo(0);
    assertThat(stillLocked(m)).as("这个市场的下注锁定归零").isEqualByComparingTo("0");
    assertThat(ledgerQuery.mismatches()).as("账本合计 = 余额").isEmpty();
  }

  /** 这个客户在这个市场收到的某个模板的站内信条数 */
  protected int inbox(long uid, String template) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE user_id = ? AND template_code = ?", Integer.class, uid, template);
  }

  /** 两种事务边界：0 = 计算和派彩同一个事务（需求原文），500 = 每 500 个持仓一个事务（架构方案建议 B） */
  protected static List<Integer> modes() {
    return List.of(0, 500);
  }
}
