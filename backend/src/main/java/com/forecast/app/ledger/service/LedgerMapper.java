package com.forecast.app.ledger.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 账户和账本的 SQL，见 resources/mapper/ledger/LedgerMapper.xml。只有 ledger 模块能用。 */
@Mapper
interface LedgerMapper {

  int insertAccountIfAbsent(@Param("userId") long userId);

  AccountRow lockAccount(@Param("userId") long userId);

  AccountRow findAccount(@Param("userId") long userId);

  int updateBucket(@Param("accountId") long accountId, @Param("column") String column, @Param("value") BigDecimal value);

  LedgerRow findEntry(@Param("bizType") String bizType, @Param("bizId") String bizId, @Param("entryType") String entryType, @Param("bucket") String bucket);

  int insertEntry(LedgerEntry entry);

  /** 对账用的一行：键和金额 */
  record KeyAmount(String key, BigDecimal amount) {}

  List<KeyAmount> totals(@Param("bizType") String bizType, @Param("entryType") String entryType, @Param("bucket") String bucket);

  List<KeyAmount> withdrawFrozen();

  // ---------- 客户流水合计（提现自动审批） ----------

  class DepositRow {
    BigDecimal net;
    LocalDateTime firstAt;
  }

  /** since 为空表示全部时间 */
  DepositRow depositsSince(@Param("userId") long userId, @Param("since") LocalDateTime since);

  // ---------- 余额流水（客户端、后台） ----------

  class EntryRow {
    long id;
    String entryType;
    BigDecimal amount;
    BigDecimal valueAfter;
    String bizType;
    String bizId;
    String remark;
    LocalDateTime createdAt;
  }

  /** 后台：余额的最近 limit 条流水 */
  List<EntryRow> balanceLedger(@Param("userId") long userId, @Param("limit") int limit);

  /** 客户端：余额流水，按类型和时间筛选，最新的在前 */
  List<EntryRow> userLedger(@Param("userId") long userId, @Param("types") Collection<String> types, @Param("since") LocalDateTime since,
      @Param("offset") int offset, @Param("limit") int limit);

  long countUserLedger(@Param("userId") long userId, @Param("types") Collection<String> types, @Param("since") LocalDateTime since);

  class UserAmount {
    long userId;
    BigDecimal amount;
  }

  List<UserAmount> balances(@Param("ids") Collection<Long> ids);
}
