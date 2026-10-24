package com.forecast.app.wallet;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 钱包参数（application.yml 的 forecast.wallet），值由 Amos 填进服务器环境变量，仓库里只有变量名（底线）。
 *
 * @param ethXpub 以太坊体系的账户级 xpub（m/44'/60'/0'），只有公钥，服务器动不了充值地址里的钱（充值方案 D1）
 * @param tronXpub TRON 的账户级 xpub（m/44'/195'/0'）
 * @param minDeposit 最小充值金额，低于它的不入账（Amos 确认 1 USDT，充值方案 D4）
 * @param chains 每条链的节点、USDT 合约、精度、确认数
 */
@ConfigurationProperties("forecast.wallet")
public record WalletProperties(String ethXpub, String tronXpub, @DefaultValue("1") BigDecimal minDeposit, Map<Chain, ChainConfig> chains) {

  /**
   * 一条链的配置。
   *
   * @param rpcUrl 节点服务地址（含密钥，Amos 填）
   * @param usdtContract 只认这个 USDT 合约（修复 F3）；没有配置时这条链不扫块
   * @param usdtDecimals USDT 精度：ERC20、TRC20 是 6；BSC 是 18，要上链实测确认
   * @param confirmations 入账需要的确认数（ETH 12、BSC 15、TRON 19，沿用现有系统）
   * @param blockSeconds 出块间隔（秒，可以是小数：BSC 2026-10 实测约 0.45 秒），用于给客户估算到账时间、判断扫块落后多久
   * @param scanBatch 每次最多扫多少个区块
   * @param startBlock 第一次扫块从哪个区块开始；不填就从当前区块开始
   */
  public record ChainConfig(String rpcUrl, String usdtContract, @DefaultValue("6") int usdtDecimals, int confirmations, @DefaultValue("3") double blockSeconds,
      @DefaultValue("200") int scanBatch, Long startBlock) {

    public boolean scanReady() {
      return usdtContract != null && !usdtContract.isBlank();
    }
  }

  public ChainConfig chain(Chain c) {
    return chains == null ? null : chains.get(c);
  }
}
