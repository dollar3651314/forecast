package com.forecast.app.wallet.address;

import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.SweepEvmProperties;
import com.forecast.app.wallet.WalletProperties;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 当前生效的合约地址参数（方案第 4.13 节）。四个地址都配了、格式对、parent 不是我们自己的地址时，第一次用到就登记进 {@code wallet_address_scheme}
 * 拿到套号；否则当作没配：ERC20、BEP20 充值"暂未开通"（<b>不退回 xpub</b>），问题由 {@link #problem()} 给告警（{@code deposit.evm}、{@code sweep.config}）和后台。
 * 后端额外检查（方案第 4.7 节）：parent 不能是 USDT 合约、热钱包或我们的充值地址（Amos 10-19 把合约地址当收款地址的教训）。
 */
@Component
@EnableConfigurationProperties(SweepEvmProperties.class)
public class ForwarderSchemes {

  private static final Logger log = LoggerFactory.getLogger(ForwarderSchemes.class);

  private final AddressMapper mapper;
  private final SweepEvmProperties props;
  private final WalletProperties wallet;
  private final String hotEthAddress;
  /** 登记一套参数用独立事务：第一次用到时可能在别的事务里（例如注册），那个事务回滚不能把登记也回滚掉（内存里已经记住了套号） */
  private final TransactionTemplate own;
  private volatile ForwarderScheme current;
  private volatile String problem;
  private volatile boolean resolved;

  ForwarderSchemes(AddressMapper mapper, SweepEvmProperties props, WalletProperties wallet, @Value("${forecast.wallet.hot.eth-address:}") String hotEthAddress,
      PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.props = props;
    this.wallet = wallet;
    this.hotEthAddress = hotEthAddress;
    if (tm == null) {
      this.own = null;
    } else {
      this.own = new TransactionTemplate(tm);
      this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
  }

  /** 合约地址参数配齐并且检查通过时的当前一套；否则为空（以太坊体系暂未开通） */
  public Optional<ForwarderScheme> current() {
    if (!resolved) resolve();
    return Optional.ofNullable(current);
  }

  /** 配置有问题时的说明（没配置时为空） */
  public Optional<String> problem() {
    if (!resolved) resolve();
    return Optional.ofNullable(problem);
  }

  public Optional<ForwarderScheme> byVer(int ver) {
    AddressMapper.SchemeRow r = mapper.schemeByVer(ver);
    return r == null ? Optional.empty() : Optional.of(r.toScheme());
  }

  /** 所有登记过的（老的永远不删：扫块、归集老地址都要用） */
  public List<ForwarderScheme> all() {
    return mapper.schemes().stream().map(AddressMapper.SchemeRow::toScheme).toList();
  }

  private synchronized void resolve() {
    if (resolved) return;
    List<String> problems = props.contractProblems();
    if (props.contractFieldsSet() == 0) {
      resolved = true;
      return;
    }
    if (!problems.isEmpty()) {
      fail(String.join("；", problems));
      return;
    }
    String parent = props.parent().strip();
    String pk = parent.toLowerCase();
    for (Chain c : Chain.values()) {
      WalletProperties.ChainConfig cfg = wallet.chain(c);
      if (c.family == Chain.Family.ETH && cfg != null && cfg.usdtContract() != null && cfg.usdtContract().strip().equalsIgnoreCase(parent)) {
        fail("parent（冷钱包）不能是 " + c + " 的 USDT 合约地址");
        return;
      }
    }
    if (hotEthAddress != null && hotEthAddress.strip().equalsIgnoreCase(parent)) {
      fail("parent（冷钱包）不能是热钱包地址");
      return;
    }
    if (mapper.ownerOf(Chain.Family.ETH.name(), pk) != null) {
      fail("parent（冷钱包）不能是我们的充值地址");
      return;
    }
    String key = String.join("|", props.factory().strip(), props.implementation().strip(), parent, props.batcher().strip()).toLowerCase();
    java.util.function.Supplier<AddressMapper.SchemeRow> register = () -> {
      mapper.insertSchemeIgnore(Chain.Family.ETH.name(), props.factory().strip(), props.implementation().strip(), parent, props.batcher().strip(), key);
      return mapper.schemeByKey(key);
    };
    AddressMapper.SchemeRow row = own == null ? register.get() : own.execute(st -> register.get());
    current = row.toScheme();
    problem = null;
    resolved = true;
    log.info("以太坊体系充值地址用合约转发地址：第 {} 套（工厂 {}，parent {}，批量合约 {}）", current.ver(), current.factory(), current.parent(), current.feeAddress());
  }

  private void fail(String why) {
    problem = why;
    current = null;
    resolved = true;
    log.error("合约充值地址的配置有问题，ERC20、BEP20 充值暂未开通：{}", why);
  }

  /** 测试用：配置变了以后重新判断 */
  void reset() {
    resolved = false;
    current = null;
    problem = null;
  }
}
