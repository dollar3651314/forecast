package com.forecast.app.market.api;

import java.util.Locale;

/**
 * 市场状态（需求说明书第 3.2 节，技术方案/v1-架构方案.md 第 5.1 节）。允许的跳转只有 17 条边，见 market.core.MarketStateMachine。
 * 接口里用小写（{@link #api()}）。
 */
public enum MarketStatus {
  /** 草稿：客户看不到 */
  DRAFT,
  /** 已上架：到了开放时间可以下注 */
  LISTED,
  /** 已暂停 */
  PAUSED,
  /** 截止下注 */
  CLOSED,
  /** 待裁决 */
  PENDING_RESOLUTION,
  /** 公示中（平台裁决） */
  PUBLICIZING,
  /** 争议中 */
  DISPUTED,
  /** 已结算（终点） */
  SETTLED,
  /** 已取消（终点，全额退款） */
  CANCELLED,
  /** 已作废（终点，全额退款） */
  VOIDED;

  public String api() {
    return name().toLowerCase(Locale.ROOT);
  }

  public boolean terminal() {
    return this == SETTLED || this == CANCELLED || this == VOIDED;
  }

  /** 接口里的小写名字；不认识返回 null */
  public static MarketStatus ofApi(String s) {
    if (s == null) return null;
    for (MarketStatus v : values()) if (v.api().equals(s.strip())) return v;
    return null;
  }
}
