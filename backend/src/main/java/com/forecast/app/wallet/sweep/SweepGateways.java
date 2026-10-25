package com.forecast.app.wallet.sweep;

import com.forecast.app.wallet.Chain;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 每条链用哪个 {@link SweepGateway}：启动时 {@link SweepSetup} 按配置注册真实实现；测试里注册链替身。没有的链不能归集。 */
@Component
class SweepGateways {

  private final Map<Chain, SweepGateway> gateways = new ConcurrentHashMap<>();

  void register(Chain chain, SweepGateway g) {
    gateways.put(chain, g);
  }

  void remove(Chain chain) {
    gateways.remove(chain);
  }

  Optional<SweepGateway> get(Chain chain) {
    return Optional.ofNullable(gateways.get(chain));
  }
}
