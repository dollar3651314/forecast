package com.forecast.app.wallet.withdraw;

import com.forecast.app.wallet.Chain;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 热钱包余额查询（application.yml 的 forecast.wallet.hot-balance，2026-10-17）。
 * <b>告警下限不在这里</b>：六个下限是后台"系统配置"页的配置项（{@code hotWallet.*}，{@code common.config.ConfigKey}），默认 0 = 不告警
 * （Amos 2026-10-17："告警的默认下限让我可以设置，现在设置成零"）。
 *
 * @param enabled 是否定时查询（默认开；没有配置热钱包的链本来就不查）
 * @param interval 多久查一次余额（默认 5 分钟）
 */
@ConfigurationProperties("forecast.wallet.hot-balance")
public record HotBalanceProperties(@DefaultValue("true") boolean enabled, @DefaultValue("5m") Duration interval) {

  /** 默认值（测试里手动构造时用） */
  public static HotBalanceProperties defaults() {
    return new HotBalanceProperties(true, Duration.ofMinutes(5));
  }

  /** 手续费币的名字 */
  public static String gasAsset(Chain c) {
    return switch (c) {
      case ERC20 -> "ETH";
      case BSC -> "BNB";
      case TRC20 -> "TRX";
    };
  }

  /** 手续费币的小数位：ETH、BNB 18 位（wei），TRX 6 位（sun） */
  static int gasDecimals(Chain c) {
    return c == Chain.TRC20 ? 6 : 18;
  }
}
