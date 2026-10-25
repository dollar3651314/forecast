package com.forecast.app.ledger.api;

import java.math.BigDecimal;

/**
 * 客户账户当前的三项金额（S2）。
 *
 * @param available 可用余额（可以提现、下注）
 * @param betLocked 下注锁定（客户端"下注中"）
 * @param withdrawFrozen 提现冻结
 */
public record AccountSnapshot(long userId, BigDecimal available, BigDecimal betLocked, BigDecimal withdrawFrozen) {

  /** 客户看到的余额 = 可用 + 下注锁定（提现冻结的钱已经在出账的路上，不算） */
  public BigDecimal balance() {
    return available.add(betLocked);
  }

  public BigDecimal get(Bucket b) {
    return switch (b) {
      case AVAILABLE -> available;
      case BET_LOCKED -> betLocked;
      case WITHDRAW_FROZEN -> withdrawFrozen;
    };
  }
}
