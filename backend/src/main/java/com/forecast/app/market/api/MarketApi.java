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

  /**
   * 结算要用到的市场信息（{@link #lockForSettle} 的结果，forecast S5）。
   *
   * @param feeRate 当前费率（小数）：结算按结算开始时的费率算，开始以后不能再改（需求第 3.8 节）
   * @param actualStartAt 实际开赛时间（E14），没填为空
   * @param winningOptionId 已经记下的结果，没有为空
   */
  record SettleTarget(long marketId, long publicNo, MarketStatus status, SettlePhase settlePhase, MarketCategory category, BigDecimal feeRate,
      Instant closeAt, Instant actualStartAt, Long winningOptionId, I18nText title, List<OptionRef> options) {}

  /**
   * 结算、退款的事务开头调用（<b>必须在调用方的事务里</b>）：市场行加<b>排他锁</b>（{@code FOR UPDATE}），和截止任务、改费率、状态跳转串行；
   * 下注拿的是共享锁，所以锁住以后这个市场没有进行中的下注。市场不存在或是草稿时抛 {@code NOT_FOUND}。
   */
  SettleTarget lockForSettle(long marketId);

  /**
   * 记下结果（赢的选项、实际开赛时间、谁给的结果）。只在结算还没开始（{@link SettlePhase#NONE}）时生效，返回 false 表示已经开始，什么都不改。
   * 必须在 {@link #lockForSettle} 的同一个事务里调用。选项必须属于这个市场（否则抛 {@code OPTION_NOT_FOUND}）。
   */
  boolean recordResult(long marketId, long winningOptionId, Instant actualStartAt, String resolvedBy);

  /** 结算阶段 from → to（带旧值的条件更新）；当前不是 from 时返回 false、什么都不改 */
  boolean casSettlePhase(long marketId, SettlePhase from, SettlePhase to);

  /**
   * 自动裁决要用的市场信息（forecast S6）。
   *
   * @param resolutionMode 裁决方式（草稿可能为空）
   * @param sourceRef 自动裁决用的结构化参数（{@code source_ref}，加密：{@code kind = CRYPTO_UPDOWN}、{@code symbol}、{@code windowStart}、
   *     {@code windowEnd}、{@code options}（结果 → 选项序号，例如 {@code {"UP": 1, "DOWN": 2}}）），没有为空
   * @param autogenId 由哪个自动生成模板生成（内部 id），手工建的为空
   */
  record AutoRef(long marketId, long publicNo, MarketStatus status, MarketCategory category, ResolutionMode resolutionMode, Map<String, Object> sourceRef,
      Instant eventStartAt, Instant eventEndAt, I18nText title, List<OptionRef> options, Long autogenId) {}

  /** 按内部 id 取自动裁决要用的信息；可以在调用方的事务里调用（只读） */
  Optional<AutoRef> autoRef(long marketId);

  /**
   * 平台裁决要用的市场信息（forecast S7）。
   *
   * @param disputeWindowMin 市场上的争议窗口（分钟，平台裁决的市场建的时候按品类带出；自动裁决的市场为空）
   * @param winningOptionId 已经记下的结果（公示中是拟定结果），没有为空
   * @param resolvedBy 裁决人编号（管理员 id 或 AUTO）
   * @param disputeDeadlineAt 争议截止时间（公示中才有）
   */
  record PlatformRef(long marketId, long publicNo, MarketStatus status, SettlePhase settlePhase, MarketCategory category, ResolutionMode resolutionMode,
      I18nText title, List<OptionRef> options, Instant closeAt, Instant eventStartAt, Instant eventEndAt, Integer disputeWindowMin, Long winningOptionId,
      Instant actualStartAt, String resolvedBy, Instant disputeDeadlineAt, String voidReason) {}

  /** 按内部 id 取平台裁决要用的信息（只读）；不存在、删除的草稿为空 */
  Optional<PlatformRef> platformRef(long marketId);

  /**
   * 平台裁决的写操作开头调用（<b>必须在调用方的事务里</b>）：市场行加<b>排他锁</b>（和截止任务、结算、状态跳转串行；锁住以后结算阶段、状态不会被别人改）。
   * 市场不存在或是草稿时抛 {@code NOT_FOUND}。
   */
  PlatformRef lockPlatform(long marketId);

  /**
   * 确认结果、进入公示（forecast S7，第 11 条边 PENDING_RESOLUTION → PUBLICIZING）：带旧状态的条件跳转（写状态历史、通知监听者），
   * 同时写拟定结果、实际开赛时间、裁决人编号、争议截止时间。必须在 {@link #lockPlatform} 的同一个事务里调用；
   * 市场不在待裁决或结算已经开始时返回 false、什么都不改。选项必须属于这个市场（否则抛 {@code OPTION_NOT_FOUND}）。
   *
   * @param actor 状态历史里的操作人（管理员 id）
   */
  boolean publicize(long marketId, long optionId, Instant actualStartAt, String resolvedBy, Instant disputeDeadlineAt, String actor);

  // ---------- forecast S13：来源统计、增长数据导出（growth）用 ----------

  /** 一个已结束的市场（已结算 / 已作废 / 已取消）和结束时间、作废 / 取消原因 */
  record Ended(long marketId, long publicNo, MarketCategory category, I18nText title, MarketStatus status, Instant endedAt, String voidReason,
      String cancelReason) {}

  /** 结束时间在 [from, to) 的市场，最近结束的在前，最多 limit 个 */
  List<Ended> ended(Instant from, Instant to, int limit);
}
