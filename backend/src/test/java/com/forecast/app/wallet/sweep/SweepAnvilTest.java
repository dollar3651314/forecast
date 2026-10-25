package com.forecast.app.wallet.sweep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.Confirmations;
import com.forecast.app.wallet.address.AddressService;
import com.forecast.app.wallet.address.Create2;
import com.forecast.app.wallet.api.SweepAdminApi;
import com.forecast.app.wallet.api.WalletReconApi;
import com.forecast.app.wallet.chain.JsonRpc;
import com.forecast.app.wallet.chain.NodeException;
import com.forecast.app.wallet.deposit.DepositService;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 本地链端到端（方案第 4.11 节）：anvil 上部署真实的 BitGo 工厂、实现合约、批量合约和测试 USDT（不返回 bool，和以太坊主网 USDT 一样）后，
 * 后端用真实的 JSON-RPC 跑完整流程：CREATE2 地址（和链上批量合约算的一致）→ 客户转 USDT → 扫块入账 → 预览 → 执行归集（发起账户签名、批量部署 + 归集）
 * → 确认 → 冷钱包收到 → 扫块认出归集转出 → 再充值（已部署）再归集 → parent 直接 flushTokens（扫块认成"不是归集的转出"）→ 撤销发起账户后归集被拒。
 * <p>只在设置了 {@code FC_SWEEP_ANVIL_RPC} 时运行：由 {@code contracts/script/anvil-e2e.sh} 启动 anvil、部署合约后调用。
 * 私钥：发起账户每次随机生成；部署账户和 parent 用 anvil 自带的已解锁账户（{@code eth_sendTransaction}，不需要私钥）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@EnabledIfEnvironmentVariable(named = "FC_SWEEP_ANVIL_RPC", matches = ".+")
class SweepAnvilTest {

  static final ECKeyPair OPERATOR = key();
  static final ObjectMapper JSON = new ObjectMapper();

  static ECKeyPair key() {
    try {
      return Keys.createEcKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String env(String k) {
    return System.getenv(k);
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("forecast.wallet.chains.ERC20.rpc-url", () -> env("FC_SWEEP_ANVIL_RPC"));
    r.add("forecast.wallet.chains.ERC20.usdt-contract", () -> env("FC_SWEEP_ANVIL_USDT"));
    r.add("forecast.wallet.chains.ERC20.scan-batch", () -> "500");
    r.add("forecast.wallet.sweep.evm.factory", () -> env("FC_SWEEP_ANVIL_FACTORY"));
    r.add("forecast.wallet.sweep.evm.implementation", () -> env("FC_SWEEP_ANVIL_IMPLEMENTATION"));
    r.add("forecast.wallet.sweep.evm.parent", () -> env("FC_SWEEP_ANVIL_PARENT"));
    r.add("forecast.wallet.sweep.evm.batcher", () -> env("FC_SWEEP_ANVIL_BATCHER"));
    r.add("forecast.wallet.sweep.evm.operator-address", () -> "0x" + Keys.getAddress(OPERATOR));
    r.add("forecast.wallet.sweep.evm.operator-key", () -> Numeric.toHexStringWithPrefixZeroPadded(OPERATOR.getPrivateKey(), 64));
    r.add("forecast.wallet.sweep.evm.erc20-chain-id", () -> "31337");
  }

  @MockitoBean com.forecast.app.notification.api.MailApi mail;
  @Autowired AddressService addresses;
  @Autowired DepositService deposits;
  @Autowired SweepService sweep;
  @Autowired LedgerApi ledger;
  @Autowired JdbcTemplate jdbc;
  @Autowired Confirmations confirmations;
  @Autowired WalletReconApi recon;

  final JsonRpc rpc = new JsonRpc(env("FC_SWEEP_ANVIL_RPC"), JSON);
  final String usdt = env("FC_SWEEP_ANVIL_USDT");
  final String batcher = env("FC_SWEEP_ANVIL_BATCHER");
  final String parent = env("FC_SWEEP_ANVIL_PARENT");
  final String deployer = env("FC_SWEEP_ANVIL_DEPLOYER");
  final String operator = "0x" + Keys.getAddress(OPERATOR);

  // ---------- 链上小工具（anvil 的账户已解锁，eth_sendTransaction 不需要私钥） ----------

  JsonNode send(String from, String to, String data) {
    String hash = rpc.call("eth_sendTransaction", List.of(Map.of("from", from, "to", to, "data", data))).asString();
    // anvil 自动出块是异步的：机器忙时（CI）发完交易立刻查回执可能还是空，等它出块再判断（PR #72 的 CI 偶发失败，2026-10-21）
    JsonNode r = rpc.call("eth_getTransactionReceipt", List.of(hash));
    for (int i = 0; i < 50 && (r == null || r.isNull() || r.isMissingNode()); i++) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      r = rpc.call("eth_getTransactionReceipt", List.of(hash));
    }
    assertThat(r.path("status").asString()).as("交易应该成功 " + hash + "，回执：" + r).isEqualTo("0x1");
    return r;
  }

  void mine(long n) {
    rpc.call("anvil_mine", List.of(JsonRpc.toHex(n)));
  }

  static String word(String address) {
    return "0".repeat(24) + address.substring(2).toLowerCase();
  }

  static String word(BigInteger v) {
    String h = v.toString(16);
    return "0".repeat(64 - h.length()) + h;
  }

  BigInteger usdtOf(String a) {
    return JsonRpc.bigHex(rpc.call("eth_call", List.of(Map.of("to", usdt, "data", "0x70a08231" + word(a)), "latest")).asString());
  }

  boolean hasCode(String a) {
    return rpc.call("eth_getCode", List.of(a, "latest")).asString().length() > 2;
  }

  void mint(String to, long usdt6) {
    send(deployer, usdt, SweepAbi.selector("mint(address,uint256)") + word(to) + word(BigInteger.valueOf(usdt6)));
  }

  void scan() {
    for (int i = 0; i < 20; i++) deposits.run(Chain.ERC20);
  }

  /** 入账并过 2 倍确认数 */
  void credit(String to, long usdt6) {
    mint(to, usdt6);
    scan();
    mine(confirmations.of(Chain.ERC20));
    scan();
    mine(2L * confirmations.of(Chain.ERC20) + 1);
    scan();
  }

  long confirm(long no) {
    mine(confirmations.of(Chain.ERC20));
    sweep.advance();
    String status = jdbc.queryForObject("SELECT status FROM wallet_sweep_batch WHERE public_no = ?", String.class, no);
    assertThat(status).isEqualTo("CONFIRMED");
    String tx = jdbc.queryForObject("SELECT tx_hash FROM wallet_sweep_batch WHERE public_no = ?", String.class, no);
    JsonNode r = rpc.call("eth_getTransactionReceipt", List.of(tx));
    long gas = JsonRpc.hex(r.path("gasUsed").asString());
    System.out.println("[anvil-e2e] 归集批次 " + no + " 交易 " + tx + " gasUsed=" + gas + " type=" + r.path("type").asString());
    return gas;
  }

  @Test
  void 部署合约_转USDT_扫块入账_归集_冷钱包收到() {
    // 发起账户：每次随机生成，部署账户（批量合约当前的 owner）把它登记进去，给它一点手续费
    rpc.call("anvil_setBalance", List.of(operator, "0x8ac7230489e80000"));
    send(deployer, batcher, SweepAbi.selector("setOperator(address,bool)") + word(operator) + word(BigInteger.ONE));
    scan(); // 扫块游标从现在开始

    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "anvil" + uid + "@example.com");
    ledger.openAccount(uid);
    addresses.allocateAddresses(uid);
    String addr = addresses.addressOf(uid, Chain.ERC20).orElseThrow();
    // 三方一致：Java 算的 = 链上批量合约 forwarderAddress(salt) 算的
    String onChain = "0x" + rpc.call("eth_call", List.of(Map.of("to", batcher, "data", SweepAbi.FORWARDER_ADDRESS + Create2.salt(uid).substring(2)), "latest"))
        .asString().substring(26);
    assertThat(addr).isEqualToIgnoringCase(onChain);
    assertThat(hasCode(addr)).as("收款前还没有合约").isFalse();

    credit(addr, 250_000_000L);
    assertThat(ledger.snapshot(uid).balance()).isEqualByComparingTo("250");

    SweepAdminApi.Preview p = sweep.preview("ERC20");
    SweepAdminApi.Candidate c = p.candidates().stream().filter(x -> x.userId() == uid).findFirst().orElseThrow();
    assertThat(c.skip()).isNull();
    assertThat(c.deploy()).isTrue();
    BigInteger before = usdtOf(parent);
    long no = sweep.run("ERC20", 1, "anvil 端到端");
    long firstGas = confirm(no);
    assertThat(usdtOf(parent).subtract(before)).isEqualTo(BigInteger.valueOf(250_000_000L));
    assertThat(usdtOf(addr)).isZero();
    assertThat(hasCode(addr)).as("归集时顺便部署了转发合约").isTrue();
    scan();
    assertThat(jdbc.queryForObject("SELECT kind FROM wallet_sweep_outflow WHERE from_address = ? ORDER BY id LIMIT 1", String.class, addr)).isEqualTo("SWEEP");

    // 已部署的地址再充值、再归集
    credit(addr, 100_000_000L);
    assertThat(sweep.preview("ERC20").candidates().stream().filter(x -> x.userId() == uid).findFirst().orElseThrow().deploy()).isFalse();
    long no2 = sweep.run("ERC20", 1, "anvil 已部署地址");
    long secondGas = confirm(no2);
    assertThat(usdtOf(parent).subtract(before)).isEqualTo(BigInteger.valueOf(350_000_000L));
    System.out.println("[anvil-e2e] 1 个地址：首次（部署 + 归集）gasUsed=" + firstGas + "，已部署 gasUsed=" + secondGas);

    // parent（Safe）直接调用转发合约的 flushTokens：成功；扫块认成"不是系统归集的转出"，对账列出
    mint(addr, 7_000_000L);
    send(parent, addr, SweepAbi.selector("flushTokens(address)") + word(usdt));
    assertThat(usdtOf(parent).subtract(before)).isEqualTo(BigInteger.valueOf(357_000_000L));
    scan();
    assertThat(jdbc.queryForList("SELECT kind FROM wallet_sweep_outflow WHERE from_address = ? ORDER BY id", String.class, addr)).containsExactly("SWEEP", "SWEEP",
        "UNKNOWN");
    recon.run();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_recon_issue WHERE kind = 'SWEEP_UNKNOWN_OUTFLOW'", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_recon_issue WHERE kind = 'SWEEP_AMOUNT_MISMATCH'", Integer.class)).isZero();
    // 其他人（这里是发起账户本人）不能直接调用转发合约
    assertThatThrownBy(() -> rpc.call("eth_call", List.of(Map.of("from", operator, "to", addr, "data", SweepAbi.selector("flushTokens(address)") + word(usdt)), "latest")))
        .isInstanceOf(NodeException.Rejected.class);

    // 撤销发起账户：归集在模拟这一步就被拒，不发交易
    send(deployer, batcher, SweepAbi.selector("setOperator(address,bool)") + word(operator) + word(BigInteger.ZERO));
    credit(addr, 120_000_000L);
    long no3 = sweep.run("ERC20", 1, "撤销后");
    assertThat(jdbc.queryForObject("SELECT CONCAT(status, ':', fail_reason) FROM wallet_sweep_batch WHERE public_no = ?", String.class, no3)).startsWith("FAILED:模拟执行失败").contains("NotOperator");
  }
}
