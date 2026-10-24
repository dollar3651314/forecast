package com.forecast.app.ledger.service;

import com.forecast.app.common.error.BizException;
import com.forecast.app.ledger.api.CustomerFlowApi;
import com.forecast.app.ledger.api.FundsApi;
import com.forecast.app.ledger.api.LedgerAdminApi;
import com.forecast.app.ledger.api.LedgerEntryType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账本的只读查询：可提金额（{@link FundsApi}）、客户流水合计（{@link CustomerFlowApi}）、后台的余额和流水（{@link LedgerAdminApi}）。
 * 复制源里这几个实现在 trading/order（要算持仓保证金、浮动盈亏和交易量），forecast S1 搬到账本模块并去掉了交易部分。
 */
@Service
class LedgerQueries implements FundsApi, CustomerFlowApi, LedgerAdminApi {

  private final LedgerMapper mapper;

  LedgerQueries(LedgerMapper mapper) {
    this.mapper = mapper;
  }

  private static BigDecimal zero(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  // ---------- FundsApi ----------

  @Override
  public BigDecimal withdrawable(long userId) {
    AccountRow a = mapper.findAccount(userId);
    if (a == null) throw new BizException("ACCOUNT_NOT_FOUND", "账户不存在：" + userId);
    return a.balance();
  }

  // ---------- CustomerFlowApi ----------

  @Override
  @Transactional(readOnly = true)
  public Deposits depositsSince(long userId, LocalDateTime since) {
    LedgerMapper.DepositRow r = mapper.depositsSince(userId, since);
    return r == null ? new Deposits(BigDecimal.ZERO, null) : new Deposits(zero(r.net), r.firstAt);
  }

  @Override
  @Transactional(readOnly = true)
  public BigDecimal netDeposits(long userId) {
    return depositsSince(userId, null).net();
  }

  /** S1 过渡：恒为 0（见 {@link CustomerFlowApi} 的说明，S10 改成下注口径） */
  @Override
  public BigDecimal realizedPnl(long userId, LocalDateTime since) {
    return BigDecimal.ZERO;
  }

  /** S1 过渡：恒为 0（同上） */
  @Override
  public NetProfit netProfit(long userId) {
    return new NetProfit(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  /** S1 过渡：恒为 0（同上） */
  @Override
  public BigDecimal openedNotionalSince(long userId, LocalDateTime since) {
    return BigDecimal.ZERO;
  }

  // ---------- LedgerAdminApi ----------

  @Override
  @Transactional(readOnly = true)
  public List<LedgerLine> balanceLedger(long userId, int limit) {
    return mapper.balanceLedger(userId, limit).stream()
        .map(r -> new LedgerLine(r.id, LedgerEntryType.valueOf(r.entryType), r.amount, r.valueAfter, r.bizType, r.bizId, r.createdAt)).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, BigDecimal> balances(Collection<Long> userIds) {
    Map<Long, BigDecimal> m = new HashMap<>();
    if (userIds == null || userIds.isEmpty()) return m;
    for (LedgerMapper.UserAmount u : mapper.balances(userIds)) m.put(u.userId, u.amount);
    return m;
  }
}
