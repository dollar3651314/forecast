package com.forecast.app.wallet.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 后台查看充值和提现的只读查询（wallet_* 表）。只有 wallet 模块使用。 */
@Mapper
interface WalletAdminMapper {

  class DepositRow {
    long id;
    long publicNo;
    long userId;
    String chain;
    String txHash;
    BigDecimal amount;
    int confirmations;
    String status;
    LocalDateTime createdAt;
    LocalDateTime creditedAt;
  }

  class WithdrawalRow {
    long id;
    long publicNo;
    long userId;
    BigDecimal amount;
    BigDecimal fee;
    String network;
    String address;
    boolean firstUse;
    LocalDateTime whitelistAddedAt;
    String status;
    LocalDateTime delayEndsAt;
    LocalDateTime createdAt;
    Long reviewerId;
    String rejectReason;
    String txHash;
  }

  class BucketRow {
    String status;
    long count;
    BigDecimal amount;
  }

  List<DepositRow> deposits(@Param("chain") String chain, @Param("statuses") Collection<String> statuses, @Param("txHash") String txHash,
      @Param("userIds") Collection<Long> userIds, @Param("no") Long no, @Param("offset") int offset, @Param("limit") int limit);

  long countDeposits(@Param("chain") String chain, @Param("statuses") Collection<String> statuses, @Param("txHash") String txHash,
      @Param("userIds") Collection<Long> userIds, @Param("no") Long no);

  List<WithdrawalRow> withdrawals(@Param("statuses") Collection<String> statuses, @Param("chain") String chain, @Param("min") BigDecimal min,
      @Param("max") BigDecimal max, @Param("userIds") Collection<Long> userIds, @Param("no") Long no, @Param("id") Long id, @Param("auto") String auto,
      @Param("offset") int offset, @Param("limit") int limit);

  long countWithdrawals(@Param("statuses") Collection<String> statuses, @Param("chain") String chain, @Param("min") BigDecimal min,
      @Param("max") BigDecimal max, @Param("userIds") Collection<Long> userIds, @Param("no") Long no, @Param("auto") String auto);

  Long withdrawalIdByNo(@Param("no") long no);

  List<BucketRow> openBuckets();

  BucketRow completedSince(@Param("since") LocalDateTime since);

  BigDecimal totalDeposit(@Param("userId") long userId);

  BucketRow creditedSince(@Param("since") LocalDateTime since);
}
