package com.forecast.app.wallet.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 后台查看充值和提现（S16b）。只读；审核操作走 {@link WithdrawAdminApi}。 */
public interface WalletAdminApi {

  record Page<T>(List<T> items, long total) {}

  /**
   * 一笔充值。status：PENDING / CREDITED / DROPPED / BELOW_MIN / REVERSED。no 是对外编号（雪花 ID，旧数据等于 id；后台显示和搜索用它）。
   */
  record Deposit(long id, long no, long userId, String chain, String txHash, BigDecimal amount, int confirmations, int requiredConfirmations, String status,
      LocalDateTime detectedAt, LocalDateTime creditedAt) {}

  /**
   * 一笔提现。status 是数据库状态（COOLING / REVIEWING / APPROVED / DELAYED / BROADCASTING / COMPLETED / REJECTED / CANCELLED / FAILED_REFUNDED）；
   * firstUse：这个客户第一次往这个地址提现；whitelistAddedAt：地址加入白名单的时间（白名单已删除时为空）。no 是对外编号（雪花 ID，旧数据等于 id）。
   */
  record Withdrawal(long id, long no, long userId, BigDecimal amount, BigDecimal fee, String chain, String address, boolean firstUse,
      LocalDateTime whitelistAddedAt, String status, LocalDateTime delayEndsAt, LocalDateTime submittedAt, Long reviewerId, String rejectReason,
      String txHash) {}

  /** 某个状态的笔数和金额 */
  record Bucket(long count, BigDecimal amount) {}

  record Summary(Bucket reviewing, Bucket delayed, Bucket cooling, Bucket completedToday) {}

  /** 客户的资金概况（出金审核详情页） */
  record CustomerFunds(BigDecimal totalDeposit, List<Deposit> recentDeposits, List<Withdrawal> recentWithdrawals) {}

  /**
   * userIds 不为空时只查这些客户；txHash 不为空时按交易哈希匹配；no 不为空时也匹配这个对外编号（和 userIds 是"或"：
   * 后台搜索框输入数字时，既可能是客户号也可能是编号）。userIds 为空列表并且 no 为空时返回空页。
   */
  Page<Deposit> deposits(String chain, Collection<String> statuses, String txHash, Collection<Long> userIds, Long no, int offset, int limit);

  default Page<Withdrawal> withdrawals(Collection<String> statuses, String chain, BigDecimal minAmount, BigDecimal maxAmount, Collection<Long> userIds, Long no,
      int offset, int limit) {
    return withdrawals(statuses, chain, minAmount, maxAmount, userIds, no, null, offset, limit);
  }

  /**
   * autoReview（v2 自动审批，按最近一次判定筛选）：AUTO_APPROVED / MANUAL（含 ERROR）/ MASTER_OFF / NONE（没有判定记录）/ R1…R13（触发了这条规则）；为空不筛选。
   */
  Page<Withdrawal> withdrawals(Collection<String> statuses, String chain, BigDecimal minAmount, BigDecimal maxAmount, Collection<Long> userIds, Long no,
      String autoReview, int offset, int limit);

  Optional<Withdrawal> withdrawal(long id);

  /** 对外编号 → 内部 id（后台接口路径里是对外编号） */
  Optional<Long> withdrawalIdByNo(long no);

  /** 今天按 UTC 算 */
  Summary summary(LocalDateTime startOfToday);

  CustomerFunds customerFunds(long userId, int recent);

  long pendingReview();

  /** 仪表盘（S16c）：从 since 起入账的充值（按入账时间） */
  Bucket creditedSince(LocalDateTime since);
}
