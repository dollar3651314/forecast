package com.forecast.app.wallet.withdraw;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 白名单、提现单、提现事件的 SQL，见 resources/mapper/wallet/WithdrawMapper.xml。 */
@Mapper
interface WithdrawMapper {

  // ---------- 白名单 ----------
  List<WhitelistRow> whitelist(@Param("userId") long userId);

  WhitelistRow whitelistById(@Param("userId") long userId, @Param("id") long id);

  int countWhitelist(@Param("userId") long userId);

  int insertWhitelist(WhitelistRow row);

  int deleteWhitelist(@Param("userId") long userId, @Param("id") long id);

  // ---------- 提现单 ----------
  int insertWithdrawal(WithdrawalRow row);

  WithdrawalRow lock(@Param("id") long id);

  WithdrawalRow byId(@Param("id") long id);

  /** 客户自己的提现：对外编号 → 内部 id；没有时为 null */
  Long idByNo(@Param("userId") long userId, @Param("no") long no);

  /** 对外编号 → 内部 id（不限客户：后台、端到端测试替身用）；没有时为 null */
  Long idByPublicNo(@Param("no") long no);

  List<WithdrawalRow> byUser(@Param("userId") long userId, @Param("offset") int offset, @Param("limit") int limit);

  long countByUser(@Param("userId") long userId);

  /** 当天（from 起）还算数的提现合计：撤销、驳回、失败退回的不算 */
  BigDecimal usedSince(@Param("userId") long userId, @Param("from") LocalDateTime from);

  /** 状态从 from 改到 to；返回 1 表示改成功（状态在条件里，防止并发重复处理） */
  int transit(@Param("id") long id, @Param("from") String from, @Param("to") String to);

  /** 人工审核（通过或驳回）：同时记 review_mode = MANUAL（v2 自动审批，驳回也记，方便统计） */
  int setReview(@Param("id") long id, @Param("reviewerId") Long reviewerId, @Param("at") LocalDateTime at, @Param("reason") String reason,
      @Param("delayEndsAt") LocalDateTime delayEndsAt);

  int setTx(@Param("id") long id, @Param("txHash") String txHash);

  int setCompleted(@Param("id") long id, @Param("at") LocalDateTime at);

  List<Long> due(@Param("status") String status, @Param("timeColumn") String timeColumn, @Param("now") LocalDateTime now);

  List<Long> withStatus(@Param("status") String status);

  int insertEvent(@Param("withdrawalId") long withdrawalId, @Param("from") String from, @Param("to") String to, @Param("actor") String actor,
      @Param("note") String note);
}
