package com.forecast.app.pool;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.pool.api.Odds;
import com.forecast.app.pool.api.PoolMath;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 前后端共用的测试向量 {@code pool/pool-vectors.json}（技术方案第 4.1 节；生成脚本 {@code scripts/gen-pool-vectors.py} 用精确分数独立算出）：
 * 后端 {@link Odds}、{@link PoolMath} 逐条逐分一致。客户端 {@code web/client/src/lib/pool.test.ts} 读同一个文件。
 */
class PoolMathVectorsTest {

  static JsonNode vectors() throws Exception {
    try (InputStream in = PoolMathVectorsTest.class.getResourceAsStream("/pool/pool-vectors.json")) {
      return new ObjectMapper().readTree(in);
    }
  }

  @Test
  void 每一组都逐分一致() throws Exception {
    JsonNode cases = vectors().get("cases");
    assertThat(cases.size()).as("需求第 3.5 节两个例子、费率 8%、边界 + 500 组随机").isGreaterThan(500);
    List<String> diffs = new ArrayList<>();
    int checks = 0;
    for (JsonNode c : cases) {
      String name = c.get("name").asString();
      BigDecimal r = new BigDecimal(c.get("feeRate").asString());
      List<BigDecimal> pools = new ArrayList<>();
      for (JsonNode p : c.get("pools")) pools.add(new BigDecimal(p.asString()));
      BigDecimal t = pools.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      if (t.compareTo(new BigDecimal(c.get("total").asString())) != 0) diffs.add(name + " total");
      for (int i = 0; i < pools.size(); i++) {
        checks += 3;
        eq(diffs, name + " pctList[" + i + "]", c.get("pctList").get(i).asString(), Odds.percent(pools.get(i), t, 0));
        eq(diffs, name + " pctDetail[" + i + "]", c.get("pctDetail").get(i).asString(), Odds.percent(pools.get(i), t, 1));
        eq(diffs, name + " mult[" + i + "]", c.get("mult").get(i).asString(), Odds.multiplier(pools.get(i), t, r));
      }
      for (JsonNode b : c.get("bets")) {
        checks++;
        int o = b.get("option").asInt();
        eq(diffs, name + " bet " + b, b.get("expectedReturn").asString(), Odds.expectedReturn(pools.get(o), t, new BigDecimal(b.get("amount").asString()), r).toPlainString());
      }
      for (JsonNode p : c.get("positions")) {
        checks += 3;
        int o = p.get("option").asInt();
        PoolMath.Payout x = PoolMath.payout(new BigDecimal(p.get("amount").asString()), pools.get(o), t, r);
        eq(diffs, name + " gross " + p, p.get("gross").asString(), x.gross().toPlainString());
        eq(diffs, name + " fee " + p, p.get("fee").asString(), x.fee().toPlainString());
        eq(diffs, name + " payout " + p, p.get("payout").asString(), x.payout().toPlainString());
      }
    }
    assertThat(diffs).as("和向量不一致的项（共核对 " + checks + " 项）").isEmpty();
    assertThat(checks).isGreaterThan(5000);
  }

  @Test
  void 向量里有需求第35节的两个例子和费率8() throws Exception {
    JsonNode cases = vectors().get("cases");
    JsonNode ex = cases.get(0);
    assertThat(ex.get("feeRate").asString()).isEqualTo("0.05");
    assertThat(ex.get("pctList").toString()).isEqualTo("[\"60%\",\"15%\",\"25%\"]");
    assertThat(ex.get("mult").toString()).isEqualTo("[\"1.63\",\"6.38\",\"3.85\"]");
    assertThat(ex.get("bets").get(0).get("expectedReturn").asString()).isEqualTo("162.29");
    assertThat(ex.get("positions").get(0).get("payout").asString()).isEqualTo("163.33");
    assertThat(ex.get("positions").get(1).get("payout").asString()).isEqualTo("4083.33");
    assertThat(ex.get("positions").get(2).get("payout").asString()).isEqualTo("5553.33");
    JsonNode eight = cases.get(1);
    assertThat(eight.get("feeRate").asString()).isEqualTo("0.08");
    assertThat(eight.get("positions").get(0).get("payout").asString()).isEqualTo("161.33");
  }

  private static void eq(List<String> diffs, String what, String expected, String actual) {
    if (!expected.equals(actual)) diffs.add(what + "：期望 " + expected + "，实际 " + actual);
  }
}
