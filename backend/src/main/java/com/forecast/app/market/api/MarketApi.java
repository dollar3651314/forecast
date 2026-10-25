package com.forecast.app.market.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 给其他模块用的市场接口（forecast S3）。
 */
public interface MarketApi {

  /**
   * 下注要用到的市场信息（{@link #lockForBet} 的结果）。
   *
   * @param feeRate 当前费率（小数，0.05 = 5%）
   * @param userCap 单客户在本市场的累计上限
   * @param title 题目（下注成功通知 #8 用）
   * @param optionLabel 选项文字
   */
  record BetTarget(long marketId, long marketNo, long optionId, long optionNo, MarketCategory category, BigDecimal feeRate, BigDecimal userCap,
      Instant closeAt, I18nText title, I18nText optionLabel) {}

  /** 市场的基本信息 */
  record MarketRef(long marketId, long publicNo, MarketStatus status, MarketCategory category, I18nText title, Instant openAt, Instant closeAt,
      Instant eventEndAt, BigDecimal feeRate, BigDecimal userCap) {}

  /** 一个选项（上架后不变） */
  record OptionRef(long optionId, long optionNo, long marketId, int seq, I18nText label) {}

  /** 按对外编号找；草稿、删除的也找得到（调用方自己看状态） */
  Optional<MarketRef> find(long publicNo);

  /** 按内部 id 找（S4：推送、我的下注、后台下注明细用） */
  Optional<MarketRef> findById(long marketId);

  /** 一批市场（内部 id → 信息），不存在的不在结果里 */
  Map<Long, MarketRef> refs(Collection<Long> marketIds);

  /** 一个市场的选项，按顺序 */
  List<OptionRef> options(long marketId);

  /** 一批选项（内部 id → 信息），不存在的不在结果里 */
  Map<Long, OptionRef> optionsById(Collection<Long> optionIds);

  /**
   * 下注前的截止屏障（技术方案第 4.2、5.2 节；S4 的下注事务调用，<b>必须在调用方的事务里</b>）：
   * 对市场行加<b>共享锁</b>（{@code SELECT … FOR SHARE}），截止任务要拿排他锁，所以截止任务会等这笔下注提交；
   * 截止任务之后才拿到锁的下注会看到状态已经不是"已上架"而被拒绝。
   * <p>只用服务器收到请求的时间 {@code receivedAt} 判断：{@code openAt ≤ receivedAt < closeAt} 才接受，正好等于截止时间的拒绝（AC-15）。
   *
   * @throws com.forecast.app.common.error.BizException 拒绝的原因（需求第 3.3 节）：{@code NOT_FOUND}（市场不存在或是草稿）、
   *     {@code MARKET_CANCELLED}、{@code MARKET_CLOSED}（已截止或之后的状态）、{@code MARKET_PAUSED}、{@code MARKET_NOT_OPEN}（还没到开放时间）、
   *     {@code OPTION_NOT_FOUND}（选项不属于这个市场）
   */
  BetTarget lockForBet(long marketPublicNo, long optionPublicNo, Instant receivedAt);

  /**
   * 系统或其他模块触发的状态跳转（S5～S7：自动裁决结算、确认结果、公示期满、争议、作废）。只允许 17 条边，其他抛 {@code ILLEGAL_TRANSITION}。
   * 带旧状态的条件更新：市场当前不是 {@code from} 时什么都不做、返回 false（别人已经改过，调用方不要重复后续动作）。
   * 进入 CANCELLED、VOIDED 时在同一个事务里通知 {@link MarketListener#onRefundRequired}（S5 建退款单）。
   *
   * @param actor 管理员 id、{@code SYSTEM} 或 {@code AUTO}
   */
  boolean transition(long marketId, MarketStatus from, MarketStatus to, String actor, String reason);
}
