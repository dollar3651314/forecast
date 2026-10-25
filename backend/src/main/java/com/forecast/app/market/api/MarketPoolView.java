package com.forecast.app.market.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 市场需要的奖池信息，<b>由 pool 模块实现</b>（S4，{@code pool/bet/PoolMarketView}）。market 不依赖 pool（技术方案第 2.3 节的依赖方向），
 * 反过来由 pool 提供这个接口的实现。没有实现时 market 按"没有任何下注"处理：截止时有效选项为 0（按 E4 作废）、没有要通知的下注客户、
 * 客户端接口里没有奖池。
 * <p>除了 {@link #clientPools}，其余方法都在 market 的事务里同步调用（市场行已经加了排他锁），抛异常会让整个操作回滚。
 */
public interface MarketPoolView {

  /** 一个选项（上架后不变） */
  record OptionRef(long optionId, long optionNo, int seq) {}

  /**
   * 客户端接口要奖池时 market 给的市场信息（market 自己的字段，pool 不再查）。
   *
   * @param feeRate 当前费率（小数，0.05 = 5%）：预计赔率按当前费率显示（第 3.8 节）
   * @param userCap 单客户在本市场的累计上限
   */
  record PoolContext(long marketId, MarketStatus status, BigDecimal feeRate, BigDecimal userCap, List<OptionRef> options) {}

  /**
   * 上架事务里调用（DRAFT → LISTED，S4）：建好各选项的奖池行（金额 0），之后下注只需要原子加，不用在下注时插入（避免并发插入的间隙锁）。
   * 重复调用没有效果。
   */
  void open(long marketId, List<OptionRef> options);

  /**
   * 截止事务里调用（市场行已经加了排他锁，进行中的下注都已提交）：定格各选项的奖池（{@code pool_option_total.final_amount}），
   * 返回有有效下注的选项个数。少于 2 个时市场在同一个事务里作废（E3、E4，需求第 3.6 节）。
   */
  int freezeAndCountActiveOptions(long marketId);

  /** 一个市场的奖池合计和下注人数（后台市场列表用，S9） */
  record Totals(BigDecimal amount, int bettors) {}

  /** 后台市场列表（S9）：marketId → 奖池合计、下注人数；没有记录的不在结果里（按 0）。只读 */
  default Map<Long, Totals> totals(List<Long> marketIds) {
    return Map.of();
  }

  /** 在这个市场有有效下注的客户（费率修改通知 #22、补充说明通知 #18 用） */
  List<Long> bettors(long marketId);

  /**
   * 市场状态变了、费率改了、截止时间提前了（market 的事务里调用）：pool 同步状态副本，并在事务提交后推送一次最新的奖池
   * （WebSocket {@code /api/ws/markets}，第 7 节）。
   */
  void changed(long marketId, MarketStatus status);

  /**
   * 客户端市场列表、详情里的 {@code pool} 字段（结构由 pool 定，见 {@code pool/bet/PoolViews.Pool}）：各选项奖池、概率、预计赔率；
   * {@code userId} 不为空时（详情）再加本人的持仓、单笔限额和本市场还能押多少。只读，不在事务里也可以调用。
   *
   * @return marketId → pool 字段；市场在彩池里没有记录时也返回全 0 的奖池
   */
  Map<Long, Object> clientPools(List<PoolContext> markets, Long userId);
}
