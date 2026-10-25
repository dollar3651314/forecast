package com.forecast.app.settle.view;

import com.forecast.app.market.api.MarketSettleView;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.pool.api.PoolResultView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户端要的结算信息（forecast S11）：市场详情的 {@code settle} 字段（{@link MarketSettleView}）、我的下注的派彩和汇总（{@link PoolResultView}）。
 * 只读，只依赖 settle 自己的表；数字都来自结算单和明细（写入后不改，约定 35），和后台结算记录、账本一致。
 * <p>结算单公开的是：结算用的费率、奖池合计 T、派彩合计 ΣPay、抽成合计 ΣF、尾差 D、赢方 / 输方持仓数（需求第 3.5 节 ΣPay + ΣF + D = T）；
 * 不公开别的客户的明细。
 */
@Service
class ClientSettleViews implements MarketSettleView, PoolResultView {

  /** 详情里的结算信息 */
  record ClientSettle(String kind, String status, String feeRate, String poolTotal, String payoutTotal, String feeTotal, String dust, int winnerPositions,
      int loserPositions, String doneAt, Mine mine) {}

  /**
   * 本人的结果（没有下注时为 null）。
   *
   * @param stake 已结算的本金（取消、作废时为 0.00）
   * @param payout 派彩（已扣抽成）
   * @param fee 抽成
   * @param refund 退回的金额（取消、作废的全额退款，E14、E15、提前截止的退回）
   * @param compensation 补偿（E13）
   */
  record Mine(String stake, String payout, String fee, String refund, String compensation) {}

  private final SettleViewMapper mapper;

  ClientSettleViews(SettleViewMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  @Transactional(readOnly = true)
  public Object clientSettle(long marketId, MarketStatus status, Long userId) {
    boolean refund = status == MarketStatus.CANCELLED || status == MarketStatus.VOIDED;
    if (!refund && status != MarketStatus.SETTLED) return null;
    SettleViewMapper.RunRow r = mapper.run(marketId, refund ? "REFUND" : "PAYOUT");
    if (r == null || (!refund && !"DONE".equals(r.status))) return null;
    Mine mine = userId == null ? null : mine(userId, marketId, refund);
    if (refund) {
      return new ClientSettle("refund", r.status.toLowerCase(java.util.Locale.ROOT), null, money(r.poolTotal), money(r.sumPayout), money(BigDecimal.ZERO),
          money(BigDecimal.ZERO), 0, 0, iso(r.doneAt), mine);
    }
    return new ClientSettle("payout", "done", percent(r.feeRate), money(r.poolTotal), money(r.sumPayout), money(r.sumFee), money(r.dust), r.winnerPositions,
        r.loserPositions, iso(r.doneAt), mine);
  }

  private Mine mine(long userId, long marketId, boolean refund) {
    BigDecimal stake = BigDecimal.ZERO;
    BigDecimal payout = BigDecimal.ZERO;
    BigDecimal fee = BigDecimal.ZERO;
    if (!refund) {
      for (SettleViewMapper.ItemRow i : mapper.userItems(userId, List.of(marketId))) {
        stake = stake.add(i.stake);
        payout = payout.add(i.payout);
        fee = fee.add(i.fee);
      }
    }
    BigDecimal refunded = mapper.userRefunded(userId, marketId);
    BigDecimal comp = mapper.userCompensation(userId, marketId);
    if (stake.signum() == 0 && refunded.signum() == 0 && comp.signum() == 0) return null;
    return new Mine(money(stake), money(payout), money(fee), money(refunded), money(comp));
  }

  @Override
  @Transactional(readOnly = true)
  public List<PositionResult> results(long userId, Collection<Long> marketIds) {
    if (marketIds == null || marketIds.isEmpty()) return List.of();
    return mapper.userItems(userId, marketIds).stream().map(i -> new PositionResult(i.marketId, i.optionId, i.win, i.stake, i.payout, i.fee)).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Totals totals(long userId) {
    SettleViewMapper.SumRow s = mapper.userSettled(userId);
    BigDecimal comp = mapper.userCompensation(userId, null);
    return new Totals(s == null ? BigDecimal.ZERO : s.stake, s == null ? BigDecimal.ZERO : s.payout, comp);
  }

  static String money(BigDecimal v) {
    return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.DOWN).toPlainString();
  }

  /** 费率：小数 → 百分比字符串（0.0500 → "5"），和市场接口一致 */
  static String percent(BigDecimal rate) {
    return rate == null ? null : rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
  }

  static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }
}
