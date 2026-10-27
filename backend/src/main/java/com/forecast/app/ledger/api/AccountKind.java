package com.forecast.app.ledger.api;

/** 账户的种类（表 ledger_account 的 kind 列，S2）。平台账户每种只有一行，没有用户 id。 */
public enum AccountKind {
  /** 客户：三项都不能为负 */
  USER,
  /** 平台收入：抽成和结算尾差，不能为负 */
  PLATFORM_REVENUE,
  /** 平台补偿支出（需求 E13）：平台自己掏钱补偿客户，可用余额允许为负（= −累计补偿） */
  PLATFORM_COMPENSATION,
  /**
   * 测试入金（forecast S17A，只在测试环境用）：测试入金的对手方，可用余额允许为负（= −累计测试入金）。
   * 生产上这一行也存在（迁移 V29 建），但测试入金的接口不存在，余额永远是 0。
   */
  PLATFORM_TEST_CREDIT,
  /**
   * 人工调账（forecast S17B，需求说明书第 21 节）：人工加款、扣款的对手方。加款时 −x、扣款时 +x，余额可正可负（= 累计扣款 − 累计加款）。
   */
  PLATFORM_MANUAL_ADJUST;

  /** 可用余额能不能为负（补偿支出、测试入金的余额 = −平台付出的累计金额；人工调账可正可负） */
  public boolean mayGoNegative() {
    return this == PLATFORM_COMPENSATION || this == PLATFORM_TEST_CREDIT || this == PLATFORM_MANUAL_ADJUST;
  }
}
