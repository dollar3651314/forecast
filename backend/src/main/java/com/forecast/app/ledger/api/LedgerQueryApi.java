package com.forecast.app.ledger.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 账本的只读查询，给对账用（入金对账、提现对账；S5 起彩池对账）。不改任何金额。 */
public interface LedgerQueryApi {

  /** 某个来源业务、某种业务类型、某一项的金额合计，按来源单号分组（例如 DEPOSIT / DEPOSIT / AVAILABLE → 每笔充值入账了多少） */
  Map<String, BigDecimal> totals(String bizType, LedgerEntryType type, Bucket bucket);

  /** 提现冻结不为 0 的账户：用户 → 提现冻结金额 */
  Map<Long, BigDecimal> withdrawFrozenByUser();

  /**
   * 账本合计和账户金额对不上的账户（不变量"任何时刻账本合计 = 余额"）：每个账户（含平台账户）每一项，账本里这一项的变动加起来必须等于账户上这一项的金额。
   * 正常时为空。全表扫描，给每日对账和测试用，不要放在请求路径上。
   */
  List<Mismatch> mismatches();

  /**
   * 一个账户的一项对不上。
   *
   * @param userId 客户的用户 id；平台账户为空
   * @param bucket 对不上的项；为空表示账本里有不认识的项（不是三项之一）
   * @param ledgerSum 账本里这一项的变动合计
   * @param accountValue 账户上这一项的金额
   */
  record Mismatch(long accountId, AccountKind kind, Long userId, Bucket bucket, BigDecimal ledgerSum, BigDecimal accountValue) {}

  /** 某个市场（ref_market_id）在账本里的合计，按类型和项分组（彩池对账、结算收尾复核，第 4.4 节）；没有记录时为空 */
  Map<TypeBucket, BigDecimal> marketTotals(long marketId);

  record TypeBucket(LedgerEntryType type, Bucket bucket) {}
}
