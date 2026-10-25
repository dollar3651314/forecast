package com.forecast.app.pool.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 赔率的显示（纯函数；forecast S4，需求说明书第 3.4 节，技术方案/v1-架构方案.md 第 4.1 节）。返回的是显示用的字符串，
 * 客户端直接显示，不再自己舍入；客户端的 {@code lib/pool.ts} 是同一套规则（下注抽屉里输入金额时本地算预计回报），共用测试向量。
 * <ul>
 *   <li>概率 % = Pᵢ ÷ T × 100：列表 0 位、详情 1 位小数，四舍五入；Pᵢ &gt; 0 且 &lt; 1% 显示 {@code <1%}，&gt; 99% 且 &lt; 100% 显示 {@code >99%}；
 *       T = 0 显示 {@value #NONE}。</li>
 *   <li>预计赔率 x（扣费后）= (Pᵢ + (T − Pᵢ)(1 − r)) ÷ Pᵢ，2 位小数<b>向下取整</b>（不夸大回报）；Pᵢ = 0 显示 {@value #NONE}。</li>
 *   <li>本注预计回报：把本注加进奖池后按第 3.5 节算（G 向下取整、F 四舍五入）。</li>
 * </ul>
 * 预计赔率、预计回报都用市场<b>当前</b>的费率（费率改了以后立即按新费率显示，第 3.8 节）。
 */
public final class Odds {

  /** 没有数据时显示的符号（T = 0 的概率、Pᵢ = 0 的赔率） */
  public static final String NONE = "—";
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final BigDecimal ONE = BigDecimal.ONE;
  private static final BigDecimal NINETY_NINE = BigDecimal.valueOf(99);

  private Odds() {}

  /**
   * 概率。
   *
   * @param decimals 列表 0、详情 1
   * @return 例如 {@code 60%}、{@code 60.0%}、{@code <1%}、{@code >99%}、{@value #NONE}
   */
  public static String percent(BigDecimal optionPool, BigDecimal total, int decimals) {
    if (total.signum() == 0) return NONE;
    // 判断"<1%"、">99%"用精确值（Pᵢ × 100 和 T 比），不用舍入后的值
    BigDecimal p100 = optionPool.multiply(HUNDRED);
    if (optionPool.signum() > 0 && p100.compareTo(total) < 0) return "<1%";
    if (p100.compareTo(total.multiply(NINETY_NINE)) > 0 && optionPool.compareTo(total) < 0) return ">99%";
    return p100.divide(total, decimals, RoundingMode.HALF_UP).toPlainString() + "%";
  }

  /** 预计赔率（扣费后，不带 x），例如 {@code 1.63}；Pᵢ = 0 时 {@value #NONE} */
  public static String multiplier(BigDecimal optionPool, BigDecimal total, BigDecimal rate) {
    if (optionPool.signum() == 0) return NONE;
    BigDecimal back = optionPool.add(total.subtract(optionPool).multiply(ONE.subtract(rate)));
    return back.divide(optionPool, 2, RoundingMode.DOWN).toPlainString();
  }

  /**
   * 本注预计回报（下注抽屉："押 100 → 赢了约回 162.29（已扣抽成）"）：G = b × (T − Pᵢ) ÷ (Pᵢ + b) 向下取整到 0.01，
   * F = G × r 四舍五入，回报 = b + G − F。T、Pᵢ 是下注<b>之前</b>的奖池。
   */
  public static BigDecimal expectedReturn(BigDecimal optionPool, BigDecimal total, BigDecimal stake, BigDecimal rate) {
    BigDecimal g = PoolMath.gross(stake, total.subtract(optionPool), optionPool.add(stake));
    BigDecimal f = PoolMath.fee(g, rate);
    // 金额都是 2 位小数（库里是 8 位小数的同一个值），结果统一成 2 位
    return stake.add(g).subtract(f).setScale(2, RoundingMode.UNNECESSARY);
  }
}
