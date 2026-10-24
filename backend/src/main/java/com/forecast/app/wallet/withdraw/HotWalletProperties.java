package com.forecast.app.wallet.withdraw;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 热钱包（application.yml 的 forecast.wallet.hot，提现方案 W1）。私钥由 Amos 填进服务器环境变量，仓库里只有变量名；
 * {@link #toString()} 不输出私钥，防止被打进日志。
 *
 * @param ethPrivateKey 以太坊体系热钱包私钥（ERC20、BSC 共用）
 * @param ethAddress 对应的地址，启动时和私钥算出的地址核对
 * @param erc20ChainId ERC20 网络的 chainId（主网 1，Sepolia 11155111）
 * @param bscChainId BSC 网络的 chainId（主网 56，测试网 97）
 * @param gasLimit USDT 转账的 gas 上限
 * @param tronFeeLimitSun TRON 每笔的手续费上限（sun，1 TRX = 1,000,000 sun）
 * @param stuckAfter 以太坊体系多久没上链就加价重发
 * @param maxReplacements 最多加价重发几次，之后通知人工处理
 */
@ConfigurationProperties("forecast.wallet.hot")
record HotWalletProperties(String ethPrivateKey, String ethAddress, String tronPrivateKey, String tronAddress, Long erc20ChainId, Long bscChainId,
    @DefaultValue("100000") long gasLimit, @DefaultValue("30000000") long tronFeeLimitSun, @DefaultValue("30m") Duration stuckAfter,
    @DefaultValue("3") int maxReplacements) {

  @Override
  public String toString() {
    return "HotWalletProperties[ethAddress=" + ethAddress + ", tronAddress=" + tronAddress + ", 私钥不输出]";
  }
}
