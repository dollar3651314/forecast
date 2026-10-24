package com.forecast.app.ledger.api;

/**
 * 账本记录的业务类型。新增业务时在这里加，不复用已有含义。
 * <p>forecast S1：删掉了差价合约的挂单冻结、手续费、平仓盈亏、隔夜费和后台调余额（调余额改为补偿流程，S7）。下注、派彩、抽成、退款、补偿在 S2 加。
 */
public enum LedgerEntryType {
  DEPOSIT,
  /** 充值入账后链上回滚的冲正 */
  DEPOSIT_REVERSAL,
  WITHDRAW_FREEZE,
  WITHDRAW_UNFREEZE,
  WITHDRAW_PAYOUT
}
