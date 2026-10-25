package com.forecast.app.wallet.recon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.notification.api.MailApi;
import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.ledger.api.LedgerQueryApi;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.api.WalletReconApi;
import com.forecast.app.wallet.deposit.ChainClient;
import com.forecast.app.wallet.deposit.ChainClients;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 对账（S14，修复 F5），真实 MySQL。这里故意造出不一致的数据，确认每一类差异都能发现；一致的数据不报。
 * 数据库里有其他测试留下的数据，所以只对这里造的对象断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class ReconTest {

  @Autowired ReconService recon;
  @Autowired ReconMapper mapper;
  @Autowired LedgerApi ledger;
  @Autowired LedgerQueryApi ledgerQuery;
  @Autowired ChainClients clients;
  @Autowired TransactionTemplate tx;
  @Autowired Clock clock;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean MailApi mail;

  @AfterEach
  void cleanup() {
    clients.remove(Chain.ERC20);
  }

  long user(String balance) {
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "rc" + uid + "@example.com");
    ledger.openAccount(uid);
    if (new BigDecimal(balance).signum() > 0) post(uid, Bucket.AVAILABLE, balance, LedgerEntryType.DEPOSIT, "TEST", "seed-" + uid);
    return uid;
  }

  void post(long uid, Bucket b, String amount, LedgerEntryType type, String bizType, String bizId) {
    ledger.apply(new LedgerCommand(uid, b, new BigDecimal(amount), type, bizType, bizId, "对账测试"));
  }

  /** 造一条充值记录，返回 链:交易:序号 */
  String deposit(long uid, String amount, String status, long id[]) {
    String hash = "0x" + UUID.randomUUID().toString().replace("-", "");
    jdbc.update("INSERT INTO wallet_deposit (public_no, chain, tx_hash, log_index, block_number, block_hash, from_address, to_address, user_id, amount, status) "
        + "VALUES (?, 'ERC20', ?, 0, 100, '0xblock', '0xa', '0xb', ?, ?, ?)", com.forecast.app.common.id.Snowflake.shared(1).next(), hash, uid,
        new BigDecimal(amount), status);
    if (id != null) id[0] = jdbc.queryForObject("SELECT id FROM wallet_deposit WHERE tx_hash = ?", Long.class, hash);
    return "ERC20:" + hash + ":0";
  }

  List<WalletReconApi.Issue> open(String kind, String ref) {
    return recon.issues("OPEN", 500).stream().filter(i -> i.kind().equals(kind) && i.refId().equals(ref)).toList();
  }

  @Test
  void 一致的充值不报_没入账的_账本有但钱包没有的_都报出来() {
    long uid = user("0");
    String ok = deposit(uid, "100", "CREDITED", null);
    post(uid, Bucket.AVAILABLE, "100", LedgerEntryType.DEPOSIT, "DEPOSIT", ok);
    String missing = deposit(uid, "70", "CREDITED", null);
    String unknown = "ERC20:0x" + UUID.randomUUID().toString().replace("-", "") + ":0";
    post(uid, Bucket.AVAILABLE, "30", LedgerEntryType.DEPOSIT, "DEPOSIT", unknown);
    recon.run();
    assertThat(recon.issues("OPEN", 500)).noneMatch(i -> i.refId().equals(ok));
    assertThat(open("DEPOSIT_LEDGER_MISMATCH", missing)).singleElement().satisfies(i -> {
      assertThat(i.expected()).isEqualByComparingTo("70");
      assertThat(i.actual()).isEqualByComparingTo("0");
      assertThat(i.userId()).isEqualTo(uid);
    });
    assertThat(open("DEPOSIT_UNKNOWN_CREDIT", unknown)).singleElement().satisfies(i -> assertThat(i.actual()).isEqualByComparingTo("30"));
  }

  @Test
  void 冲正_扣的加挂账等于充值金额才对() {
    long uid = user("0");
    long[] id = new long[1];
    String good = deposit(uid, "100", "REVERSED", id);
    post(uid, Bucket.AVAILABLE, "100", LedgerEntryType.DEPOSIT, "DEPOSIT", good);
    post(uid, Bucket.AVAILABLE, "-20", LedgerEntryType.DEPOSIT_REVERSAL, "DEPOSIT", good);
    jdbc.update("INSERT INTO wallet_suspense (user_id, deposit_id, amount) VALUES (?, ?, 80)", uid, id[0]);
    String bad = deposit(uid, "100", "REVERSED", null);
    post(uid, Bucket.AVAILABLE, "100", LedgerEntryType.DEPOSIT, "DEPOSIT", bad);
    post(uid, Bucket.AVAILABLE, "-30", LedgerEntryType.DEPOSIT_REVERSAL, "DEPOSIT", bad);
    recon.run();
    assertThat(open("DEPOSIT_REVERSAL_MISMATCH", good)).isEmpty();
    assertThat(open("DEPOSIT_REVERSAL_MISMATCH", bad)).singleElement().satisfies(i -> assertThat(i.actual()).isEqualByComparingTo("30"));
  }

  @Test
  void 挂账待处理会报_标记已处理后挂账也关闭_不能重复标记_F5() {
    long uid = user("0");
    long[] id = new long[1];
    deposit(uid, "100", "REVERSED", id);
    jdbc.update("INSERT INTO wallet_suspense (user_id, deposit_id, amount) VALUES (?, ?, 55)", uid, id[0]);
    long sid = jdbc.queryForObject("SELECT id FROM wallet_suspense WHERE deposit_id = ?", Long.class, id[0]);
    recon.run();
    WalletReconApi.Issue issue = open("SUSPENSE_OPEN", String.valueOf(sid)).getFirst();
    assertThat(issue.actual()).isEqualByComparingTo("55");

    assertThatThrownBy(() -> recon.resolve(issue.id(), 7L, " ")).isInstanceOf(ValidationException.class);
    recon.resolve(issue.id(), 7L, "已和客户确认，从下次充值扣回");
    assertThat(open("SUSPENSE_OPEN", String.valueOf(sid))).isEmpty();
    assertThat(recon.issues("RESOLVED", 500)).anySatisfy(i -> {
      assertThat(i.id()).isEqualTo(issue.id());
      assertThat(i.resolvedBy()).isEqualTo(7L);
      assertThat(i.note()).isEqualTo("已和客户确认，从下次充值扣回");
    });
    assertThat(jdbc.queryForObject("SELECT status FROM wallet_suspense WHERE id = ?", String.class, sid)).isEqualTo("RESOLVED");
    assertThatThrownBy(() -> recon.resolve(issue.id(), 7L, "再标一次")).isInstanceOfSatisfying(BizException.class,
        e -> assertThat(e.code()).isEqualTo("ISSUE_NOT_OPEN"));
    recon.run();
    assertThat(open("SUSPENSE_OPEN", String.valueOf(sid))).as("挂账已处理，不再报").isEmpty();
  }

  @Test
  void 重复对账不重复报_处理后问题还在会重新报() {
    long uid = user("0");
    String missing = deposit(uid, "40", "CREDITED", null);
    recon.run();
    recon.run();
    assertThat(open("DEPOSIT_LEDGER_MISMATCH", missing)).hasSize(1);
    recon.resolve(open("DEPOSIT_LEDGER_MISMATCH", missing).getFirst().id(), 7L, "先标记，看还会不会再报");
    recon.run();
    assertThat(open("DEPOSIT_LEDGER_MISMATCH", missing)).as("问题没真正解决，重新报一条").hasSize(1);
  }

  @Test
  void 提现_账本和状态不一致_提现冻结和进行中的合计不一致() {
    long uid = user("1000");
    jdbc.update("INSERT INTO wallet_withdrawal (public_no, user_id, network, address, amount, fee, status, cooling_ends_at, created_at) "
        + "VALUES (?, ?, 'ERC20', '0xa', 100, 5, 'COMPLETED', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", com.forecast.app.common.id.Snowflake.shared(1).next(), uid);
    long wid = jdbc.queryForObject("SELECT MAX(id) FROM wallet_withdrawal WHERE user_id = ?", Long.class, uid);
    // 有冻结，没有转出
    post(uid, Bucket.AVAILABLE, "-100", LedgerEntryType.WITHDRAW_FREEZE, "WITHDRAW", String.valueOf(wid));
    post(uid, Bucket.WITHDRAW_FROZEN, "100", LedgerEntryType.WITHDRAW_FREEZE, "WITHDRAW", String.valueOf(wid));
    recon.run();
    assertThat(open("WITHDRAW_LEDGER_MISMATCH", String.valueOf(wid))).hasSize(1);
    assertThat(open("WITHDRAW_FROZEN_MISMATCH", String.valueOf(uid))).as("已完成的提现，冻结却还在").singleElement().satisfies(i -> {
      assertThat(i.expected()).isEqualByComparingTo("0");
      assertThat(i.actual()).isEqualByComparingTo("100");
    });
  }

  @Test
  void 近期入账的充值去链上核对_区块哈希变了就报() {
    long uid = user("0");
    String key = deposit(uid, "10", "CREDITED", null);
    post(uid, Bucket.AVAILABLE, "10", LedgerEntryType.DEPOSIT, "DEPOSIT", key);
    clients.register(Chain.ERC20, new ChainClient() {
      public long headBlock() { return 1000; }
      public List<TokenTransfer> transfers(long f, long t, String c) { return List.of(); }
      public Optional<String> blockHash(long n) { return Optional.of("0xreorged"); }
    });
    recon.run();
    assertThat(open("DEPOSIT_NOT_ON_CHAIN", key)).hasSize(1);
  }

  @Test
  void 有新问题时通知风控人员_一致时不通知() {
    StaffAlertApi staff = mock(StaffAlertApi.class);
    ReconService service = new ReconService(mapper, ledgerQuery, clients, staff, tx, clock);
    long uid = user("0");
    deposit(uid, "12", "CREDITED", null);
    assertThat(service.run()).isPositive();
    verify(staff).notifyStaff(eq("RECON_ISSUES"), anyMap());
    assertThat(service.run()).as("没有新问题").isZero();
  }
}
