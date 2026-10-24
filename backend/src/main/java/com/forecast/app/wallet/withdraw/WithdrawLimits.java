package com.forecast.app.wallet.withdraw;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.wallet.Chain;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 提现参数的当前值：后台系统配置优先（后台方案 G7），没有时用 application.yml（{@link WithdrawProperties}）。 */
@Component
class WithdrawLimits {

  private final SystemConfig config;
  private final WithdrawProperties defaults;

  WithdrawLimits(SystemConfig config, WithdrawProperties defaults) {
    this.config = config;
    this.defaults = defaults;
  }

  WithdrawProperties current() {
    Map<Chain, BigDecimal> fees = new EnumMap<>(Chain.class);
    fees.put(Chain.ERC20, config.decimal(ConfigKey.WITHDRAW_FEE_ERC20, defaults.fee(Chain.ERC20)));
    fees.put(Chain.TRC20, config.decimal(ConfigKey.WITHDRAW_FEE_TRC20, defaults.fee(Chain.TRC20)));
    fees.put(Chain.BSC, config.decimal(ConfigKey.WITHDRAW_FEE_BSC, defaults.fee(Chain.BSC)));
    return new WithdrawProperties(
        config.decimal(ConfigKey.WITHDRAW_MIN_AMOUNT, defaults.minAmount()),
        config.decimal(ConfigKey.WITHDRAW_MAX_SINGLE, defaults.maxPerTx()),
        config.decimal(ConfigKey.WITHDRAW_DAILY_LIMIT, defaults.dailyLimit()),
        config.decimal(ConfigKey.WITHDRAW_LARGE_THRESHOLD, defaults.largeAmount()),
        config.hours(ConfigKey.WITHDRAW_LARGE_DELAY_HOURS, defaults.largeDelay()),
        config.hours(ConfigKey.WITHDRAW_COOLING_HOURS, defaults.cooling()),
        config.hours(ConfigKey.WHITELIST_EFFECTIVE_HOURS, defaults.whitelistDelay()),
        config.integer(ConfigKey.WHITELIST_MAX_ADDRESSES, defaults.maxWhitelist()),
        fees);
  }
}
