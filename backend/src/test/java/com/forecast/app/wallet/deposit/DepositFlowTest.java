package com.forecast.app.wallet.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.notification.api.MailApi;
import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.wallet.address.AddressService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** 充值（S12a）：链替身 + 真实 MySQL（C37）。和其他测试共用一个 Spring 上下文。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class DepositFlowTest {

  static final String ERC20_USDT = "0xdAC17F958D2ee523a2206206994597C13D831ec7";
  static final String BSC_USDT = "0x55d398326f99059fF775485246999027B3197955";
  static final String TRC20_USDT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

  @Autowired DepositService deposits;
  @Autowired DepositMapper mapper;
  @Autowired ChainClients clients;
  @Autowired AddressService addresses;
  @Autowired LedgerApi ledger;
  @Autowired WalletProperties props;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired com.forecast.app.ledger.api.FundsApi funds;
  @Autowired com.forecast.app.notification.api.StaffAlertApi staff;
  @Autowired com.forecast.app.notification.api.NotifyApi notify;
  @Autowired com.forecast.app.wallet.Confirmations confirmations;
  @Autowired com.forecast.app.common.push.UserEventsApi events;
  @MockitoBean MailApi mail;

  @AfterEach
  void cleanup() {
    for (Chain c : Chain.values()) clients.remove(c);
  }

  long newUser() {
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "w" + uid + "@example.com");
    ledger.openAccount(uid);
    addresses.allocateAddresses(uid);
    return uid;
  }

  /** 新建一条链替身，扫块从 block 开始 */
  FakeChain chain(Chain c, long block) {
    FakeChain f = new FakeChain(block);
    clients.register(c, f);
    jdbc.update("REPLACE INTO wallet_chain_cursor (chain, last_block) VALUES (?, ?)", c.name(), block - 1);
    return f;
  }

  String status(Chain c, String txHash) {
    return jdbc.queryForObject("SELECT status FROM wallet_deposit WHERE chain = ? AND tx_hash = ?", String.class, c.name(), txHash);
  }

  BigDecimal balance(long uid) {
    return ledger.snapshot(uid).balance();
  }

  static BigInteger usdt6(String v) {
    return new BigDecimal(v).movePointRight(6).toBigIntegerExact();
  }

  @Test
  void ERC20_达到12个确认后入账_之前是待确认() {
    long uid = newUser();
    String to = addresses.addressOf(uid, Chain.ERC20).orElseThrow();
    FakeChain f = chain(Chain.ERC20, 1000);
    String tx = f.transfer(1000, ERC20_USDT, to.toLowerCase(), usdt6("100"));
    deposits.run(Chain.ERC20);
    assertThat(status(Chain.ERC20, tx)).isEqualTo("PENDING");
    f.head = 1010;
    deposits.run(Chain.ERC20);
    assertThat(status(Chain.ERC20, tx)).as("11 个确认").isEqualTo("PENDING");
    assertThat(jdbc.queryForObject("SELECT confirmations FROM wallet_deposit WHERE tx_hash = ?", Integer.class, tx)).isEqualTo(11);
    f.head = 1011;
    deposits.run(Chain.ERC20);
    assertThat(status(Chain.ERC20, tx)).isEqualTo("CREDITED");
    assertThat(balance(uid)).isEqualByComparingTo("100");
    deposits.run(Chain.ERC20);
    assertThat(balance(uid)).as("不会重复入账").isEqualByComparingTo("100");
    // 到账通知（AC-07），只发一次
    assertThat(jdbc.queryForList("SELECT params FROM notify_message WHERE user_id = ? AND template_code = 'DEPOSIT_CREDITED'", String.class, uid))
        .singleElement().asString().contains("\"amount\":\"100\"").contains("\"chain\":\"ERC20\"");
  }

  @Test
  void 假币不入账_F3_AC08() {
    long uid = newUser();
    String to = addresses.addressOf(uid, Chain.ERC20).orElseThrow();
    FakeChain f = chain(Chain.ERC20, 2000);
    String fake = f.transfer(2000, "0x1111111111111111111111111111111111111111", to, usdt6("500"));
    f.head = 2020;
    deposits.run(Chain.ERC20);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_deposit WHERE tx_hash = ?", Integer.class, fake)).isZero();
    assertThat(balance(uid)).isEqualByComparingTo("0");
  }

  @Test
  void 最小充值1USDT_低于的不入账_AC41() {
    long uid = newUser();
    String to = addresses.addressOf(uid, Chain.ERC20).orElseThrow();
    FakeChain f = chain(Chain.ERC20, 3000);
    String small = f.transfer(3000, ERC20_USDT, to, usdt6("0.99"));
    String ok = f.transfer(3000, ERC20_USDT, to, usdt6("1"));
    f.head = 3020;
    deposits.run(Chain.ERC20);
    assertThat(status(Chain.ERC20, small)).isEqualTo("BELOW_MIN");
    assertThat(status(Chain.ERC20, ok)).isEqualTo("CREDITED");
    assertThat(balance(uid)).isEqualByComparingTo("1");
    // 通知 #4（S9）：低于最小金额的只发一条站内信（重复扫到不再发），写明金额、网络和最小金额
    deposits.run(Chain.ERC20);
    assertThat(jdbc.queryForList("SELECT params FROM notify_message WHERE user_id = ? AND template_code = 'DEPOSIT_BELOW_MIN'", String.class, uid))
        .singleElement().satisfies(p -> assertThat(p).contains("\"amount\":\"0.99\"").contains("\"chain\":\"ERC20\"").contains("\"min\":\"1\""));
    assertThat(jdbc.queryForList("SELECT channel FROM notify_record WHERE user_id = ? AND template_code = 'DEPOSIT_BELOW_MIN'", String.class, uid))
        .containsExactly("INBOX");
  }

  @Test
  void 确认前区块被回滚_作废不入账() {
    long uid = newUser();
    String to = addresses.addressOf(uid, Chain.ERC20).orElseThrow();
    FakeChain f = chain(Chain.ERC20, 4000);
    String tx = f.transfer(4000, ERC20_USDT, to, usdt6("50"));
    deposits.run(Chain.ERC20);
    f.reorg(4000);
    f.head = 4020;
    deposits.run(Chain.ERC20);
    assertThat(status(Chain.ERC20, tx)).isEqualTo("DROPPED");
    assertThat(balance(uid)).isEqualByComparingTo("0");
  }

  @Test
  void 同一笔重复扫到只记一次() {
    long uid = newUser();
    String to = addresses.addressOf(uid, Chain.ERC20).orElseThrow();
    FakeChain f = chain(Chain.ERC20, 5000);
    String tx = f.transfer(5000, ERC20_USDT, to, usdt6("20"));
    f.head = 5020;
    deposits.run(Chain.ERC20);
    jdbc.update("UPDATE wallet_chain_cursor SET last_block = 4999 WHERE chain = 'ERC20'");
    deposits.run(Chain.ERC20);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_deposit WHERE tx_hash = ?", Integer.class, tx)).isEqualTo(1);
    assertThat(balance(uid)).isEqualByComparingTo("20");
  }

  @Test
  void BSC和ERC20同一个地址_BSC按18位精度_15个确认() {
    long uid = newUser();
    assertThat(addresses.addressOf(uid, Chain.BSC)).isEqualTo(addresses.addressOf(uid, Chain.ERC20));
    String to = addresses.addressOf(uid, Chain.BSC).orElseThrow();
    FakeChain f = chain(Chain.BSC, 6000);
    String tx = f.transfer(6000, BSC_USDT, to, new BigInteger("5000000000000000000"));
    f.head = 6013;
    deposits.run(Chain.BSC);
    assertThat(status(Chain.BSC, tx)).as("14 个确认").isEqualTo("PENDING");
    f.head = 6014;
    deposits.run(Chain.BSC);
    assertThat(status(Chain.BSC, tx)).isEqualTo("CREDITED");
    assertThat(balance(uid)).isEqualByComparingTo("5");
  }

  @Test
  void TRC20_T开头的地址_19个确认入账_别人的地址不记() {
    long uid = newUser();
    String to = addresses.addressOf(uid, Chain.TRC20).orElseThrow();
    assertThat(to).startsWith("T");
    FakeChain f = chain(Chain.TRC20, 7000);
    String tx = f.transfer(7000, TRC20_USDT, to, usdt6("12.345678"));
    String other = f.transfer(7000, TRC20_USDT, "TXYZnotOurs111111111111111111111", usdt6("99"));
    f.head = 7018;
    deposits.run(Chain.TRC20);
    assertThat(status(Chain.TRC20, tx)).isEqualTo("CREDITED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_deposit WHERE tx_hash = ?", Integer.class, other)).isZero();
    assertThat(balance(uid)).isEqualByComparingTo("12.345678");
  }

  @Test
  void 没有配置USDT合约的链不扫块_F3() {
    var noContract = new WalletProperties(props.tronXpub(), props.minDeposit(),
        Map.of(Chain.ERC20, new WalletProperties.ChainConfig(null, "", 6, 12, 12, 200, null)));
    DepositService service = new DepositService(mapper, clients, addresses, ledger, noContract, tx, funds, staff, notify, confirmations, events);
    FakeChain f = chain(Chain.ERC20, 8000);
    service.run(Chain.ERC20);
    assertThat(f.calls).isZero();
  }

  long creditedErc20(long uid, FakeChain f, long block, String amount) {
    String to = addresses.addressOf(uid, Chain.ERC20).orElseThrow();
    String tx = f.transfer(block, ERC20_USDT, to, usdt6(amount));
    f.head = block + 11;
    deposits.run(Chain.ERC20);
    assertThat(status(Chain.ERC20, tx)).isEqualTo("CREDITED");
    return jdbc.queryForObject("SELECT id FROM wallet_deposit WHERE tx_hash = ?", Long.class, tx);
  }

  @Test
  void 入账后被回滚_可用余额够就全额冲正() {
    long uid = newUser();
    FakeChain f = chain(Chain.ERC20, 9000);
    long id = creditedErc20(uid, f, 9000, "100");
    assertThat(balance(uid)).isEqualByComparingTo("100");
    f.reorg(9000);
    deposits.run(Chain.ERC20);
    assertThat(jdbc.queryForObject("SELECT status FROM wallet_deposit WHERE id = ?", String.class, id)).isEqualTo("REVERSED");
    assertThat(balance(uid)).isEqualByComparingTo("0");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_suspense WHERE deposit_id = ?", Integer.class, id)).isZero();
    deposits.run(Chain.ERC20);
    assertThat(balance(uid)).as("不会重复冲正").isEqualByComparingTo("0");
  }

  @Test
  void 入账后被回滚_可用余额不够_不扣成负数_挂账并通知风控_AC09() {
    long uid = newUser();
    FakeChain f = chain(Chain.ERC20, 10000);
    long id = creditedErc20(uid, f, 10000, "100");
    // 客户已经用掉 80（这里用提现冻结模拟：余额 −80、提现冻结 +80；复制源用的是差价合约的挂单冻结），只剩 20 可以自由动用
    ledger.apply(new com.forecast.app.ledger.api.LedgerCommand(uid, com.forecast.app.ledger.api.Bucket.AVAILABLE, new BigDecimal("-80"),
        com.forecast.app.ledger.api.LedgerEntryType.WITHDRAW_FREEZE, "TEST", "frz-" + uid, "测试冻结"));
    ledger.apply(new com.forecast.app.ledger.api.LedgerCommand(uid, com.forecast.app.ledger.api.Bucket.WITHDRAW_FROZEN, new BigDecimal("80"),
        com.forecast.app.ledger.api.LedgerEntryType.WITHDRAW_FREEZE, "TEST", "frz-" + uid, "测试冻结"));
    com.forecast.app.notification.api.StaffAlertApi staffMock = org.mockito.Mockito.mock(com.forecast.app.notification.api.StaffAlertApi.class);
    DepositService service = new DepositService(mapper, clients, addresses, ledger, props, tx, funds, staffMock, notify, confirmations, events);
    f.reorg(10000);
    service.run(Chain.ERC20);
    assertThat(jdbc.queryForObject("SELECT status FROM wallet_deposit WHERE id = ?", String.class, id)).isEqualTo("REVERSED");
    assertThat(balance(uid)).as("只扣了能动用的 20").isEqualByComparingTo("0");
    assertThat(ledger.snapshot(uid).withdrawFrozen()).as("冻结的 80 还在").isEqualByComparingTo("80");
    assertThat(jdbc.queryForObject("SELECT amount FROM wallet_suspense WHERE deposit_id = ?", BigDecimal.class, id)).isEqualByComparingTo("80");
    org.mockito.Mockito.verify(staffMock).notifyStaff(eq("DEPOSIT_SUSPENSE"), org.mockito.ArgumentMatchers.argThat(m -> "80.00000000".equals(m.get("shortfall"))));
  }

  @Test
  void 入账后被回滚_下注锁定的钱不扣_只扣可用余额_S2口径() {
    long uid = newUser();
    FakeChain f = chain(Chain.ERC20, 11000);
    long id = creditedErc20(uid, f, 11000, "100");
    // 客户押了 70（S2 的下注锁定：可用 −70、下注锁定 +70），可用只剩 30；下注中的钱属于奖池，冲正不能动
    ledger.applyAll(java.util.List.of(
        new com.forecast.app.ledger.api.LedgerCommand(uid, com.forecast.app.ledger.api.Bucket.AVAILABLE, new BigDecimal("-70"),
            com.forecast.app.ledger.api.LedgerEntryType.BET_LOCK, "BET", "rev-bet-" + uid, "下注", 1L),
        new com.forecast.app.ledger.api.LedgerCommand(uid, com.forecast.app.ledger.api.Bucket.BET_LOCKED, new BigDecimal("70"),
            com.forecast.app.ledger.api.LedgerEntryType.BET_LOCK, "BET", "rev-bet-" + uid, "下注", 1L)));
    assertThat(funds.withdrawable(uid)).as("可提 = 可用余额，不含下注锁定").isEqualByComparingTo("30");
    com.forecast.app.notification.api.StaffAlertApi staffMock = org.mockito.Mockito.mock(com.forecast.app.notification.api.StaffAlertApi.class);
    DepositService service = new DepositService(mapper, clients, addresses, ledger, props, tx, funds, staffMock, notify, confirmations, events);
    f.reorg(11000);
    service.run(Chain.ERC20);
    assertThat(jdbc.queryForObject("SELECT status FROM wallet_deposit WHERE id = ?", String.class, id)).isEqualTo("REVERSED");
    assertThat(ledger.snapshot(uid).available()).as("可用的 30 扣完").isEqualByComparingTo("0");
    assertThat(ledger.snapshot(uid).betLocked()).as("下注锁定的 70 不动").isEqualByComparingTo("70");
    assertThat(jdbc.queryForObject("SELECT amount FROM wallet_suspense WHERE deposit_id = ?", BigDecimal.class, id)).isEqualByComparingTo("70");
    org.mockito.Mockito.verify(staffMock).notifyStaff(eq("DEPOSIT_SUSPENSE"), org.mockito.ArgumentMatchers.argThat(m -> "70.00000000".equals(m.get("shortfall"))));
  }

  /** 记下推给每个用户的事件（代替 WebSocket） */
  static final class RecordingEvents implements com.forecast.app.common.push.UserEventsApi {
    final java.util.List<Map<String, Object>> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
    final long only;

    RecordingEvents(long only) {
      this.only = only;
    }

    @Override
    public void send(long userId, Map<String, Object> event) {
      if (userId == only) sent.add(event);
    }

    java.util.List<Map<String, Object>> deposits() {
      return sent.stream().filter(e -> "deposit".equals(e.get("type"))).toList();
    }
  }

  @Test
  void 发现待确认的充值就推送_确认数变化_入账_都推给本人_E2E2() {
    long uid = newUser();
    RecordingEvents rec = new RecordingEvents(uid);
    DepositService service = new DepositService(mapper, clients, addresses, ledger, props, tx, funds, staff, notify, confirmations, rec);
    String to = addresses.addressOf(uid, Chain.ERC20).orElseThrow();
    FakeChain f = chain(Chain.ERC20, 12000);
    f.transfer(12000, ERC20_USDT, to, usdt6("100"));
    f.transfer(12000, ERC20_USDT, to, usdt6("0.5"));
    service.run(Chain.ERC20);
    // 发现时就推（原来只有入账时才推，客户端看不到"待确认"），每笔只推一次
    assertThat(rec.deposits()).containsExactly(
        Map.of("type", "deposit", "status", "belowMin", "network", "ERC20", "amount", "0.5", "confirmations", 1),
        Map.of("type", "deposit", "status", "pending", "network", "ERC20", "amount", "100", "confirmations", 1));
    rec.sent.clear();
    service.run(Chain.ERC20);
    assertThat(rec.sent).as("没有变化不推").isEmpty();
    f.head = 12005;
    service.run(Chain.ERC20);
    assertThat(rec.deposits()).as("确认数变了").containsExactly(Map.of("type", "deposit", "status", "pending", "network", "ERC20", "amount", "100", "confirmations", 6));
    rec.sent.clear();
    f.head = 12011;
    service.run(Chain.ERC20);
    assertThat(rec.sent).as("入账后推充值记录和账户").containsExactly(
        Map.of("type", "deposit", "status", "credited", "network", "ERC20", "amount", "100", "confirmations", 12), Map.of("type", "account"));
    assertThat(balance(uid)).isEqualByComparingTo("100");
  }

  @Test
  void 作废时也推送_推送失败不影响扫块() {
    long uid = newUser();
    RecordingEvents rec = new RecordingEvents(uid);
    DepositService service = new DepositService(mapper, clients, addresses, ledger, props, tx, funds, staff, notify, confirmations, rec);
    String to = addresses.addressOf(uid, Chain.ERC20).orElseThrow();
    FakeChain f = chain(Chain.ERC20, 13000);
    String txHash = f.transfer(13000, ERC20_USDT, to, usdt6("30"));
    service.run(Chain.ERC20);
    f.reorg(13000);
    f.head = 13020;
    rec.sent.clear();
    service.run(Chain.ERC20);
    assertThat(rec.deposits()).extracting(e -> e.get("status")).containsExactly("dropped");
    // 推送抛异常：照样记录、照样入账
    com.forecast.app.common.push.UserEventsApi broken = (u, e) -> {
      throw new IllegalStateException("连接断了");
    };
    DepositService failing = new DepositService(mapper, clients, addresses, ledger, props, tx, funds, staff, notify, confirmations, broken);
    String ok = f.transfer(13021, ERC20_USDT, to, usdt6("7"));
    f.head = 13032;
    failing.run(Chain.ERC20);
    assertThat(status(Chain.ERC20, ok)).isEqualTo("CREDITED");
    assertThat(status(Chain.ERC20, txHash)).isEqualTo("DROPPED");
    assertThat(balance(uid)).isEqualByComparingTo("7");
  }

  @SuppressWarnings("unchecked")
  @Test
  void 注册时分配地址_接口返回三条链的地址确认数和最小充值_AC06() throws Exception {
    String email = "dep" + System.nanoTime() + "@example.com";
    mvc.perform(post("/api/auth/email-code").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}"));
    ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(mail, atLeastOnce()).send(eq(email), eq("EMAIL_CODE"), p.capture());
    Cookie c = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"code\":\"" + p.getValue().get("code")
        + "\",\"password\":\"password123\",\"firstName\":\"A\",\"lastName\":\"B\",\"birthDate\":\"1990-05-01\",\"nationality\":\"CN\"}")).andReturn().getResponse().getCookie("fc_session");
    long uid = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_address WHERE user_id = ?", Integer.class, uid)).as("注册时已分配").isEqualTo(2);
    String body = mvc.perform(get("/api/wallet/addresses").cookie(c)).andReturn().getResponse().getContentAsString();
    String eth = addresses.addressOf(uid, Chain.ERC20).orElseThrow();
    String tron = addresses.addressOf(uid, Chain.TRC20).orElseThrow();
    // 以太坊体系只用 CREATE2 合约地址（开发记录/钱包-合约地址方案.md）：就是 application-test.yml 那套参数按公式算出的地址，不是 xpub 派生
    assertThat(eth).isEqualTo(com.forecast.app.wallet.address.Create2.forwarderAddress("0x1111111111111111111111111111111111111111",
        "0x2222222222222222222222222222222222222222", "0x5afe00000000000000000000000000000000a001", "0xba7c000000000000000000000000000000000001",
        com.forecast.app.wallet.address.Create2.salt(uid)));
    assertThat(jdbc.queryForList("SELECT CONCAT(family, ':', scheme) FROM wallet_address WHERE user_id = ? ORDER BY family", String.class, uid))
        .containsExactly("ETH:FORWARDER", "TRON:XPUB");
    assertThat(mvc.perform(get("/api/health")).andReturn().getResponse().getContentAsString()).as("配好了合约地址：ERC20、BEP20 已开通").contains("\"deposit.evm\":\"UP\"");
    assertThat(body).contains("{\"network\":\"ERC20\",\"address\":\"" + eth + "\",\"confirmations\":12,\"etaMinutes\":3,\"minDeposit\":\"1\"}")
        .contains("{\"network\":\"BSC\",\"address\":\"" + eth + "\",\"confirmations\":15,\"etaMinutes\":1,\"minDeposit\":\"1\"}")
        .contains("{\"network\":\"TRC20\",\"address\":\"" + tron + "\",\"confirmations\":19,\"etaMinutes\":1,\"minDeposit\":\"1\"}");
    assertThat(mvc.perform(get("/api/wallet/addresses")).andReturn().getResponse().getStatus()).isEqualTo(401);
    // 充值记录
    FakeChain f = chain(Chain.TRC20, 11000);
    String txHash = f.transfer(11000, TRC20_USDT, tron, usdt6("25.5"));
    String small = f.transfer(11000, TRC20_USDT, tron, usdt6("0.5"));
    f.head = 11005;
    deposits.run(Chain.TRC20);
    String list = mvc.perform(get("/api/wallet/deposits").cookie(c)).andReturn().getResponse().getContentAsString();
    assertThat(list).contains("\"network\":\"TRC20\",\"txHash\":\"" + txHash + "\",\"amount\":\"25.5\",\"status\":\"pending\",\"confirmations\":6,\"requiredConfirmations\":19")
        .contains("\"txHash\":\"" + small + "\",\"amount\":\"0.5\",\"status\":\"belowMin\"");
  }

  /** 可以模拟"连不上节点"的链替身 */
  static class FlakyChain extends FakeChain {
    volatile boolean down;

    FlakyChain(long head) {
      super(head);
    }

    @Override
    public long headBlock() {
      if (down) throw new com.forecast.app.wallet.chain.NodeException.Unreachable("connect timed out", null);
      return super.headBlock();
    }
  }

  @Test
  void 链节点连不上_健康检查的alerts变成DOWN_status和HTTP状态码不变_恢复后UP_AC2_20() throws Exception {
    FlakyChain f = new FlakyChain(20000);
    clients.register(Chain.TRC20, f);
    jdbc.update("REPLACE INTO wallet_chain_cursor (chain, last_block) VALUES ('TRC20', 19999)");
    deposits.run(Chain.TRC20);
    String ok = mvc.perform(get("/api/health")).andReturn().getResponse().getContentAsString();
    assertThat(ok).contains("\"node.TRC20\":\"UP\"").contains("\"scan.TRC20\":\"UP\"");
    f.down = true;
    for (int i = 0; i < 3; i++) {
      try {
        deposits.run(Chain.TRC20);
      } catch (com.forecast.app.wallet.chain.NodeException expected) {
        // 定时任务里会记日志，继续下一轮
      }
    }
    var r = mvc.perform(get("/api/health")).andReturn().getResponse();
    assertThat(r.getStatus()).as("节点不通不能让部署脚本回滚").isEqualTo(200);
    assertThat(r.getContentAsString()).contains("\"status\":\"UP\"").contains("\"alert\":\"ALERT\"").contains("\"node.TRC20\":\"DOWN\"")
        .doesNotContain("connect timed out");
    f.down = false;
    deposits.run(Chain.TRC20);
    assertThat(mvc.perform(get("/api/health")).andReturn().getResponse().getContentAsString()).contains("\"node.TRC20\":\"UP\"");
  }

  @Test
  void 充值记录的编号是雪花ID字符串_不是内部id_AC2_22() throws Exception {
    long uid = newUser();
    String to = addresses.addressOf(uid, Chain.BSC).orElseThrow();
    FakeChain f = chain(Chain.BSC, 30000);
    String txHash = f.transfer(30000, BSC_USDT, to.toLowerCase(), new BigDecimal("3").movePointRight(18).toBigIntegerExact());
    deposits.run(Chain.BSC);
    long internal = jdbc.queryForObject("SELECT id FROM wallet_deposit WHERE tx_hash = ?", Long.class, txHash);
    long no = jdbc.queryForObject("SELECT public_no FROM wallet_deposit WHERE tx_hash = ?", Long.class, txHash);
    assertThat(no).isNotEqualTo(internal).isGreaterThan((1L << 53) - 1);
    assertThat(com.forecast.app.common.id.Snowflake.nodeOf(no)).isEqualTo(1);
    var records = deposits.history(uid, 10);
    assertThat(records).singleElement().extracting(DepositService.DepositRecord::no).isEqualTo(no);
  }
}
