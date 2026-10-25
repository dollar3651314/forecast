package com.forecast.app.wallet.sweep;

import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.id.Snowflake;
import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.Confirmations;
import com.forecast.app.wallet.SweepEvmProperties;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.wallet.address.AddressService;
import com.forecast.app.wallet.address.ForwarderScheme;
import com.forecast.app.wallet.address.ForwarderSchemes;
import com.forecast.app.wallet.api.SweepAdminApi;
import com.forecast.app.wallet.api.SweepSettings;
import com.forecast.app.wallet.chain.NodeException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import tools.jackson.databind.ObjectMapper;

/**
 * 以太坊、BSC 的资金归集（v2 第一阶段，技术方案/v2-半自动归集.md 第 4.5、4.6 节；SW-5 先手动）。
 * <ol>
 *   <li><b>预览</b>：收到过这条链充值的合约地址，逐个查 USDT 余额和是否已部署，按跳过规则（有待确认的充值 / 最近入账不到 2 倍确认数 / 已在进行中的批次 /
 *       低于门槛 / 超过单地址上限 / 手续费占比过高 / 超过每批上限 / 超过每批地址数）决定这次归集哪些。<b>金额上限在这里（链下）检查</b>，合约里没有（理由见 contracts/README.md）。</li>
 *   <li><b>执行</b>（后台高危确认）：按实时预览建批次和明细（每个地址 {@code active_key} 保证同时只在一个批次里，每条链同时只有一个进行中的批次），
 *       {@code eth_call} 模拟 → 估 gas × 1.2 → 发起账户签名（以太坊 EIP-1559、BSC legacy）→ 先落库再广播；nonce 单独一套（{@code wallet_sweep_nonce}）。</li>
 *   <li><b>确认</b>（每 15 秒）：交易成功且确认数够了，按 USDT 的 Transfer 事件（充值地址 → parent）逐个记实际金额；批量合约的 SweepFailed 事件记失败原因。
 *       全部成功 CONFIRMED、部分 PARTIAL、都没成功 FAILED；没成功的地址释放，下次重新列入。30 分钟没上链同一 nonce 加价 20% 重发，最多 3 次。</li>
 * </ol>
 * <b>连不上节点不等于失败</b>（约定 13 同一原则）：广播时连不上当"可能已发出"，按哈希继续查；只有节点明确拒绝、链上回滚才算失败。
 * 归集不写账本、不改客户余额（方案第 4.8 节）。
 */
@Service
public class SweepService implements SweepAdminApi {

  private static final Logger log = LoggerFactory.getLogger(SweepService.class);
  static final String PENDING = "PENDING";
  static final String SENT = "SENT";
  static final String CONFIRMED = "CONFIRMED";
  static final String PARTIAL = "PARTIAL";
  static final String FAILED = "FAILED";
  static final String SYSTEM = "system";
  private static final BigInteger GWEI = BigInteger.TEN.pow(9);

  /** 一个地址的预览结果（内部用，带链上整数余额） */
  record Plan(SweepMapper.CandidateRow row, BigInteger raw, BigDecimal usdt, boolean deploy, BigDecimal estFee, BigDecimal estFeeUsd, String skip) {}

  /** 一次完整的预览 */
  record Computed(Ctx ctx, SweepSettings settings, SweepGateway.Fees fees, List<Plan> plans, Preview preview) {}

  /** 能执行归集时的全部条件 */
  record Ctx(Chain chain, WalletProperties.ChainConfig cfg, ForwarderScheme scheme, Credentials operator, SweepGateway gw, long chainId) {}

  private final WalletProperties wallet;
  private final SweepEvmProperties props;
  private final ForwarderSchemes schemes;
  private final AddressService addresses;
  private final SweepOperator operator;
  private final SweepGateways gateways;
  private final SweepMapper mapper;
  private final SystemConfig config;
  private final NativePriceSource prices;
  private final StaffAlertApi staff;
  private final Confirmations confirmations;
  private final TransactionTemplate tx;
  private final Snowflake ids;
  private final ObjectMapper json;
  private final Clock clock;
  /** 最近一次预览（后台概览用；每 5 分钟和每次打开预览时更新） */
  private final Map<Chain, Computed> snapshots = new ConcurrentHashMap<>();
  private final Map<Chain, LocalDateTime> snapshotAt = new ConcurrentHashMap<>();
  private final Map<Long, LocalDateTime> stuckAlerts = new ConcurrentHashMap<>();

  SweepService(WalletProperties wallet, SweepEvmProperties props, ForwarderSchemes schemes, AddressService addresses, SweepOperator operator,
      SweepGateways gateways, SweepMapper mapper, SystemConfig config, NativePriceSource prices, StaffAlertApi staff, Confirmations confirmations, TransactionTemplate tx,
      Snowflake ids, ObjectMapper json, Clock clock) {
    this.wallet = wallet;
    this.props = props;
    this.schemes = schemes;
    this.addresses = addresses;
    this.operator = operator;
    this.gateways = gateways;
    this.mapper = mapper;
    this.config = config;
    this.prices = prices;
    this.staff = staff;
    this.confirmations = confirmations;
    this.tx = tx;
    this.ids = ids;
    this.json = json;
    this.clock = clock;
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  SweepSettings settings() {
    return SweepSettings.parse(config.snapshot());
  }

  static String gasAsset(Chain c) {
    return switch (c) {
      case ERC20 -> "ETH";
      case BSC -> "BNB";
      case TRC20 -> "TRX";
    };
  }

  // ---------- 能不能执行 ----------

  /** 这条链现在不能执行归集的原因；能执行时为空 */
  Optional<String> unavailable(Chain chain) {
    if (chain.family != Chain.Family.ETH) return Optional.of("TRON 归集（离线签名 + 租能量）是下一阶段");
    Optional<ForwarderScheme> s = schemes.current();
    if (s.isEmpty()) return Optional.of(schemes.problem().orElse("没有配置归集合约地址（FC_SWEEP_EVM_FACTORY / IMPLEMENTATION / PARENT / BATCHER），ERC20、BEP20 充值暂未开通"));
    if (operator.problem().isPresent()) return operator.problem();
    WalletProperties.ChainConfig cfg = wallet.chain(chain);
    if (cfg == null || cfg.rpcUrl() == null || cfg.rpcUrl().isBlank() || !cfg.scanReady()) return Optional.of(chain + " 没有配置节点或 USDT 合约");
    if (props.chainId(chain) == null) return Optional.of(chain + " 没有配置 chainId（" + (chain == Chain.ERC20 ? "FC_WALLET_ERC20_CHAIN_ID" : "FC_WALLET_BSC_CHAIN_ID") + "）");
    if (gateways.get(chain).isEmpty()) return Optional.of(chain + " 节点没有接上");
    return Optional.empty();
  }

  Ctx ctx(Chain chain) {
    Optional<String> why = unavailable(chain);
    if (why.isPresent()) throw new BizException("SWEEP_UNAVAILABLE", why.get());
    return new Ctx(chain, wallet.chain(chain), schemes.current().orElseThrow(), operator.credentials().orElseThrow(), gateways.get(chain).orElseThrow(),
        props.chainId(chain));
  }

  static Chain chainOf(String chain) {
    try {
      return Chain.valueOf(chain == null ? "" : chain.strip().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new com.forecast.app.common.error.ValidationException(List.of(new com.forecast.app.common.error.ValidationException.Field("chain", "invalid")));
    }
  }

  // ---------- 预览 ----------

  @Override
  public Preview preview(String chain) {
    return compute(chainOf(chain), null).preview();
  }

  /** 实时预览；only 不为空时只看这些地址（重试） */
  Computed compute(Chain chain, Set<Long> only) {
    Ctx ctx = ctx(chain);
    SweepSettings s = settings();
    SweepGateway gw = ctx.gw();
    long head = gw.blockNumber();
    SweepGateway.Fees fees = gw.fees(gwei(props.priorityFeeGwei()));
    BigDecimal feePrice = wei(fees.maxFee());
    Optional<BigDecimal> nativeUsd = nativeUsd(chain);
    int decimals = ctx.cfg().usdtDecimals();
    long recentBlocks = 2L * confirmations.of(chain);
    BigDecimal threshold = s.threshold(chain.name());
    String token = ctx.cfg().usdtContract().strip();
    List<Plan> plans = new ArrayList<>();
    for (SweepMapper.CandidateRow r : mapper.candidates(chain.name())) {
      if (only != null && !only.contains(r.addressId())) continue;
      BigInteger raw = gw.tokenBalance(token, r.address());
      if (raw.signum() == 0) continue;
      boolean deployed = r.deployed();
      if (!deployed && gw.hasCode(r.address())) {
        deployed = true;
        addresses.markDeployed(r.addressId());
      }
      BigDecimal usdt = new BigDecimal(raw).movePointLeft(decimals).stripTrailingZeros();
      BigDecimal estFee = feePrice.multiply(BigDecimal.valueOf(deployed ? props.flushGas() : props.deployGas()));
      BigDecimal estFeeUsd = nativeUsd.map(estFee::multiply).orElse(null);
      String skip = null;
      if (r.inBatch()) skip = "inBatch";
      else if (r.pendingDeposit()) skip = "pendingDeposit";
      else if (r.lastCreditedBlock() != null && r.lastCreditedBlock() > head - recentBlocks) skip = "recentCredit";
      else if (r.schemeVer() != ctx.scheme().ver()) skip = "otherScheme";
      else if (usdt.compareTo(threshold) < 0) skip = "belowThreshold";
      else if (s.capPerAddress().signum() > 0 && usdt.compareTo(s.capPerAddress()) > 0) skip = "overAddressCap";
      else if (estFeeUsd != null && estFeeUsd.compareTo(usdt.multiply(s.maxFeeRatioPct()).movePointLeft(2)) > 0) skip = "feeRatio";
      plans.add(new Plan(r, raw, usdt, !deployed, estFee, estFeeUsd, skip));
    }
    // 按余额从大到小装进这一批：超过每批上限的跳过（继续看更小的），超过每批地址数的跳过
    plans.sort(Comparator.comparing(Plan::usdt).reversed().thenComparing(p -> p.row().addressId()));
    BigDecimal sum = BigDecimal.ZERO;
    int count = 0;
    List<Plan> out = new ArrayList<>();
    for (Plan p : plans) {
      if (p.skip() == null) {
        if (count >= props.maxBatchSize()) p = with(p, "batchFull");
        else if (s.capPerBatch().signum() > 0 && sum.add(p.usdt()).compareTo(s.capPerBatch()) > 0) p = with(p, "overBatchCap");
        else {
          sum = sum.add(p.usdt());
          count++;
        }
      }
      out.add(p);
    }
    List<Plan> included = out.stream().filter(p -> p.skip() == null).toList();
    BigDecimal fee = included.isEmpty() ? BigDecimal.ZERO
        : feePrice.multiply(BigDecimal.valueOf(props.baseGas())).add(included.stream().map(Plan::estFee).reduce(BigDecimal.ZERO, BigDecimal::add));
    BigDecimal feeUsd = nativeUsd.map(fee::multiply).orElse(null);
    BigDecimal feePct = feeUsd == null || sum.signum() == 0 ? null : feeUsd.multiply(BigDecimal.valueOf(100)).divide(sum, 4, RoundingMode.HALF_UP);
    List<String> warnings = new ArrayList<>();
    if (nativeUsd.isEmpty()) warnings.add("priceMissing");
    String gasText = fees.eip1559() ? "baseFee " + gweiText(fees.baseFee()) + " gwei + 小费 " + gweiText(fees.priority()) + " gwei（上限 " + gweiText(fees.maxFee()) + " gwei）"
        : "gasPrice " + gweiText(fees.maxFee()) + " gwei";
    Preview p = new Preview(chain.name(), "CONTRACT", ctx.scheme().parent(), threshold, s.maxFeeRatioPct(), gasText, gasAsset(chain),
        out.stream().map(x -> new Candidate(x.row().userId(), x.row().addressId(), x.row().address(), x.usdt(), x.deploy(), scale(x.estFee()),
            x.estFeeUsd() == null ? null : x.estFeeUsd().setScale(2, RoundingMode.HALF_UP), x.skip())).toList(),
        new Totals(included.size(), sum, scale(fee), feeUsd == null ? null : feeUsd.setScale(2, RoundingMode.HALF_UP), feePct, out.size() - included.size()),
        warnings);
    Computed c = new Computed(ctx, s, fees, out, p);
    snapshots.put(chain, c);
    snapshotAt.put(chain, now());
    return c;
  }

  private static Plan with(Plan p, String skip) {
    return new Plan(p.row(), p.raw(), p.usdt(), p.deploy(), p.estFee(), p.estFeeUsd(), skip);
  }

  private Optional<BigDecimal> nativeUsd(Chain chain) {
    try {
      return prices.usd(chain).filter(v -> v != null && v.signum() > 0);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }


  // ---------- 执行 ----------

  @Override
  public long run(String chain, long adminId, String reason) {
    return start(chainOf(chain), adminId, reason, null, null);
  }

  @Override
  public long retry(long batchNo, long adminId, String reason) {
    SweepMapper.BatchRow b = mapper.batchByNo(batchNo);
    if (b == null) throw new BizException("NOT_FOUND", "批次不存在");
    if (!FAILED.equals(b.status) && !PARTIAL.equals(b.status)) throw new BizException("SWEEP_BATCH_STATE", "只有失败或部分成功的批次可以重试");
    Set<Long> failed = new HashSet<>();
    for (SweepMapper.ItemRow i : mapper.items(b.id)) if (FAILED.equals(i.status())) failed.add(i.addressId());
    if (failed.isEmpty()) throw new BizException("SWEEP_NOTHING", "这一批没有需要重试的地址");
    return start(Chain.valueOf(b.chain), adminId, reason, failed, b.id);
  }

  private long start(Chain chain, long adminId, String reason, Set<Long> only, Long retryOf) {
    Computed c = compute(chain, only);
    List<Plan> included = c.plans().stream().filter(p -> p.skip() == null).toList();
    if (included.isEmpty()) throw new BizException("SWEEP_NOTHING", "没有可以归集的地址");
    LocalDateTime now = now();
    SweepMapper.BatchRow b = new SweepMapper.BatchRow();
    b.publicNo = ids.next();
    b.chain = chain.name();
    b.mode = "CONTRACT";
    b.status = PENDING;
    b.triggerType = "MANUAL";
    b.activeChain = chain.name();
    b.schemeVer = c.ctx().scheme().ver();
    b.token = c.ctx().cfg().usdtContract().strip();
    b.destination = c.ctx().scheme().parent();
    b.executor = c.ctx().scheme().feeAddress();
    b.createdBy = adminId;
    b.reason = reason;
    b.retryOf = retryOf;
    b.addressCount = included.size();
    b.expectedUsdt = c.preview().totals().usdt();
    b.feeAsset = gasAsset(chain);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("threshold", SweepSettings.plain(c.preview().threshold()));
    params.put("maxFeeRatioPct", SweepSettings.plain(c.settings().maxFeeRatioPct()));
    params.put("capPerAddress", SweepSettings.plain(c.settings().capPerAddress()));
    params.put("capPerBatch", SweepSettings.plain(c.settings().capPerBatch()));
    params.put("settingsVersion", c.settings().version());
    params.put("gasPrice", c.preview().gasPrice());
    params.put("estFee", SweepSettings.plain(c.preview().totals().fee()));
    params.put("schemeVer", c.ctx().scheme().ver());
    b.paramsJson = json.writeValueAsString(params);
    b.createdAt = now;
    try {
      tx.executeWithoutResult(st -> {
        mapper.insertBatch(b);
        int n = 1;
        for (Plan p : included) {
          mapper.insertItem(b.id, n++, p.row().addressId(), p.row().userId(), p.row().address(), p.row().salt(), p.deploy(), p.usdt(), scale(p.estFee()),
              chain.name() + ":" + p.row().addressKey());
        }
        mapper.insertEvent(b.id, String.valueOf(adminId), (retryOf == null ? "手动执行归集：" : "重试失败的地址：") + included.size() + " 个地址，合计 "
            + SweepSettings.plain(b.expectedUsdt) + " USDT，冷钱包 " + b.destination + "；原因：" + reason, now);
      });
    } catch (DuplicateKeyException e) {
      throw new BizException("SWEEP_BATCH_ACTIVE", "这条链有进行中的批次，或者有地址已经在别的批次里，请等它完成");
    }
    log.info("归集批次已建立 chain={} batch={} 地址 {} 个，合计 {} USDT", chain, b.publicNo, included.size(), b.expectedUsdt);
    try {
      send(b.id);
    } catch (RuntimeException e) {
      log.warn("归集批次发送出错，交给定时任务继续 batch={}：{}", b.publicNo, e.getMessage());
    }
    return b.publicNo;
  }

  /**
   * 发出一个 PENDING 批次的交易（建批次后马上调用；定时任务也会补发）。已经有发送记录的不再发（防止重复用 nonce）。
   */
  void send(long batchId) {
    SweepMapper.BatchRow b = mapper.batch(batchId);
    if (b == null || !PENDING.equals(b.status) || !mapper.txs(batchId).isEmpty()) return;
    Chain chain = Chain.valueOf(b.chain);
    Optional<String> why = unavailable(chain);
    if (why.isPresent()) {
      fail(b, "不能执行：" + why.get(), null);
      return;
    }
    Ctx ctx = ctx(chain);
    SweepGateway gw = ctx.gw();
    List<SweepMapper.ItemRow> items = mapper.items(batchId);
    String data = SweepAbi.sweepData(b.token, items.stream().map(SweepMapper.ItemRow::salt).toList());
    String from = ctx.operator().getAddress();
    BigInteger gasLimit;
    SweepGateway.Fees fees;
    try {
      long nodeChain = gw.chainId();
      if (nodeChain != ctx.chainId()) {
        fail(b, "节点的 chainId（" + nodeChain + "）和配置的（" + ctx.chainId() + "）不一致", null);
        return;
      }
      gw.call(from, b.executor, data);
      gasLimit = gw.estimateGas(from, b.executor, data).multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
      fees = gw.fees(gwei(props.priorityFeeGwei()));
      BigInteger need = gasLimit.multiply(fees.maxFee());
      BigInteger have = gw.nativeBalance(from);
      if (have.compareTo(need) < 0) {
        fail(b, "发起账户 " + from + " 的 " + gasAsset(chain) + " 不够：需要约 " + plain(wei(need)) + "，余额 " + plain(wei(have)), null);
        return;
      }
    } catch (NodeException.Rejected e) {
      fail(b, "模拟执行失败（没有发出交易）：" + SweepAbi.explain(e.getMessage()), null);
      return;
    } catch (NodeException.Unreachable e) {
      log.warn("归集发送前连不上节点，稍后再试 batch={}", b.publicNo);
      return;
    }
    record Signed(String raw, String hash, long nonce) {}
    Signed s = tx.execute(st -> {
      SweepMapper.BatchRow locked = mapper.lockBatch(batchId);
      if (!PENDING.equals(locked.status) || !mapper.txs(batchId).isEmpty()) return null;
      Long next = mapper.lockNonce(chain.name(), from);
      long nonce = next != null ? next : gw.pendingNonce(from);
      String raw = SweepAbi.sign(ctx.operator(), ctx.chainId(), fees.eip1559(), BigInteger.valueOf(nonce), gasLimit, fees.maxFee(), fees.priority(), b.executor, data);
      String hash = Hash.sha3(raw);
      mapper.insertTx(batchId, chain.name(), 1, nonce, hash, raw, gasLimit.longValueExact(), new BigDecimal(fees.maxFee()),
          fees.eip1559() ? new BigDecimal(fees.priority()) : null, now());
      mapper.saveNonce(chain.name(), from, nonce + 1);
      mapper.sendBatch(batchId, hash, now());
      mapper.insertEvent(batchId, SYSTEM, "发出批量交易 " + hash + "（发起账户 nonce " + nonce + "，gas 上限 " + gasLimit + "，每单位最多 " + gweiText(fees.maxFee()) + " gwei）", now());
      return new Signed(raw, hash, nonce);
    });
    if (s == null) return;
    broadcast(b, chain, ctx, s.raw(), s.hash());
  }

  private void broadcast(SweepMapper.BatchRow b, Chain chain, Ctx ctx, String raw, String hash) {
    try {
      ctx.gw().sendRaw(raw);
      log.info("归集交易已广播 chain={} batch={} tx={}", chain, b.publicNo, hash);
    } catch (NodeException.Unreachable e) {
      log.warn("归集交易广播时连不上节点，可能已经发出，按哈希继续查 chain={} batch={} tx={}", chain, b.publicNo, hash);
    } catch (NodeException.Rejected e) {
      String msg = e.getMessage().toLowerCase();
      if (msg.contains("already known") || msg.contains("known transaction")) return;
      SweepMapper.TxRow t = mapper.txs(b.id).getFirst();
      mapper.setTxStatus(t.id(), FAILED);
      resyncNonce(chain, ctx);
      if (mapper.txs(b.id).stream().noneMatch(x -> SENT.equals(x.status()))) fail(b, "节点拒绝了批量交易（没有上链）：" + e.getMessage(), null);
    }
  }

  private void resyncNonce(Chain chain, Ctx ctx) {
    try {
      String from = ctx.operator().getAddress();
      mapper.saveNonce(chain.name(), from, ctx.gw().pendingNonce(from));
    } catch (NodeException e) {
      log.warn("重新同步发起账户 nonce 失败 chain={}", chain);
    }
  }

  /** 批次失败：明细都标失败并释放（下次重新列入），通知风控人员 */
  private void fail(SweepMapper.BatchRow b, String reason, FeeResult fee) {
    Boolean done = tx.execute(st -> {
      int n = mapper.finishBatch(b.id, FAILED, BigDecimal.ZERO, fee == null ? BigDecimal.ZERO : fee.amount(), fee == null ? null : fee.txHash(),
          fee == null ? null : fee.block(), cut(reason), now());
      if (n == 0) return false;
      for (SweepMapper.ItemRow i : mapper.items(b.id)) {
        if (PENDING.equals(i.status())) mapper.finishItem(i.id(), FAILED, null, cut(reason), fee == null ? null : fee.txHash());
      }
      mapper.releaseItems(b.id);
      mapper.insertEvent(b.id, SYSTEM, "失败：" + reason, now());
      return true;
    });
    if (!Boolean.TRUE.equals(done)) return;
    log.warn("归集批次失败 chain={} batch={}：{}", b.chain, b.publicNo, reason);
    notifyStaff(b, "失败", "failed", reason);
  }

  private void notifyStaff(SweepMapper.BatchRow b, String zh, String en, String detail) {
    try {
      staff.notifyStaff("SWEEP_FAILED", Map.of("chain", b.chain, "batchNo", String.valueOf(b.publicNo), "statusZh", zh, "statusEn", en, "detail", cut(detail)));
    } catch (RuntimeException e) {
      log.error("归集失败通知写入失败", e);
    }
  }

  record FeeResult(BigDecimal amount, String txHash, Long block) {}

  // ---------- 确认 ----------

  /** 查一个 SENT 批次的链上结果（定时任务每 15 秒） */
  void check(long batchId) {
    SweepMapper.BatchRow b = mapper.batch(batchId);
    if (b == null || !SENT.equals(b.status)) return;
    Chain chain = Chain.valueOf(b.chain);
    Optional<SweepGateway> g = gateways.get(chain);
    if (g.isEmpty()) return;
    SweepGateway gw = g.get();
    List<SweepMapper.TxRow> sent = mapper.txs(batchId).stream().filter(t -> SENT.equals(t.status())).toList();
    if (sent.isEmpty()) return;
    try {
      // 先查 nonce 再查回执：避免"查回执时还没上链、查 nonce 时刚上链"被误判成 nonce 被别人用掉
      Optional<Credentials> op = operator.credentials();
      long usedNonce = op.isPresent() ? gw.latestNonce(op.get().getAddress()) : -1;
      long head = gw.blockNumber();
      for (SweepMapper.TxRow t : sent) {
        Optional<SweepGateway.Receipt> r = gw.receipt(t.txHash());
        if (r.isEmpty()) continue;
        long conf = head - r.get().blockNumber() + 1;
        if (conf < confirmations.of(chain)) return; // 已上链，等确认数
        finish(b, chain, t, r.get());
        return;
      }
      // 都还没上链
      SweepMapper.TxRow latest = sent.getFirst();
      if (usedNonce > latest.nonce()) {
        // nonce 已经被用掉了，但不是我们这几笔：发起账户被别人用了（私钥可能泄露，第 4.13.4 节）
        tx.executeWithoutResult(st -> fail(mapper.lockBatch(b.id), "发起账户 nonce " + latest.nonce() + " 已被别的交易用掉，这一批没有上链；请检查发起账户私钥是否泄露（方案第 4.13.4 节）", null));
        return;
      }
      Duration age = Duration.between(latest.createdAt(), now());
      if (age.compareTo(props.stuckAfter()) > 0) {
        if (sent.size() <= props.maxReplacements()) replace(b, chain, gw, latest);
        else stuck(b, latest, sent.size());
      } else if (age.compareTo(Duration.ofMinutes(2)) > 0) {
        try {
          gw.sendRaw(latest.rawTx());
        } catch (NodeException e) {
          log.debug("归集交易重发结果：{}", e.getMessage());
        }
      }
    } catch (NodeException e) {
      log.warn("查归集交易时节点不可用 chain={} batch={}", chain, b.publicNo);
    }
  }

  /** 同一 nonce、价格提高 20%（当前价格更高时用当前价格）重发 */
  private void replace(SweepMapper.BatchRow b, Chain chain, SweepGateway gw, SweepMapper.TxRow latest) {
    Optional<Credentials> op = operator.credentials();
    Long chainId = props.chainId(chain);
    if (op.isEmpty() || chainId == null) return;
    SweepGateway.Fees now = gw.fees(gwei(props.priorityFeeGwei()));
    BigInteger bumped = latest.maxFee().toBigInteger().multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100));
    BigInteger maxFee = bumped.max(now.maxFee());
    BigInteger priority = now.eip1559() ? (latest.priorityFee() == null ? now.priority() : latest.priorityFee().toBigInteger()).multiply(BigInteger.valueOf(120))
        .divide(BigInteger.valueOf(100)).max(now.priority()) : BigInteger.ZERO;
    String data = SweepAbi.sweepData(b.token, mapper.items(b.id).stream().map(SweepMapper.ItemRow::salt).toList());
    String raw = SweepAbi.sign(op.get(), chainId, now.eip1559(), BigInteger.valueOf(latest.nonce()), BigInteger.valueOf(latest.gasLimit()), maxFee, priority,
        b.executor, data);
    String hash = Hash.sha3(raw);
    tx.executeWithoutResult(st -> {
      mapper.insertTx(b.id, chain.name(), latest.attempt() + 1, latest.nonce(), hash, raw, latest.gasLimit(), new BigDecimal(maxFee),
          now.eip1559() ? new BigDecimal(priority) : null, now());
      mapper.insertEvent(b.id, SYSTEM, "超过 " + props.stuckAfter().toMinutes() + " 分钟没上链，同一 nonce 加价重发 " + hash + "（每单位最多 " + gweiText(maxFee) + " gwei）", now());
    });
    log.warn("归集交易超时未上链，加价重发 chain={} batch={} nonce={} tx={}", chain, b.publicNo, latest.nonce(), hash);
    try {
      gw.sendRaw(raw);
    } catch (NodeException e) {
      log.warn("加价重发被拒或连不上：{}", e.getMessage());
    }
  }

  private void stuck(SweepMapper.BatchRow b, SweepMapper.TxRow latest, int attempts) {
    LocalDateTime last = stuckAlerts.get(b.id);
    if (last != null && Duration.between(last, now()).compareTo(Duration.ofHours(1)) < 0) return;
    stuckAlerts.put(b.id, now());
    try {
      staff.notifyStaff("SWEEP_STUCK", Map.of("chain", b.chain, "batchNo", String.valueOf(b.publicNo), "nonce", String.valueOf(latest.nonce()), "attempts",
          String.valueOf(attempts - 1)));
    } catch (RuntimeException e) {
      log.error("归集卡住通知写入失败", e);
    }
  }

  /** 是否卡住（加价重发次数用完还没上链）：告警用 */
  boolean stuck(long batchId) {
    return stuckAlerts.containsKey(batchId);
  }

  /** 交易已上链且确认数够了：按事件逐个地址记结果 */
  private void finish(SweepMapper.BatchRow b, Chain chain, SweepMapper.TxRow t, SweepGateway.Receipt r) {
    BigInteger price = r.effectiveGasPrice() != null ? r.effectiveGasPrice() : t.maxFee().toBigInteger();
    FeeResult fee = new FeeResult(wei(r.gasUsed().multiply(price)), t.txHash(), r.blockNumber());
    if (!r.success()) {
      tx.executeWithoutResult(st -> fail(mapper.lockBatch(b.id), "批量交易在链上执行失败（回滚），地址里的钱没有动", fee));
      return;
    }
    int decimals = wallet.chain(chain).usdtDecimals();
    String token = b.token.toLowerCase();
    String parent = b.destination.toLowerCase();
    String executor = b.executor.toLowerCase();
    Map<String, BigInteger> moved = new HashMap<>();
    Map<String, String> failed = new HashMap<>();
    Set<String> ok = new HashSet<>();
    for (SweepGateway.Log l : r.logs()) {
      if (l.topics().isEmpty()) continue;
      String t0 = l.topics().getFirst();
      if (l.address().equals(token) && t0.equals(SweepAbi.TRANSFER_TOPIC) && l.topics().size() >= 3
          && SweepAbi.topicAddress(l.topics().get(2)).equals(parent)) {
        moved.merge(SweepAbi.topicAddress(l.topics().get(1)), new BigInteger(strip(l.data()), 16), BigInteger::add);
      } else if (l.address().equals(executor) && l.topics().size() >= 3) {
        String fwd = SweepAbi.topicAddress(l.topics().get(2));
        if (t0.equals(SweepAbi.SWEPT_TOPIC)) ok.add(fwd);
        else if (t0.equals(SweepAbi.SWEEP_FAILED_TOPIC)) failed.put(fwd, SweepAbi.eventReason(l.data()));
      }
    }
    Boolean done = tx.execute(st -> {
      SweepMapper.BatchRow locked = mapper.lockBatch(b.id);
      if (!SENT.equals(locked.status)) return false;
      BigDecimal total = BigDecimal.ZERO;
      int swept = 0;
      List<SweepMapper.ItemRow> items = mapper.items(b.id);
      for (SweepMapper.ItemRow i : items) {
        String key = i.address().toLowerCase();
        BigInteger amount = moved.get(key);
        if (amount != null && amount.signum() > 0) {
          BigDecimal v = new BigDecimal(amount).movePointLeft(decimals);
          mapper.finishItem(i.id(), "SWEPT", v, null, t.txHash());
          total = total.add(v);
          swept++;
          if (i.deploy()) addresses.markDeployed(i.addressId());
        } else if (failed.containsKey(key)) {
          mapper.finishItem(i.id(), FAILED, null, cut("批量合约报告失败：" + failed.get(key)), t.txHash());
        } else if (ok.contains(key)) {
          if (i.deploy()) addresses.markDeployed(i.addressId());
          mapper.finishItem(i.id(), FAILED, null, "执行时地址里没有 USDT（可能已经被 Safe 手动归集）", t.txHash());
        } else {
          mapper.finishItem(i.id(), FAILED, null, "交易里没有这个地址的结果", t.txHash());
        }
      }
      String status = swept == items.size() ? CONFIRMED : swept > 0 ? PARTIAL : FAILED;
      mapper.finishBatch(b.id, status, total, fee.amount(), t.txHash(), r.blockNumber(), status.equals(CONFIRMED) ? null : (items.size() - swept) + " 个地址没有归集成功", now());
      mapper.releaseItems(b.id);
      for (SweepMapper.TxRow other : mapper.txs(b.id)) if (!other.txHash().equals(t.txHash()) && SENT.equals(other.status())) mapper.setTxStatus(other.id(), "REPLACED");
      mapper.insertEvent(b.id, SYSTEM, "交易 " + t.txHash() + " 已确认（区块 " + r.blockNumber() + "）：" + swept + "/" + items.size() + " 个地址归集成功，合计 " + plain(total)
          + " USDT 进冷钱包，手续费 " + plain(fee.amount()) + " " + b.feeAsset, now());
      return true;
    });
    if (!Boolean.TRUE.equals(done)) return;
    SweepMapper.BatchRow after = mapper.batch(b.id);
    log.info("归集批次完成 chain={} batch={} status={} 金额={} 手续费={}", chain, b.publicNo, after.status, after.sweptUsdt, after.feePaid);
    if (PARTIAL.equals(after.status)) notifyStaff(after, "部分成功", "partially swept", after.failReason);
    else if (FAILED.equals(after.status)) notifyStaff(after, "失败", "failed", after.failReason);
  }

  /** 定时推进：补发 PENDING（建批次后没发出去的）、查 SENT */
  void advance() {
    for (long id : mapper.inFlight(PENDING)) {
      SweepMapper.BatchRow b = mapper.batch(id);
      // 刚建的批次由后台请求线程马上发；超过 1 分钟还是 PENDING 的才由这里补发
      if (b == null || Duration.between(b.createdAt, now()).toSeconds() < 60) continue;
      try {
        send(id);
      } catch (RuntimeException e) {
        log.warn("补发归集批次出错 batch={}：{}", b.publicNo, e.getMessage());
      }
    }
    for (long id : mapper.inFlight(SENT)) {
      try {
        check(id);
      } catch (RuntimeException e) {
        log.warn("查归集批次出错 id={}：{}", id, e.getMessage());
      }
    }
  }

  // ---------- 查询 ----------

  @Override
  public List<Overview> overview() {
    List<Overview> out = new ArrayList<>();
    for (Chain c : Chain.values()) {
      if (c.family != Chain.Family.ETH) {
        out.add(new Overview(c.name(), "OFFLINE", false, unavailable(c).orElse(null), BigDecimal.ZERO, 0, 0, null, null, gasAsset(c), mapper.lastSweptAt(c.name()),
            null, null));
        continue;
      }
      Optional<String> why = unavailable(c);
      Computed snap = snapshots.get(c);
      BigDecimal pending = BigDecimal.ZERO;
      int eligible = 0;
      int below = 0;
      if (snap != null) {
        for (Plan p : snap.plans()) {
          if (p.skip() == null) {
            pending = pending.add(p.usdt());
            eligible++;
          } else if ("belowThreshold".equals(p.skip())) {
            below++;
          }
        }
      }
      SweepMapper.BatchRow active = mapper.activeBatch(c.name());
      out.add(new Overview(c.name(), "CONTRACT", why.isEmpty(), why.orElse(null), pending, eligible, below, schemes.current().map(ForwarderScheme::parent).orElse(null),
          operatorBalance(c), gasAsset(c), mapper.lastSweptAt(c.name()), active == null ? null : active.publicNo, snapshotAt.get(c)));
    }
    return out;
  }

  /** 发起账户余额（由 {@link SweepMonitor} 定时查询后放进来） */
  private final Map<Chain, BigDecimal> operatorBalances = new ConcurrentHashMap<>();
  private volatile LocalDateTime operatorReadAt;

  BigDecimal operatorBalance(Chain c) {
    return operatorBalances.get(c);
  }

  /** 查一次发起账户余额和待归集金额（定时任务调用；访问节点只在这里和预览、执行里） */
  void refresh() {
    for (Chain c : List.of(Chain.ERC20, Chain.BSC)) {
      Optional<SweepGateway> g = gateways.get(c);
      Optional<String> addr = operator.address();
      if (g.isEmpty() || addr.isEmpty()) continue;
      try {
        operatorBalances.put(c, wei(g.get().nativeBalance(addr.get())));
        operatorReadAt = now();
      } catch (RuntimeException e) {
        log.warn("查发起账户余额失败（保留上次结果）chain={}：{}", c, e.getMessage());
      }
      if (unavailable(c).isEmpty()) {
        try {
          compute(c, null);
        } catch (RuntimeException e) {
          log.warn("查待归集金额失败 chain={}：{}", c, e.getMessage());
        }
      }
    }
  }

  @Override
  public BatchPage batches(String chain, int offset, int limit) {
    String c = chain == null || chain.isBlank() ? null : chainOf(chain).name();
    return new BatchPage(mapper.page(c, offset, limit).stream().map(SweepService::view).toList(), mapper.count(c));
  }

  @Override
  public Optional<BatchDetail> batch(long batchNo) {
    SweepMapper.BatchRow b = mapper.batchByNo(batchNo);
    if (b == null) return Optional.empty();
    List<Item> items = mapper.items(b.id).stream()
        .map(i -> new Item(i.itemNo(), i.userId(), i.address(), i.balanceBefore(), i.swept(), i.deploy(), i.status(), i.txHash(), i.failReason())).toList();
    List<Event> events = mapper.events(b.id).stream().map(e -> new Event(e.createdAt(), e.actor(), e.text())).toList();
    return Optional.of(new BatchDetail(view(b), b.reason, b.failReason, items, events));
  }

  static Batch view(SweepMapper.BatchRow b) {
    return new Batch(b.publicNo, b.chain, b.mode, b.status, b.triggerType, b.addressCount, b.expectedUsdt, b.sweptUsdt, b.feePaid, b.feeAsset, b.destination,
        b.createdBy, b.createdAt, b.finishedAt, b.expiresAt, b.txHash);
  }

  @Override
  public Contracts contracts() {
    Optional<ForwarderScheme> s = schemes.current();
    Map<String, BigDecimal> balances = new LinkedHashMap<>();
    for (Chain c : List.of(Chain.ERC20, Chain.BSC)) {
      BigDecimal v = operatorBalances.get(c);
      if (v != null) balances.put(c.name(), v);
    }
    return new Contracts(s.isPresent(), schemes.problem().orElse(null), s.map(ForwarderScheme::ver).orElse(0), blankTo(props.factory()),
        blankTo(props.implementation()), blankTo(props.parent()), blankTo(props.batcher()), operator.address().orElse(null), operator.problem().isEmpty(),
        operator.problem().orElse(null), balances, operatorReadAt);
  }

  // ---------- 小工具 ----------

  static BigInteger gwei(BigDecimal g) {
    return g.multiply(new BigDecimal(GWEI)).toBigInteger();
  }

  static BigDecimal wei(BigInteger w) {
    return new BigDecimal(w).movePointLeft(18);
  }

  static String gweiText(BigInteger w) {
    return plain(new BigDecimal(w).movePointLeft(9).setScale(4, RoundingMode.HALF_UP));
  }

  static BigDecimal scale(BigDecimal v) {
    return v.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
  }

  static String plain(BigDecimal v) {
    return SweepSettings.plain(v);
  }

  private static String strip(String h) {
    String s = h.startsWith("0x") ? h.substring(2) : h;
    return s.isEmpty() ? "0" : s;
  }

  private static String cut(String s) {
    return s == null ? null : s.length() <= 500 ? s : s.substring(0, 500);
  }

  private static String blankTo(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
