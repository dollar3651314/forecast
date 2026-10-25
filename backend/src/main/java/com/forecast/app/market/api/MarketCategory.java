package com.forecast.app.market.api;

import com.forecast.app.common.config.ConfigKey;

/**
 * 品类（需求说明书第 4.1 节）。每个品类有自己的默认费率、单客户上限、争议窗口（参数设置页，迁移 V10）。
 */
public enum MarketCategory {
  FOOTBALL(ConfigKey.FEE_FOOTBALL, ConfigKey.USER_CAP_FOOTBALL, ConfigKey.DISPUTE_WINDOW_FOOTBALL),
  NBA(ConfigKey.FEE_NBA, ConfigKey.USER_CAP_NBA, ConfigKey.DISPUTE_WINDOW_NBA),
  CRYPTO_15M(ConfigKey.FEE_CRYPTO_15M, ConfigKey.USER_CAP_CRYPTO_15M, null),
  CRYPTO_1H(ConfigKey.FEE_CRYPTO_1H, ConfigKey.USER_CAP_CRYPTO_1H, null),
  MACRO(ConfigKey.FEE_MACRO, ConfigKey.USER_CAP_MACRO, ConfigKey.DISPUTE_WINDOW_MACRO);

  /** 默认费率（百分比） */
  public final ConfigKey feeKey;
  /** 单客户单市场上限的默认值 */
  public final ConfigKey userCapKey;
  /** 争议窗口的默认值（分钟）；加密是自动裁决，没有 */
  public final ConfigKey disputeWindowKey;

  MarketCategory(ConfigKey feeKey, ConfigKey userCapKey, ConfigKey disputeWindowKey) {
    this.feeKey = feeKey;
    this.userCapKey = userCapKey;
    this.disputeWindowKey = disputeWindowKey;
  }

  /** 不认识返回 null */
  public static MarketCategory of(String s) {
    if (s == null) return null;
    for (MarketCategory c : values()) if (c.name().equals(s.strip())) return c;
    return null;
  }
}
