package com.forecast.app.pool.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 彩池的金额计算（纯函数，不碰数据库；forecast S4，需求说明书第 3.3、3.5 节，技术方案/v1-架构方案.md 第 4.1 节）。
 * <ul>
 *   <li>一律 {@link BigDecimal} 精确计算，不用 double，不用 MathContext 截断；只在需求第 3.5 节写明的地方舍入：
 *       盈利 G 向下取整到 0.01、抽成 F 四舍五入到 0.01、派彩 = b + G − F 不再舍入。</li>
 *   <li>和客户端 {@code web/client/src/lib/pool.ts} 是同一套公式，共用测试向量 {@code backend/src/test/resources/pool/pool-vectors.json}
 *       （{@code PoolMathVectorsTest}），结果必须逐分一致。</li>
 *   <li>费率 r 是小数（0.05 = 5%），和数据库 {@code market_question.fee_rate} 一样。</li>
 * </ul>
 * S5 的结算（{@code SettleMath}）用同样的 {@link #gross}、{@link #fee}，结算时 L、P_W 用截止时定格的奖池。
 */
public final class PoolMath {

  public static final BigDecimal CENT = new BigDecimal("0.01");

  private PoolMath() {}

  /**
   * 下注金额（需求第 3.3 节、NF-07）：大于 0、最多 2 位小数，<b>不舍入</b>（多于 2 位直接拒绝）。
   *
   * @return 合法时是 scale 为 2 的金额；格式不对（空、科学计数法、负数、0、多于 2 位小数）返回 null
   */
  public static BigDecimal parseStake(String s) {
    if (s == null) return null;
    String v = s.strip();
    if (!v.matches("\\d{1,12}(\\.\\d{1,8})?")) return null;
    BigDecimal b = new BigDecimal(v);
    if (b.signum() <= 0 || b.stripTrailingZeros().scale() > 2) return null;
    return b.setScale(2, RoundingMode.UNNECESSARY);
  }

  /** 盈利（毛）G = b × L ÷ P_W，向下取整到 0.01（需求第 3.5 节 ①）。P_W 必须 &gt; 0 */
  public static BigDecimal gross(BigDecimal stake, BigDecimal loserPool, BigDecimal winnerPool) {
    // divide 带 scale 时按精确商舍入：等于"向下取整到 0.01"
    return stake.multiply(loserPool).divide(winnerPool, 2, RoundingMode.DOWN);
  }

  /** 抽成 F = G × r，四舍五入到 0.01（需求第 3.5 节 ②） */
  public static BigDecimal fee(BigDecimal gross, BigDecimal rate) {
    return gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);
  }

  /** 一个持仓按某个奖池结算的结果：盈利 G、抽成 F、派彩 b + G − F */
  public record Payout(BigDecimal gross, BigDecimal fee, BigDecimal payout) {}

  /**
   * 一个<b>已经在奖池里</b>的持仓 b，如果它押的选项赢了、按现在的奖池结算能拿回多少（市场详情里"我的持仓"显示用；
   * 截止以后用定格的奖池，就是最终派彩）。L = T − Pᵢ，P_W = Pᵢ。
   */
  public static Payout payout(BigDecimal stake, BigDecimal optionPool, BigDecimal total, BigDecimal rate) {
    BigDecimal g = gross(stake, total.subtract(optionPool), optionPool);
    BigDecimal f = fee(g, rate);
    return new Payout(g, f, stake.add(g).subtract(f).setScale(2, RoundingMode.UNNECESSARY));
  }
}
