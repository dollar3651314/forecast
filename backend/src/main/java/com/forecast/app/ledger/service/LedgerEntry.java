package com.forecast.app.ledger.service;

import java.math.BigDecimal;

/** 写入 ledger_entry 的一条记录；插入后 id 由数据库回填。 */
final class LedgerEntry {
  Long id;
  long accountId;
  String bucket;
  String entryType;
  BigDecimal amount;
  BigDecimal valueAfter;
  String bizType;
  String bizId;
  Long refMarketId;
  String remark;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public long getAccountId() { return accountId; }
  public String getBucket() { return bucket; }
  public String getEntryType() { return entryType; }
  public BigDecimal getAmount() { return amount; }
  public BigDecimal getValueAfter() { return valueAfter; }
  public String getBizType() { return bizType; }
  public String getBizId() { return bizId; }
  public Long getRefMarketId() { return refMarketId; }
  public String getRemark() { return remark; }
}
