package com.forecast.app.ledger.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客户的资金流水合计（只读），给提现自动审批的 R8、R9、R10、R13 用。金额都按账本（{@code ledger_entry}）的
 * AVAILABLE（可用余额）一项算，不改任何金额。时间窗口只有下限（从 since 起），账本里不会有"将来"的记录。
 * <p><b>forecast S1 过渡</b>：差价合约删除后，"交易量""已实现盈亏""手续费""隔夜费"在账本里都没有记录了，下面这几个方法暂时恒为 0
 * （{@link #openedNotionalSince}、{@link #realizedPnl}、{@link #netProfit}）。S10 按需求说明书第 8.2 节改成下注口径
 * （有效下注本金、Σ(派彩 − 本金) + 补偿），见技术方案/v1-架构方案.md 第 1.2 节 ledger ⑤。恒为 0 时 R8 在有充值时不通过（转人工审核）；
 * S1～S9 还没有下注，客户本来就没有盈利，R9、R10、R13 按"没有盈利"判定和实际一致。
 */
public interface CustomerFlowApi {

  /**
   * 一段时间里入账的充值。
   *
   * @param net 充值 − 冲正（账本 DEPOSIT + DEPOSIT_REVERSAL，冲正是负数）
   * @param firstAt 其中最早一笔充值的入账时间；没有充值时为空
   */
  record Deposits(BigDecimal net, LocalDateTime firstAt) {}

  /** 从 since 起（含）入账的充值 */
  Deposits depositsSince(long userId, LocalDateTime since);

  /** 累计充值 − 冲正（全部时间） */
  BigDecimal netDeposits(long userId);

  /** 已实现盈亏合计；since 为空表示全部时间。S1 过渡：恒为 0（见类注释） */
  BigDecimal realizedPnl(long userId, LocalDateTime since);

  /**
   * 累计净盈利的三个组成（R13）：从开户到现在。S1 过渡：三项都恒为 0（见类注释），S10 改成下注口径。
   *
   * @param realized 已实现盈亏（盈利为正）
   * @param fee 客户付的费用（付出为正）
   * @param swap 复制源的隔夜费，forecast 没有这项，S10 删除
   */
  record NetProfit(BigDecimal realized, BigDecimal fee, BigDecimal swap) {

    /** 累计净盈利 = 已实现 − 手续费 − 隔夜费 */
    public BigDecimal net() {
      return realized.subtract(fee).subtract(swap);
    }
  }

  /** 从开户到现在的累计净盈利（R13）。S1 过渡：恒为 0 */
  NetProfit netProfit(long userId);

  /** 从 since 起（含）的交易量（R8）。S1 过渡：恒为 0（见类注释），S10 改成有效下注本金 */
  BigDecimal openedNotionalSince(long userId, LocalDateTime since);
}
