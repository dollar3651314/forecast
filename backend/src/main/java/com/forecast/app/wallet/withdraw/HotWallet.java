package com.forecast.app.wallet.withdraw;

import com.forecast.app.common.health.HealthCheck;
import com.forecast.app.wallet.address.Base58;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;

/**
 * 热钱包（提现方案 W1）。启动时用私钥算出地址，和配置的地址核对：对不上就不能提现，健康检查 withdraw 报 DOWN。
 * 私钥只在签名时用；任何日志、错误信息里都不出现私钥。没有配置私钥的链不提现（提现停在待上链），不算错误。
 */
@Component
@EnableConfigurationProperties(HotWalletProperties.class)
class HotWallet implements HealthCheck {

  private static final Logger log = LoggerFactory.getLogger(HotWallet.class);

  private final Credentials eth;
  private final Credentials tron;
  private final boolean ok;

  HotWallet(HotWalletProperties p) {
    boolean good = true;
    Credentials e = null;
    if (present(p.ethPrivateKey())) {
      e = load(p.ethPrivateKey());
      if (e == null || !present(p.ethAddress()) || !e.getAddress().equalsIgnoreCase(p.ethAddress().trim())) {
        log.error("以太坊体系热钱包私钥和配置的地址对不上（或私钥格式不对），不能提现");
        e = null;
        good = false;
      }
    }
    Credentials t = null;
    if (present(p.tronPrivateKey())) {
      t = load(p.tronPrivateKey());
      if (t == null || !present(p.tronAddress()) || !tronAddress(t).equals(p.tronAddress().trim())) {
        log.error("TRON 热钱包私钥和配置的地址对不上（或私钥格式不对），不能提现");
        t = null;
        good = false;
      }
    }
    this.eth = e;
    this.tron = t;
    this.ok = good;
  }

  Optional<Credentials> eth() {
    return Optional.ofNullable(eth);
  }

  Optional<Credentials> tron() {
    return Optional.ofNullable(tron);
  }

  /** TRON 地址：0x41 + 以太坊地址的 20 字节，Base58Check */
  static String tronAddress(Credentials c) {
    return Base58.encodeChecked(HexFormat.of().parseHex("41" + c.getAddress().substring(2)));
  }

  @Override
  public String name() {
    return "withdraw";
  }

  @Override
  public boolean ok() {
    return ok;
  }

  private static Credentials load(String key) {
    try {
      return Credentials.create(key.trim());
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static boolean present(String s) {
    return s != null && !s.isBlank();
  }
}
