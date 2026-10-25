package com.forecast.app.wallet;

import com.forecast.app.wallet.address.Create2;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 以太坊、BSC 资金归集（application.yml 的 forecast.wallet.sweep.evm，技术方案/v2-半自动归集.md 第 4.4、4.6、4.9 节）。
 * 四个合约相关地址都是公开地址，由 Amos 部署合约后填进服务器 .env；<b>发起账户私钥只由 Amos 填</b>，{@link #toString()} 不输出它。
 * <ul>
 *   <li>工厂、实现合约、parent、批量合约<b>四个都配了</b>：以太坊体系（ERC20、BEP20）的充值地址用 CREATE2 算（合约转发地址）。
 *       换一套参数时老地址标"已停用"，继续扫块 {@code retiredScanDays} 天（SW-9）。</li>
 *   <li>没配齐（一个没配、只配了一部分、格式不对）：ERC20、BEP20 充值<b>"暂未开通"，不退回 xpub 派生</b>（开发记录/钱包-合约地址方案.md）；
 *       健康检查 {@code alerts} 的 {@code deposit.evm} 报 DOWN（{@code status} 不变），配错时 {@code sweep.config} 另外写原因。</li>
 *   <li>发起账户（地址 + 私钥）：启动时用私钥算出地址和配置的地址核对，对不上或没配时只是不能执行归集，<b>不影响启动和健康检查</b>。</li>
 * </ul>
 *
 * @param factory BitGo ForwarderFactoryV4 地址（{@code FC_SWEEP_EVM_FACTORY}）
 * @param implementation BitGo ForwarderV4 实现合约地址（{@code FC_SWEEP_EVM_IMPLEMENTATION}）
 * @param parent 冷钱包 = Safe 多签地址（{@code FC_SWEEP_EVM_PARENT}），写在每个转发地址里，不能改
 * @param batcher 批量合约 SweepBatcher 地址（{@code FC_SWEEP_EVM_BATCHER}），也是所有转发地址的 feeAddress
 * @param operatorAddress 发起账户地址（{@code FC_SWEEP_EVM_OPERATOR_ADDRESS}）
 * @param operatorKey 发起账户私钥（{@code FC_SWEEP_EVM_OPERATOR_KEY}，只由 Amos 填）
 * @param erc20ChainId ERC20 网络的 chainId（和提现共用 {@code FC_WALLET_ERC20_CHAIN_ID}）
 * @param bscChainId BSC 网络的 chainId（和提现共用 {@code FC_WALLET_BSC_CHAIN_ID}）
 * @param retiredScanDays 停用的老地址继续扫块多少天（SW-9：30 天）
 * @param migrateOnStart 启动时把已有用户不是当前这一套的以太坊体系地址（以前的 xpub 地址或老一套合约地址）换成当前合约地址（老地址停用、继续扫块）
 * @param schedulerEnabled 是否定时推进批次和查余额（测试里关掉，直接调用）
 * @param maxBatchSize 每批最多多少个地址（方案第 4.5 节：100）
 * @param deployGas 预估：一个未部署地址"部署 + 归集"的 gas（Foundry 实测约 13 万，见 contracts/README.md）
 * @param flushGas 预估：一个已部署地址归集的 gas
 * @param baseGas 预估：一笔批量交易本身的 gas（21,000 + 调用开销）
 * @param priorityFeeGwei 以太坊 EIP-1559 小费（gwei）
 * @param stuckAfter 多久没上链就同一 nonce 加价 20% 重发
 * @param maxReplacements 最多加价重发几次，之后告警等人工
 * @param balanceInterval 多久查一次发起账户余额和待归集金额（后台概览、告警）
 */
@ConfigurationProperties("forecast.wallet.sweep.evm")
public record SweepEvmProperties(String factory, String implementation, String parent, String batcher, String operatorAddress, String operatorKey,
    Long erc20ChainId, Long bscChainId, @DefaultValue("30") int retiredScanDays, @DefaultValue("true") boolean migrateOnStart,
    @DefaultValue("true") boolean schedulerEnabled, @DefaultValue("100") int maxBatchSize, @DefaultValue("160000") long deployGas,
    @DefaultValue("45000") long flushGas, @DefaultValue("40000") long baseGas, @DefaultValue("0.05") BigDecimal priorityFeeGwei,
    @DefaultValue("30m") Duration stuckAfter, @DefaultValue("3") int maxReplacements, @DefaultValue("5m") Duration balanceInterval) {

  /** 四个合约地址有没有填（填了几个） */
  public int contractFieldsSet() {
    int n = 0;
    for (String s : new String[] {factory, implementation, parent, batcher}) if (s != null && !s.isBlank()) n++;
    return n;
  }

  /** 四个都配了、格式都对 */
  public boolean contractsReady() {
    return contractProblems().isEmpty() && contractFieldsSet() == 4;
  }

  /** 配置问题（显示在告警和后台，不含私钥）：只配了一部分、地址格式不对、parent 和其他地址相同 */
  public List<String> contractProblems() {
    List<String> out = new ArrayList<>();
    int n = contractFieldsSet();
    if (n == 0) return out;
    if (n < 4) out.add("工厂、实现合约、parent、批量合约四个地址只配了 " + n + " 个（FC_SWEEP_EVM_FACTORY / IMPLEMENTATION / PARENT / BATCHER）");
    String[][] fields = {{"FC_SWEEP_EVM_FACTORY", factory}, {"FC_SWEEP_EVM_IMPLEMENTATION", implementation}, {"FC_SWEEP_EVM_PARENT", parent},
        {"FC_SWEEP_EVM_BATCHER", batcher}};
    for (String[] f : fields) if (f[1] != null && !f[1].isBlank() && !Create2.isAddress(f[1])) out.add(f[0] + " 不是以太坊地址");
    if (n == 4 && out.isEmpty()) {
      String p = parent.strip().toLowerCase();
      if (p.equals(factory.strip().toLowerCase()) || p.equals(implementation.strip().toLowerCase()) || p.equals(batcher.strip().toLowerCase())) {
        out.add("parent（冷钱包）不能等于工厂、实现合约或批量合约");
      }
    }
    return out;
  }

  public Long chainId(Chain c) {
    return c == Chain.ERC20 ? erc20ChainId : c == Chain.BSC ? bscChainId : null;
  }

  @Override
  public String toString() {
    return "SweepEvmProperties[factory=" + factory + ", implementation=" + implementation + ", parent=" + parent + ", batcher=" + batcher + ", operatorAddress="
        + operatorAddress + ", 私钥不输出]";
  }
}
