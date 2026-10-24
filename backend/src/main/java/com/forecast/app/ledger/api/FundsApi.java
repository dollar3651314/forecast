package com.forecast.app.ledger.api;

import java.math.BigDecimal;

/** 账户里可以自由动用的钱，给钱包用（充值冲正、提现）。 */
public interface FundsApi {

  /**
   * 可以拿走的金额。forecast S1：没有持仓和挂单，就是余额（{@link Bucket#BALANCE}）；S2 起是"可用余额"（下注锁定的钱不能提）。
   * 调用方要先在同一个事务里 {@link LedgerApi#lock} 锁住账户，结果到提交前都不会变。
   */
  BigDecimal withdrawable(long userId);
}
