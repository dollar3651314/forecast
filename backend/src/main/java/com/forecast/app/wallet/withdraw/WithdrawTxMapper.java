package com.forecast.app.wallet.withdraw;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** nonce 和广播记录的 SQL，见 resources/mapper/wallet/WithdrawTxMapper.xml。 */
@Mapper
interface WithdrawTxMapper {

  record TxRow(long id, long withdrawalId, String chain, int attempt, Long nonce, String txHash, String rawTx, long feePrice, LocalDateTime expiresAt,
      String status, LocalDateTime createdAt) {}

  Long lockNonce(@Param("chain") String chain);

  int saveNonce(@Param("chain") String chain, @Param("nonce") long nonce);

  int insert(@Param("withdrawalId") long withdrawalId, @Param("chain") String chain, @Param("attempt") int attempt, @Param("nonce") Long nonce,
      @Param("txHash") String txHash, @Param("rawTx") String rawTx, @Param("feePrice") long feePrice, @Param("expiresAt") LocalDateTime expiresAt,
      @Param("createdAt") LocalDateTime createdAt);

  int setStatus(@Param("id") long id, @Param("status") String status);

  /** 提现的对外编号（后台和客户端显示的编号，告警邮件用它；体验优化 1021 UX-11） */
  Long publicNo(@Param("withdrawalId") long withdrawalId);

  /** 这笔提现的广播记录，最新的在前 */
  List<TxRow> attempts(@Param("withdrawalId") long withdrawalId);
}
