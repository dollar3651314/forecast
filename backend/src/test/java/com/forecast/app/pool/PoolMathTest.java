package com.forecast.app.pool;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.pool.api.Odds;
import com.forecast.app.pool.api.PoolMath;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 赔率和预计回报（纯函数）：期望值照需求说明书第 3.4、3.5 节和 AC-19 的原文写死，不依赖测试向量的生成脚本。
 * 向量的逐条核对见 {@link PoolMathVectorsTest}。
 */
class PoolMathTest {

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  // 第 3.5 节例子：主胜 6,000、平 1,500、客胜 2,500，T = 10,000
  static final BigDecimal HOME = d("6000"), DRAW = d("1500"), AWAY = d("2500"), T = d("10000");

  @Test
  void AC19_第35节例子_概率_列表整数_详情一位小数() {
    assertThat(List.of(Odds.percent(HOME, T, 0), Odds.percent(DRAW, T, 0), Odds.percent(AWAY, T, 0))).containsExactly("60%", "15%", "25%");
    assertThat(List.of(Odds.percent(HOME, T, 1), Odds.percent(DRAW, T, 1), Odds.percent(AWAY, T, 1))).containsExactly("60.0%", "15.0%", "25.0%");
  }

  @Test
  void AC19_预计赔率_扣费后_向下取整() {
    assertThat(Odds.multiplier(HOME, T, d("0.05"))).isEqualTo("1.63"); // 1.6333
    assertThat(Odds.multiplier(DRAW, T, d("0.05"))).isEqualTo("6.38"); // 6.3833
    assertThat(Odds.multiplier(AWAY, T, d("0.05"))).isEqualTo("3.85");
  }

  @Test
  void AC19_例子2_押主胜100的预计回报16229() {
    assertThat(Odds.expectedReturn(HOME, T, d("100"), d("0.05"))).isEqualByComparingTo("162.29");
    assertThat(Odds.expectedReturn(HOME, T, d("100"), d("0.05")).toPlainString()).isEqualTo("162.29");
  }

  @Test
  void AC19_奖池为0显示横线_T为0全部横线_1比1001显示小于1() {
    assertThat(Odds.multiplier(BigDecimal.ZERO, T, d("0.05"))).isEqualTo("—");
    assertThat(Odds.percent(BigDecimal.ZERO, BigDecimal.ZERO, 0)).isEqualTo("—");
    assertThat(Odds.percent(BigDecimal.ZERO, BigDecimal.ZERO, 1)).isEqualTo("—");
    assertThat(Odds.percent(d("1"), d("1001"), 0)).isEqualTo("<1%");
    assertThat(Odds.percent(d("1"), d("1001"), 1)).isEqualTo("<1%");
    assertThat(Odds.percent(d("1000"), d("1001"), 0)).isEqualTo(">99%");
    assertThat(Odds.percent(BigDecimal.ZERO, d("1001"), 0)).as("没人押的选项是 0%").isEqualTo("0%");
    assertThat(Odds.percent(d("1"), d("100"), 0)).as("正好 1% 不算 <1%").isEqualTo("1%");
    assertThat(Odds.percent(d("99"), d("100"), 0)).as("正好 99% 不算 >99%").isEqualTo("99%");
    assertThat(Odds.percent(d("10"), d("10"), 0)).isEqualTo("100%");
  }

  @Test
  void 第35节例子_持仓按奖池结算_Alice两笔合成一个持仓() {
    // Alice 100（60 + 40 合成一个持仓，只舍入一次）、Bob 2,500、Carol 3,400
    PoolMath.Payout alice = PoolMath.payout(d("100"), HOME, T, d("0.05"));
    assertThat(alice.gross()).isEqualByComparingTo("66.66");
    assertThat(alice.fee()).isEqualByComparingTo("3.33");
    assertThat(alice.payout()).isEqualByComparingTo("163.33");
    assertThat(PoolMath.payout(d("2500"), HOME, T, d("0.05")).payout()).isEqualByComparingTo("4083.33");
    assertThat(PoolMath.payout(d("2500"), HOME, T, d("0.05")).fee()).isEqualByComparingTo("83.33");
    assertThat(PoolMath.payout(d("3400"), HOME, T, d("0.05")).payout()).isEqualByComparingTo("5553.33");
    // 合成一个持仓只舍入一次。注：技术方案第 4.5 节注释里"分开算 39.99 + 26.66 = 66.65"算错了（60 × 4000 ÷ 6000 = 40.00 整除），
    // 这个例子分开算也是 66.66；分开算会差的例子：两笔 0.01（各自 0.01 × 4000 ÷ 6000 = 0.0066 → 0），合成 0.02 → 0.0133 → 0.01
    assertThat(PoolMath.gross(d("100"), d("4000"), HOME)).isEqualByComparingTo("66.66");
    assertThat(PoolMath.gross(d("0.01"), d("4000"), HOME).add(PoolMath.gross(d("0.01"), d("4000"), HOME))).isEqualByComparingTo("0");
    assertThat(PoolMath.gross(d("0.02"), d("4000"), HOME)).isEqualByComparingTo("0.01");
  }

  @Test
  void AC37_费率改成8_按新费率显示和结算() {
    assertThat(Odds.multiplier(HOME, T, d("0.08"))).isEqualTo("1.61");
    assertThat(Odds.multiplier(DRAW, T, d("0.08"))).isEqualTo("6.21");
    assertThat(Odds.multiplier(AWAY, T, d("0.08"))).isEqualTo("3.76");
    assertThat(PoolMath.payout(d("100"), HOME, T, d("0.08")).payout()).as("Alice 100 + 66.66 − 5.33").isEqualByComparingTo("161.33");
    assertThat(Odds.expectedReturn(HOME, T, d("100"), d("0.08"))).isEqualByComparingTo("160.32");
  }

  @Test
  void 下注金额_大于0_最多2位小数_不舍入() {
    assertThat(PoolMath.parseStake("100")).isEqualByComparingTo("100");
    assertThat(PoolMath.parseStake("100").scale()).isEqualTo(2);
    assertThat(PoolMath.parseStake(" 0.01 ")).isEqualByComparingTo("0.01");
    assertThat(PoolMath.parseStake("1.10")).isEqualByComparingTo("1.1");
    assertThat(PoolMath.parseStake("1.100000")).as("末尾的 0 不算位数").isEqualByComparingTo("1.1");
    for (String bad : new String[] {"1.001", "0", "0.00", "-1", "", " ", "1e3", "abc", "1,000", "+1", ".5", "1."}) {
      assertThat(PoolMath.parseStake(bad)).as(bad).isNull();
    }
    assertThat(PoolMath.parseStake(null)).isNull();
  }
}
