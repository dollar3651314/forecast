package com.forecast.app.wallet.withdraw;

import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.chain.JsonRpc;
import com.forecast.app.wallet.chain.NodeException;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;
import tools.jackson.databind.JsonNode;

/**
 * 以太坊体系（ERC20、BSC）的提现广播（S13b，修复 F6）。规则：
 * <ul>
 *   <li>同一笔提现已经有"已发出"的记录就不再用新 nonce 发（防止崩溃重启后重复付钱）</li>
 *   <li>nonce 按链存在数据库里，第一次从链上同步；节点明确拒绝时重新同步</li>
 *   <li>连不上节点：当作"可能已经发出"，交给确认任务去查，<b>不退款</b></li>
 *   <li>30 分钟没上链：同一 nonce 提高 gas 价格重发，最多 3 次，之后通知人工处理</li>
 *   <li>链上执行失败（回滚）：转账确定没发生，可以退款</li>
 * </ul>
 */
class EvmWithdrawSender implements WithdrawSender {

  private static final Logger log = LoggerFactory.getLogger(EvmWithdrawSender.class);
  static final String TRANSFER = "0xa9059cbb";
  static final String BALANCE_OF = "0x70a08231";
  private static final int MAX_FAILED_SENDS = 3;

  private final Chain chain;
  private final JsonRpc rpc;
  private final Credentials hot;
  private final String contract;
  private final int decimals;
  private final long chainId;
  private final int confirmations;
  private final HotWalletProperties props;
  private final WithdrawTxMapper txs;
  private final TransactionTemplate tx;
  private final StaffAlertApi staff;
  private final Clock clock;
  private volatile LocalDateTime lastAlert;

  EvmWithdrawSender(Chain chain, JsonRpc rpc, Credentials hot, String contract, int decimals, long chainId, int confirmations, HotWalletProperties props,
      WithdrawTxMapper txs, TransactionTemplate tx, StaffAlertApi staff, Clock clock) {
    this.chain = chain;
    this.rpc = rpc;
    this.hot = hot;
    this.contract = contract;
    this.decimals = decimals;
    this.chainId = chainId;
    this.confirmations = confirmations;
    this.props = props;
    this.txs = txs;
    this.tx = tx;
    this.staff = staff;
    this.clock = clock;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  /** USDT transfer(to, amount) 的调用数据 */
  static String transferData(String to, BigInteger amount) {
    return TRANSFER + Numeric.toHexStringNoPrefixZeroPadded(Numeric.toBigInt(to), 64) + Numeric.toHexStringNoPrefixZeroPadded(amount, 64);
  }

  /** 签名：legacy 交易 + EIP-155（chainId 防重放） */
  static String sign(Credentials c, long chainId, BigInteger nonce, BigInteger gasPrice, BigInteger gasLimit, String contract, String data) {
    RawTransaction raw = RawTransaction.createTransaction(nonce, gasPrice, gasLimit, contract, BigInteger.ZERO, data);
    return Numeric.toHexString(TransactionEncoder.signMessage(raw, chainId, c));
  }

  @Override
  public Outcome send(Order order) {
    List<WithdrawTxMapper.TxRow> history = txs.attempts(order.withdrawalId());
    for (WithdrawTxMapper.TxRow r : history) if ("SENT".equals(r.status())) return new Sent(r.txHash());
    if (history.size() >= MAX_FAILED_SENDS) return new Rejected("节点连续拒绝：" + chain);

    BigInteger amount = order.sendAmount().setScale(decimals, RoundingMode.DOWN).movePointRight(decimals).toBigIntegerExact();
    BigInteger gasPrice;
    try {
      BigInteger usdt = JsonRpc.bigHex(rpc.call("eth_call", List.of(Map.of("to", contract, "data", BALANCE_OF + pad(hot.getAddress())), "latest")).asString());
      gasPrice = JsonRpc.bigHex(rpc.call("eth_gasPrice", List.of()).asString()).multiply(BigInteger.valueOf(110)).divide(BigInteger.valueOf(100));
      BigInteger gas = JsonRpc.bigHex(rpc.call("eth_getBalance", List.of(hot.getAddress(), "latest")).asString());
      if (usdt.compareTo(amount) < 0) return lowFunds("USDT");
      if (gas.compareTo(gasPrice.multiply(BigInteger.valueOf(props.gasLimit()))) < 0) return lowFunds(gasCoin(chain));
    } catch (NodeException e) {
      return new Wait("节点暂时不可用：" + e.getMessage());
    }

    String data = transferData(order.address(), amount);
    record Signed(String raw, String hash, long nonce, long id) {}
    Signed s;
    try {
      s = tx.execute(st -> {
        Long next = txs.lockNonce(chain.name());
        long nonce = next != null ? next : pendingCount();
        String raw = sign(hot, chainId, BigInteger.valueOf(nonce), gasPrice, BigInteger.valueOf(props.gasLimit()), contract, data);
        String hash = Hash.sha3(raw);
        txs.insert(order.withdrawalId(), chain.name(), history.size() + 1, nonce, hash, raw, gasPrice.longValueExact(), null, now());
        txs.saveNonce(chain.name(), nonce + 1);
        long id = txs.attempts(order.withdrawalId()).getFirst().id();
        return new Signed(raw, hash, nonce, id);
      });
    } catch (NodeException e) {
      return new Wait("同步 nonce 失败：" + e.getMessage());
    }
    try {
      rpc.call("eth_sendRawTransaction", List.of(s.raw()));
      log.info("提现已广播 chain={} withdrawal={} nonce={} tx={}", chain, order.withdrawalId(), s.nonce(), s.hash());
      return new Sent(s.hash());
    } catch (NodeException.Unreachable e) {
      log.warn("提现广播时连不上节点，可能已经发出，交给确认任务 chain={} withdrawal={} tx={}", chain, order.withdrawalId(), s.hash());
      return new Sent(s.hash());
    } catch (NodeException.Rejected e) {
      String msg = e.getMessage().toLowerCase();
      if (msg.contains("already known") || msg.contains("known transaction")) return new Sent(s.hash());
      txs.setStatus(s.id(), "FAILED");
      resyncNonce();
      log.warn("节点拒绝提现广播 chain={} withdrawal={}：{}", chain, order.withdrawalId(), e.getMessage());
      if (msg.contains("insufficient funds")) return lowFunds(gasCoin(chain));
      return history.size() + 1 >= MAX_FAILED_SENDS ? new Rejected(e.getMessage()) : new Wait(e.getMessage());
    }
  }

  @Override
  public Check check(long withdrawalId) {
    List<WithdrawTxMapper.TxRow> sent = txs.attempts(withdrawalId).stream().filter(r -> "SENT".equals(r.status())).toList();
    if (sent.isEmpty()) return new Pending();
    try {
      long head = JsonRpc.hex(rpc.call("eth_blockNumber", List.of()).asString());
      for (WithdrawTxMapper.TxRow r : sent) {
        JsonNode receipt = rpc.call("eth_getTransactionReceipt", List.of(r.txHash()));
        if (receipt == null || receipt.isNull() || receipt.isMissingNode()) continue;
        if (!"0x1".equals(receipt.path("status").asString())) return new Failed("链上执行失败（交易回滚）tx=" + r.txHash());
        long conf = head - JsonRpc.hex(receipt.path("blockNumber").asString()) + 1;
        return conf >= confirmations ? new Confirmed(r.txHash()) : new Pending();
      }
      WithdrawTxMapper.TxRow latest = sent.getFirst();
      Duration age = Duration.between(latest.createdAt(), now());
      if (age.compareTo(props.stuckAfter()) > 0) {
        if (sent.size() <= props.maxReplacements()) replace(latest);
        else alert("WITHDRAW_STUCK", Map.of("chain", chain.name(), "withdrawalId", publicNo(withdrawalId), "nonce", String.valueOf(latest.nonce())));
      } else if (age.compareTo(Duration.ofMinutes(2)) > 0) {
        rebroadcast(latest);
      }
    } catch (NodeException e) {
      log.warn("查提现结果时节点不可用 chain={} withdrawal={}", chain, withdrawalId);
    }
    return new Pending();
  }

  /** 同一 nonce、gas 价格提高 20%（或当前价格更高时用当前价格）重发 */
  private void replace(WithdrawTxMapper.TxRow latest) {
    BigInteger bumped = BigInteger.valueOf(latest.feePrice()).multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100));
    BigInteger current = JsonRpc.bigHex(rpc.call("eth_gasPrice", List.of()).asString());
    BigInteger price = bumped.max(current);
    String data = decodeData(latest.rawTx());
    String raw = sign(hot, chainId, BigInteger.valueOf(latest.nonce()), price, BigInteger.valueOf(props.gasLimit()), contract, data);
    String hash = Hash.sha3(raw);
    txs.insert(latest.withdrawalId(), chain.name(), latest.attempt() + 1, latest.nonce(), hash, raw, price.longValueExact(), null, now());
    log.warn("提现超时未上链，加价重发 chain={} withdrawal={} nonce={} gasPrice={}", chain, latest.withdrawalId(), latest.nonce(), price);
    try {
      rpc.call("eth_sendRawTransaction", List.of(raw));
    } catch (NodeException e) {
      log.warn("加价重发被拒或连不上：{}", e.getMessage());
    }
  }

  /** 节点可能把交易丢了（例如广播时网络断了），原样再发一次；已经在内存池里时节点会回"已知"，无害 */
  private void rebroadcast(WithdrawTxMapper.TxRow r) {
    try {
      rpc.call("eth_sendRawTransaction", List.of(r.rawTx()));
    } catch (NodeException e) {
      log.debug("重发结果：{}", e.getMessage());
    }
  }

  /** 从签好名的交易里取回调用数据（加价重发时转给同一个地址、同样的金额） */
  static String decodeData(String rawHex) {
    var decoded = org.web3j.crypto.TransactionDecoder.decode(rawHex);
    return decoded.getData().startsWith("0x") ? decoded.getData() : "0x" + decoded.getData();
  }

  private long pendingCount() {
    return JsonRpc.hex(rpc.call("eth_getTransactionCount", List.of(hot.getAddress(), "pending")).asString());
  }

  private void resyncNonce() {
    try {
      long n = pendingCount();
      txs.saveNonce(chain.name(), n);
    } catch (NodeException e) {
      log.warn("重新同步 nonce 失败 chain={}", chain);
    }
  }

  /**
   * 热钱包余额不足：告警邮件的 {@code {what}} 只传币种（USDT / ETH / BNB），"余额不足"由模板写
   * （体验优化 1021 UX-11：以前传"USDT 不够"，渲染成"USDT 不够 不足"，英文邮件里也是中文）。
   */
  private Outcome lowFunds(String coin) {
    alert("HOT_WALLET_LOW", Map.of("chain", chain.name(), "address", hot.getAddress(), "what", coin));
    return new Wait("热钱包 " + coin + " 余额不足");
  }

  /** 付手续费的币：以太坊 ETH，BSC 是 BNB */
  static String gasCoin(Chain chain) {
    return chain == Chain.BSC ? "BNB" : "ETH";
  }

  /** 告警邮件里的提现编号：后台显示的对外编号；查不到时（不应该发生）退回内部 id */
  private String publicNo(long withdrawalId) {
    Long no = txs.publicNo(withdrawalId);
    return String.valueOf(no == null ? withdrawalId : no);
  }

  /** 同一条链一小时最多通知一次 */
  private void alert(String template, Map<String, String> params) {
    LocalDateTime now = now();
    if (lastAlert != null && Duration.between(lastAlert, now).compareTo(Duration.ofHours(1)) < 0) return;
    lastAlert = now;
    staff.notifyStaff(template, params);
  }

  private static String pad(String address) {
    return Numeric.toHexStringNoPrefixZeroPadded(Numeric.toBigInt(address), 64);
  }
}
