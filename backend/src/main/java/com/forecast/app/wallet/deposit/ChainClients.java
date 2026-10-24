package com.forecast.app.wallet.deposit;

import com.forecast.app.wallet.Chain;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 每条链用哪个客户端。S12b 按配置注册真实客户端；测试里注册链替身。没有客户端的链不扫块。 */
@Component
public class ChainClients {

  private final Map<Chain, ChainClient> clients = new ConcurrentHashMap<>();

  public void register(Chain chain, ChainClient client) {
    clients.put(chain, client);
  }

  public void remove(Chain chain) {
    clients.remove(chain);
  }

  public Optional<ChainClient> get(Chain chain) {
    return Optional.ofNullable(clients.get(chain));
  }
}
