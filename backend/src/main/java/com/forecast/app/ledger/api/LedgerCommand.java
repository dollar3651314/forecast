package com.forecast.app.ledger.api;

import java.math.BigDecimal;

/**
 * 一次账户变动。
 *
 * @param amount 增加为正、减少为负，最多 8 位小数
 * @param bizType 来源业务，和 bizId、type、bucket 一起组成幂等键：同一个键只记一次账
 */
public record LedgerCommand(long userId, Bucket bucket, BigDecimal amount, LedgerEntryType type, String bizType, String bizId, String remark) {}
