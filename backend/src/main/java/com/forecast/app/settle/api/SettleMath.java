package com.forecast.app.settle.api;

import com.forecast.app.pool.api.PoolMath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 彩池结算的计算（纯函数，不碰数据库；forecast S5，需求说明书第 3.5 节，技术方案/v1-架构方案.md 第 4.1、4.3、4.5 节）。
 * <ul>
 *   <li>按<b>持仓</b>算（同一客户同一选项的下注合计，只舍入一次）：Gⱼ = bⱼ × L ÷ P_W 向下取整到 0.01；Fⱼ = Gⱼ × r 四舍五入到 0.01；
 *       Payⱼ = bⱼ + Gⱼ − Fⱼ 不再舍入；尾差 D = L − ΣGⱼ 归平台。G、F 的舍入和下注时的预计回报是同一份代码（{@link PoolMath#gross}、{@link PoolMath#fee}）。</li>
 *   <li>输方持仓派彩 0、不收费。平台收入 = ΣF + D。</li>
 *   <li>守恒：ΣPay + ΣF + D = T，0 ≤ D &lt; 0.01 × 赢方持仓数（不通过的市场停在"结算异常"，不派彩）。</li>
 *   <li>没人押赢方（P_W = 0，T &gt; 0）：不算，{@link Result#noWinner()} 为真，调用方作废、全额退款（E1）。</li>
 * </ul>
 */
public final class SettleMath {

  private SettleMath() {}

  /** 一个持仓：key 是调用方自己的编号（持仓 id） */
  public record Stake(long key, long userId, long optionId, BigDecimal amount) {}

  /** 一个持仓的结算结果；输方 gross、fee、payout 都是 0 */
  public record Line(Stake stake, boolean win, BigDecimal gross, BigDecimal fee, BigDecimal payout) {}

  /**
   * 一个市场的结算结果。
   *
   * @param total T
   * @param winnerPool P_W
   * @param loserPool L = T − P_W
   * @param dust 尾差 D = L − ΣG
   */
  public record Result(BigDecimal total, BigDecimal winnerPool, BigDecimal loserPool, BigDecimal sumGross, BigDecimal sumFee, BigDecimal dust,
      BigDecimal sumPayout, int winners, int losers, List<Line> lines, boolean noWinner) {

    /** 平台收入 = 抽成 + 尾差（平台在这个市场上的全部变动） */
    public BigDecimal platformIncome() {
      return sumFee.add(dust);
    }

    /** 守恒校验（需求第 3.5 节）：ΣPay + ΣF + D = T，0 ≤ D &lt; 0.01 × 赢方持仓数；没人押赢方时不成立（要作废） */
    public boolean conserved() {
      if (noWinner || winners == 0) return false;
      return sumPayout.add(sumFee).add(dust).compareTo(total) == 0 && dust.signum() >= 0
          && dust.compareTo(PoolMath.CENT.multiply(BigDecimal.valueOf(winners))) < 0;
    }
  }

  /**
   * 按结果 winningOptionId、费率 rate（小数，0.05 = 5%）结算这些持仓。金额 &gt; 0、最多 2 位小数；同一个 key 只能出现一次。
   */
  public static Result settle(List<Stake> positions, long winningOptionId, BigDecimal rate) {
    if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) throw new IllegalArgumentException("费率不对：" + rate);
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal winnerPool = BigDecimal.ZERO;
    for (Stake s : positions) {
      if (s.amount() == null || s.amount().signum() <= 0) throw new IllegalArgumentException("持仓金额必须大于 0：" + s);
      total = total.add(s.amount());
      if (s.optionId() == winningOptionId) winnerPool = winnerPool.add(s.amount());
    }
    BigDecimal zero = BigDecimal.ZERO.setScale(2);
    total = total.setScale(2, RoundingMode.UNNECESSARY);
    winnerPool = winnerPool.setScale(2, RoundingMode.UNNECESSARY);
    if (winnerPool.signum() == 0) {
      return new Result(total, zero, total, zero, zero, zero, zero, 0, positions.size(), List.of(), true);
    }
    BigDecimal loserPool = total.subtract(winnerPool);
    List<Line> lines = new ArrayList<>(positions.size());
    BigDecimal sumG = zero;
    BigDecimal sumF = zero;
    BigDecimal sumPay = zero;
    int winners = 0;
    for (Stake s : positions) {
      if (s.optionId() != winningOptionId) {
        lines.add(new Line(s, false, zero, zero, zero));
        continue;
      }
      BigDecimal g = PoolMath.gross(s.amount(), loserPool, winnerPool);
      BigDecimal f = PoolMath.fee(g, rate);
      BigDecimal pay = s.amount().add(g).subtract(f).setScale(2, RoundingMode.UNNECESSARY);
      lines.add(new Line(s, true, g, f, pay));
      sumG = sumG.add(g);
      sumF = sumF.add(f);
      sumPay = sumPay.add(pay);
      winners++;
    }
    BigDecimal dust = loserPool.subtract(sumG);
    return new Result(total, winnerPool, loserPool, sumG, sumF, dust, sumPay, winners, positions.size() - winners, lines, false);
  }
}
