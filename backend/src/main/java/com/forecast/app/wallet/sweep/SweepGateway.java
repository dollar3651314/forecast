package com.forecast.app.wallet.sweep;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * 归集访问一条以太坊体系链的全部操作（只依赖这个接口：真实实现 {@link EvmSweepGateway} 用 JSON-RPC；单元测试用内存里的链替身）。
 * 出错时抛 {@code NodeException}：{@code Rejected} 节点明确拒绝（eth_call 回滚、交易被拒），{@code Unreachable} 连不上——不知道结果，不能当成失败。
 */
interface SweepGateway {

  /**
   * 手续费价格。
   *
   * @param eip1559 以太坊用 EIP-1559（maxFeePerGas = 2 × baseFee + 小费），BSC 用老式 gasPrice
   * @param baseFee EIP-1559 的 baseFee（wei）；BSC 为 0
   * @param priority EIP-1559 小费（wei）；BSC 为 0
   * @param maxFee 每单位 gas 最多付多少（wei）：以太坊 maxFeePerGas，BSC gasPrice
   */
  record Fees(boolean eip1559, BigInteger baseFee, BigInteger priority, BigInteger maxFee) {}

  record Log(String address, List<String> topics, String data) {}

  /** 交易回执；effectiveGasPrice 为空时按发送时的价格算手续费 */
  record Receipt(String txHash, boolean success, long blockNumber, BigInteger gasUsed, BigInteger effectiveGasPrice, List<Log> logs) {}

  long chainId();

  long blockNumber();

  BigInteger tokenBalance(String token, String holder);

  boolean hasCode(String address);

  BigInteger nativeBalance(String address);

  Fees fees(BigInteger priorityWei);

  /** eth_call（模拟执行）；回滚时抛 Rejected，带回滚原因 */
  String call(String from, String to, String data);

  BigInteger estimateGas(String from, String to, String data);

  /** 包括内存池里的交易在内的下一个 nonce */
  long pendingNonce(String address);

  /** 已经上链的交易用到的下一个 nonce */
  long latestNonce(String address);

  void sendRaw(String rawTx);

  Optional<Receipt> receipt(String txHash);
}
