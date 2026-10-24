package com.forecast.app.wallet.sweep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.common.health.HealthCheck;
import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.support.MutableClock;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.address.Create2;
import com.forecast.app.wallet.api.SweepAdminApi;
import com.forecast.app.wallet.api.WalletReconApi;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 资金归集第一阶段（以太坊、BSC；技术方案/v2-半自动归集.md v1.2 第 4.4–4.8 节）：地址切换、扫块入账、预览和跳过规则、执行、确认、部分成功和重试、
 * 失败（模拟失败、节点拒绝、链上回滚、nonce 被占用）、连不上节点、加价重发、扫块区分归集和异常转出、对账、告警、健康检查不受影响。
 */
class SweepFlowTest extends SweepContextSupport {

  /** 手续费币价格（复制源用行情模块的报价，forecast 换成 NativePriceSource，见它的说明）；没设置时 Mockito 返回空 = 没有价格 */
  @MockitoBean NativePriceSource prices;
  @Autowired WalletReconApi recon;
  @Autowired SweepMonitor monitor;
  @Autowired SweepMapper mapper;
  @Autowired ApplicationContext ctx;

  String fwd(long uid) {
    return Create2.forwarderAddress(FACTORY, IMPLEMENTATION, PARENT, BATCHER, Create2.salt(uid));
  }

  SweepAdminApi.Candidate candidate(SweepAdminApi.Preview p, long uid) {
    return p.candidates().stream().filter(c -> c.userId() == uid).findFirst().orElseThrow(() -> new AssertionError("预览里没有用户 " + uid));
  }

  String batchStatus(long no) {
    return jdbc.queryForObject("SELECT status FROM wallet_sweep_batch WHERE public_no = ?", String.class, no);
  }

  // ---------- 地址 ----------

  @Test
  void 配好合约地址后新地址用CREATE2_老xpub地址停用但30天内照常入账() {
    long uid = newUser();
    // 模拟"配置合约地址之前就发出的 xpub 地址"：先把刚分配的合约地址停用，插一个正在用的 xpub 地址
    String old = "0x00000000000000000000000000000000" + String.format("%08x", uid);
    jdbc.update("DELETE FROM wallet_address WHERE user_id = ? AND family = 'ETH'", uid);
    jdbc.update("INSERT INTO wallet_address (user_id, family, scheme, scheme_ver, address_index, address, address_key, status, active_key) "
        + "VALUES (?, 'ETH', 'XPUB', 0, ?, ?, ?, 'ACTIVE', CONCAT(?, ':ETH'))", uid, (int) uid, old, old, uid);

    String now = addresses.addressOf(uid, Chain.ERC20).orElseThrow();
    assertThat(now).isEqualTo(fwd(uid)).isEqualTo(addresses.addressOf(uid, Chain.BSC).orElseThrow());
    assertThat(jdbc.queryForMap("SELECT status, retired_at FROM wallet_address WHERE address_key = ?", old)).containsEntry("status", "RETIRED")
        .extractingByKey("retired_at").isNotNull();
    assertThat(jdbc.queryForObject("SELECT CONCAT(scheme, ':', status, ':', salt) FROM wallet_address WHERE address_key = ?", String.class, now.toLowerCase()))
        .isEqualTo("FORWARDER:ACTIVE:" + Create2.salt(uid));
    assertThat(addresses.addressOf(uid, Chain.TRC20).orElseThrow()).as("TRON 不变，仍是 xpub").startsWith("T");

    FakeEvm f = evm(Chain.ERC20);
    credited(f, Chain.ERC20, old, "30");
    credited(f, Chain.ERC20, now, "40");
    assertThat(ledger.snapshot(uid).balance()).as("停用的老地址 30 天内照常入账").isEqualByComparingTo("70");

    ((MutableClock) clock).advance(Duration.ofDays(31));
    f.deposit(old, raw(Chain.ERC20, "5"));
    scanAll(f, Chain.ERC20);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_deposit WHERE user_id = ? AND amount = 5", Integer.class, uid)).as("停用超过 30 天不再入账").isZero();
  }

  @Test
  void 启动迁移把正在用的xpub地址换成合约地址() {
    long uid = newUser();
    jdbc.update("DELETE FROM wallet_address WHERE user_id = ? AND family = 'ETH'", uid);
    String old = "0x00000000000000000000000000000001" + String.format("%08x", uid);
    jdbc.update("INSERT INTO wallet_address (user_id, family, scheme, scheme_ver, address_index, address, address_key, status, active_key) "
        + "VALUES (?, 'ETH', 'XPUB', 0, ?, ?, ?, 'ACTIVE', CONCAT(?, ':ETH'))", uid, (int) uid, old, old, uid);
    assertThat(addresses.migrateAll()).isGreaterThanOrEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT address FROM wallet_address WHERE active_key = CONCAT(?, ':ETH')", String.class, uid)).isEqualTo(fwd(uid));
    assertThat(addresses.migrateAll()).as("再跑一次没有要换的").isZero();
  }

  // ---------- 预览、执行、确认 ----------

  @Test
  void 预览跳过规则_执行_确认_扫块认出归集转出_对账没有问题() {
    FakeEvm f = evm(Chain.ERC20);
    long a = newUser(), b = newUser(), c = newUser(), d = newUser(), e = newUser();
    credited(f, Chain.ERC20, fwd(a), "200");
    credited(f, Chain.ERC20, fwd(b), "50");
    credited(f, Chain.ERC20, fwd(c), "6000");
    credited(f, Chain.ERC20, fwd(d), "300");
    f.deposit(fwd(d), raw(Chain.ERC20, "1")); // 待确认
    scanAll(f, Chain.ERC20);
    f.deposit(fwd(e), raw(Chain.ERC20, "500"));
    scanAll(f, Chain.ERC20);
    f.mineEmpty(confirmations.of(Chain.ERC20));
    scanAll(f, Chain.ERC20); // e 刚入账（不到 2 倍确认数）；d 的 1 USDT 也入账了，再来一笔待确认
    f.deposit(fwd(d), raw(Chain.ERC20, "2"));
    scanAll(f, Chain.ERC20);

    SweepAdminApi.Preview p = sweep.preview("ERC20");
    assertThat(candidate(p, a).skip()).isNull();
    assertThat(candidate(p, a).deploy()).as("还没部署：这次顺便部署").isTrue();
    assertThat(candidate(p, b).skip()).isEqualTo("belowThreshold");
    assertThat(candidate(p, c).skip()).as("超过每个地址 5,000 的上线初期上限").isEqualTo("overAddressCap");
    assertThat(candidate(p, d).skip()).isEqualTo("pendingDeposit");
    assertThat(candidate(p, e).skip()).isEqualTo("recentCredit");
    assertThat(p.destination()).isEqualToIgnoringCase(PARENT);
    assertThat(candidate(p, a).estFee()).as("未部署：16 万 gas × (2 × 1 gwei + 0.05 gwei)").isEqualByComparingTo("0.000328");

    long no = sweep.run("ERC20", 1, "测试归集");
    assertThat(batchStatus(no)).isEqualTo("SENT");
    SweepAdminApi.BatchDetail detail = sweep.batch(no).orElseThrow();
    assertThat(detail.items()).extracting(SweepAdminApi.Item::userId).containsExactly(a);
    assertThat(f.sentRaw).hasSize(1);
    assertThat(f.balance(PARENT)).isZero();

    f.mine();
    sweep.advance();
    assertThat(batchStatus(no)).as("上链了但确认数不够").isEqualTo("SENT");
    f.mineEmpty(confirmations.of(Chain.ERC20));
    sweep.advance();
    assertThat(batchStatus(no)).isEqualTo("CONFIRMED");
    assertThat(f.balance(PARENT)).isEqualTo(raw(Chain.ERC20, "200"));
    detail = sweep.batch(no).orElseThrow();
    assertThat(detail.batch().sweptUsdt()).isEqualByComparingTo("200");
    assertThat(detail.batch().fee()).isPositive();
    assertThat(detail.items().getFirst().status()).isEqualTo("SWEPT");
    assertThat(jdbc.queryForObject("SELECT deployed_at IS NOT NULL FROM wallet_address WHERE address_key = ?", Boolean.class, fwd(a).toLowerCase())).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_sweep_item WHERE active_key IS NOT NULL", Integer.class)).isZero();

    // 扫块：充值地址 → 冷钱包的转出是归集（SWEEP），不当成异常，也不当成充值
    scanAll(f, Chain.ERC20);
    assertThat(jdbc.queryForObject("SELECT kind FROM wallet_sweep_outflow WHERE tx_hash = ?", String.class, detail.batch().txHash())).isEqualTo("SWEEP");
    verify(staff, never()).notifyStaff(eq("SWEEP_UNKNOWN_OUTFLOW"), anyMap());
    recon.run();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_recon_issue WHERE kind LIKE 'SWEEP%' AND (ref_id LIKE ? OR ref_id LIKE ?)", Integer.class,
        "%" + detail.batch().txHash() + "%", "ERC20:" + no + ":%")).as("归集转出、金额核对都没有问题").isZero();
    assertThat(ledger.snapshot(a).balance()).as("归集不改客户余额").isEqualByComparingTo("200");

    // 同一个地址再充值：这次不用部署
    credited(f, Chain.ERC20, fwd(a), "150");
    assertThat(candidate(sweep.preview("ERC20"), a).deploy()).isFalse();
  }

  @Test
  void 手续费占比过高跳过_每批上限() {
    FakeEvm f = evm(Chain.ERC20);
    when(prices.usd(Chain.ERC20)).thenReturn(java.util.Optional.of(new BigDecimal("2500.00")));
    long a = newUser(), b = newUser(), small = newUser();
    credited(f, Chain.ERC20, fwd(a), "4000");
    credited(f, Chain.ERC20, fwd(b), "3000");
    credited(f, Chain.ERC20, fwd(small), "100");
    f.baseFee = java.math.BigInteger.valueOf(20_000_000_000L); // 20 gwei：未部署约 6.4 万分之一 ETH × 2500 ≈ 16 美元 > 100 × 3%
    jdbc.update("UPDATE common_config SET value = ? WHERE config_key = ?", "5000", com.forecast.app.common.config.ConfigKey.SWEEP_CAP_PER_BATCH.key);
    config.refresh();
    SweepAdminApi.Preview p = sweep.preview("ERC20");
    assertThat(p.warnings()).doesNotContain("priceMissing");
    assertThat(candidate(p, small).skip()).isEqualTo("feeRatio");
    assertThat(candidate(p, a).skip()).isNull();
    assertThat(candidate(p, b).skip()).as("4000 + 3000 > 每批 5000").isEqualTo("overBatchCap");
    assertThat(candidate(p, a).estFeeUsd()).isPositive();
    assertThat(p.totals().count()).isEqualTo(1);
  }

  @Test
  void 部分成功_失败的地址可以重试_通知风控() {
    FakeEvm f = evm(Chain.ERC20);
    long a = newUser(), b = newUser();
    credited(f, Chain.ERC20, fwd(a), "120");
    credited(f, Chain.ERC20, fwd(b), "130");
    f.failFor.add(fwd(b).toLowerCase());
    long no = sweep.run("ERC20", 1, "测试部分成功");
    mineAndConfirm(f, Chain.ERC20);
    assertThat(batchStatus(no)).isEqualTo("PARTIAL");
    SweepAdminApi.BatchDetail d = sweep.batch(no).orElseThrow();
    assertThat(d.items()).filteredOn(i -> i.userId() == b).singleElement().satisfies(i -> {
      assertThat(i.status()).isEqualTo("FAILED");
      assertThat(i.failReason()).contains("批量合约报告失败");
    });
    verify(staff).notifyStaff(eq("SWEEP_FAILED"), argThat(m -> String.valueOf(no).equals(m.get("batchNo")) && "部分成功".equals(m.get("statusZh"))));

    f.failFor.clear();
    long retry = sweep.retry(no, 1, "重试");
    assertThat(sweep.batch(retry).orElseThrow().items()).extracting(SweepAdminApi.Item::userId).containsExactly(b);
    mineAndConfirm(f, Chain.ERC20);
    assertThat(batchStatus(retry)).isEqualTo("CONFIRMED");
    assertThat(f.balance(PARENT)).isEqualTo(raw(Chain.ERC20, "250"));
    assertThatThrownBy(() -> sweep.retry(retry, 1, "再试")).isInstanceOf(BizException.class).hasFieldOrPropertyWithValue("code", "SWEEP_BATCH_STATE");
  }

  @Test
  void 模拟执行失败不发交易_地址释放() {
    FakeEvm f = evm(Chain.ERC20);
    long a = newUser();
    credited(f, Chain.ERC20, fwd(a), "200");
    f.rejectCall = true;
    long no = sweep.run("ERC20", 1, "测试");
    assertThat(batchStatus(no)).isEqualTo("FAILED");
    assertThat(f.sentRaw).isEmpty();
    assertThat(sweep.batch(no).orElseThrow().failReason()).contains("模拟执行失败");
    f.rejectCall = false;
    assertThat(candidate(sweep.preview("ERC20"), a).skip()).as("释放了，下次重新列入").isNull();
  }

  @Test
  void 广播时连不上节点不算失败_按哈希继续查() {
    FakeEvm f = evm(Chain.ERC20);
    long a = newUser();
    credited(f, Chain.ERC20, fwd(a), "200");
    f.unreachableSend = true;
    long no = sweep.run("ERC20", 1, "测试");
    assertThat(batchStatus(no)).isEqualTo("SENT");
    mineAndConfirm(f, Chain.ERC20);
    assertThat(batchStatus(no)).isEqualTo("CONFIRMED");
  }

  @Test
  void 节点拒绝广播_没有上链_失败() {
    FakeEvm f = evm(Chain.ERC20);
    long a = newUser();
    credited(f, Chain.ERC20, fwd(a), "200");
    f.rejectSend = true;
    long no = sweep.run("ERC20", 1, "测试");
    assertThat(batchStatus(no)).isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("SELECT status FROM wallet_sweep_tx WHERE batch_id = (SELECT id FROM wallet_sweep_batch WHERE public_no = ?)", String.class, no))
        .isEqualTo("FAILED");
  }

  @Test
  void 链上回滚_整批失败_记手续费() {
    FakeEvm f = evm(Chain.ERC20);
    long a = newUser();
    credited(f, Chain.ERC20, fwd(a), "200");
    f.revertOnChain = true;
    long no = sweep.run("ERC20", 1, "测试");
    mineAndConfirm(f, Chain.ERC20);
    assertThat(batchStatus(no)).isEqualTo("FAILED");
    assertThat(sweep.batch(no).orElseThrow().batch().fee()).isPositive();
    assertThat(f.balance(fwd(a))).isEqualTo(raw(Chain.ERC20, "200"));
  }

  @Test
  void 三十分钟没上链_同一nonce加价重发() {
    FakeEvm f = evm(Chain.ERC20);
    long a = newUser();
    credited(f, Chain.ERC20, fwd(a), "200");
    long no = sweep.run("ERC20", 1, "测试");
    ((MutableClock) clock).advance(Duration.ofMinutes(31));
    sweep.advance();
    List<SweepMapper.TxRow> txs = mapper.txs(jdbc.queryForObject("SELECT id FROM wallet_sweep_batch WHERE public_no = ?", Long.class, no));
    assertThat(txs).hasSize(2);
    assertThat(txs.get(0).nonce()).isEqualTo(txs.get(1).nonce());
    assertThat(txs.get(0).maxFee()).isGreaterThan(txs.get(1).maxFee());
    f.mempool.remove(txs.get(1).txHash()); // 旧的那笔被替换掉
    mineAndConfirm(f, Chain.ERC20);
    assertThat(batchStatus(no)).isEqualTo("CONFIRMED");
    assertThat(sweep.batch(no).orElseThrow().batch().txHash()).isEqualTo(txs.get(0).txHash());
  }

  @Test
  void 发起账户nonce被别的交易用掉_失败并提示检查私钥() {
    FakeEvm f = evm(Chain.ERC20);
    long a = newUser();
    credited(f, Chain.ERC20, fwd(a), "200");
    long no = sweep.run("ERC20", 1, "测试");
    f.mempool.clear();
    f.latestNonce++;
    sweep.advance();
    assertThat(batchStatus(no)).isEqualTo("FAILED");
    assertThat(sweep.batch(no).orElseThrow().failReason()).contains("泄露");
  }

  @Test
  void 同一条链同时只有一个进行中的批次() {
    FakeEvm f = evm(Chain.ERC20);
    long a = newUser(), b = newUser();
    credited(f, Chain.ERC20, fwd(a), "200");
    sweep.run("ERC20", 1, "第一批");
    credited(f, Chain.ERC20, fwd(b), "200");
    assertThat(candidate(sweep.preview("ERC20"), a).skip()).isEqualTo("inBatch");
    assertThatThrownBy(() -> sweep.run("ERC20", 1, "第二批")).isInstanceOf(BizException.class).hasFieldOrPropertyWithValue("code", "SWEEP_BATCH_ACTIVE");
  }

  @Test
  void BSC用legacy交易_18位小数() throws Exception {
    FakeEvm f = evm(Chain.BSC);
    long a = newUser();
    credited(f, Chain.BSC, fwd(a), "12.5");
    long no = sweep.run("BSC", 1, "测试 BSC");
    org.web3j.crypto.RawTransaction t = org.web3j.crypto.TransactionDecoder.decode(f.sentRaw.getFirst());
    assertThat(t.getTransaction()).isInstanceOf(org.web3j.crypto.transaction.type.LegacyTransaction.class);
    assertThat(((org.web3j.crypto.SignedRawTransaction) t).getChainId()).isEqualTo(97L);
    assertThat(((org.web3j.crypto.SignedRawTransaction) t).getFrom()).isEqualToIgnoringCase(operatorAddress());
    mineAndConfirm(f, Chain.BSC);
    assertThat(batchStatus(no)).isEqualTo("CONFIRMED");
    assertThat(sweep.batch(no).orElseThrow().batch().sweptUsdt()).isEqualByComparingTo("12.5");
  }

  @Test
  void 以太坊用EIP1559交易() throws Exception {
    FakeEvm f = evm(Chain.ERC20);
    long a = newUser();
    credited(f, Chain.ERC20, fwd(a), "200");
    sweep.run("ERC20", 1, "测试");
    org.web3j.crypto.RawTransaction t = org.web3j.crypto.TransactionDecoder.decode(f.sentRaw.getFirst());
    assertThat(t.getTransaction()).isInstanceOf(org.web3j.crypto.transaction.type.Transaction1559.class);
    assertThat(((org.web3j.crypto.SignedRawTransaction) t).getFrom()).isEqualToIgnoringCase(operatorAddress());
    assertThat(t.getTo()).isEqualToIgnoringCase(BATCHER);
    assertThat(SweepAbi.decodeSweep(t.getData().startsWith("0x") ? t.getData() : "0x" + t.getData())).containsExactly(ERC20_USDT.toLowerCase(), Create2.salt(a));
  }

  // ---------- 不是归集的转出 ----------

  @Test
  void 不是归集的转出_告警_对账区分去了冷钱包和去了别处() {
    FakeEvm f = evm(Chain.ERC20);
    long a = newUser();
    credited(f, Chain.ERC20, fwd(a), "200");
    clearInvocations(staff);
    String manual = f.outflow(fwd(a), PARENT, raw(Chain.ERC20, "50")); // 例如 Safe 手动 flushTokens
    String stolen = f.outflow(fwd(a), "0x000000000000000000000000000000000000bad1", raw(Chain.ERC20, "10"));
    scanAll(f, Chain.ERC20);
    assertThat(jdbc.queryForObject("SELECT kind FROM wallet_sweep_outflow WHERE tx_hash = ?", String.class, manual)).isEqualTo("UNKNOWN");
    assertThat(jdbc.queryForObject("SELECT kind FROM wallet_sweep_outflow WHERE tx_hash = ?", String.class, stolen)).isEqualTo("DEST_MISMATCH");
    verify(staff, atLeastOnce()).notifyStaff(eq("SWEEP_UNKNOWN_OUTFLOW"), argThat(m -> manual.equals(m.get("txHash"))));
    verify(staff, atLeastOnce()).notifyStaff(eq("SWEEP_UNKNOWN_OUTFLOW"), argThat(m -> stolen.equals(m.get("txHash")) && m.get("hint").contains("不是冷钱包")));
    assertThat(ledger.snapshot(a).balance()).as("转出不影响客户余额").isEqualByComparingTo("200");

    monitor.loadStats();
    assertThat(monitor.evaluate()).filteredOn(i -> i.name().equals("sweep.outflow")).singleElement().extracting(AlertCheck.Item::ok).isEqualTo(false);

    recon.run();
    long manualIssue = jdbc.queryForObject("SELECT id FROM wallet_recon_issue WHERE kind = 'SWEEP_UNKNOWN_OUTFLOW' AND ref_id = ?", Long.class, "ERC20:" + manual + ":0");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_recon_issue WHERE kind = 'SWEEP_DEST_MISMATCH' AND ref_id = ?", Integer.class, "ERC20:" + stolen + ":0"))
        .isEqualTo(1);
    recon.resolve(manualIssue, 1, "Safe 手动归集，已核对");
    recon.run();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_recon_issue WHERE ref_id = ?", Integer.class, "ERC20:" + manual + ":0")).as("处理过的不再重复列出")
        .isEqualTo(1);
  }

  @Test
  void xpub地址的转出只记日志() {
    FakeEvm f = evm(Chain.ERC20);
    long uid = newUser();
    String tron = addresses.addressOf(uid, Chain.TRC20).orElseThrow();
    assertThat(tron).startsWith("T");
    String old = "0x00000000000000000000000000000002" + String.format("%08x", uid);
    jdbc.update("INSERT INTO wallet_address (user_id, family, scheme, scheme_ver, address_index, address, address_key, status, retired_at) "
        + "VALUES (?, 'ETH', 'XPUB', 0, ?, ?, ?, 'RETIRED', UTC_TIMESTAMP(3))", uid, (int) uid, old, old);
    String tx = f.outflow(old, PARENT, raw(Chain.ERC20, "1"));
    scanAll(f, Chain.ERC20);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_sweep_outflow WHERE tx_hash = ?", Integer.class, tx)).isZero();
  }

  // ---------- 不可用、告警、健康检查 ----------

  @Test
  void TRON是下一阶段_没有节点时不可用() {
    gateways.remove(Chain.ERC20);
    assertThatThrownBy(() -> sweep.preview("TRC20")).isInstanceOf(BizException.class).hasFieldOrPropertyWithValue("code", "SWEEP_UNAVAILABLE")
        .hasMessageContaining("下一阶段");
    assertThatThrownBy(() -> sweep.preview("ERC20")).as("没有接上节点").isInstanceOf(BizException.class).hasMessageContaining("节点没有接上");
    List<SweepAdminApi.Overview> o = sweep.overview();
    assertThat(o).extracting(SweepAdminApi.Overview::chain).containsExactly("ERC20", "BSC", "TRC20");
    assertThat(o.get(2).available()).isFalse();
    assertThat(o.get(2).mode()).isEqualTo("OFFLINE");
  }

  @Test
  void 发起账户余额告警_归集类都不是健康检查() {
    FakeEvm f = evm(Chain.ERC20);
    f.natives.put(operatorAddress().toLowerCase(), new BigDecimal("0.01").movePointRight(18).toBigInteger());
    jdbc.update("UPDATE common_config SET value = ? WHERE config_key = ?", "0.05", com.forecast.app.common.config.ConfigKey.SWEEP_OPERATOR_MIN_ERC20.key);
    config.refresh();
    monitor.refresh();
    assertThat(monitor.evaluate()).filteredOn(i -> i.name().equals("sweep.operator.ERC20")).singleElement().satisfies(i -> {
      assertThat(i.ok()).isFalse();
      assertThat(i.detail()).contains("0.01").contains("0.05");
    });
    assertThat(monitor.evaluate()).filteredOn(i -> i.name().equals("sweep.config")).singleElement().extracting(AlertCheck.Item::ok).isEqualTo(true);
    // 约定 24：归集出错不能让健康检查的 status 变成 DOWN（部署脚本会回滚）
    Map<String, HealthCheck> health = ctx.getBeansOfType(HealthCheck.class);
    assertThat(health.values()).noneMatch(h -> h.getClass().getName().contains(".wallet.sweep.") || h.getClass().getName().contains(".wallet.address."));
  }

  @Test
  void 发起账户的手续费币不够_不发交易() {
    FakeEvm f = evm(Chain.ERC20);
    long a = newUser();
    credited(f, Chain.ERC20, fwd(a), "200");
    f.natives.put(operatorAddress().toLowerCase(), java.math.BigInteger.ONE);
    long no = sweep.run("ERC20", 1, "测试");
    assertThat(batchStatus(no)).isEqualTo("FAILED");
    assertThat(sweep.batch(no).orElseThrow().failReason()).contains("不够");
    assertThat(f.sentRaw).isEmpty();
  }

  @Test
  void 没有可归集的地址() {
    evm(Chain.ERC20);
    assertThatThrownBy(() -> sweep.run("ERC20", 1, "测试")).isInstanceOf(BizException.class).hasFieldOrPropertyWithValue("code", "SWEEP_NOTHING");
  }
}
