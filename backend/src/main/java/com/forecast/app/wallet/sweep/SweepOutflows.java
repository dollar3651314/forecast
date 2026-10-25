package com.forecast.app.wallet.sweep;

import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.address.AddressService;
import com.forecast.app.wallet.address.ForwarderScheme;
import com.forecast.app.wallet.address.ForwarderSchemes;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 扫块看到"从我们的充值地址转出"的 USDT 时调用（方案第 4.4、4.8 节："归集转出不能被误判成异常，对账要能区分"）：
 * <ul>
 *   <li>合约地址转出、交易是我们的归集批次发的、去了 parent → {@code SWEEP}（正常，不告警）；</li>
 *   <li>去了 parent，但交易不是我们发的（例如 Safe 手动 flushTokens）→ {@code UNKNOWN}：钱在冷钱包，但要人工确认，告警；</li>
 *   <li>去了不是 parent 的地方 → {@code DEST_MISMATCH}：合约不应该允许，<b>最高级别告警</b>。</li>
 * </ul>
 * 记在 {@code wallet_sweep_outflow}，对账（{@code ReconService}）把后两种列成问题。不是归集的转出<b>每次都发</b>告警邮件给风控人员。
 * xpub 地址的转出（Amos 现在离线手动归集，充值方案 D2；TRON 归集下一阶段做完后再纳入）只记日志。
 */
@Component
public class SweepOutflows {

  private static final Logger log = LoggerFactory.getLogger(SweepOutflows.class);

  private final SweepMapper mapper;
  private final ForwarderSchemes schemes;
  private final StaffAlertApi staff;

  SweepOutflows(SweepMapper mapper, ForwarderSchemes schemes, StaffAlertApi staff) {
    this.mapper = mapper;
    this.schemes = schemes;
    this.staff = staff;
  }

  /**
   * @param from 转出的充值地址（我们的）
   * @param rawAmount 链上整数金额
   * @return 记下的类型；xpub 地址或已经记过的为空
   */
  public Optional<String> record(Chain chain, AddressService.OwnAddress from, String txHash, int logIndex, long blockNumber, String to, BigInteger rawAmount,
      int decimals) {
    BigDecimal amount = new BigDecimal(rawAmount).movePointLeft(decimals);
    String shown = amount.signum() == 0 ? "0" : amount.stripTrailingZeros().toPlainString();
    if (!from.forwarder()) {
      log.info("xpub 充值地址转出（离线手动归集）chain={} from={} to={} amount={} tx={}", chain, from.address(), to, shown, txHash);
      return Optional.empty();
    }
    Optional<ForwarderScheme> scheme = schemes.byVer(from.schemeVer());
    String parent = scheme.map(ForwarderScheme::parent).orElse("");
    boolean toParent = parent.equalsIgnoreCase(to);
    Long batch = mapper.batchOfTx(chain.name(), txHash);
    String kind = !toParent ? "DEST_MISMATCH" : batch != null ? "SWEEP" : "UNKNOWN";
    if (mapper.insertOutflow(chain.name(), txHash, logIndex, blockNumber, from.address(), to, amount, from.id(), from.userId(), kind, batch) == 0) {
      return Optional.empty();
    }
    if ("SWEEP".equals(kind)) {
      log.info("归集转出 chain={} from={} amount={} tx={}", chain, from.address(), shown, txHash);
      return Optional.of(kind);
    }
    log.error("充值地址出现不是归集的转出 kind={} chain={} from={} to={} amount={} tx={}", kind, chain, from.address(), to, shown, txHash);
    Map<String, String> p = new LinkedHashMap<>();
    p.put("chain", chain.name());
    p.put("from", from.address());
    p.put("to", to);
    p.put("amount", shown);
    p.put("txHash", txHash);
    p.put("userId", String.valueOf(from.userId()));
    p.put("hint", toParent ? "去向是冷钱包（parent），可能是有人在 Safe 里手动归集。 / The destination is the cold wallet (parent); possibly a manual flush from the Safe."
        : "去向不是冷钱包！合约不应该允许这样转出，请立即停止归集并排查。 / The destination is NOT the cold wallet! Stop sweeping and investigate now.");
    try {
      staff.notifyStaff("SWEEP_UNKNOWN_OUTFLOW", p);
    } catch (RuntimeException e) {
      log.error("异常转出告警写入失败", e);
    }
    return Optional.of(kind);
  }
}
