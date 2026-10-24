package com.forecast.app.wallet.withdraw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.forecast.app.notification.api.MailApi;
import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.support.MutableClock;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.chain.JsonRpc;
import com.forecast.app.wallet.chain.TronHttp;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实广播器（S13b）+ 本地 HTTP 替身节点 + 真实 MySQL。热钱包测试私钥就是数字 1（见 SigningTest）。
 * 重点：连不上节点不退款、节点明确拒绝才可能退款、nonce 由数据库管理、超时加价重发、TRON 过期重建、不签被调包的交易。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class BroadcastFlowTest {

  static final String ETH_TO = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed";
  static final String TRON_TO = "TUiRdTFS88JN9k1joQ5Hw6zgBBP9MtLobc";
  static final String USDT_ERC20 = "0xdAC17F958D2ee523a2206206994597C13D831ec7";
  static final String USDT_TRC20 = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

  @Autowired WithdrawService withdraws;
  @Autowired WhitelistService whitelist;
  @Autowired WithdrawSenders senders;
  @Autowired WithdrawTxMapper txs;
  @Autowired TransactionTemplate tx;
  @Autowired LedgerApi ledger;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;
  @Autowired ObjectMapper json;
  @MockitoBean MailApi mail;

  final StaffAlertApi staff = mock(StaffAlertApi.class);
  final HotWalletProperties hotProps = new HotWalletProperties(null, null, null, null, 11155111L, 97L, 100000, 30_000_000, Duration.ofMinutes(30), 3);
  HttpServer server;
  final List<String> calls = new CopyOnWriteArrayList<>();
  volatile Function<String, String> node;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      String key = ex.getRequestURI().getPath() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      calls.add(key);
      String resp = node.apply(key);
      byte[] out = (resp == null ? "down" : resp).getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(resp == null ? 503 : 200, out.length);
      ex.getResponseBody().write(out);
      ex.close();
    });
    server.start();
    jdbc.update("DELETE FROM wallet_chain_nonce");
  }

  @AfterEach
  void stop() {
    server.stop(0);
    ((MutableClock) clock).reset();
    for (Chain c : Chain.values()) senders.remove(c);
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  // ---------- 准备一笔待审核的提现 ----------

  long uid;

  long reviewing(String network, String address, String amount) {
    uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "bc" + uid + "@example.com");
    ledger.openAccount(uid);
    ledger.apply(new LedgerCommand(uid, Bucket.BALANCE, new BigDecimal("5000"), LedgerEntryType.DEPOSIT, "TEST", "dep-" + uid, "测试入金"));
    jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', ?, 'f', 'b', 's', 'APPROVED')",
        uid, "P" + uid);
    WhitelistRow w = whitelist.add(uid, network, address, "测试");
    jdbc.update("UPDATE wallet_whitelist SET active_at = created_at - INTERVAL 1 HOUR WHERE id = ?", w.id);
    WithdrawalRow r = withdraws.submit(uid, String.valueOf(w.id), amount);
    ((MutableClock) clock).advance(Duration.ofHours(3));
    withdraws.advance();
    return r.id;
  }

  String status(long id) {
    return jdbc.queryForObject("SELECT status FROM wallet_withdrawal WHERE id = ?", String.class, id);
  }

  BigDecimal bal() {
    return ledger.snapshot(uid).balance();
  }

  // ---------- 以太坊体系 ----------

  volatile String sendRawResult = "ok";
  volatile String receipt = "null";
  volatile long head = 1000;

  String evm(String req) {
    if (req.contains("eth_call")) return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" + Long.toHexString(1_000_000_000_000L) + "\"}";
    if (req.contains("eth_gasPrice")) return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x4a817c800\"}";
    if (req.contains("eth_getBalance")) return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0xde0b6b3a7640000\"}";
    if (req.contains("eth_getTransactionCount")) return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x5\"}";
    if (req.contains("eth_blockNumber")) return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" + Long.toHexString(head) + "\"}";
    if (req.contains("eth_getTransactionReceipt")) return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + receipt + "}";
    if (req.contains("eth_sendRawTransaction")) {
      if (sendRawResult == null) return null;
      if (sendRawResult.equals("ok")) return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0xabc\"}";
      return "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"" + sendRawResult + "\"}}";
    }
    return null;
  }

  EvmWithdrawSender evmSender() {
    node = this::evm;
    EvmWithdrawSender s = new EvmWithdrawSender(Chain.ERC20, new JsonRpc(url(), json), SigningTest.TEST_KEY, USDT_ERC20, 6, 11155111L, 12, hotProps, txs, tx, staff, clock);
    senders.register(Chain.ERC20, s);
    return s;
  }

  List<WithdrawTxMapper.TxRow> attempts(long id) {
    return txs.attempts(id);
  }

  @Test
  void ERC20_审核通过后签名广播_nonce从链上同步再由数据库递增_确认12个后完成() {
    evmSender();
    long a = reviewing("ERC20", ETH_TO, "100");
    withdraws.approve(a, 9L);
    assertThat(status(a)).isEqualTo("BROADCASTING");
    assertThat(attempts(a)).singleElement().satisfies(t -> assertThat(t.nonce()).isEqualTo(5L));
    // 22 gwei = 20 gwei × 1.1
    assertThat(attempts(a).getFirst().feePrice()).isEqualTo(22_000_000_000L);
    assertThat(EvmWithdrawSender.decodeData(attempts(a).getFirst().rawTx())).contains("5aaeb6053f3e94c9b9a09f33669435e7ef1beaed").endsWith(Long.toHexString(95_000_000L));
    long b = reviewing("ERC20", ETH_TO, "50");
    withdraws.approve(b, 9L);
    assertThat(attempts(b).getFirst().nonce()).as("第二笔用数据库里的下一个 nonce，不再问链").isEqualTo(6L);

    String hash = attempts(a).getFirst().txHash();
    receipt = "{\"status\":\"0x1\",\"blockNumber\":\"0x" + Long.toHexString(head - 10) + "\",\"transactionHash\":\"" + hash + "\"}";
    withdraws.track();
    assertThat(status(a)).as("11 个确认").isEqualTo("BROADCASTING");
    head += 1;
    withdraws.track();
    assertThat(status(a)).isEqualTo("COMPLETED");
    assertThat(jdbc.queryForObject("SELECT tx_hash FROM wallet_withdrawal WHERE id = ?", String.class, a)).isEqualTo(hash);
  }

  /**
   * F-22：BSC 出金和 ERC20 共用以太坊体系的广播器，但 USDT 是 18 位小数、chainId 是 97（测试网）/ 56（主网）。
   * 检查：金额按 18 位换算（扣掉 0.5 手续费）、签名带 BSC 的 chainId（EIP-155，换到别的链上不能重放）、签名人是热钱包、确认 15 个后完成。
   */
  @Test
  void BSC_18位精度和chainId97签名广播_确认15个后完成_F22() throws Exception {
    String usdtBsc = "0x55d398326f99059fF775485246999027B3197955";
    node = req -> req.contains("eth_call")
        ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" + new java.math.BigInteger("1000000").multiply(java.math.BigInteger.TEN.pow(18)).toString(16) + "\"}"
        : evm(req);
    senders.register(Chain.BSC, new EvmWithdrawSender(Chain.BSC, new JsonRpc(url(), json), SigningTest.TEST_KEY, usdtBsc, 18, 97L, 15, hotProps, txs, tx, staff,
        clock));
    long a = reviewing("BSC", ETH_TO, "100");
    withdraws.approve(a, 9L);
    assertThat(status(a)).isEqualTo("BROADCASTING");
    String raw = attempts(a).getFirst().rawTx();
    // 100 - 0.5 手续费 = 99.5 USDT = 99.5 × 10^18
    assertThat(EvmWithdrawSender.decodeData(raw)).contains("5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")
        .endsWith(new BigDecimal("99.5").movePointRight(18).toBigIntegerExact().toString(16));
    var signed = (org.web3j.crypto.SignedRawTransaction) org.web3j.crypto.TransactionDecoder.decode(raw);
    assertThat(signed.getChainId()).as("EIP-155 chainId = BSC 测试网").isEqualTo(97L);
    assertThat(signed.getFrom()).isEqualToIgnoringCase(SigningTest.TEST_KEY.getAddress());
    assertThat(signed.getTo()).as("调用的是 BSC 的 USDT 合约").isEqualToIgnoringCase(usdtBsc);
    assertThat(calls).anyMatch(c -> c.contains("eth_sendRawTransaction"));

    String hash = attempts(a).getFirst().txHash();
    receipt = "{\"status\":\"0x1\",\"blockNumber\":\"0x" + Long.toHexString(head - 13) + "\",\"transactionHash\":\"" + hash + "\"}";
    withdraws.track();
    assertThat(status(a)).as("14 个确认").isEqualTo("BROADCASTING");
    head += 1;
    withdraws.track();
    assertThat(status(a)).isEqualTo("COMPLETED");
  }

  @Test
  void 热钱包余额不够_留在待上链并通知() {
    node = req -> req.contains("eth_getBalance") ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x0\"}" : evm(req);
    EvmWithdrawSender s = new EvmWithdrawSender(Chain.ERC20, new JsonRpc(url(), json), SigningTest.TEST_KEY, USDT_ERC20, 6, 11155111L, 12, hotProps, txs, tx, staff, clock);
    senders.register(Chain.ERC20, s);
    long a = reviewing("ERC20", ETH_TO, "100");
    withdraws.approve(a, 9L);
    assertThat(status(a)).isEqualTo("APPROVED");
    assertThat(attempts(a)).isEmpty();
    // 体验优化 1021 UX-11：{what} 只传币种（这里手续费币 ETH 是 0），"余额不足"由模板写（以前传"手续费币不够"，渲染成"手续费币不够 不足"）
    verify(staff, atLeastOnce()).notifyStaff(eq("HOT_WALLET_LOW"), eq(Map.of("chain", "ERC20", "address", SigningTest.TEST_KEY.getAddress(), "what", "ETH")));
    assertThat(EvmWithdrawSender.gasCoin(Chain.BSC)).isEqualTo("BNB");
  }

  @Test
  void 广播时连不上节点_当作可能已发出_不退款() {
    evmSender();
    sendRawResult = null;
    long a = reviewing("ERC20", ETH_TO, "100");
    withdraws.approve(a, 9L);
    assertThat(status(a)).isEqualTo("BROADCASTING");
    assertThat(bal()).as("冻结还在，没有退款").isEqualByComparingTo("4900");
  }

  @Test
  void 节点明确拒绝_重新同步nonce_重试三次都被拒才退款() {
    evmSender();
    sendRawResult = "nonce too low";
    long a = reviewing("ERC20", ETH_TO, "100");
    withdraws.approve(a, 9L);
    assertThat(status(a)).isEqualTo("APPROVED");
    assertThat(attempts(a)).singleElement().satisfies(t -> assertThat(t.status()).isEqualTo("FAILED"));
    assertThat(jdbc.queryForObject("SELECT next_nonce FROM wallet_chain_nonce WHERE chain = 'ERC20'", Long.class)).as("从链上重新同步").isEqualTo(5L);
    withdraws.advance();
    withdraws.advance();
    assertThat(status(a)).isEqualTo("FAILED_REFUNDED");
    assertThat(bal()).isEqualByComparingTo("5000");
  }

  @Test
  void 已经发出过的提现不会用新nonce再发_防止重启后重复付钱() {
    EvmWithdrawSender s = evmSender();
    long a = reviewing("ERC20", ETH_TO, "100");
    withdraws.approve(a, 9L);
    String first = attempts(a).getFirst().txHash();
    var again = s.send(new WithdrawSender.Order(a, "ERC20", ETH_TO, new BigDecimal("95")));
    assertThat(again).isEqualTo(new WithdrawSender.Sent(first));
    assertThat(attempts(a)).hasSize(1);
  }

  @Test
  void 三十分钟没上链_同一nonce加价重发_最多3次后通知人工() {
    evmSender();
    long a = reviewing("ERC20", ETH_TO, "100");
    withdraws.approve(a, 9L);
    for (int i = 0; i < 3; i++) {
      ((MutableClock) clock).advance(Duration.ofMinutes(31));
      withdraws.track();
    }
    List<WithdrawTxMapper.TxRow> ts = attempts(a);
    assertThat(ts).hasSize(4);
    assertThat(ts).allMatch(t -> t.nonce() == 5L);
    assertThat(ts.get(0).feePrice()).isGreaterThan(ts.get(1).feePrice());
    assertThat(ts.get(1).feePrice()).isGreaterThan(ts.get(2).feePrice());
    ((MutableClock) clock).advance(Duration.ofMinutes(31));
    withdraws.track();
    assertThat(attempts(a)).as("不再重发").hasSize(4);
    // 体验优化 1021 UX-11：提现编号是后台显示的对外编号（public_no），不是内部 id。
    // 同一条链一小时只告警一次，告警的可能是同一个测试类里更早卡住的那笔：只检查编号是某笔提现的 public_no
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> stuck = ArgumentCaptor.forClass(Map.class);
    verify(staff, atLeastOnce()).notifyStaff(eq("WITHDRAW_STUCK"), stuck.capture());
    String no = stuck.getValue().get("withdrawalId");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_withdrawal WHERE CAST(public_no AS CHAR) = ? AND public_no <> id", Integer.class, no))
        .as("WITHDRAW_STUCK 的编号 %s 是对外编号", no).isEqualTo(1);
    assertThat(status(a)).as("不自动退款").isEqualTo("BROADCASTING");
    // 加价后的那一笔上链了：用它的哈希完成
    String last = ts.get(0).txHash();
    node = req -> req.contains("eth_getTransactionReceipt") && req.contains(last)
        ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":\"0x1\",\"blockNumber\":\"0x1\"}}" : evm(req);
    withdraws.track();
    assertThat(status(a)).isEqualTo("COMPLETED");
    assertThat(jdbc.queryForObject("SELECT tx_hash FROM wallet_withdrawal WHERE id = ?", String.class, a)).isEqualTo(last);
  }

  @Test
  void 链上执行失败_转账没发生_退款() {
    evmSender();
    long a = reviewing("ERC20", ETH_TO, "100");
    withdraws.approve(a, 9L);
    receipt = "{\"status\":\"0x0\",\"blockNumber\":\"0x1\"}";
    withdraws.track();
    assertThat(status(a)).isEqualTo("FAILED_REFUNDED");
    assertThat(bal()).isEqualByComparingTo("5000");
  }

  // ---------- TRON ----------

  volatile boolean tamper = false;
  volatile String txInfo = "{}";
  volatile long expiration = FixedClockConfig.NOW.toEpochMilli() + 60_000;
  final List<String> broadcasts = new CopyOnWriteArrayList<>();

  String tron(String req) {
    if (req.startsWith("/wallet/triggerconstantcontract")) return "{\"constant_result\":[\"" + "0".repeat(48) + "e8d4a51000\"]}";
    if (req.startsWith("/wallet/getaccount")) return "{\"balance\":100000000}";
    if (req.startsWith("/wallet/getnowblock")) return "{\"block_header\":{\"raw_data\":{\"number\":5000}}}";
    if (req.startsWith("/wallet/gettransactioninfobyid")) return txInfo;
    if (req.startsWith("/wallet/triggersmartcontract")) {
      var n = json.readTree(req.substring(req.indexOf(' ') + 1));
      String rawHex = "0a02" + n.path("owner_address").asString().substring(2) + "12" + n.path("contract_address").asString().substring(2) + "1a"
          + "a9059cbb" + n.path("parameter").asString() + String.format("%016x", System.nanoTime());
      if (tamper) rawHex = rawHex.replace(n.path("parameter").asString().substring(24, 64), "1111111111111111111111111111111111111111");
      String txId = TronWithdrawSender.sha256Hex(rawHex);
      return "{\"result\":{\"result\":true},\"transaction\":{\"txID\":\"" + txId + "\",\"raw_data\":{\"expiration\":" + expiration + "},\"raw_data_hex\":\"" + rawHex + "\"}}";
    }
    if (req.startsWith("/wallet/broadcasttransaction")) {
      broadcasts.add(req);
      return "{\"result\":true}";
    }
    return null;
  }

  void tronSender() {
    node = this::tron;
    senders.register(Chain.TRC20, new TronWithdrawSender(new TronHttp(url(), json), json, SigningTest.TEST_KEY, USDT_TRC20, 6, 19, hotProps, txs, staff, clock));
  }

  @Test
  void TRC20_节点构造_本地签名_广播_确认19个后完成_F2() {
    tronSender();
    long a = reviewing("TRC20", TRON_TO, "100");
    withdraws.approve(a, 9L);
    assertThat(status(a)).isEqualTo("BROADCASTING");
    var t = attempts(a).getFirst();
    assertThat(broadcasts).singleElement().satisfies(b -> assertThat(b).contains(t.txHash()).contains("\"signature\":[\""));
    // 99 USDT = 0x5e69ec0（6 位精度），转给 TUiRd… 的 20 字节地址
    assertThat(calls.stream().filter(c -> c.startsWith("/wallet/triggersmartcontract")).findFirst().orElseThrow())
        .contains("cd9eb0fd1d6e8cbe9c53c84cc9c1e2c6b88c0a9a").contains("5e69ec0").contains("\"owner_address\":\"417e5f4552091a69125d5dfcb7b8c2659029395bdf\"");
    txInfo = "{\"id\":\"" + t.txHash() + "\",\"blockNumber\":4985,\"receipt\":{\"result\":\"SUCCESS\"}}";
    withdraws.track();
    assertThat(status(a)).as("16 个确认").isEqualTo("BROADCASTING");
    txInfo = "{\"id\":\"" + t.txHash() + "\",\"blockNumber\":4982,\"receipt\":{\"result\":\"SUCCESS\"}}";
    withdraws.track();
    assertThat(status(a)).isEqualTo("COMPLETED");
  }

  @Test
  void TRON_节点返回的交易被调包_拒绝签名() {
    tronSender();
    tamper = true;
    long a = reviewing("TRC20", TRON_TO, "100");
    withdraws.approve(a, 9L);
    assertThat(status(a)).isEqualTo("APPROVED");
    assertThat(attempts(a)).isEmpty();
    assertThat(broadcasts).isEmpty();
  }

  @Test
  void TRON_过期查不到_重建_三次都过期才退款() {
    tronSender();
    long a = reviewing("TRC20", TRON_TO, "100");
    expiration = clock.millis() + 60_000;
    withdraws.approve(a, 9L);
    for (int i = 0; i < 2; i++) {
      ((MutableClock) clock).advance(Duration.ofMinutes(3));
      expiration = clock.millis() + 60_000;
      withdraws.track();
      assertThat(status(a)).isEqualTo("BROADCASTING");
    }
    assertThat(attempts(a)).hasSize(3);
    assertThat(attempts(a).stream().filter(t -> t.status().equals("EXPIRED"))).hasSize(2);
    ((MutableClock) clock).advance(Duration.ofMinutes(3));
    withdraws.track();
    assertThat(status(a)).isEqualTo("FAILED_REFUNDED");
    assertThat(bal()).isEqualByComparingTo("5000");
  }

  @Test
  void TRON_链上执行失败_退款() {
    tronSender();
    long a = reviewing("TRC20", TRON_TO, "100");
    withdraws.approve(a, 9L);
    txInfo = "{\"blockNumber\":4900,\"receipt\":{\"result\":\"OUT_OF_ENERGY\"}}";
    withdraws.track();
    assertThat(status(a)).isEqualTo("FAILED_REFUNDED");
  }
}
