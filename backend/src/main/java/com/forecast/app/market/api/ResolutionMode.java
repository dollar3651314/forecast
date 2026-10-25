package com.forecast.app.market.api;

/** 裁决方式（需求说明书第 4.1 节）：自动（加密、体育的数据源）/ 平台裁决（公示 + 争议窗口）。 */
public enum ResolutionMode {
  AUTO,
  PLATFORM;

  public static ResolutionMode of(String s) {
    if (s == null) return null;
    for (ResolutionMode m : values()) if (m.name().equals(s.strip())) return m;
    return null;
  }
}
