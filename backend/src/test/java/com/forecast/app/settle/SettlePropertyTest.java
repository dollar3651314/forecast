package com.forecast.app.settle;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.settle.api.SettleMath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 守恒的性质测试（AC-21、AC-22；技术方案/v1-架构方案.md 第 17 节）：固定种子随机生成 1,000 个市场
 * （2～10 个选项、1～500 笔下注、金额 1～5,000 两位小数、费率 0%～20%（百分比两位小数）、同一客户同一选项的下注合成一个持仓、结果在全部选项里随机），每个市场：
 * <ul>
 *   <li>没人押赢方（P_W = 0）⇔ 作废（不派彩）；</li>
 *   <li>否则 Σ派彩 + Σ抽成 + 尾差 = T（守恒），0 ≤ 尾差 &lt; 0.01 × 赢方持仓数，平台收入 = 抽成 + 尾差 ≥ 0；</li>
 *   <li>每个赢方持仓：派彩 ≥ 本金、抽成 ≤ 盈利；输方派彩 0。</li>
 * </ul>
 * 长跑版：{@code -Dsettle.propertyMarkets=100000}（本地和 staging 运行，不在每个 PR 上跑）。
 */
class SettlePropertyTest {

  static final long SEED = 20261025L;

  @Test
  void 固定种子随机1000个市场_守恒_尾差范围_平台不亏_没人押赢方作废() {
    int markets = Integer.getInteger("settle.propertyMarkets", 1000);
    Random rnd = new Random(SEED);
    int voided = 0;
    int withDust = 0;
    int maxPositions = 0;
    for (int m = 0; m < markets; m++) {
      int options = 2 + rnd.nextInt(9);
      int bets = 1 + rnd.nextInt(500);
      int users = 1 + rnd.nextInt(Math.max(1, bets));
      BigDecimal rate = BigDecimal.valueOf(rnd.nextInt(2001), 4); // 0.0000～0.2000：百分比最多 2 位小数
      Map<String, BigDecimal> merged = new LinkedHashMap<>();
      for (int b = 0; b < bets; b++) {
        int user = rnd.nextInt(users);
        int option = 1 + rnd.nextInt(options);
        BigDecimal amount = BigDecimal.valueOf(100 + rnd.nextInt(500_000 - 100 + 1), 2); // 1.00～5,000.00
        merged.merge(user + ":" + option, amount, BigDecimal::add);
      }
      List<SettleMath.Stake> positions = new ArrayList<>();
      long key = 0;
      BigDecimal total = BigDecimal.ZERO;
      for (Map.Entry<String, BigDecimal> e : merged.entrySet()) {
        String[] uo = e.getKey().split(":");
        positions.add(new SettleMath.Stake(++key, Long.parseLong(uo[0]), Long.parseLong(uo[1]), e.getValue()));
        total = total.add(e.getValue());
      }
      maxPositions = Math.max(maxPositions, positions.size());
      long winner = 1 + rnd.nextInt(options);
      BigDecimal winnerPool = positions.stream().filter(p -> p.optionId() == winner).map(SettleMath.Stake::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
      SettleMath.Result r = SettleMath.settle(positions, winner, rate);
      String at = "市场 #" + m + "（选项 " + options + "、下注 " + bets + "、持仓 " + positions.size() + "、费率 " + rate + "、结果 " + winner + "）";
      assertThat(r.total()).as(at + " T").isEqualByComparingTo(total);
      if (winnerPool.signum() == 0) {
        assertThat(r.noWinner()).as(at + " 没人押赢方 → 作废").isTrue();
        assertThat(r.lines()).isEmpty();
        voided++;
        continue;
      }
      assertThat(r.noWinner()).as(at).isFalse();
      assertThat(r.sumPayout().add(r.sumFee()).add(r.dust())).as(at + " 守恒 ΣPay + ΣF + D = T").isEqualByComparingTo(total);
      assertThat(r.dust().signum()).as(at + " 尾差 ≥ 0").isGreaterThanOrEqualTo(0);
      assertThat(r.dust()).as(at + " 尾差 < 0.01 × 赢方持仓数").isLessThan(new BigDecimal("0.01").multiply(BigDecimal.valueOf(r.winners())));
      assertThat(r.platformIncome().signum()).as(at + " 平台收入 ≥ 0").isGreaterThanOrEqualTo(0);
      assertThat(r.platformIncome()).as(at + " 平台收入 = 抽成 + 尾差").isEqualByComparingTo(r.sumFee().add(r.dust()));
      assertThat(r.conserved()).as(at).isTrue();
      for (SettleMath.Line l : r.lines()) {
        if (l.win()) {
          assertThat(l.payout()).as(at + " 赢方派彩 ≥ 本金").isGreaterThanOrEqualTo(l.stake().amount());
          assertThat(l.fee()).as(at + " 抽成 ≤ 盈利").isLessThanOrEqualTo(l.gross());
        } else {
          assertThat(l.payout()).as(at + " 输方派彩 0").isEqualByComparingTo("0");
        }
      }
      if (r.dust().signum() > 0) withDust++;
    }
    System.out.println("[性质测试] " + markets + " 个市场：作废（没人押赢方）" + voided + " 个，有尾差 " + withDust + " 个，最多持仓 " + maxPositions);
    assertThat(voided).as("确实覆盖到没人押赢方").isPositive();
    assertThat(withDust).as("确实覆盖到有尾差的").isPositive();
  }
}
