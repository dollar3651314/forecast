package com.forecast.app.settle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.pool.api.PoolMath;
import com.forecast.app.settle.api.SettleMath;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 结算公式（纯函数，需求说明书第 3.5 节；技术方案/v1-架构方案.md 第 4.5 节的用例）：曼城 vs 利物浦，主胜 6,000（Alice 60 + 40、Bob 2,500、Carol 3,400）、
 * 平 1,500（Dave）、客胜 2,500（Eve 2,000、Frank 500），结果主胜，费率 5%。数字逐分照需求原文。
 */
class SettleMathTest {

  static final long HOME = 1;
  static final long DRAW = 2;
  static final long AWAY = 3;

  static SettleMath.Stake pos(long key, long option, String amount) {
    return new SettleMath.Stake(key, key, option, new BigDecimal(amount));
  }

  /** Alice 的两笔 60 + 40 先合成一个持仓 100（结算按持仓算） */
  static final List<SettleMath.Stake> EXAMPLE = List.of(pos(1, HOME, "100.00"), pos(2, HOME, "2500.00"), pos(3, HOME, "3400.00"), pos(4, DRAW, "1500.00"),
      pos(5, AWAY, "2000.00"), pos(6, AWAY, "500.00"));

  static Map<Long, SettleMath.Line> byKey(SettleMath.Result r) {
    return r.lines().stream().collect(Collectors.toMap(l -> l.stake().key(), l -> l));
  }

  @Test
  void 第35节例子_费率5_逐分相等() {
    SettleMath.Result r = SettleMath.settle(EXAMPLE, HOME, new BigDecimal("0.05"));
    Map<Long, SettleMath.Line> l = byKey(r);
    assertThat(r.total()).isEqualByComparingTo("10000.00");
    assertThat(r.winnerPool()).isEqualByComparingTo("6000.00");
    assertThat(r.loserPool()).isEqualByComparingTo("4000.00");
    // Alice：G = 100 × 4,000 ÷ 6,000 = 66.666… → 66.66；F = 3.333 → 3.33；派彩 163.33
    assertThat(l.get(1L).gross()).isEqualByComparingTo("66.66");
    assertThat(l.get(1L).fee()).isEqualByComparingTo("3.33");
    assertThat(l.get(1L).payout()).isEqualByComparingTo("163.33");
    // Bob：1,666.66 / 83.33 / 4,083.33；Carol：2,266.66 / 113.33 / 5,553.33
    assertThat(l.get(2L).gross()).isEqualByComparingTo("1666.66");
    assertThat(l.get(2L).fee()).isEqualByComparingTo("83.33");
    assertThat(l.get(2L).payout()).isEqualByComparingTo("4083.33");
    assertThat(l.get(3L).gross()).isEqualByComparingTo("2266.66");
    assertThat(l.get(3L).fee()).isEqualByComparingTo("113.33");
    assertThat(l.get(3L).payout()).isEqualByComparingTo("5553.33");
    // 合计：ΣG 3,999.98、ΣF 199.99、ΣPay 9,799.99；尾差 0.02；平台收入 200.01；Dave、Eve、Frank 派彩 0
    assertThat(r.sumGross()).isEqualByComparingTo("3999.98");
    assertThat(r.sumFee()).isEqualByComparingTo("199.99");
    assertThat(r.sumPayout()).isEqualByComparingTo("9799.99");
    assertThat(r.dust()).isEqualByComparingTo("0.02");
    assertThat(r.platformIncome()).isEqualByComparingTo("200.01");
    for (long loser : List.of(4L, 5L, 6L)) {
      assertThat(l.get(loser).win()).isFalse();
      assertThat(l.get(loser).payout()).isEqualByComparingTo("0");
      assertThat(l.get(loser).fee()).isEqualByComparingTo("0");
    }
    assertThat(r.winners()).isEqualTo(3);
    assertThat(r.losers()).isEqualTo(3);
    // 守恒：9,799.99 + 199.99 + 0.02 = 10,000.00
    assertThat(r.sumPayout().add(r.sumFee()).add(r.dust())).isEqualByComparingTo("10000.00");
    assertThat(r.conserved()).isTrue();
    // 核对 Alice：100 × 1.6333 = 163.33，和最终赔率一致（需求原文）
    assertThat(new BigDecimal("100").multiply(new BigDecimal("1.6333")).setScale(2, java.math.RoundingMode.DOWN)).isEqualByComparingTo(l.get(1L).payout());
  }

  @Test
  void 两笔合成一个持仓_只舍入一次() {
    // 架构方案第 4.5 节原注释写"60、40 分别算：39.99 + 26.66 = 66.65"是算错了（S4 发现，F-6）：60 × 4,000 ÷ 6,000 = 40.00 正好整除，分别算也是 66.66。
    assertThat(PoolMath.gross(new BigDecimal("60"), new BigDecimal("4000"), new BigDecimal("6000"))).isEqualByComparingTo("40.00");
    assertThat(PoolMath.gross(new BigDecimal("40"), new BigDecimal("4000"), new BigDecimal("6000"))).isEqualByComparingTo("26.66");
    assertThat(PoolMath.gross(new BigDecimal("100"), new BigDecimal("4000"), new BigDecimal("6000"))).isEqualByComparingTo("66.66");
    // 分别算会差的例子：两笔 0.01 各自算盈利都是 0，合成 0.02 算 0.01（L = 1、P_W = 2 时）
    assertThat(PoolMath.gross(new BigDecimal("0.01"), new BigDecimal("1"), new BigDecimal("2"))).isEqualByComparingTo("0.00");
    assertThat(PoolMath.gross(new BigDecimal("0.02"), new BigDecimal("1"), new BigDecimal("2"))).isEqualByComparingTo("0.01");
  }

  @Test
  void 费率改成8_按新费率结算_AC37() {
    SettleMath.Result r = SettleMath.settle(EXAMPLE, HOME, new BigDecimal("0.08"));
    Map<Long, SettleMath.Line> l = byKey(r);
    assertThat(l.get(1L).fee()).isEqualByComparingTo("5.33"); // 66.66 × 8% = 5.3328 → 5.33
    assertThat(l.get(1L).payout()).isEqualByComparingTo("161.33");
    assertThat(l.get(2L).payout()).isEqualByComparingTo("4033.33"); // 2,500 + 1,666.66 − 133.33
    assertThat(l.get(3L).payout()).isEqualByComparingTo("5485.33"); // 3,400 + 2,266.66 − 181.33
    assertThat(r.sumFee()).isEqualByComparingTo("319.99");
    assertThat(r.dust()).isEqualByComparingTo("0.02");
    assertThat(r.sumPayout().add(r.sumFee()).add(r.dust())).isEqualByComparingTo("10000.00");
    assertThat(r.conserved()).isTrue();
  }

  @Test
  void 费率0_不收抽成_只有尾差归平台() {
    SettleMath.Result r = SettleMath.settle(EXAMPLE, HOME, BigDecimal.ZERO);
    assertThat(r.sumFee()).isEqualByComparingTo("0");
    assertThat(r.platformIncome()).isEqualByComparingTo("0.02");
    assertThat(r.conserved()).isTrue();
  }

  @Test
  void E1_没人押赢方_不算_要作废() {
    SettleMath.Result r = SettleMath.settle(List.of(pos(1, HOME, "10"), pos(2, AWAY, "20")), DRAW, new BigDecimal("0.05"));
    assertThat(r.noWinner()).isTrue();
    assertThat(r.lines()).isEmpty();
    assertThat(r.conserved()).as("没人押赢方时不能派彩").isFalse();
  }

  @Test
  void 只有赢方_L为0_拿回本金_不收费() {
    SettleMath.Result r = SettleMath.settle(List.of(pos(1, HOME, "10.00"), pos(2, HOME, "20.00")), HOME, new BigDecimal("0.05"));
    assertThat(r.loserPool()).isEqualByComparingTo("0");
    assertThat(r.lines()).allSatisfy(x -> {
      assertThat(x.gross()).isEqualByComparingTo("0");
      assertThat(x.payout()).isEqualByComparingTo(x.stake().amount());
    });
    assertThat(r.dust()).isEqualByComparingTo("0");
    assertThat(r.conserved()).isTrue();
  }

  @Test
  void 参数不对直接拒绝() {
    assertThatThrownBy(() -> SettleMath.settle(EXAMPLE, HOME, new BigDecimal("-0.01"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SettleMath.settle(EXAMPLE, HOME, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SettleMath.settle(List.of(pos(1, HOME, "0")), HOME, new BigDecimal("0.05"))).isInstanceOf(IllegalArgumentException.class);
  }
}
