package com.forecast.app.market.core;

import static com.forecast.app.market.api.MarketStatus.CANCELLED;
import static com.forecast.app.market.api.MarketStatus.CLOSED;
import static com.forecast.app.market.api.MarketStatus.DISPUTED;
import static com.forecast.app.market.api.MarketStatus.DRAFT;
import static com.forecast.app.market.api.MarketStatus.LISTED;
import static com.forecast.app.market.api.MarketStatus.PAUSED;
import static com.forecast.app.market.api.MarketStatus.PENDING_RESOLUTION;
import static com.forecast.app.market.api.MarketStatus.PUBLICIZING;
import static com.forecast.app.market.api.MarketStatus.SETTLED;
import static com.forecast.app.market.api.MarketStatus.VOIDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.common.error.BizException;
import com.forecast.app.market.api.MarketStatus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 状态机的穷举测试（AC-10，技术方案第 17 节）：10 × 10 = 100 种组合，只有需求第 3.2 节图上的 17 条边通过，其余 83 种（含原地跳转）全部拒绝并返回 ILLEGAL_TRANSITION。
 * 数据库上的条件更新在 {@link MarketTransitionDbTest} 同样穷举一遍。
 */
class MarketStateMachineTest {

  /** 需求第 3.2 节图上的 17 条边（逐条照图抄写，不从代码生成） */
  static final Set<String> EDGES = new LinkedHashSet<>(List.of(
      "DRAFT>LISTED",
      "LISTED>PAUSED", "PAUSED>LISTED",
      "LISTED>CLOSED", "PAUSED>CLOSED",
      "LISTED>CANCELLED", "PAUSED>CANCELLED",
      "CLOSED>VOIDED", "CLOSED>PENDING_RESOLUTION",
      "PENDING_RESOLUTION>SETTLED", "PENDING_RESOLUTION>PUBLICIZING",
      "PUBLICIZING>SETTLED", "PUBLICIZING>DISPUTED", "DISPUTED>SETTLED",
      "PENDING_RESOLUTION>VOIDED", "PUBLICIZING>VOIDED", "DISPUTED>VOIDED"));

  @Test
  void 一共10个状态_17条边() {
    assertThat(MarketStatus.values()).hasSize(10);
    assertThat(EDGES).hasSize(17);
    assertThat(MarketStateMachine.edgeCount()).isEqualTo(17);
  }

  @Test
  void 穷举100种组合_只有17条边通过_其余83种全部拒绝() {
    List<String> passed = new ArrayList<>();
    int rejected = 0;
    for (MarketStatus from : MarketStatus.values()) {
      for (MarketStatus to : MarketStatus.values()) {
        String edge = from + ">" + to;
        if (MarketStateMachine.allowed(from, to)) {
          passed.add(edge);
          MarketStateMachine.check(from, to);
        } else {
          rejected++;
          assertThatThrownBy(() -> MarketStateMachine.check(from, to)).as(edge).isInstanceOf(BizException.class)
              .extracting(e -> ((BizException) e).code()).isEqualTo("ILLEGAL_TRANSITION");
        }
      }
    }
    assertThat(passed).containsExactlyInAnyOrderElementsOf(EDGES);
    assertThat(rejected).isEqualTo(83);
  }

  @Test
  void 终点状态出不去_需求举的两个反例被拒() {
    for (MarketStatus end : List.of(SETTLED, CANCELLED, VOIDED)) {
      assertThat(end.terminal()).isTrue();
      for (MarketStatus to : MarketStatus.values()) assertThat(MarketStateMachine.allowed(end, to)).as(end + ">" + to).isFalse();
    }
    // AC-10 判定口径里的两个例子
    assertThat(MarketStateMachine.allowed(SETTLED, PUBLICIZING)).isFalse();
    assertThat(MarketStateMachine.allowed(DRAFT, CLOSED)).isFalse();
    // 几个容易写错的
    assertThat(MarketStateMachine.allowed(CLOSED, CANCELLED)).as("截止后只能作废，不能取消").isFalse();
    assertThat(MarketStateMachine.allowed(CLOSED, LISTED)).as("截止后不能重新开放").isFalse();
    assertThat(MarketStateMachine.allowed(PAUSED, PENDING_RESOLUTION)).isFalse();
    assertThat(MarketStateMachine.allowed(DRAFT, CANCELLED)).as("草稿直接删除，不走取消").isFalse();
    assertThat(MarketStateMachine.allowed(PENDING_RESOLUTION, DISPUTED)).isFalse();
    assertThat(MarketStateMachine.allowed(null, LISTED)).isFalse();
  }
}
