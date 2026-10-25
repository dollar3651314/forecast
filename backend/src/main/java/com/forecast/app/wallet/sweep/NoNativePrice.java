package com.forecast.app.wallet.sweep;

import com.forecast.app.wallet.Chain;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** {@link NativePriceSource} 的默认实现：没有价格来源，一律返回空（见接口说明）。 */
@Component
class NoNativePrice implements NativePriceSource {

  @Override
  public Optional<BigDecimal> usd(Chain chain) {
    return Optional.empty();
  }
}
