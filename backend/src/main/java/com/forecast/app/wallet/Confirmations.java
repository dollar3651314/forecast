package com.forecast.app.wallet;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import org.springframework.stereotype.Component;

/**
 * 充值需要的确认数：后台系统配置的值，只能比 application.yml 的值大（后台方案 G7：调低会增加被链上回滚的风险）。
 */
@Component
public class Confirmations {

  private final SystemConfig config;
  private final WalletProperties props;

  Confirmations(SystemConfig config, WalletProperties props) {
    this.config = config;
    this.props = props;
  }

  public int of(Chain chain) {
    WalletProperties.ChainConfig cfg = props.chain(chain);
    int base = cfg == null ? 0 : cfg.confirmations();
    ConfigKey key = switch (chain) {
      case ERC20 -> ConfigKey.DEPOSIT_CONFIRMATIONS_ERC20;
      case TRC20 -> ConfigKey.DEPOSIT_CONFIRMATIONS_TRC20;
      case BSC -> ConfigKey.DEPOSIT_CONFIRMATIONS_BSC;
    };
    return Math.max(base, config.integer(key, base));
  }
}
