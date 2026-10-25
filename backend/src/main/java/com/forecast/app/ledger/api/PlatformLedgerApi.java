package com.forecast.app.ledger.api;

import java.math.BigDecimal;

/**
 * 平台账户的记账（S2，技术方案/v1-架构方案.md 第 3.2 节）：平台收入（抽成、结算尾差）和平台补偿支出。
 * 和 {@link LedgerApi} 同样的保证：金额变动和账本记录同一个事务、幂等键只记一次、行锁排队。
 * 锁顺序：平台账户行最后锁（先锁市场、客户账户，第 4.2 节），所以调用方应在客户账户记完账之后再调这里。
 */
public interface PlatformLedgerApi {

  /**
   * 平台账户的一次变动（只改可用余额一项）。
   *
   * @param account 只能是 {@link AccountKind#PLATFORM_REVENUE} 或 {@link AccountKind#PLATFORM_COMPENSATION}
   * @param amount 方向要符合 {@link LedgerEntryType} 的规则（收入只加、补偿支出只减），不能为 0
   */
  record Command(AccountKind account, BigDecimal amount, LedgerEntryType type, String bizType, String bizId, String remark, Long refMarketId) {}

  LedgerResult apply(Command command);

  /** 平台账户的可用余额（补偿支出账户为负数 = 累计补偿） */
  BigDecimal balance(AccountKind account);
}
