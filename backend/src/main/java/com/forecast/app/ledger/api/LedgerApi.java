package com.forecast.app.ledger.api;

/**
 * 账户和账本：所有模块改余额都只能走这里（沿用 复制源的约定）。
 * <ul>
 *   <li>余额变动和账本记录在同一个数据库事务里；调用方已有事务时加入调用方的事务，一起提交或一起回滚。</li>
 *   <li>同一个幂等键（bizType、bizId、type、bucket）只记一次账，重复调用返回第一次的结果。</li>
 *   <li>任何一项不能变成负数，余额不足抛 {@code BizException("INSUFFICIENT_FUNDS")}。</li>
 *   <li>同一账户的变动按行锁排队，并发安全。</li>
 * </ul>
 */
public interface LedgerApi {

  /** 开户：已有账户时什么也不做。 */
  void openAccount(long userId);

  LedgerResult apply(LedgerCommand command);

  AccountSnapshot snapshot(long userId);

  /**
   * 在调用方的事务里锁住账户行并返回当前金额（必须已有事务）。同一账户的提现、记账（S4 起还有下注）从这里开始排队，
   * "检查可用余额 → 写业务记录 → 记账"之间不会插进别的请求。
   */
  AccountSnapshot lock(long userId);
}
