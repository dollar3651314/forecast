package com.forecast.app.ledger.api;

import java.math.BigDecimal;

/**
 * 记账结果。
 *
 * @param valueAfter 记账后这一项的值；duplicate 时是第一次记账后的值
 * @param duplicate 这个幂等键之前已经记过账（账户、金额都相同），本次没有再变动金额
 */
public record LedgerResult(long entryId, BigDecimal valueAfter, boolean duplicate) {}
