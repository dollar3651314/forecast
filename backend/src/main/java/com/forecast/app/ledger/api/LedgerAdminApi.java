package com.forecast.app.ledger.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 后台查看客户账户和流水（只读）。复制源里这部分在 {@code TradingAdminApi}，差价合约的持仓、订单、强平删除后剩下这几项（forecast S1）。
 */
public interface LedgerAdminApi {

  /** 可用余额的一条流水 */
  record LedgerLine(long id, LedgerEntryType type, BigDecimal amount, BigDecimal balanceAfter, String bizType, String bizId, LocalDateTime time) {}

  /** 可用余额的最近 limit 条流水，最新的在前 */
  List<LedgerLine> balanceLedger(long userId, int limit);

  /** 批量：用户 → 余额（可用 + 下注锁定，和客户端"余额"一致；没有账户的用户不在结果里） */
  Map<Long, BigDecimal> balances(Collection<Long> userIds);

  /**
   * 平台账户的一行（forecast S17A，对账页"平台账户"）：账户上的余额和账本合计，两者应该相等。
   * 平台收入 ≥ 0；补偿支出、测试入金（S17A）是平台付出去的钱，余额是负数。
   */
  record PlatformAccount(AccountKind kind, BigDecimal balance, BigDecimal ledgerTotal, long entries) {}

  /** 全部平台账户（按 AccountKind 的顺序） */
  List<PlatformAccount> platformAccounts();
}
