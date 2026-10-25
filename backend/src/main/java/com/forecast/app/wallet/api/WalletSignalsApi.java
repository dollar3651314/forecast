package com.forecast.app.wallet.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 风控要用的钱包数据（forecast S10，需求说明书第 8.1 节）：充值来源地址、提现白名单地址（关联账户的线索）、提现申请（"充了不玩就提"）。
 * 只读，给 riskalert 模块按 id 往后扫描（id 升序，最多 limit 行）。写入了多久才处理（等进行中的事务提交）由 riskalert 按写入时间判断；
 * 充值表的写入时间是数据库时间（{@code wallet_deposit.created_at} 的默认值），所以在这里按数据库的当前时间算好 {@code ripe}。
 */
public interface WalletSignalsApi {

  /**
   * 一笔充值的来源地址（不论状态：地址出现过就算线索）。
   *
   * @param fromAddress 以太坊体系小写
   * @param ripe 写入时间早于"数据库现在 − lag"
   */
  record DepositSource(long id, long userId, String chain, String fromAddress, LocalDateTime at, boolean ripe) {}

  List<DepositSource> depositSourcesAfter(long afterId, java.time.Duration lag, int limit);

  /** 提现白名单里加的一个地址（addressKey：以太坊体系小写） */
  record WhitelistAddress(long id, long userId, String network, String addressKey, LocalDateTime at) {}

  List<WhitelistAddress> whitelistAfter(long afterId, int limit);

  /** 一笔提现申请（金额含手续费） */
  record WithdrawalRequest(long id, long publicNo, long userId, BigDecimal amount, LocalDateTime at) {}

  List<WithdrawalRequest> withdrawalsAfter(long afterId, int limit);
}
