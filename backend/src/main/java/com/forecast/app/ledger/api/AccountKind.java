package com.forecast.app.ledger.api;

/** 账户的种类（表 ledger_account 的 kind 列，S2）。平台账户每种只有一行，没有用户 id。 */
public enum AccountKind {
  /** 客户：三项都不能为负 */
  USER,
  /** 平台收入：抽成和结算尾差，不能为负 */
  PLATFORM_REVENUE,
  /** 平台补偿支出（需求 E13）：平台自己掏钱补偿客户，可用余额允许为负（= −累计补偿） */
  PLATFORM_COMPENSATION;

  /** 可用余额能不能为负 */
  public boolean mayGoNegative() {
    return this == PLATFORM_COMPENSATION;
  }
}
