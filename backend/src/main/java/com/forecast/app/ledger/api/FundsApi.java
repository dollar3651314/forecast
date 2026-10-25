package com.forecast.app.ledger.api;

import java.math.BigDecimal;

/** 账户里可以自由动用的钱，给钱包用（充值冲正、提现）。 */
public interface FundsApi {

  /**
   * 可以拿走的金额 = 可用余额（{@link Bucket#AVAILABLE}，S2）：下注锁定、提现冻结的钱都不能提，冲正也不扣它们。
   * 调用方要先在同一个事务里 {@link LedgerApi#lock} 锁住账户，结果到提交前都不会变。
   */
  BigDecimal withdrawable(long userId);
}
