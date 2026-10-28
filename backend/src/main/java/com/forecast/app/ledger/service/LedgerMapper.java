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

  // ---------- 批量记账（LedgerApi.applyBatch，forecast S5） ----------

  /** 锁住一批客户账户（按 user_id 升序扫唯一索引，加锁顺序和逐个锁一样是升序） */
  List<AccountRow> lockAccounts(@Param("userIds") Collection<Long> userIds);

  /** 一批幂等键已有的记录（同一个来源业务、一批单号） */
  List<EntryKeyRow> findEntries(@Param("bizType") String bizType, @Param("bizIds") Collection<String> bizIds);

  /** 已有记录的幂等键和金额（批量记账查重用） */
  record EntryKeyRow(long id, long accountId, String bizId, String entryType, String bucket, BigDecimal amount) {}

  /** 一批账户的三项改成新值（每个账户一行，只改给出的） */
  int updateAccounts(@Param("rows") List<AccountUpdate> rows);

  /** 一批账本记录（多行 INSERT） */
  int insertEntries(@Param("entries") List<LedgerEntry> entries);

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

  /** 已结算的盈亏的组成（forecast S10）；marketId 不为空时只算这个市场 */
  class RealizedRow {
    Long marketId;
    BigDecimal payout;
    BigDecimal compensation;
    BigDecimal stake;
    BigDecimal fee;
    LocalDateTime firstPayoutAt;
  }

  /** 一个客户的派彩、补偿、已结算的本金、抽成合计；since 为空表示全部时间 */
  RealizedRow realized(@Param("userId") long userId, @Param("since") LocalDateTime since);

  /** 从 since 起有派彩的市场 */
  List<Long> paidMarketsSince(@Param("userId") long userId, @Param("since") LocalDateTime since);

  /** 这些市场里，这个客户的派彩、补偿、已结算的本金、抽成合计（全部时间），每个市场一行 */
  List<RealizedRow> realizedByMarket(@Param("userId") long userId, @Param("marketIds") java.util.Collection<Long> marketIds);

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

  /** 平台账户：余额、账本合计、记录条数（forecast S17A，对账页） */
  class PlatformRow {
    String kind;
    BigDecimal available;
    BigDecimal ledgerTotal;
    long entries;
  }

  List<PlatformRow> platformAccounts();

  /** 人工调账单的类别（forecast S17B，客户端流水显示）：调账单 id → 类别 */
  class CategoryRow {
    String id;
    String category;
  }

  List<CategoryRow> adjustCategories(@Param("ids") Collection<Long> ids);
}
