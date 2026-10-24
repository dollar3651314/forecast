package com.forecast.app.wallet.withdraw;

import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.wallet.chain.JsonRpc;
import com.forecast.app.wallet.chain.TronHttp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 启动时按配置给每条链接上真实广播器：热钱包核对通过、节点、USDT 合约（以太坊体系还要 chainId）都配了才接。否则提现停在待上链。 */
@Component
class WithdrawSetup {

  private static final Logger log = LoggerFactory.getLogger(WithdrawSetup.class);

  private final HotWallet hot;
  private final HotWalletProperties hotProps;
  private final WalletProperties wallet;
  private final WithdrawSenders senders;
  private final WithdrawTxMapper txs;
  private final TransactionTemplate tx;
  private final StaffAlertApi staff;
  private final Clock clock;
  private final ObjectMapper json;

  WithdrawSetup(HotWallet hot, HotWalletProperties hotProps, WalletProperties wallet, WithdrawSenders senders, WithdrawTxMapper txs, TransactionTemplate tx,
      StaffAlertApi staff, Clock clock, ObjectMapper json) {
    this.hot = hot;
    this.hotProps = hotProps;
    this.wallet = wallet;
    this.senders = senders;
    this.txs = txs;
    this.tx = tx;
    this.staff = staff;
    this.clock = clock;
    this.json = json;
  }

  @EventListener(ApplicationReadyEvent.class)
  void connect() {
    for (Chain c : Chain.values()) {
      WalletProperties.ChainConfig cfg = wallet.chain(c);
      if (cfg == null || cfg.rpcUrl() == null || cfg.rpcUrl().isBlank() || !cfg.scanReady()) continue;
      if (c.family == Chain.Family.ETH) {
        Long chainId = c == Chain.ERC20 ? hotProps.erc20ChainId() : hotProps.bscChainId();
        if (hot.eth().isEmpty() || chainId == null) {
          log.info("{} 没有可用的热钱包或 chainId，提现停在待上链", c);
          continue;
        }
        senders.register(c, new EvmWithdrawSender(c, new JsonRpc(cfg.rpcUrl(), json), hot.eth().get(), cfg.usdtContract(), cfg.usdtDecimals(), chainId,
            cfg.confirmations(), hotProps, txs, tx, staff, clock));
      } else {
        if (hot.tron().isEmpty()) {
          log.info("{} 没有可用的热钱包，提现停在待上链", c);
          continue;
        }
        senders.register(c, new TronWithdrawSender(new TronHttp(cfg.rpcUrl(), json), json, hot.tron().get(), cfg.usdtContract(), cfg.usdtDecimals(),
            cfg.confirmations(), hotProps, txs, staff, clock));
      }
      log.info("{} 提现广播已接上", c);
    }
  }
}
