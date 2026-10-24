package com.forecast.app.wallet.deposit;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 链替身：可以出块、转账、回滚（换区块哈希），也可以故意返回别的合约的事件（模拟假币）。 */
class FakeChain implements ChainClient {

  long head;
  final Map<Long, String> hashes = new HashMap<>();
  final List<TokenTransfer> transfers = new ArrayList<>();
  int calls;

  FakeChain(long head) {
    this.head = head;
  }

  String hash(long block) {
    return hashes.computeIfAbsent(block, b -> "0x" + UUID.randomUUID().toString().replace("-", ""));
  }

  /** 在 block 区块里加一笔转账，返回交易哈希 */
  String transfer(long block, String contract, String to, BigInteger raw) {
    String tx = "0x" + UUID.randomUUID().toString().replace("-", "");
    transfers.add(new TokenTransfer(tx, 0, block, hash(block), "0xsender", to, contract, raw));
    return tx;
  }

  /** 回滚：这个区块换了哈希 */
  void reorg(long block) {
    hashes.put(block, "0x" + UUID.randomUUID().toString().replace("-", ""));
  }

  @Override
  public long headBlock() {
    calls++;
    return head;
  }

  /** 注意：不按合约过滤，把所有事件都返回，用来验证服务自己还会再检查一次合约（F3） */
  @Override
  public List<TokenTransfer> transfers(long fromBlock, long toBlock, String contract) {
    calls++;
    return transfers.stream().filter(t -> t.blockNumber() >= fromBlock && t.blockNumber() <= toBlock).toList();
  }

  @Override
  public Optional<String> blockHash(long blockNumber) {
    calls++;
    return blockNumber <= head ? Optional.of(hash(blockNumber)) : Optional.empty();
  }
}
