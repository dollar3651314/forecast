package com.forecast.app.ledger.api;

import java.math.BigDecimal;

/**
 * 记账结果。
 *
 * @param duplicate 这个幂等键之前已经记过账，本次没有再变动余额
 */
public record LedgerResult(long entryId, BigDecimal valueAfter, boolean duplicate) {}
