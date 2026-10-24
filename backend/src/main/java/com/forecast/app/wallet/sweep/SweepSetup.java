package com.forecast.app.wallet.sweep;

import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.SweepEvmProperties;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.wallet.address.AddressService;
import com.forecast.app.wallet.address.ForwarderSchemes;
import com.forecast.app.wallet.chain.JsonRpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 启动时：给配置了节点和 USDT 合约的以太坊、BSC 接上归集用的节点访问；合约地址参数配好了时把已有用户的以太坊体系地址换成合约地址（SW-9）。
 * 出任何问题都只记日志，<b>不影响启动</b>（归集是业务功能，约定 24）。
 */
@Component
class SweepSetup {

  private static final Logger log = LoggerFactory.getLogger(SweepSetup.class);

  private final WalletProperties wallet;
  private final SweepEvmProperties props;
  private final SweepGateways gateways;
  private final ForwarderSchemes schemes;
  private final AddressService addresses;
  private final ObjectMapper json;

  SweepSetup(WalletProperties wallet, SweepEvmProperties props, SweepGateways gateways, ForwarderSchemes schemes, AddressService addresses, ObjectMapper json) {
    this.wallet = wallet;
    this.props = props;
    this.gateways = gateways;
    this.schemes = schemes;
    this.addresses = addresses;
    this.json = json;
  }

  @EventListener(ApplicationReadyEvent.class)
  void start() {
    try {
      for (Chain c : new Chain[] {Chain.ERC20, Chain.BSC}) {
        WalletProperties.ChainConfig cfg = wallet.chain(c);
        if (cfg == null || cfg.rpcUrl() == null || cfg.rpcUrl().isBlank() || !cfg.scanReady()) continue;
        gateways.register(c, new EvmSweepGateway(new JsonRpc(cfg.rpcUrl().strip(), json), c == Chain.ERC20));
      }
      if (schemes.current().isPresent() && props.migrateOnStart()) addresses.migrateAll();
    } catch (RuntimeException e) {
      log.error("资金归集启动准备出错（不影响其他功能）", e);
    }
  }
}
