package com.forecast.app.wallet.withdraw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.id.Snowflake;
import com.forecast.app.notification.api.MailApi;
import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.support.MutableClock;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.api.AutoApprove;
import com.forecast.app.wallet.api.AutoApproveAdminApi;
import com.forecast.app.wallet.api.HotWalletApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 提现自动审批（技术方案/v2-提现自动审批.md 第 3、5、10 节），真实 MySQL（C37）：总开关关闭照常判定、自动通过的两条事件和后续流程、大额延迟、
 * 预判和复判不同、冷静期内改规则、快照、判定出错转人工、并发不超 R3（多线程，每笔两个"节点"同时推进）、R8 的数据来源、R11 不算客户自己撤销的、
 * R13 盈利客户（账本的已实现 − 手续费 − 隔夜费，浮盈不计）。
 * 热钱包余额用替身（判定读热钱包余额告警定时查好的值）；广播用 {@link WithdrawFlowTest.FakeSender}；时间用可以往前拨的测试时钟。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class AutoApproveFlowTest {

  static final String TRON_ADDR = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
  static final LocalDateTime NOW = LocalDateTime.ofInstant(FixedClockConfig.NOW, ZoneOffset.UTC);

  @Autowired WithdrawService withdraws;
  @Autowired WhitelistService whitelist;
  @Autowired WithdrawSenders senders;
  @Autowired AutoApproveService auto;
  @Autowired AutoApproveAdminApi admin;
  @Autowired AccountApi accounts;
  @Autowired LedgerApi ledger;
  @Autowired SystemConfig config;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;
  @MockitoBean MailApi mail;
  @MockitoBean HotWalletApi hot;

  MutableClock time() {
    return (MutableClock) clock;
  }

  @BeforeEach
  void setUp() {
    // 前面的测试自动通过的金额挪到 24 小时以外，R2 / R3 的累计从 0 开始
    jdbc.update("UPDATE wallet_withdrawal SET auto_approved_at = auto_approved_at - INTERVAL 400 DAY WHERE review_mode = 'AUTO'");
    hotBalance("10000000");
    save(true, Map.of("R8.enabled", "false"));
  }

  @AfterEach
  void tearDown() {
    time().reset();
    for (Chain c : Chain.values()) senders.remove(c);
    // 恢复默认（总开关关闭），不影响其他测试
    save(false, Map.of());
  }

  /** 三条链的热钱包 USDT 余额，查询时间 = 现在 */
  void hotBalance(String usdt) {
    when(hot.hotBalances()).thenAnswer(inv -> List.of(
        new HotWalletApi.Balance("TRC20", "USDT", false, "T-hot", new BigDecimal(usdt), BigDecimal.ZERO, false, clock.instant(), null),
        new HotWalletApi.Balance("TRC20", "TRX", true, "T-hot", new BigDecimal("1000"), BigDecimal.ZERO, false, clock.instant(), null)));
  }

  /** 像后台保存一样写规则配置（默认值 + overrides，键是 "R1.maxAmount" 这样的），版本号 +1 */
  long save(boolean master, Map<String, String> overrides) {
    Map<String, String> cfg = new HashMap<>(AutoApprove.parse(Map.of()).toConfig());
    cfg.put(AutoApprove.MASTER_KEY, String.valueOf(master));
    overrides.forEach((k, v) -> cfg.put(AutoApprove.PREFIX + k, v));
    Map<ConfigKey, String> values = new java.util.LinkedHashMap<>();
    cfg.forEach((k, v) -> values.put(ConfigKey.of(k).orElseThrow(), v));
    long v = Long.parseLong(jdbc.queryForObject("SELECT value FROM common_config WHERE config_key = ?", String.class, AutoApprove.VERSION_KEY));
    config.setVersioned(ConfigKey.AUTO_APPROVE_VERSION, v, values, 1L, NOW);
    config.refresh();
    return Long.parseLong(jdbc.queryForObject("SELECT value FROM common_config WHERE config_key = ?", String.class, AutoApprove.VERSION_KEY));
  }

  /**
   * 一个满足全部规则的客户：注册 43 天、KYC 已通过、充值 deposit、白名单 TRC20 地址已生效、以前往这个地址成功提现过一笔 100。返回 [客户号, 白名单 id]
   */
  long[] customer(String deposit) {
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status, created_at) VALUES (?, ?, 'ACTIVE', '2026-09-01 00:00:00')", uid, "aa" + uid + "@example.com");
    ledger.openAccount(uid);
    ledger.apply(new LedgerCommand(uid, Bucket.AVAILABLE, new BigDecimal(deposit), LedgerEntryType.DEPOSIT, "TEST", "aa-dep-" + uid, "测试入金"));
    jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', ?, 'f', 'b', 's', 'APPROVED')",
        uid, "P" + uid);
    WhitelistRow r = whitelist.add(uid, "TRC20", TRON_ADDR, "测试");
    jdbc.update("UPDATE wallet_whitelist SET active_at = created_at - INTERVAL 1 HOUR WHERE id = ?", r.id);
    jdbc.update("INSERT INTO wallet_withdrawal (public_no, user_id, network, address, amount, fee, status, cooling_ends_at, completed_at, created_at) "
        + "VALUES (?, ?, 'TRC20', ?, 100, 1, 'COMPLETED', '2026-10-01 02:00:00', '2026-10-01 03:00:00', '2026-10-01 00:00:00')", Snowflake.shared(1).next(), uid, TRON_ADDR);
    return new long[] {uid, r.id};
  }

  WithdrawalRow submit(long[] c, String amount) {
    return withdraws.submit(c[0], String.valueOf(c[1]), amount);
  }

  String status(long id) {
    return jdbc.queryForObject("SELECT status FROM wallet_withdrawal WHERE id = ?", String.class, id);
  }

  Map<String, Object> row(long id) {
    return jdbc.queryForMap("SELECT review_mode, auto_approved_at, reviewer_id, reviewed_at, last_check_id, delay_ends_at FROM wallet_withdrawal WHERE id = ?", id);
  }

  /** 判定记录：phase outcome failed_rules，按先后 */
  List<String> checks(long id) {
    return jdbc.queryForList("SELECT CONCAT(phase, ' ', outcome, ' ', failed_rules) FROM wallet_withdraw_rule_check WHERE withdrawal_id = ? ORDER BY id", String.class, id)
        .stream().map(String::strip).toList();
  }

  List<String> hits(long id) {
    return jdbc.queryForList("SELECT rule_code FROM wallet_withdraw_rule_hit WHERE withdrawal_id = ? ORDER BY id", String.class, id);
  }

  /** 提现事件：from→to actor */
  List<String> events(long id) {
    return jdbc.queryForList("SELECT CONCAT(COALESCE(from_status, '-'), '→', to_status, ' ', actor) FROM wallet_withdraw_event WHERE withdrawal_id = ? ORDER BY id",
        String.class, id);
  }

  /** 同 WithdrawFlowTest.notices：按客户筛选，避免 NotifyFlowTest 留下的同编号通知混进来 */
  List<String> notices(long withdrawalId) {
    return jdbc.queryForList("SELECT JSON_UNQUOTE(JSON_EXTRACT(params, '$.status')) FROM notify_message WHERE template_code = 'WITHDRAW_STATUS' "
        + "AND JSON_UNQUOTE(JSON_EXTRACT(params, '$.orderId')) = (SELECT CAST(public_no AS CHAR) FROM wallet_withdrawal WHERE id = ?) "
        + "AND user_id = (SELECT user_id FROM wallet_withdrawal WHERE id = ?) ORDER BY id",
        String.class, withdrawalId, withdrawalId);
  }

  AutoApproveAdminApi.RuleResult rule(long withdrawalId, String code) {
    return admin.detail(withdrawalId).orElseThrow().rules().stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
  }

  void coolDown() {
    time().advance(Duration.ofHours(2).plusSeconds(1));
  }

  @Test
  void 总开关关闭_照常预判和复判并记录_全部转人工_不占额度() {
    save(false, Map.of("R8.enabled", "false"));
    long[] c = customer("1000");
    WithdrawalRow w = submit(c, "100");
    assertThat(checks(w.id)).containsExactly("PRECHECK MASTER_OFF");
    coolDown();
    withdraws.advance();
    assertThat(status(w.id)).isEqualTo("REVIEWING");
    assertThat(checks(w.id)).containsExactly("PRECHECK MASTER_OFF", "FINAL MASTER_OFF");
    assertThat(row(w.id).get("review_mode")).isNull();
    assertThat(row(w.id).get("auto_approved_at")).isNull();
    assertThat(events(w.id)).containsExactly("-→COOLING user:" + c[0], "COOLING→REVIEWING system");
    AutoApproveAdminApi.Detail d = admin.detail(w.id).orElseThrow();
    assertThat(d.masterEnabled()).isFalse();
    assertThat(d.summary().failed()).as("如果打开，本笔会自动通过").isEmpty();
    assertThat(d.precheck().phase()).isEqualTo("PRECHECK");
    // 人工通过照旧，记 MANUAL
    withdraws.approve(w.id, 9L);
    assertThat(row(w.id).get("review_mode")).isEqualTo("MANUAL");
    assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM wallet_withdrawal WHERE review_mode = 'AUTO' AND auto_approved_at > ?", BigDecimal.class,
        NOW.minusDays(1))).as("不占 R2 / R3 额度").isEqualByComparingTo("0");
  }

  @Test
  void 自动通过_小额立即上链_两条事件_审核人为空_客户收到的通知和人工通过一样() {
    WithdrawFlowTest.FakeSender s = new WithdrawFlowTest.FakeSender();
    senders.register(Chain.TRC20, s);
    long version = save(true, Map.of("R8.enabled", "false"));
    long[] c = customer("1000");
    WithdrawalRow w = submit(c, "100");
    assertThat(checks(w.id)).containsExactly("PRECHECK AUTO_APPROVED");
    assertThat(status(w.id)).as("预判不决定结果").isEqualTo("COOLING");
    coolDown();
    withdraws.advance();
    assertThat(status(w.id)).isEqualTo("BROADCASTING");
    assertThat(s.sent).filteredOn(o -> o.withdrawalId() == w.id).singleElement().satisfies(o -> assertThat(o.sendAmount()).isEqualByComparingTo("99"));
    assertThat(checks(w.id)).containsExactly("PRECHECK AUTO_APPROVED", "FINAL AUTO_APPROVED");
    assertThat(events(w.id)).containsExactly("-→COOLING user:" + c[0], "COOLING→REVIEWING system", "REVIEWING→APPROVED system:auto-approve",
        "APPROVED→BROADCASTING system");
    long checkId = jdbc.queryForObject("SELECT MAX(id) FROM wallet_withdraw_rule_check WHERE withdrawal_id = ?", Long.class, w.id);
    assertThat(jdbc.queryForObject("SELECT note FROM wallet_withdraw_event WHERE withdrawal_id = ? AND actor = 'system:auto-approve'", String.class, w.id))
        .isEqualTo("自动审批通过（判定 #" + checkId + "，配置第 " + version + " 版）");
    Map<String, Object> r = row(w.id);
    assertThat(r.get("review_mode")).isEqualTo("AUTO");
    assertThat(r.get("reviewer_id")).isNull();
    assertThat(((LocalDateTime) r.get("auto_approved_at"))).isEqualTo(NOW.plusHours(2).plusSeconds(1));
    assertThat(((Number) r.get("last_check_id")).longValue()).isEqualTo(checkId);
    assertThat(hits(w.id)).isEmpty();
    assertThat(notices(w.id)).as("和人工通过发的是同一条").containsExactly("APPROVED", "BROADCASTING");
    AutoApproveAdminApi.Detail d = admin.detail(w.id).orElseThrow();
    assertThat(d.configVersion()).isEqualTo(version);
    assertThat(d.rules()).hasSize(13);
    assertThat(d.rules().stream().filter(x -> x.code().equals("R8")).findFirst().orElseThrow().status()).isEqualTo("disabled");
    assertThat(rule(w.id, "R4").values()).containsEntry("count", "1");
    assertThat(rule(w.id, "R12").values()).containsEntry("balance", "10000000");
  }

  @Test
  void 大额自动通过照旧延迟6小时_可紧急取消_取消的也占R2额度() {
    save(true, Map.of("R8.enabled", "false", "R1.maxAmount", "5000", "R2.maxUser24h", "10000", "R3.maxPlatform24h", "100000"));
    long[] c = customer("5000");
    WithdrawalRow w = submit(c, "3000");
    coolDown();
    withdraws.advance();
    assertThat(status(w.id)).isEqualTo("DELAYED");
    assertThat(events(w.id)).contains("REVIEWING→DELAYED system:auto-approve");
    assertThat(row(w.id).get("delay_ends_at")).isEqualTo(NOW.plusHours(8).plusSeconds(1));
    assertThat(notices(w.id)).containsExactly("DELAYED");
    withdraws.emergencyCancel(w.id, 11L, "核实中");
    assertThat(status(w.id)).isEqualTo("CANCELLED");
    WithdrawalRow next = submit(c, "100");
    assertThat(rule(next.id, "R2").values()).as("自动通过后被紧急取消的也算（AA-3）").containsEntry("used", "3000").containsEntry("total", "3100");
  }

  @Test
  void 冷静期内解冻_复判触发R6转人工_详情里有和复判不同的预判() {
    long[] c = customer("1000");
    WithdrawalRow w = submit(c, "100");
    assertThat(checks(w.id)).containsExactly("PRECHECK AUTO_APPROVED");
    time().advance(Duration.ofHours(1));
    accounts.freeze(c[0]);
    accounts.unfreeze(c[0]);
    time().advance(Duration.ofHours(1).plusSeconds(1));
    withdraws.advance();
    assertThat(status(w.id)).isEqualTo("REVIEWING");
    assertThat(checks(w.id)).containsExactly("PRECHECK AUTO_APPROVED", "FINAL MANUAL R6");
    assertThat(hits(w.id)).containsExactly("R6");
    assertThat(events(w.id)).containsExactly("-→COOLING user:" + c[0], "COOLING→REVIEWING system");
    assertThat(rule(w.id, "R6").values()).containsEntry("event", "unfreeze").containsEntry("ago", "1.0").containsEntry("hours", "48");
    AutoApproveAdminApi.Detail d = admin.detail(w.id).orElseThrow();
    assertThat(d.summary().phase()).isEqualTo("FINAL");
    assertThat(d.precheck().outcome()).isEqualTo("AUTO_APPROVED");
    assertThat(d.precheck().failed()).isEmpty();
    withdraws.reject(w.id, 9L, "核对后驳回");
    assertThat(row(w.id).get("review_mode")).as("驳回也记 MANUAL").isEqualTo("MANUAL");
  }

  @Test
  void 冷静期内改规则_复判用新配置_判定记录是当时的快照_以后改规则不影响() {
    save(true, Map.of("R8.enabled", "false", "R1.maxAmount", "1000"));
    long[] c = customer("1000");
    WithdrawalRow w = submit(c, "600");
    assertThat(checks(w.id)).containsExactly("PRECHECK AUTO_APPROVED");
    long v = save(true, Map.of("R8.enabled", "false", "R1.maxAmount", "500"));
    coolDown();
    withdraws.advance();
    assertThat(checks(w.id)).containsExactly("PRECHECK AUTO_APPROVED", "FINAL MANUAL R1");
    save(true, Map.of("R8.enabled", "false", "R1.maxAmount", "2000"));
    AutoApproveAdminApi.Detail d = admin.detail(w.id).orElseThrow();
    assertThat(d.configVersion()).isEqualTo(v);
    assertThat(rule(w.id, "R1").values()).containsEntry("amount", "600").containsEntry("limit", "500");
    String cfg = jdbc.queryForObject("SELECT config_json FROM wallet_withdraw_rule_check WHERE id = ?", String.class, d.summary().checkId());
    assertThat(cfg.replace(" ", "")).contains("\"maxAmount\":\"500\"").contains("\"version\":" + v);
  }

  @Test
  void 判定数据读不到_这条无法判定_整笔转人工_记ERROR() {
    long[] c = customer("1000");
    WithdrawalRow w = submit(c, "100");
    when(hot.hotBalances()).thenThrow(new IllegalStateException("内存里的余额读不到"));
    coolDown();
    withdraws.advance();
    assertThat(status(w.id)).isEqualTo("REVIEWING");
    assertThat(checks(w.id)).containsExactly("PRECHECK AUTO_APPROVED", "FINAL ERROR R12");
    assertThat(rule(w.id, "R12").status()).isEqualTo("unknown");
    assertThat(row(w.id).get("review_mode")).isNull();
  }

  @Test
  void 热钱包余额不够付排在前面的和本笔_转人工() {
    hotBalance("50");
    long[] c = customer("1000");
    WithdrawalRow w = submit(c, "100");
    coolDown();
    withdraws.advance();
    assertThat(checks(w.id).getLast()).isEqualTo("FINAL MANUAL R12");
    assertThat(rule(w.id, "R12").values()).containsEntry("issues", "hotWallet").containsEntry("balance", "50");
  }

  @Test
  void 并发_20笔同时复判_每笔两个节点同时推进_自动通过合计不超过R3_没有死锁_每笔只处理一次() throws Exception {
    save(true, Map.of("R8.enabled", "false", "R3.maxPlatform24h", "5000"));
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < 20; i++) ids.add(submit(customer("1000"), "300").id);
    coolDown();
    ExecutorService pool = Executors.newFixedThreadPool(40);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<?>> fs = new ArrayList<>();
    for (long id : ids) {
      for (int node = 0; node < 2; node++) {
        fs.add(pool.submit(() -> {
          go.await();
          withdraws.coolingEnded(id);
          return null;
        }));
      }
    }
    go.countDown();
    for (Future<?> f : fs) f.get();
    pool.shutdown();
    String in = ids.toString().replace("[", "(").replace("]", ")");
    BigDecimal autoTotal = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM wallet_withdrawal WHERE review_mode = 'AUTO' AND id IN " + in, BigDecimal.class);
    assertThat(autoTotal).isLessThanOrEqualTo(new BigDecimal("5000"));
    assertThat(autoTotal).as("300 一笔，最多 16 笔").isEqualByComparingTo("4800");
    for (long id : ids) {
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_withdraw_event WHERE withdrawal_id = ? AND to_status = 'REVIEWING'", Integer.class, id))
          .as("每笔只有一组事件").isEqualTo(1);
      List<String> cs = checks(id);
      assertThat(cs).hasSize(2);
      assertThat(cs.getLast()).as("没有死锁或出错，没通过的只有 R3").isIn("FINAL AUTO_APPROVED", "FINAL MANUAL R3");
    }
    assertThat(ids.stream().filter(id -> status(id).equals("REVIEWING")).count()).isEqualTo(4);
  }

  @Test
  void R8_S1过渡期交易量恒为0_有充值时不通过_R11_客户自己撤销的不算() {
    // 复制源里 R8 的交易量来自差价合约的订单；forecast S1 删除了订单，交易量恒为 0（ledger.api.CustomerFlowApi），S10 改成有效下注本金
    save(true, Map.of());
    long[] c = customer("1000");
    jdbc.update("UPDATE ledger_entry SET created_at = ? WHERE biz_id = ?", NOW.minusDays(5), "aa-dep-" + c[0]);
    // 金额 ≥ 单笔最小提现 100（RQ-16，S2 起默认 100）
    WithdrawalRow w1 = submit(c, "150");
    assertThat(rule(w1.id, "R8").status()).isEqualTo("failed");
    assertThat(rule(w1.id, "R8").values()).containsEntry("volume", "0").containsEntry("deposit", "1000").containsEntry("ratio", "0");
    WithdrawalRow w2 = submit(c, "150");
    withdraws.cancel(c[0], w2.id);
    WithdrawalRow w3 = submit(c, "150");
    WithdrawalRow w4 = submit(c, "150");
    assertThat(rule(w3.id, "R11").values()).containsEntry("count", "2");
    assertThat(rule(w4.id, "R11").values()).as("撤销的那笔不算").containsEntry("count", "3");
    assertThat(rule(w4.id, "R11").status()).isEqualTo("passed");
    assertThat(rule(w4.id, "R9").values()).as("撤销的不算累计提现").containsEntry("withdrawn", "550");
  }

  @Test
  void R13_S1过渡期累计净盈利恒为0_等于门槛算通过_调门槛_关掉不参与() {
    // 复制源里 R13 按差价合约的已实现盈亏、手续费、隔夜费算；forecast S1 恒为 0，S10 改成 Σ(派彩 − 本金) + 补偿，届时补回"盈利转人工"的用例
    save(true, Map.of("R8.enabled", "false"));
    long[] c = customer("1000");
    WithdrawalRow w1 = submit(c, "100");
    coolDown();
    withdraws.advance();
    assertThat(checks(w1.id)).containsExactly("PRECHECK AUTO_APPROVED", "FINAL AUTO_APPROVED");
    assertThat(rule(w1.id, "R13").status()).isEqualTo("passed");
    assertThat(rule(w1.id, "R13").values()).containsEntry("net", "0").containsEntry("realized", "0").containsEntry("limit", "0");
    String cfg = jdbc.queryForObject("SELECT config_json FROM wallet_withdraw_rule_check WHERE id = ?", String.class, row(w1.id).get("last_check_id"));
    assertThat(cfg.replace(" ", "")).as("快照里有 R13 的门槛").contains("\"maxNetProfit\":\"0\"");

    save(true, Map.of("R8.enabled", "false", "R13.maxNetProfit", "0.01"));
    WithdrawalRow w3 = submit(c, "100");
    assertThat(rule(w3.id, "R13").status()).isEqualTo("passed");
    assertThat(rule(w3.id, "R13").values()).containsEntry("limit", "0.01");
    save(true, Map.of("R8.enabled", "false", "R13.enabled", "false"));
    WithdrawalRow w4 = submit(c, "150");
    assertThat(rule(w4.id, "R13").status()).isEqualTo("disabled");
  }
}
