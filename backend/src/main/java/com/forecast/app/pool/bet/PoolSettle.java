package com.forecast.app.pool.bet;

import com.forecast.app.pool.api.PoolApi;
import com.forecast.app.pool.api.PoolSettleApi;
import com.forecast.app.pool.push.PoolChanges;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PoolSettleApi} 的实现（forecast S5）。退款只改 {@code pool_bet} 的状态、原因和时间（金额、收到时间、请求号写入后不改，S4 约定），
 * 同时按退回的下注扣持仓和选项奖池，保持"Σ有效下注 = Σ奖池 = Σ持仓"。
 */
@Component
class PoolSettle implements PoolSettleApi {

  /** 一条 IN (…) 最多带多少个 id */
  static final int CHUNK = 500;

  private final PoolMapper mapper;
  private final PoolChanges changes;

  PoolSettle(PoolMapper mapper, PoolChanges changes) {
    this.mapper = mapper;
    this.changes = changes;
  }

  @Override
  public List<OptionPool> pools(long marketId) {
    return mapper.totals(marketId).stream().map(t -> new OptionPool(t.optionId, t.amount, t.finalAmount, t.bettors)).toList();
  }

  @Override
  public List<Position> positions(long marketId) {
    return mapper.settlePositions(marketId).stream().map(p -> new Position(p.id, p.userId, p.optionId, p.amount)).toList();
  }

  @Override
  public List<Bet> activeBets(long marketId) {
    return bets(mapper.activeBets(marketId, null, null));
  }

  @Override
  public List<Bet> activeBetsReceivedFrom(long marketId, LocalDateTime from) {
    return bets(mapper.activeBets(marketId, from, null));
  }

  @Override
  public List<Bet> activeBetsOfUsers(long marketId, Collection<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) return List.of();
    List<Long> all = List.copyOf(new TreeSet<>(userIds));
    List<Bet> out = new ArrayList<>();
    for (int i = 0; i < all.size(); i += CHUNK) out.addAll(bets(mapper.activeBets(marketId, null, all.subList(i, Math.min(all.size(), i + CHUNK)))));
    return out;
  }

  @Override
  public List<Long> openMarkets() {
    return mapper.openMarkets();
  }

  @Override
  public BigDecimal sumActiveBets(long marketId) {
    return mapper.sumActiveBets(marketId);
  }

  @Override
  public BigDecimal sumPositions(long marketId) {
    return mapper.sumPositions(marketId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<Long> markRefunded(long marketId, Collection<Long> betIds, String reason, LocalDateTime at) {
    if (!PoolApi.REFUND_REASONS.contains(reason)) throw new IllegalArgumentException("不认识的退回原因：" + reason);
    List<Long> ids = List.copyOf(new TreeSet<>(betIds));
    List<Long> done = new ArrayList<>();
    for (int i = 0; i < ids.size(); i += CHUNK) {
      List<Long> chunk = ids.subList(i, Math.min(ids.size(), i + CHUNK));
      List<PoolRows.Bet> locked = mapper.lockActiveBets(marketId, chunk);
      if (locked.isEmpty()) continue;
      List<Long> lockedIds = locked.stream().map(b -> b.id).toList();
      int n = mapper.markRefunded(lockedIds, reason, at);
      if (n != lockedIds.size()) throw new IllegalStateException("退回下注时状态变了：锁到 " + lockedIds.size() + " 笔，改了 " + n + " 笔");
      mapper.subtractFromPositions(marketId, lockedIds, at);
      mapper.subtractFromTotals(lockedIds, at);
      mapper.recountBettors(marketId, locked.stream().map(b -> b.optionId).distinct().toList());
      done.addAll(lockedIds);
    }
    if (!done.isEmpty()) changes.afterCommit(marketId);
    return done;
  }

  private static List<Bet> bets(List<PoolRows.Bet> rows) {
    return rows.stream().map(b -> new Bet(b.id, b.publicNo, b.userId, b.optionId, b.amount, b.receivedAt)).toList();
  }
}
