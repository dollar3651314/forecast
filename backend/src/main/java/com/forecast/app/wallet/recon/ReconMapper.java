package com.forecast.app.wallet.recon;

import com.forecast.app.wallet.api.WalletReconApi;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 对账的 SQL，见 resources/mapper/wallet/ReconMapper.xml。 */
@Mapper
interface ReconMapper {

  record DepositRow(long id, String chain, String txHash, int logIndex, long userId, BigDecimal amount, String status, long blockNumber, String blockHash,
      LocalDateTime createdAt) {}

  record SuspenseRow(long id, long userId, long depositId, BigDecimal amount, String status) {}

  record WithdrawalRow(long id, long userId, BigDecimal amount, String status) {}

  /** v2 归集：不是归集的转出（UNKNOWN / DEST_MISMATCH） */
  record OutflowRow(String chain, String txHash, int logIndex, long userId, String fromAddress, String toAddress, BigDecimal amount, String kind) {}

  /** v2 归集：已归集成功的明细，和扫块看到的同一笔交易、同一个地址转出的合计（扫块已经扫过那个区块的才列出） */
  record SweptRow(String chain, long batchNo, int itemNo, long userId, BigDecimal swept, BigDecimal outflow) {}

  List<OutflowRow> sweepOutflows();

  List<SweptRow> sweptItems();

  /** 不管状态（归集的问题处理过以后不再重复列出） */
  int issueCount(@Param("kind") String kind, @Param("refId") String refId);

  /** 已入账和已冲正的充值（入过账的） */
  List<DepositRow> creditedDeposits();

  List<SuspenseRow> suspenses();

  List<WithdrawalRow> withdrawals();

  int openIssueCount(@Param("kind") String kind, @Param("refId") String refId);

  int insertIssue(@Param("kind") String kind, @Param("refId") String refId, @Param("userId") Long userId, @Param("expected") BigDecimal expected,
      @Param("actual") BigDecimal actual, @Param("detail") String detail);

  List<WalletReconApi.Issue> issues(@Param("status") String status, @Param("limit") int limit);

  List<WalletReconApi.Issue> issuePage(@Param("status") String status, @Param("offset") int offset, @Param("limit") int limit);

  long countIssues(@Param("status") String status);

  WalletReconApi.Issue lockIssue(@Param("id") long id);

  /** 只改"待处理"的（修复 F5：现有系统把这个条件写反了） */
  int resolveIssue(@Param("id") long id, @Param("adminId") long adminId, @Param("note") String note, @Param("at") LocalDateTime at);

  int resolveSuspense(@Param("id") long id, @Param("at") LocalDateTime at, @Param("note") String note);
}
