package com.forecast.app.wallet.address;

import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.SweepEvmProperties;
import com.forecast.app.wallet.WalletProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 告警项 {@code deposit.evm}：ERC20、BEP20 充值有没有开通（开发记录/钱包-合约地址方案.md）。
 * 以太坊体系的充值地址只用 CREATE2 合约地址，合约地址参数（{@code FC_SWEEP_EVM_FACTORY / IMPLEMENTATION / PARENT / BATCHER}）没配齐或检查不通过时
 * 这两条链"暂未开通"，这一项报 DOWN。
 * <p><b>只进健康检查的 {@code alerts} 和告警邮件，不进 {@code status}</b>（项目地图约定 11）：{@code status} 变成 DOWN 会让部署脚本回滚，
 * 而缺配置换版本解决不了，要 Amos 部署合约、填 {@code .env}。
 * <p>只在"以太坊或 BSC 配置了 USDT 合约（要扫块），或者填了任何一个合约地址"时出现（{@link AlertCheck}：只检查配置了的东西），
 * 本机开发什么都没配时不出现。
 */
@Component
class EvmDepositAlert implements AlertCheck {

  static final String NAME = "deposit.evm";

  private final ForwarderSchemes schemes;
  private final WalletProperties wallet;
  private final SweepEvmProperties sweep;

  EvmDepositAlert(ForwarderSchemes schemes, WalletProperties wallet, SweepEvmProperties sweep) {
    this.schemes = schemes;
    this.wallet = wallet;
    this.sweep = sweep;
  }

  private boolean configured() {
    if (sweep.contractFieldsSet() > 0) return true;
    for (Chain c : new Chain[] {Chain.ERC20, Chain.BSC}) {
      WalletProperties.ChainConfig cfg = wallet.chain(c);
      if (cfg != null && cfg.scanReady()) return true;
    }
    return false;
  }

  @Override
  public List<Item> evaluate() {
    if (!configured()) return List.of();
    boolean ok = schemes.current().isPresent();
    String detail = ok ? "正常（第 " + schemes.current().get().ver() + " 套合约地址参数）"
        : "ERC20、BEP20 充值暂未开通：" + schemes.problem().orElse("没有配置归集合约地址（FC_SWEEP_EVM_FACTORY / IMPLEMENTATION / PARENT / BATCHER）")
            + "。以太坊体系只用合约地址，不退回 xpub。请按 部署方案/v1-归集合约部署.md 部署合约后填进服务器 .env 并重启";
    return List.of(new Item(NAME, "ERC20、BEP20 充值暂未开通", "ERC20 / BEP20 deposits not open", ok, detail));
  }
}
