package com.forecast.app.ledger.service;

import java.math.BigDecimal;

/** 批量记账时一个账户三项的新值（{@code LedgerMapper.updateAccounts}；MyBatis 按 getter 取值）。 */
final class AccountUpdate {
  private final long id;
  private final BigDecimal available;
  private final BigDecimal betLocked;
  private final BigDecimal withdrawFrozen;

  AccountUpdate(long id, BigDecimal available, BigDecimal betLocked, BigDecimal withdrawFrozen) {
    this.id = id;
    this.available = available;
    this.betLocked = betLocked;
    this.withdrawFrozen = withdrawFrozen;
  }

  public long getId() {
    return id;
  }

  public BigDecimal getAvailable() {
    return available;
  }

  public BigDecimal getBetLocked() {
    return betLocked;
  }

  public BigDecimal getWithdrawFrozen() {
    return withdrawFrozen;
  }
}
