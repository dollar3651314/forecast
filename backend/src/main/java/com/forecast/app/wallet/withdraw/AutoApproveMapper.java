package com.forecast.app.wallet.withdraw;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提现自动审批的 SQL（判定记录、触发的规则、全局锁、判定用的合计），见 resources/mapper/wallet/AutoApproveMapper.xml。只有 wallet 模块使用。 */
@Mapper
interface AutoApproveMapper {

  /** wallet_withdraw_rule_check 一行 */
  class CheckRow {
    Long id;
    long withdrawalId;
    String phase;
    String outcome;
    boolean masterEnabled;
    String failedRules;
    long configVersion;
    String configJson;
    String resultsJson;
    LocalDateTime decidedAt;
  }

  /** 锁住全局锁那一行（总开关打开时，所有复判在这里排队） */
  Integer lockGlobal();

  int insertCheck(CheckRow row);

  /** 这笔提现现有的"触发的规则"行（不加锁；提现单已经锁住了） */
  List<Long> hitIds(@Param("withdrawalId") long withdrawalId);

  /** 按主键删：不按 withdrawal_id 范围删，避免间隙锁和并发插入互相等待（死锁） */
  int deleteHits(@Param("ids") Collection<Long> ids);

  int insertHits(@Param("checkId") long checkId, @Param("withdrawalId") long withdrawalId, @Param("codes") Collection<String> codes);

  int setLastCheck(@Param("id") long id, @Param("checkId") long checkId);

  /** 自动通过：review_mode = AUTO、自动通过时间、审核时间（审核人为空）、大额的延迟到期时间 */
  int setAutoApproved(@Param("id") long id, @Param("at") LocalDateTime at, @Param("delayEndsAt") LocalDateTime delayEndsAt);

  CheckRow check(@Param("id") long id);

  /** 这些提现最近一次判定（wallet_withdrawal.last_check_id） */
  List<CheckRow> latest(@Param("ids") Collection<Long> withdrawalIds);

  /** 这笔提现最近一次预判 */
  CheckRow latestPrecheck(@Param("withdrawalId") long withdrawalId);

  // ---------- 判定用的合计（技术方案/v2-提现自动审批.md 第 4 节） ----------

  BigDecimal autoApprovedSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

  long completedCount(@Param("userId") long userId);

  long completedToAddress(@Param("userId") long userId, @Param("network") String network, @Param("address") String address);

  BigDecimal withdrawnTotal(@Param("userId") long userId);

  BigDecimal completedAmount(@Param("userId") long userId);

  long countOnDay(@Param("userId") long userId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("upToId") long upToId);

  BigDecimal queuedNet(@Param("network") String network);
}
