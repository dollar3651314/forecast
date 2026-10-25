package com.forecast.app.wallet.withdraw;

import com.forecast.app.wallet.Chain;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 提现参数（application.yml 的 forecast.wallet.withdraw），默认值沿用复制源和 Amos 确认的手续费；后台系统配置有值时以后台为准（{@link WithdrawLimits}）。
 * 单笔最小提现默认 100 USDT（需求 RQ-16，S2；后台保存时必须大于每条链的手续费，见 admin.system.SystemService）。
 */
@ConfigurationProperties("forecast.wallet.withdraw")
record WithdrawProperties(
    @DefaultValue("100") BigDecimal minAmount,
    @DefaultValue("10000") BigDecimal maxPerTx,
    @DefaultValue("30000") BigDecimal dailyLimit,
    @DefaultValue("3000") BigDecimal largeAmount,
    @DefaultValue("6h") Duration largeDelay,
    @DefaultValue("2h") Duration cooling,
    @DefaultValue("24h") Duration whitelistDelay,
    @DefaultValue("10") int maxWhitelist,
    Map<Chain, BigDecimal> fees) {

  BigDecimal fee(Chain network) {
    return fees == null ? BigDecimal.ZERO : fees.getOrDefault(network, BigDecimal.ZERO);
  }
}
