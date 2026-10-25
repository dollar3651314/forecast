package com.forecast.app.ledger.api;

import java.math.BigDecimal;

/**
 * 客户账户的一次变动。
 *
 * @param amount 增加为正、减少为负，不能为 0，最多 8 位小数；方向要符合 {@link LedgerEntryType} 的规则
 * @param bizType 来源业务，和 bizId、type、bucket 一起组成幂等键：同一个键只记一次账
 * @param refMarketId 和哪个市场有关（下注、派彩、退款、补偿）；充值、提现为空
 */
public record LedgerCommand(long userId, Bucket bucket, BigDecimal amount, LedgerEntryType type, String bizType, String bizId, String remark,
    Long refMarketId) {

  /** 和市场无关的变动（充值、提现） */
  public LedgerCommand(long userId, Bucket bucket, BigDecimal amount, LedgerEntryType type, String bizType, String bizId, String remark) {
    this(userId, bucket, amount, type, bizType, bizId, remark, null);
  }
}
