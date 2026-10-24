package com.forecast.app.wallet.deposit;

import com.forecast.app.common.health.HealthCheck;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.WalletProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 启动时按配置给每条链接上真实客户端：配置了节点地址和 USDT 合约的链才扫块。
 * 配置了节点却没有配置 USDT 合约：不扫块，健康检查报 wallet DOWN（修复 F3，架构方案 ④）。
 */
@Component
class ChainClientSetup implements HealthCheck {

  private static final Logger log = LoggerFactory.getLogger(ChainClientSetup.class);

  private final WalletProperties props;
  private final ChainClients clients;
  private final ObjectMapper json;

  ChainClientSetup(WalletProperties props, ChainClients clients, ObjectMapper json) {
    this.props = props;
    this.clients = clients;
    this.json = json;
  }

  @EventListener(ApplicationReadyEvent.class)
  void connect() {
    for (Chain c : Chain.values()) {
      WalletProperties.ChainConfig cfg = props.chain(c);
      if (cfg == null || blank(cfg.rpcUrl())) {
        log.info("{} 没有配置节点，不扫块", c);
        continue;
      }
      if (!cfg.scanReady()) {
        log.error("{} 配置了节点但没有配置 USDT 合约，不扫块（F3）", c);
        continue;
      }
      clients.register(c, c.family == Chain.Family.TRON ? new TronChainClient(cfg.rpcUrl(), json) : new EvmChainClient(cfg.rpcUrl(), json));
      log.info("{} 开始扫块，只认合约 {}", c, cfg.usdtContract());
    }
  }

  @Override
  public String name() {
    return "wallet";
  }

  /** 每条配置了节点的链都必须配置 USDT 合约 */
  @Override
  public boolean ok() {
    for (Chain c : Chain.values()) {
      WalletProperties.ChainConfig cfg = props.chain(c);
      if (cfg != null && !blank(cfg.rpcUrl()) && !cfg.scanReady()) return false;
    }
    return true;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
