package com.forecast.app.wallet.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 资金归集给后台用的接口（v2 第一阶段：以太坊、BSC 的合约归集；技术方案/v2-半自动归集.md 第 4.5、4.6、4.9 节）。
 * TRON（离线签名 + 租能量）是下一阶段：{@link #overview()} 里 TRC20 的 {@code available} 为 false。
 * 时间都是 UTC。金额是 USDT，手续费是手续费币（ETH / BNB）。
 */
public interface SweepAdminApi {

  /**
   * 一条链的概览（后台"归集记录"页的卡片）。待归集金额、地址数来自最近一次查询（每 5 分钟，或者打开预览时）。
   *
   * @param available 这条链现在能不能执行归集
   * @param unavailable 不能执行的原因（中文，给后台显示）；能执行时为空
   * @param operatorBalance 发起账户的手续费币余额；没查到为空
   * @param activeBatchNo 进行中的批次（对外编号）
   * @param checkedAt 待归集金额的查询时间；还没查过为空
   */
  record Overview(String chain, String mode, boolean available, String unavailable, BigDecimal pendingUsdt, int eligibleCount, int belowCount, String destination,
      BigDecimal operatorBalance, String gasAsset, LocalDateTime lastSweepAt, Long activeBatchNo, LocalDateTime checkedAt) {}

  /**
   * 预览里的一个地址。
   *
   * @param deploy 这次要顺便部署转发合约
   * @param estFeeUsd 预估手续费折美元；查不到币价时为空
   * @param skip 这次不归集的原因：pendingDeposit / recentCredit / inBatch / belowThreshold / overAddressCap / feeRatio / overBatchCap / batchFull；归集时为空
   */
  record Candidate(long userId, long addressId, String address, BigDecimal usdt, boolean deploy, BigDecimal estFee, BigDecimal estFeeUsd, String skip) {}

  /** 合计（只算这次要归集的） */
  record Totals(int count, BigDecimal usdt, BigDecimal fee, BigDecimal feeUsd, BigDecimal feePct, int skipped) {}

  /**
   * 预览。
   *
   * @param gasPrice 当前手续费价格（显示用，例如 "baseFee 1.2 gwei + 小费 0.05 gwei"）
   * @param warnings 提示：priceMissing（查不到币价，没检查手续费占比）
   */
  record Preview(String chain, String mode, String destination, BigDecimal threshold, BigDecimal maxFeeRatioPct, String gasPrice, String gasAsset,
      List<Candidate> candidates, Totals totals, List<String> warnings) {}

  record Batch(long no, String chain, String mode, String status, String trigger, int addressCount, BigDecimal expectedUsdt, BigDecimal sweptUsdt,
      BigDecimal fee, String feeAsset, String destination, Long createdBy, LocalDateTime createdAt, LocalDateTime finishedAt, LocalDateTime expiresAt,
      String txHash) {}

  record Item(int itemNo, long userId, String address, BigDecimal amount, BigDecimal swept, boolean deploy, String status, String txHash, String failReason) {}

  record Event(LocalDateTime time, String actor, String text) {}

  record BatchDetail(Batch batch, String reason, String failReason, List<Item> items, List<Event> events) {}

  record BatchPage(List<Batch> items, long total) {}

  /**
   * 合约和发起账户（归集设置页只读部分）。
   *
   * @param configured 合约地址参数配齐、检查通过
   * @param problem 配置问题；没问题或没配时为空
   * @param operatorReady 发起账户私钥和地址都配了并且对得上
   * @param operatorBalances 发起账户手续费币余额（ERC20 → ETH、BSC → BNB）
   */
  record Contracts(boolean configured, String problem, int schemeVer, String factory, String implementation, String parent, String batcher, String operator,
      boolean operatorReady, String operatorProblem, java.util.Map<String, BigDecimal> operatorBalances, LocalDateTime readAt) {}

  List<Overview> overview();

  /** 现在执行的话会归集哪些地址（实时查链上余额）；这条链不能执行时抛 {@code SWEEP_UNAVAILABLE} */
  Preview preview(String chain);

  /** 手动执行（SW-5）：按实时预览建批次并发出交易，返回批次对外编号 */
  long run(String chain, long adminId, String reason);

  /** 失败或部分成功的批次：把没归集成功的地址重新执行一次（新批次），返回新批次对外编号 */
  long retry(long batchNo, long adminId, String reason);

  BatchPage batches(String chain, int offset, int limit);

  Optional<BatchDetail> batch(long batchNo);

  Contracts contracts();
}
