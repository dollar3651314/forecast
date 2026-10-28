package com.forecast.app.ledger.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 客户的资金流水合计（只读），给提现自动审批的 R8～R10、R13、R14 和下注的 KYC 阈值用。金额都按账本（{@code ledger_entry}）算，不改任何金额。
 * 时间窗口只有下限（从 since 起），账本里不会有"将来"的记录。
 * <p><b>已结算的盈亏</b>（forecast S10，需求说明书第 8.2 节 R9、R10、R13；S1～S9 的过渡口径"恒为 0"已删除）：
 * 净盈利 = 派彩（PAYOUT，本金 + 盈利，派彩记录本身没扣抽成）+ 补偿（COMPENSATION）− 已结算的下注本金（BET_SETTLE 赢的 + BET_LOST 输的）− 抽成（FEE）。
 * 退回的下注（取消、作废、E14、E15、提前截止）只有 BET_LOCK 和 REFUND，不进任何一项，等于"退款记 0"；还没结算的下注也不算（只算已实现的）。
 * 等价于需求的 Σ(派彩 − 本金)（派彩已扣抽成；输的记 −本金；退款记 0）+ 补偿。
 */
public interface CustomerFlowApi {

  /**
   * 一段时间里入账的充值。
   *
   * @param net 充值 − 冲正（账本 DEPOSIT + DEPOSIT_REVERSAL，冲正是负数）；forecast S17A 起测试入金 TEST_CREDIT 也算充值（只在测试环境有）
   * @param firstAt 其中最早一笔充值的入账时间；没有充值时为空
   */
  record Deposits(BigDecimal net, LocalDateTime firstAt) {}

  /** 从 since 起（含）入账的充值 */
  Deposits depositsSince(long userId, LocalDateTime since);

  /** 累计充值 − 冲正（全部时间） */
  BigDecimal netDeposits(long userId);

  /**
   * 已结算的盈亏的组成（都是正数或 0）。
   *
   * @param payout 派彩合计（本金 + 盈利，抽成另记）
   * @param compensation 补偿合计（E13）
   * @param stake 已结算的下注本金合计（赢的 + 输的；退回的、没结算的不算）
   * @param fee 抽成合计
   */
  record Realized(BigDecimal payout, BigDecimal compensation, BigDecimal stake, BigDecimal fee) {

    public static final Realized ZERO = new Realized(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    /** 净盈利 = 派彩 + 补偿 − 本金 − 抽成（亏损为负） */
    public BigDecimal net() {
      return payout.add(compensation).subtract(stake).subtract(fee);
    }
  }

  /** 从 since 起（含，按记账时间）的已结算盈亏；since 为空表示从开户到现在 */
  Realized realized(long userId, LocalDateTime since);

  /**
   * 一个市场里这个客户的已结算盈亏（全部时间），R14 用。
   *
   * @param firstPayoutAt 这个市场第一笔派彩的记账时间
   */
  record MarketRealized(long marketId, Realized realized, LocalDateTime firstPayoutAt) {}

  /** 从 since 起（含）有派彩的市场，每个市场一行（按市场 id 升序） */
  List<MarketRealized> paidMarketsSince(long userId, LocalDateTime since);
}
