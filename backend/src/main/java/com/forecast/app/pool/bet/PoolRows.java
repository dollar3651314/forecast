package com.forecast.app.pool.bet;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** pool 模块的表行（MyBatis 按列名映射，下划线转驼峰）。只在 pool 模块里用。 */
final class PoolRows {

  private PoolRows() {}

  /** pool_bet */
  static class Bet {
    long id;
    long publicNo;
    long userId;
    long marketId;
    long optionId;
    BigDecimal amount;
    String clientReqId;
    LocalDateTime receivedAt;
    String status;
    String refundReason;
    LocalDateTime refundedAt;
    String snapshot;
    LocalDateTime createdAt;
  }

  /** 我的下注、后台下注明细：下注 + 选项编号 + 市场状态副本 + 分组 */
  static class BetLine extends Bet {
    long optionNo;
    int seq;
    String marketStatus;
    /** active / pending / settled / refunded（我的下注的分组） */
    String tab;
  }

  /** pool_position */
  static class Position {
    long id;
    long userId;
    long marketId;
    long optionId;
    BigDecimal amount;
    int betCount;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
  }

  /** pool_option_total */
  static class OptionTotal {
    long optionId;
    long marketId;
    long optionNo;
    int seq;
    BigDecimal amount;
    int bettors;
    BigDecimal finalAmount;
    Integer finalBettors;
    LocalDateTime frozenAt;
  }

  /** 市场 → 有持仓的客户数 */
  static class MarketCount {
    long marketId;
    int n;
  }

  /** 我的下注每个分组的笔数和金额 */
  static class TabSum {
    String tab;
    long n;
    BigDecimal amount;
  }
}
