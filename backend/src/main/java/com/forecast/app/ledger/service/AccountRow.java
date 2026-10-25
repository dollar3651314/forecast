package com.forecast.app.ledger.service;

import com.forecast.app.ledger.api.AccountKind;
import com.forecast.app.ledger.api.Bucket;
import java.math.BigDecimal;

/** ledger_account 的一行；平台账户的 userId 为空。 */
record AccountRow(long id, String kind, Long userId, BigDecimal available, BigDecimal betLocked, BigDecimal withdrawFrozen) {

  AccountKind accountKind() {
    return AccountKind.valueOf(kind);
  }

  BigDecimal get(Bucket b) {
    return switch (b) {
      case AVAILABLE -> available;
      case BET_LOCKED -> betLocked;
      case WITHDRAW_FROZEN -> withdrawFrozen;
    };
  }
}
