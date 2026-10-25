package com.forecast.app.wallet.sweep;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 资金归集的 SQL，见 resources/mapper/wallet/SweepMapper.xml（迁移 V28）。 */
@Mapper
interface SweepMapper {

  /**
   * 一个可能要归集的合约地址（收到过这条链的充值）。
   *
   * @param pendingDeposit 有待确认的充值
   * @param lastCreditedBlock 最近一笔已入账充值的区块；没有为空
   * @param inBatch 已经在进行中的批次里
   */
  record CandidateRow(long addressId, long userId, String address, String addressKey, String salt, int schemeVer, boolean deployed, boolean pendingDeposit,
      Long lastCreditedBlock, boolean inBatch) {}

  /** 批次（字段可写，插入后回填 id） */
  final class BatchRow {
    public Long id;
    public long publicNo;
    public String chain;
    public String mode;
    public String status;
    public String triggerType;
    public String activeChain;
    public Integer schemeVer;
    public String token;
    public String destination;
    public String executor;
    public Long createdBy;
    public String reason;
    public Long retryOf;
    public int addressCount;
    public BigDecimal expectedUsdt;
    public BigDecimal sweptUsdt;
    public BigDecimal feePaid;
    public String feeAsset;
    public String paramsJson;
    public String txHash;
    public Long blockNumber;
    public String failReason;
    public LocalDateTime createdAt;
    public LocalDateTime sentAt;
    public LocalDateTime finishedAt;
    public LocalDateTime expiresAt;
  }

  record ItemRow(long id, long batchId, int itemNo, long addressId, long userId, String address, String salt, boolean deploy, BigDecimal balanceBefore,
      BigDecimal estFee, BigDecimal swept, String status, String failReason, String txHash) {}

  record TxRow(long id, long batchId, String chain, int attempt, long nonce, String txHash, String rawTx, long gasLimit, BigDecimal maxFee,
      BigDecimal priorityFee, String status, LocalDateTime createdAt) {}

  record EventRow(LocalDateTime createdAt, String actor, String text) {}

  List<CandidateRow> candidates(@Param("chain") String chain);

  int insertBatch(BatchRow b);

  int insertItem(@Param("batchId") long batchId, @Param("itemNo") int itemNo, @Param("addressId") long addressId, @Param("userId") long userId,
      @Param("address") String address, @Param("salt") String salt, @Param("deploy") boolean deploy, @Param("balance") BigDecimal balance,
      @Param("estFee") BigDecimal estFee, @Param("activeKey") String activeKey);

  BatchRow batch(@Param("id") long id);

  BatchRow lockBatch(@Param("id") long id);

  BatchRow batchByNo(@Param("no") long publicNo);

  List<BatchRow> page(@Param("chain") String chain, @Param("offset") int offset, @Param("limit") int limit);

  long count(@Param("chain") String chain);

  /** 进行中的批次 id（PENDING / SENT） */
  List<Long> inFlight(@Param("status") String status);

  BatchRow activeBatch(@Param("chain") String chain);

  /** 这条链最近一个结束了的批次 */
  BatchRow lastFinished(@Param("chain") String chain);

  LocalDateTime lastSweptAt(@Param("chain") String chain);

  int sendBatch(@Param("id") long id, @Param("txHash") String txHash, @Param("at") LocalDateTime at);

  int finishBatch(@Param("id") long id, @Param("status") String status, @Param("swept") BigDecimal swept, @Param("fee") BigDecimal fee,
      @Param("txHash") String txHash, @Param("blockNumber") Long blockNumber, @Param("failReason") String failReason, @Param("at") LocalDateTime at);

  List<ItemRow> items(@Param("batchId") long batchId);

  int finishItem(@Param("id") long id, @Param("status") String status, @Param("swept") BigDecimal swept, @Param("failReason") String failReason,
      @Param("txHash") String txHash);

  int releaseItems(@Param("batchId") long batchId);

  int insertTx(@Param("batchId") long batchId, @Param("chain") String chain, @Param("attempt") int attempt, @Param("nonce") long nonce,
      @Param("txHash") String txHash, @Param("raw") String raw, @Param("gasLimit") long gasLimit, @Param("maxFee") BigDecimal maxFee,
      @Param("priorityFee") BigDecimal priorityFee, @Param("at") LocalDateTime at);

  /** 这一批的发送记录，最新的在前 */
  List<TxRow> txs(@Param("batchId") long batchId);

  int setTxStatus(@Param("id") long id, @Param("status") String status);

  /** 这笔交易是不是某个归集批次发的（扫块区分"归集"和"不是归集的转出"） */
  Long batchOfTx(@Param("chain") String chain, @Param("txHash") String txHash);

  Long lockNonce(@Param("chain") String chain, @Param("operator") String operator);

  int saveNonce(@Param("chain") String chain, @Param("operator") String operator, @Param("nonce") long nonce);

  int insertEvent(@Param("batchId") long batchId, @Param("actor") String actor, @Param("text") String text, @Param("at") LocalDateTime at);

  List<EventRow> events(@Param("batchId") long batchId);

  int insertOutflow(@Param("chain") String chain, @Param("txHash") String txHash, @Param("logIndex") int logIndex, @Param("blockNumber") long blockNumber,
      @Param("from") String from, @Param("to") String to, @Param("amount") BigDecimal amount, @Param("addressId") long addressId,
      @Param("userId") long userId, @Param("kind") String kind, @Param("batchId") Long batchId);

  /** 不是归集的转出（UNKNOWN、DEST_MISMATCH），对账还没标"已处理"的条数 */
  long openOutflows();
}
