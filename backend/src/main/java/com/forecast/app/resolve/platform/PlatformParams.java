package com.forecast.app.resolve.platform;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketCategory;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 平台裁决的时限和窗口（需求第 4.4 节"各环节时限（默认，后台可改）"；参数设置 {@code betting_params:edit}，迁移 V10、V15）。读不到或不合法时用需求写的默认值。
 */
@Component
public class PlatformParams {

  /** E6 延期、E7 腰斩最多等多久（需求第 3.6 节、RQ-14：48 小时，已定，不做成参数） */
  public static final Duration HOLD_MAX = Duration.ofHours(48);

  private final SystemConfig config;

  PlatformParams(SystemConfig config) {
    this.config = config;
  }

  /** 提议时限：体育、加密转人工的进入裁决台后 2 小时；时事事件结束后 24 小时（进入待裁决的时间就是事件结束） */
  public Duration proposeWindow(MarketCategory c) {
    ConfigKey k = switch (c) {
      case FOOTBALL -> ConfigKey.RESOLVE_PROPOSE_FOOTBALL;
      case NBA -> ConfigKey.RESOLVE_PROPOSE_NBA;
      case CRYPTO_15M -> ConfigKey.RESOLVE_PROPOSE_CRYPTO_15M;
      case CRYPTO_1H -> ConfigKey.RESOLVE_PROPOSE_CRYPTO_1H;
      case MACRO -> ConfigKey.RESOLVE_PROPOSE_MACRO;
    };
    return positive(k, c == MarketCategory.MACRO ? Duration.ofHours(24) : Duration.ofHours(2));
  }

  /** 确认时限：提议后 2 小时 */
  public Duration confirmWindow() {
    return positive(ConfigKey.RESOLVE_CONFIRM_MINUTES, Duration.ofHours(2));
  }

  /** 争议处理时限：第一个争议提交后 24 小时 */
  public Duration disputeHandleWindow() {
    return positive(ConfigKey.DISPUTE_HANDLE_MINUTES, Duration.ofHours(24));
  }

  /**
   * 争议窗口：市场上写的（平台裁决的市场建的时候按品类带出）；没有时（自动裁决转人工的）按品类的参数，体育 2 小时、时事 24 小时、加密 2 小时（D-75）。
   */
  public Duration disputeWindow(MarketApi.PlatformRef m) {
    if (m.disputeWindowMin() != null && m.disputeWindowMin() > 0) return Duration.ofMinutes(m.disputeWindowMin());
    ConfigKey k = switch (m.category()) {
      case FOOTBALL -> ConfigKey.DISPUTE_WINDOW_FOOTBALL;
      case NBA -> ConfigKey.DISPUTE_WINDOW_NBA;
      case CRYPTO_15M -> ConfigKey.DISPUTE_WINDOW_CRYPTO_15M;
      case CRYPTO_1H -> ConfigKey.DISPUTE_WINDOW_CRYPTO_1H;
      case MACRO -> ConfigKey.DISPUTE_WINDOW_MACRO;
    };
    return positive(k, m.category() == MarketCategory.MACRO ? Duration.ofHours(24) : Duration.ofHours(2));
  }

  private Duration positive(ConfigKey k, Duration fallback) {
    Duration d = config.minutes(k, fallback);
    return d == null || d.isNegative() || d.isZero() ? fallback : d;
  }
}
