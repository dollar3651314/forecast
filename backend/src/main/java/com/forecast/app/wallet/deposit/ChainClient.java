package com.forecast.app.wallet.deposit;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * 一条链的只读访问：查最新区块、查 USDT 合约的转账事件、查区块哈希。扫块只依赖这个接口，
 * 真实实现（以太坊体系 JSON-RPC、TronGrid）在 S12b，测试用链替身。
 */
public interface ChainClient {

  /** 一笔代币转账事件。rawAmount 是链上的整数金额（未按精度换算） */
  record TokenTransfer(String txHash, int logIndex, long blockNumber, String blockHash, String from, String to, String contract, BigInteger rawAmount) {}

  long headBlock();

  /** [fromBlock, toBlock] 区间里 contract 合约的转账事件 */
  List<TokenTransfer> transfers(long fromBlock, long toBlock, String contract);

  /** 区块现在的哈希；区块不存在（例如被回滚后链变短）时为空 */
  Optional<String> blockHash(long blockNumber);
}
