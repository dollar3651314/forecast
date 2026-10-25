package com.forecast.app.ledger.api;

/**
 * 账户里会变动的三项（技术方案/v1-架构方案.md 第 3.2 节，S2）。账本的每条记录只改其中一项，记录里写明是哪一项。
 * <p>客户看到的"余额" = 可用 + 下注锁定（提现冻结的钱已经在出账的路上，不算）。平台账户只用 {@link #AVAILABLE}。
 */
public enum Bucket {
  /** 可用余额：可以提现、下注 */
  AVAILABLE,
  /** 下注锁定（客户端"下注中"）：已下注、还没结算或退回的本金 */
  BET_LOCKED,
  /** 提现冻结：已提交、还没出账或退回的提现 */
  WITHDRAW_FROZEN
}
