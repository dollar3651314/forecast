package com.forecast.app.common.money;

import java.math.BigDecimal;

/** 金额规则：一律 BigDecimal、最多 8 位小数，和数据库 DECIMAL(24,8) 一致（项目地图约定 2）。 */
public final class Amounts {

  public static final int SCALE = 8;

  private Amounts() {}

  /** 校验小数位不超过 8 位并统一成 8 位；超过就拒绝，不悄悄四舍五入。 */
  public static BigDecimal normalize(BigDecimal v) {
    if (v == null) throw new IllegalArgumentException("金额不能为空");
    BigDecimal s = v.stripTrailingZeros();
    if (s.scale() > SCALE) throw new IllegalArgumentException("金额最多 " + SCALE + " 位小数：" + v.toPlainString());
    return v.setScale(SCALE);
  }
}
