package com.forecast.app.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.common.error.BizException;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.resolve.api.ResolveDeskApi;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * 平台裁决的操作 × 市场状态穷举（forecast S7；AC-10 的业务层：需求第 3.2 节图上 S7 的边只能由对应的操作、从对应的状态触发）。
 * 10 个状态各建一个市场，每种操作各试一次（每次用新的市场），只有下表的状态成功，其余全部拒绝、状态不变：
 * <pre>
 * 提议、直接确认、标记延期 / 腰斩      待裁决
 * 人工作废                           待裁决、公示中、争议中（第 15～17 条边）
 * 提交争议                           公示中（第 13 条边）、争议中（再提交，不跳转）
 * 处理争议                           争议中（第 14、17 条边）
 * </pre>
 * 状态机本身的 10 × 10 穷举在 market 的 MarketStateMachineTest / MarketTransitionDbTest。
 */
class PlatformStatesTest extends PlatformTestSupport {

  record Prepared(Market m, User a, User b) {}

  /** 建一个停在 state 的平台裁决市场（A 押选项 1、B 押选项 2；公示中以后的是 A 自己没提交过争议） */
  Prepared prepare(MarketStatus state) throws Exception {
    User a = user("100");
    User b = user("100");
    if (state == MarketStatus.DRAFT) {
      Instant now = Instant.now(clock);
      Instant close = now.plus(Duration.ofMinutes(1));
      MarketAdminApi.Detail d = admin.createDraft(new MarketAdminApi.DraftInput("MACRO", tx("草稿"), tx("规则"), "PLATFORM", "a", "b", null,
          now.minus(Duration.ofMinutes(1)).toString(), close.toString(), close.toString(), close.plus(Duration.ofHours(1)).toString(), null, 2, null,
          List.of(new MarketAdminApi.OptionInput(tx("是"), tx("是")), new MarketAdminApi.OptionInput(tx("否"), tx("否")))), 1);
      long id = jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, Long.parseLong(d.publicNo()));
      return new Prepared(new Market(id, d.publicNo(), d.options().stream().map(MarketAdminApi.OptionView::optionNo).toList()), a, b);
    }
    Market m = platformMarket(2, 2);
    bet(a.id(), m, 0, "60");
    bet(b.id(), m, 1, "40");
    switch (state) {
      case LISTED -> {}
      case PAUSED -> admin.pause(no(m), 1, "暂停");
      case CANCELLED -> admin.cancel(no(m), 1, "取消");
      case CLOSED -> {
        advance(Duration.between(Instant.now(clock), closeAt(m)));
        marketService.closeDue();
      }
      default -> {
        closeAndEnd(m);
        switch (state) {
          case PENDING_RESOLUTION -> {}
          case VOIDED -> desk.voidMarket(no(m), "作废", 1);
          default -> {
            publicize(m, 0, 1);
            if (state == MarketStatus.DISPUTED || state == MarketStatus.SETTLED) {
              if (state == MarketStatus.DISPUTED) assertThat(tryDispute(b.id(), m, m.options().get(1), "不对")).isEqualTo("OK");
              if (state == MarketStatus.SETTLED) expire(m, Duration.ZERO);
            }
          }
        }
      }
    }
    assertThat(status(m)).as("准备好的状态").isEqualTo(state.name());
    return new Prepared(m, a, b);
  }

  static final Map<String, Set<MarketStatus>> ALLOWED = new LinkedHashMap<>();

  static {
    ALLOWED.put("propose", EnumSet.of(MarketStatus.PENDING_RESOLUTION));
    ALLOWED.put("decide", EnumSet.of(MarketStatus.PENDING_RESOLUTION));
    ALLOWED.put("hold", EnumSet.of(MarketStatus.PENDING_RESOLUTION));
    ALLOWED.put("void", EnumSet.of(MarketStatus.PENDING_RESOLUTION, MarketStatus.PUBLICIZING, MarketStatus.DISPUTED));
    ALLOWED.put("dispute", EnumSet.of(MarketStatus.PUBLICIZING, MarketStatus.DISPUTED));
    ALLOWED.put("decideDisputes", EnumSet.of(MarketStatus.DISPUTED));
  }

  Map<String, BiFunction<Prepared, Long, Object>> ops() {
    Map<String, BiFunction<Prepared, Long, Object>> ops = new LinkedHashMap<>();
    ops.put("propose", (p, admin) -> desk.propose(no(p.m()), result(p.m(), 0), admin));
    ops.put("decide", (p, admin) -> desk.decide(no(p.m()), result(p.m(), 0), admin, true));
    ops.put("hold", (p, admin) -> desk.hold(no(p.m()), "abandoned", "腰斩", admin));
    ops.put("void", (p, admin) -> {
      desk.voidMarket(no(p.m()), "作废", admin);
      return null;
    });
    ops.put("dispute", (p, admin) -> desk.dispute(no(p.m()), p.a().id(), new ResolveDeskApi.DisputeInput("void", "无法判定", null), Instant.now(clock)));
    ops.put("decideDisputes", (p, admin) -> desk.decideDisputes(no(p.m()), new ResolveDeskApi.DecisionInput("uphold", null, "维持"), admin));
    return ops;
  }

  @Test
  void 六种操作乘十个状态_只有图上的状态能做_其余拒绝且状态不变() throws Exception {
    List<String> passed = new ArrayList<>();
    int rejected = 0;
    for (Map.Entry<String, BiFunction<Prepared, Long, Object>> op : ops().entrySet()) {
      for (MarketStatus state : MarketStatus.values()) {
        Prepared p = prepare(state);
        String before = status(p.m());
        String result;
        try {
          op.getValue().apply(p, 1L);
          result = "OK";
        } catch (BizException e) {
          result = e.code();
        }
        String cell = op.getKey() + "@" + state;
        if ("OK".equals(result)) {
          passed.add(cell);
        } else {
          rejected++;
          assertThat(status(p.m())).as(cell + " 被拒（" + result + "）以后状态不变").isEqualTo(before);
          assertThat(result).as(cell).isIn("NOT_FOUND", "MARKET_NOT_RESOLVABLE", "MARKET_NOT_VOIDABLE", "DISPUTE_CLOSED", "DISPUTE_NOT_OPEN");
        }
      }
    }
    List<String> expected = new ArrayList<>();
    ALLOWED.forEach((op, states) -> states.forEach(s -> expected.add(op + "@" + s)));
    assertThat(passed).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(rejected).isEqualTo(6 * 10 - expected.size());
  }
}
