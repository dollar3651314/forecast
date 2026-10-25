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

import com.forecast.app.common.error.BizException;
import com.forecast.app.market.api.MarketStatus;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 市场状态机（需求说明书第 3.2 节的图，技术方案/v1-架构方案.md 第 5.1 节）：<b>只有下面 17 条边</b>，其余 10 × 10 − 17 种组合（含"原地跳转"）一律拒绝（AC-10）。
 * <pre>
 *  1 DRAFT → LISTED                 上架（market:publish + 检查清单）；加密自动生成（S6）
 *  2 LISTED → PAUSED                暂停（market:pause）
 *  3 PAUSED → LISTED                恢复（market:pause）
 *  4 LISTED → CLOSED                截止任务（now ≥ close_at）
 *  5 PAUSED → CLOSED                截止任务（暂停中也截止，需求第 3.2 节第 2 条）
 *  6 LISTED → CANCELLED             截止前取消（market:cancel），退款
 *  7 PAUSED → CANCELLED             同上
 *  8 CLOSED → VOIDED                截止时有效选项 &lt; 2（E3、E4），退款
 *  9 CLOSED → PENDING_RESOLUTION    事件结束任务（now ≥ event_end_at）
 * 10 PENDING_RESOLUTION → SETTLED   自动裁决成功（S6）
 * 11 PENDING_RESOLUTION → PUBLICIZING  确认结果（S7）
 * 12 PUBLICIZING → SETTLED          公示期满（S7）
 * 13 PUBLICIZING → DISPUTED         客户提交争议（S7）
 * 14 DISPUTED → SETTLED             处理争议（S7）
 * 15 PENDING_RESOLUTION → VOIDED    作废（S7）
 * 16 PUBLICIZING → VOIDED           作废（S7）
 * 17 DISPUTED → VOIDED              作废（S7）
 * </pre>
 * 每次跳转由 {@link MarketService} 做成"带旧状态的条件更新"（{@code … WHERE id = ? AND status = 旧状态}），同一事务写状态历史。
 */
final class MarketStateMachine {

  static final Map<MarketStatus, Set<MarketStatus>> ALLOWED;

  static {
    Map<MarketStatus, Set<MarketStatus>> m = new EnumMap<>(MarketStatus.class);
    for (MarketStatus s : MarketStatus.values()) m.put(s, EnumSet.noneOf(MarketStatus.class));
    m.get(DRAFT).add(LISTED);
    m.get(LISTED).addAll(EnumSet.of(PAUSED, CLOSED, CANCELLED));
    m.get(PAUSED).addAll(EnumSet.of(LISTED, CLOSED, CANCELLED));
    m.get(CLOSED).addAll(EnumSet.of(VOIDED, PENDING_RESOLUTION));
    m.get(PENDING_RESOLUTION).addAll(EnumSet.of(SETTLED, PUBLICIZING, VOIDED));
    m.get(PUBLICIZING).addAll(EnumSet.of(SETTLED, DISPUTED, VOIDED));
    m.get(DISPUTED).addAll(EnumSet.of(SETTLED, VOIDED));
    m.replaceAll((k, v) -> Collections.unmodifiableSet(v));
    ALLOWED = Collections.unmodifiableMap(m);
  }

  private MarketStateMachine() {}

  static boolean allowed(MarketStatus from, MarketStatus to) {
    return from != null && to != null && ALLOWED.get(from).contains(to);
  }

  /** 不是 17 条边之一时抛 {@code ILLEGAL_TRANSITION}（HTTP 409） */
  static void check(MarketStatus from, MarketStatus to) {
    if (!allowed(from, to)) throw new BizException("ILLEGAL_TRANSITION", "市场状态不能从 " + from + " 变为 " + to);
  }

  static int edgeCount() {
    return ALLOWED.values().stream().mapToInt(Set::size).sum();
  }
}
