package com.forecast.app.ledger.api;

import java.math.BigDecimal;
import java.util.Map;

/** 账本的只读查询，给对账用（S14）。不改任何金额。 */
public interface LedgerQueryApi {

  /** 某个来源业务、某种业务类型、某一项的金额合计，按来源单号分组（例如 DEPOSIT / DEPOSIT / BALANCE → 每笔充值入账了多少） */
  Map<String, BigDecimal> totals(String bizType, LedgerEntryType type, Bucket bucket);

  /** 提现冻结不为 0 的账户：用户 → 提现冻结金额 */
  Map<Long, BigDecimal> withdrawFrozenByUser();
}
