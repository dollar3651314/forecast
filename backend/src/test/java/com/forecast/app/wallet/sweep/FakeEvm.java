package com.forecast.app.wallet.sweep;

import com.forecast.app.wallet.address.Create2;
import com.forecast.app.wallet.chain.NodeException;
import com.forecast.app.wallet.deposit.ChainClient;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.web3j.crypto.Hash;
import org.web3j.crypto.SignedRawTransaction;
import org.web3j.crypto.TransactionDecoder;

/**
 * 内存里的以太坊体系链替身：同时是扫块用的 {@link ChainClient} 和归集用的 {@link SweepGateway}。
 * 按批量合约 + BitGo 转发合约的语义执行 sweep 交易：每个盐算出转发地址（{@link Create2}），没部署就"部署"，有余额就转给 parent，
 * 记 Transfer / Swept / SweepFailed 事件；也能模拟节点拒绝、连不上、交易不上链、nonce 被别人用掉。
 */
class FakeEvm implements ChainClient, SweepGateway {

  final long chainId;
  final String token;
  final String factory;
  final String implementation;
  final String parent;
  final String batcher;
  long head;
  long latestNonce;
  final Map<String, BigInteger> balances = new HashMap<>();
  final Map<String, BigInteger> natives = new HashMap<>();
  final Set<String> code = new HashSet<>();
  final Map<Long, String> blockHashes = new HashMap<>();
  final List<TokenTransfer> transfers = new ArrayList<>();
  final Map<String, String> mempool = new LinkedHashMap<>();
  final Map<String, Receipt> receipts = new HashMap<>();
  final List<String> sentRaw = new ArrayList<>();
  /** 这些转发地址归集时失败（SweepFailed） */
  final Set<String> failFor = new HashSet<>();
  BigInteger baseFee = BigInteger.valueOf(1_000_000_000L);
  boolean rejectCall;
  boolean rejectSend;
  boolean unreachableSend;
  boolean revertOnChain;

  FakeEvm(long chainId, String token, String factory, String implementation, String parent, String batcher, long head) {
    this.chainId = chainId;
    this.token = token.toLowerCase();
    this.factory = factory;
    this.implementation = implementation;
    this.parent = parent.toLowerCase();
    this.batcher = batcher.toLowerCase();
    this.head = head;
  }

  String hash(long block) {
    return blockHashes.computeIfAbsent(block, b -> "0x" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
  }

  static String txHash() {
    return "0x" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
  }

  BigInteger balance(String a) {
    return balances.getOrDefault(a.toLowerCase(), BigInteger.ZERO);
  }

  /** 客户转入：下一个区块里一笔 Transfer */
  String deposit(String to, BigInteger raw) {
    head++;
    String tx = txHash();
    balances.merge(to.toLowerCase(), raw, BigInteger::add);
    transfers.add(new TokenTransfer(tx, 0, head, hash(head), "0x000000000000000000000000000000000000c0de", to.toLowerCase(), token, raw));
    return tx;
  }

  /** 有人直接从转发地址转出（例如 Safe 手动 flushTokens，或者模拟合约被攻破） */
  String outflow(String from, String to, BigInteger raw) {
    head++;
    String tx = txHash();
    balances.merge(from.toLowerCase(), raw.negate(), BigInteger::add);
    balances.merge(to.toLowerCase(), raw, BigInteger::add);
    transfers.add(new TokenTransfer(tx, 0, head, hash(head), from.toLowerCase(), to.toLowerCase(), token, raw));
    return tx;
  }

  void mineEmpty(int n) {
    head += n;
  }

  /** 把内存池里的交易打包进下一个区块 */
  void mine() {
    head++;
    for (Map.Entry<String, String> e : new ArrayList<>(mempool.entrySet())) {
      SignedRawTransaction t = (SignedRawTransaction) TransactionDecoder.decode(e.getValue());
      if (t.getNonce().longValueExact() != latestNonce) continue;
      execute(e.getKey(), t);
      latestNonce++;
      mempool.clear(); // 同一 nonce 的其他（加价重发前的）交易作废
      break;
    }
  }

  private void execute(String hash, SignedRawTransaction t) {
    List<Log> logs = new ArrayList<>();
    if (revertOnChain) {
      receipts.put(hash, new Receipt(hash, false, head, BigInteger.valueOf(50_000), BigInteger.valueOf(2_000_000_000L), logs));
      return;
    }
    List<String> args = SweepAbi.decodeSweep(t.getData().startsWith("0x") ? t.getData() : "0x" + t.getData());
    String tok = args.getFirst().toLowerCase();
    int logIndex = 0;
    long gas = 30_000;
    for (String salt : args.subList(1, args.size())) {
      String fwd = Create2.forwarderAddress(factory, implementation, parent, batcher, salt).toLowerCase();
      boolean deploy = !code.contains(fwd);
      if (failFor.contains(fwd)) {
        logs.add(new Log(batcher, List.of(SweepAbi.SWEEP_FAILED_TOPIC, salt, topic(fwd)), "0x" + word(32) + word(0)));
        logIndex++;
        continue;
      }
      code.add(fwd);
      gas += deploy ? 130_000 : 30_000;
      BigInteger amount = balances.getOrDefault(fwd, BigInteger.ZERO);
      if (tok.equals(token) && amount.signum() > 0) {
        balances.put(fwd, BigInteger.ZERO);
        balances.merge(parent, amount, BigInteger::add);
        logs.add(new Log(token, List.of(SweepAbi.TRANSFER_TOPIC, topic(fwd), topic(parent)), "0x" + word(amount)));
        transfers.add(new TokenTransfer(hash, logIndex, head, hash(head), fwd, parent, token, amount));
        logIndex++;
      }
      logs.add(new Log(batcher, List.of(SweepAbi.SWEPT_TOPIC, salt, topic(fwd)), "0x" + word(deploy ? 1 : 0)));
      logIndex++;
    }
    receipts.put(hash, new Receipt(hash, true, head, BigInteger.valueOf(gas), BigInteger.valueOf(2_000_000_000L), logs));
  }

  private static String topic(String address) {
    return "0x" + "0".repeat(24) + address.substring(2).toLowerCase();
  }

  private static String word(long v) {
    return word(BigInteger.valueOf(v));
  }

  private static String word(BigInteger v) {
    String h = v.toString(16);
    return "0".repeat(64 - h.length()) + h;
  }

  // ---------- ChainClient（扫块） ----------

  @Override
  public long headBlock() {
    return head;
  }

  @Override
  public List<TokenTransfer> transfers(long fromBlock, long toBlock, String contract) {
    return transfers.stream().filter(t -> t.blockNumber() >= fromBlock && t.blockNumber() <= toBlock).toList();
  }

  @Override
  public Optional<String> blockHash(long blockNumber) {
    return blockNumber <= head ? Optional.of(hash(blockNumber)) : Optional.empty();
  }

  // ---------- SweepGateway（归集） ----------

  @Override
  public long chainId() {
    return chainId;
  }

  @Override
  public long blockNumber() {
    return head;
  }

  @Override
  public BigInteger tokenBalance(String tok, String holder) {
    return tok.equalsIgnoreCase(token) ? balance(holder) : BigInteger.ZERO;
  }

  @Override
  public boolean hasCode(String address) {
    return code.contains(address.toLowerCase());
  }

  @Override
  public BigInteger nativeBalance(String address) {
    return natives.getOrDefault(address.toLowerCase(), BigInteger.TEN.pow(18));
  }

  @Override
  public Fees fees(BigInteger priorityWei) {
    return chainId == 56 || chainId == 97 ? new Fees(false, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(3_000_000_000L))
        : new Fees(true, baseFee, priorityWei, baseFee.multiply(BigInteger.TWO).add(priorityWei));
  }

  @Override
  public String call(String from, String to, String data) {
    if (rejectCall) throw new NodeException.Rejected("节点返回错误（eth_call）：execution reverted: NotOperator");
    return "0x";
  }

  @Override
  public BigInteger estimateGas(String from, String to, String data) {
    return BigInteger.valueOf(200_000);
  }

  @Override
  public long pendingNonce(String address) {
    return latestNonce + (mempool.isEmpty() ? 0 : 1);
  }

  @Override
  public long latestNonce(String address) {
    return latestNonce;
  }

  @Override
  public void sendRaw(String rawTx) {
    if (rejectSend) throw new NodeException.Rejected("节点返回错误（eth_sendRawTransaction）：nonce too low");
    String h = Hash.sha3(rawTx);
    sentRaw.add(rawTx);
    mempool.put(h, rawTx);
    if (unreachableSend) throw new NodeException.Unreachable("连接节点失败（eth_sendRawTransaction）", null);
  }

  @Override
  public Optional<Receipt> receipt(String hash) {
    return Optional.ofNullable(receipts.get(hash));
  }
}
