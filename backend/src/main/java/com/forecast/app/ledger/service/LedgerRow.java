package com.forecast.app.ledger.service;

import java.math.BigDecimal;

/** ledger_entry 的一行（只取幂等判断需要的字段）。 */
record LedgerRow(long id, BigDecimal valueAfter) {}
