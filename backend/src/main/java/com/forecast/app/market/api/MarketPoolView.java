package com.forecast.app.market.api;

import java.util.List;

/**
 * 市场需要的奖池信息，<b>由 pool 模块实现</b>（S4）。market 不依赖 pool（技术方案第 2.3 节的依赖方向），反过来由 pool 提供这个接口的实现。
 * 没有实现（S3：还没有下注）时 market 按"没有任何下注"处理：截止时有效选项为 0（按 E4 作废）、没有要通知的下注客户。
 */
public interface MarketPoolView {

  /**
   * 截止事务里调用（市场行已经加了排他锁，进行中的下注都已提交）：定格各选项的奖池（{@code pool_option_total.final_amount}），
   * 返回有有效下注的选项个数。少于 2 个时市场在同一个事务里作废（E3、E4，需求第 3.6 节）。
   */
  int freezeAndCountActiveOptions(long marketId);

  /** 在这个市场有有效下注的客户（费率修改通知 #22、补充说明通知 #18 用） */
  List<Long> bettors(long marketId);
}
