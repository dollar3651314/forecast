package com.forecast.app.ledger.service;

import com.forecast.app.ledger.api.Bucket;
import java.math.BigDecimal;

/** ledger_account 的一行。 */
record AccountRow(long id, long userId, BigDecimal balance, BigDecimal withdrawFrozen) {

  BigDecimal get(Bucket b) {
    return switch (b) {
      case BALANCE -> balance;
      case WITHDRAW_FROZEN -> withdrawFrozen;
    };
  }
}
