package com.forecast.app.ledger.api;

import java.math.BigDecimal;

/** 账户当前的各项金额。 */
public record AccountSnapshot(long userId, BigDecimal balance, BigDecimal withdrawFrozen) {}
