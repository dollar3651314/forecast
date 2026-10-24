package com.forecast.app.ledger.api;

/**
 * 账户里会变动的项。
 * <p>forecast S1：删掉了差价合约的"挂单冻结"（ORDER_FROZEN）。S2 改成可用余额 / 下注锁定 / 提现冻结（技术方案/v1-架构方案.md 第 3.2 节）。
 */
public enum Bucket {
  /** 余额（S1 时就是可用余额：没有持仓保证金和挂单冻结） */
  BALANCE,
  /** 提现冻结 */
  WITHDRAW_FROZEN
}
