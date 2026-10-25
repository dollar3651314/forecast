package com.forecast.app.wallet.sweep;

import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.support.MutableClock;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.address.AddressService;
import com.forecast.app.wallet.deposit.ChainClients;
import com.forecast.app.wallet.deposit.DepositService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

/**
 * 配置了归集合约地址和发起账户的 Spring 上下文（SweepFlowTest、SweepAdminTest 共用这一个）+ 真实 MySQL（C37）。
 * 合约地址是假的固定地址（链用 {@link FakeEvm}）；发起账户私钥每次运行随机生成，不写进仓库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
abstract class SweepContextSupport {

  static final String FACTORY = "0x1111111111111111111111111111111111111111";
  static final String IMPLEMENTATION = "0x2222222222222222222222222222222222222222";
  static final String PARENT = "0x5afe00000000000000000000000000000000a001";
  static final String BATCHER = "0xba7c000000000000000000000000000000000001";
  static final String ERC20_USDT = "0xdAC17F958D2ee523a2206206994597C13D831ec7";
  static final String BSC_USDT = "0x55d398326f99059fF775485246999027B3197955";
  static final ECKeyPair OPERATOR = random();

  /** 核对告警邮件（notifyStaff）；其余行为是真的 */
  @MockitoSpyBean com.forecast.app.notification.api.StaffAlertApi staff;
  @MockitoBean com.forecast.app.notification.api.MailApi mail;

  @Autowired JdbcTemplate jdbc;
  @Autowired AddressService addresses;
  @Autowired DepositService deposits;
  @Autowired ChainClients clients;
  @Autowired SweepGateways gateways;
  @Autowired SweepService sweep;
  @Autowired LedgerApi ledger;
  @Autowired SystemConfig config;
  @Autowired Clock clock;
  @Autowired com.forecast.app.wallet.Confirmations confirmations;

  private static ECKeyPair random() {
    try {
      return Keys.createEcKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String operatorAddress() {
    return "0x" + Keys.getAddress(OPERATOR);
  }

  @DynamicPropertySource
  static void sweepProps(DynamicPropertyRegistry r) {
    r.add("forecast.wallet.sweep.evm.factory", () -> FACTORY);
    r.add("forecast.wallet.sweep.evm.implementation", () -> IMPLEMENTATION);
    r.add("forecast.wallet.sweep.evm.parent", () -> PARENT);
    r.add("forecast.wallet.sweep.evm.batcher", () -> BATCHER);
    r.add("forecast.wallet.sweep.evm.operator-address", SweepContextSupport::operatorAddress);
    r.add("forecast.wallet.sweep.evm.operator-key", () -> Numeric.toHexStringWithPrefixZeroPadded(OPERATOR.getPrivateKey(), 64));
    r.add("forecast.wallet.sweep.evm.erc20-chain-id", () -> "11155111");
    r.add("forecast.wallet.sweep.evm.bsc-chain-id", () -> "97");
    r.add("forecast.wallet.sweep.evm.migrate-on-start", () -> "false");
    // 节点地址只是为了"配置了节点"，测试里用链替身替换，不会真的连
    r.add("forecast.wallet.chains.ERC20.rpc-url", () -> "http://127.0.0.1:9/unused");
    r.add("forecast.wallet.chains.BSC.rpc-url", () -> "http://127.0.0.1:9/unused");
  }

  /** 每个测试结束：时钟复原，没结束的批次标失败并释放（批次和地址的"进行中"键是全库唯一的），设置恢复默认 */
  @AfterEach
  void cleanupSweep() {
    ((MutableClock) clock).reset();
    jdbc.update("UPDATE wallet_sweep_batch SET status = 'FAILED', active_chain = NULL, finished_at = UTC_TIMESTAMP(3) WHERE active_chain IS NOT NULL");
    jdbc.update("UPDATE wallet_sweep_item SET active_key = NULL WHERE active_key IS NOT NULL");
    jdbc.update("UPDATE common_config SET value = CASE config_key WHEN 'sweep.threshold.ERC20' THEN '100' WHEN 'sweep.threshold.BSC' THEN '10' "
        + "WHEN 'sweep.threshold.TRC20' THEN '100' WHEN 'sweep.maxFeeRatioPct' THEN '3' WHEN 'sweep.evmCap.perAddress' THEN '5000' "
        + "WHEN 'sweep.evmCap.perBatch' THEN '20000' ELSE '0' END WHERE config_key LIKE 'sweep.%' AND config_key <> 'sweep.version'");
    config.refresh();
    for (Chain c : Chain.values()) {
      clients.remove(c);
      gateways.remove(c);
    }
  }

  long newUser() {
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "sw" + uid + "@example.com");
    ledger.openAccount(uid);
    addresses.allocateAddresses(uid);
    return uid;
  }

  /** 新的链替身，扫块从它的当前区块之后开始 */
  FakeEvm evm(Chain c) {
    FakeEvm f = new FakeEvm(c == Chain.ERC20 ? 11155111 : 97, c == Chain.ERC20 ? ERC20_USDT : BSC_USDT, FACTORY, IMPLEMENTATION, PARENT, BATCHER, 5000);
    // 发起账户的 nonce 存在库里，跨测试累加：链替身从库里的值接着走（和真实链一致）
    f.latestNonce = jdbc.queryForList("SELECT next_nonce FROM wallet_sweep_nonce WHERE chain = ? AND operator = ?", Long.class, c.name(),
        operatorAddress()).stream().findFirst().orElse(0L);
    clients.register(c, f);
    gateways.register(c, f);
    jdbc.update("REPLACE INTO wallet_chain_cursor (chain, last_block) VALUES (?, ?)", c.name(), 5000);
    return f;
  }

  static BigInteger raw(Chain c, String usdt) {
    return new BigDecimal(usdt).movePointRight(c == Chain.BSC ? 18 : 6).toBigIntegerExact();
  }

  /** 扫块追到链替身的最新区块 */
  void scanAll(FakeEvm f, Chain c) {
    for (int i = 0; i < 100; i++) {
      deposits.run(c);
      Long cursor = jdbc.queryForObject("SELECT last_block FROM wallet_chain_cursor WHERE chain = ?", Long.class, c.name());
      if (cursor != null && cursor >= f.head) return;
    }
  }

  /** 充值并入账，再过 2 倍确认数（预览里不再算"最近入账"） */
  void credited(FakeEvm f, Chain c, String address, String usdt) {
    f.deposit(address, raw(c, usdt));
    scanAll(f, c);
    f.mineEmpty(confirmations.of(c));
    scanAll(f, c);
    f.mineEmpty(2 * confirmations.of(c) + 1);
    scanAll(f, c);
  }

  /** 交易上链并过确认数，然后推进批次 */
  void mineAndConfirm(FakeEvm f, Chain c) {
    f.mine();
    f.mineEmpty(confirmations.of(c));
    sweep.advance();
  }
}
