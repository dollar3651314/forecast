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

  /** 平台账户（kind 是 PLATFORM_*），加行锁 */
  AccountRow lockPlatformAccount(@Param("kind") String kind);

  AccountRow findPlatformAccount(@Param("kind") String kind);

  int updateBucket(@Param("accountId") long accountId, @Param("column") String column, @Param("value") BigDecimal value);

  LedgerRow findEntry(@Param("bizType") String bizType, @Param("bizId") String bizId, @Param("entryType") String entryType, @Param("bucket") String bucket);

  int insertEntry(LedgerEntry entry);

  /** 对账用的一行：键和金额 */
  record KeyAmount(String key, BigDecimal amount) {}

  List<KeyAmount> totals(@Param("bizType") String bizType, @Param("entryType") String entryType, @Param("bucket") String bucket);

  List<KeyAmount> withdrawFrozen();

  /** 账本合计和账户金额（不变量检查）：一个账户一行，三项的账本合计和账户上的值 */
  class BalanceCheckRow {
    long id;
    String kind;
    Long userId;
    BigDecimal available;
    BigDecimal betLocked;
    BigDecimal withdrawFrozen;
    BigDecimal sumAvailable;
    BigDecimal sumBetLocked;
    BigDecimal sumWithdrawFrozen;
    /** 账本里不认识的项（不是三项之一）的记录条数，正常为 0 */
    int unknownBuckets;
  }

  /** 只返回至少有一项对不上的账户 */
  List<BalanceCheckRow> balanceMismatches();

  /** 某个市场的账本合计，按类型和项分组 */
  class MarketTotalRow {
    String entryType;
    String bucket;
    BigDecimal amount;
  }

  List<MarketTotalRow> marketTotals(@Param("marketId") long marketId);

  // ---------- 客户流水合计（提现自动审批） ----------

  class DepositRow {
    BigDecimal net;
    LocalDateTime firstAt;
  }

  /** since 为空表示全部时间 */
  DepositRow depositsSince(@Param("userId") long userId, @Param("since") LocalDateTime since);

  // ---------- 可用余额的流水（客户端、后台） ----------

  class EntryRow {
    long id;
    String entryType;
    BigDecimal amount;
    BigDecimal valueAfter;
    String bizType;
    String bizId;
    String remark;
    /** 和哪个市场有关（下注、派彩、退款、补偿）；充值、提现为空 */
    Long refMarketId;
    LocalDateTime createdAt;
  }

  /** 后台：可用余额的最近 limit 条流水 */
  List<EntryRow> balanceLedger(@Param("userId") long userId, @Param("limit") int limit);

  /** 客户端：可用余额的流水，按类型和时间筛选，最新的在前 */
  List<EntryRow> userLedger(@Param("userId") long userId, @Param("types") Collection<String> types, @Param("since") LocalDateTime since,
      @Param("offset") int offset, @Param("limit") int limit);

  long countUserLedger(@Param("userId") long userId, @Param("types") Collection<String> types, @Param("since") LocalDateTime since);

  class UserAmount {
    long userId;
    BigDecimal amount;
  }

  /** 用户 → 余额（可用 + 下注锁定） */
  List<UserAmount> balances(@Param("ids") Collection<Long> ids);
}
