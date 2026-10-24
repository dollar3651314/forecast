package com.forecast.app.wallet.withdraw;

import com.forecast.app.wallet.Chain;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 每条链用哪个广播器。S13b 按热钱包配置注册真实实现；测试注册替身。没有广播器的链，提现停在"已审核待上链"。 */
@Component
public class WithdrawSenders {

  private final Map<Chain, WithdrawSender> senders = new ConcurrentHashMap<>();

  public void register(Chain chain, WithdrawSender sender) {
    senders.put(chain, sender);
  }

  public void remove(Chain chain) {
    senders.remove(chain);
  }

  Optional<WithdrawSender> get(Chain chain) {
    return Optional.ofNullable(senders.get(chain));
  }
}
