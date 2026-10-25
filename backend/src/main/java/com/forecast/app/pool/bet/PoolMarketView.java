package com.forecast.app.pool.bet;

import com.forecast.app.market.api.MarketPoolView;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.pool.push.PoolChanges;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * pool 对 market 的 {@link MarketPoolView} 的实现（forecast S4；S3 留下的接口，开发记录/S3-市场.md 第 2.4 节）。
 * 除 {@link #clientPools} 外都在 market 的事务里调用，市场行已经是排他锁：此时没有进行中的下注（下注拿的是共享锁）。
 */
@Component
class PoolMarketView implements MarketPoolView {

  private final PoolMapper mapper;
  private final PoolQueries views;
  private final PoolChanges changes;
  private final Clock clock;

  PoolMarketView(PoolMapper mapper, PoolQueries views, PoolChanges changes, Clock clock) {
    this.mapper = mapper;
    this.views = views;
    this.changes = changes;
    this.clock = clock;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
  }

  /** 上架：登记市场、建好各选项的奖池行（金额 0）；重复调用没有效果 */
  @Override
  public void open(long marketId, List<OptionRef> options) {
    LocalDateTime now = now();
    mapper.insertMarketIfAbsent(marketId, MarketStatus.LISTED.name(), now);
    for (OptionRef o : options.stream().sorted(java.util.Comparator.comparingInt(OptionRef::seq)).toList()) {
      mapper.insertTotalIfAbsent(o.optionId(), marketId, o.optionNo(), o.seq(), now);
    }
  }

  /**
   * 截止：定格各选项的奖池（{@code final_amount = amount}，只定格一次），返回有有效下注的选项个数（&lt; 2 时 market 按 E3、E4 作废）。
   * 截止任务已经拿到市场行排他锁，之前开始的下注都已提交，之后的下注拿到共享锁时会看到状态已经变了（截止屏障，第 5.2 节）。
   */
  @Override
  public int freezeAndCountActiveOptions(long marketId) {
    LocalDateTime now = now();
    mapper.freezeTotals(marketId, now);
    mapper.markFrozen(marketId, now);
    return mapper.countActiveOptions(marketId);
  }

  @Override
  public List<Long> bettors(long marketId) {
    return mapper.bettors(marketId);
  }

  /** 状态副本同步（我的下注按它分组）；提交后推送一次 */
  @Override
  public void changed(long marketId, MarketStatus status) {
    mapper.updateMarketStatus(marketId, status.name(), now());
    changes.afterCommit(marketId);
  }

  @Override
  public Map<Long, Object> clientPools(List<PoolContext> markets, Long userId) {
    return views.clientPools(markets, userId);
  }
}
