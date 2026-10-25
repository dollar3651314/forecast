package com.forecast.app.ledger.api;

import java.util.List;

/**
 * 客户账户和账本：所有模块改客户的钱都只能走这里（沿用复制源的约定，项目地图约定 4）。平台账户走 {@link PlatformLedgerApi}。
 * <ul>
 *   <li>金额变动和账本记录在同一个数据库事务里；调用方已有事务时加入调用方的事务，一起提交或一起回滚。
 *       所以任何时刻（已提交的数据）每个账户每一项的账本合计都等于这一项的金额（{@link LedgerQueryApi#mismatches()} 检查）。</li>
 *   <li>同一个幂等键（bizType、bizId、type、bucket）只记一次账，重复调用返回第一次的结果；同一个键但账户或金额不同是程序错误，抛 {@link IllegalStateException}。</li>
 *   <li>流水类型要符合 {@link LedgerEntryType} 的规则（哪种账户、哪一项、加还是减），否则抛 {@link IllegalArgumentException}。</li>
 *   <li>任何一项不能变成负数，不够时抛 {@code BizException("INSUFFICIENT_FUNDS")}（数据库 CHECK 兜底）。</li>
 *   <li>同一账户的变动按行锁排队，并发安全。</li>
 * </ul>
 */
public interface LedgerApi {

  /** 开户：已有账户时什么也不做。 */
  void openAccount(long userId);

  LedgerResult apply(LedgerCommand command);

  /**
   * 一组变动在同一个事务里全部记账或全部不记（例如下注的"可用 −b、下注锁定 +b"）。涉及多个客户时先按用户 id 升序锁住全部账户（第 4.2 节的锁顺序），
   * 再按给出的顺序逐条记账。
   */
  List<LedgerResult> applyAll(List<LedgerCommand> commands);

  AccountSnapshot snapshot(long userId);

  /**
   * 在调用方的事务里锁住账户行并返回当前金额（必须已有事务）。同一账户的提现、记账（S4 起还有下注）从这里开始排队，
   * "检查可用余额 → 写业务记录 → 记账"之间不会插进别的请求。
   */
  AccountSnapshot lock(long userId);
}
