package com.forecast.app.wallet.withdraw;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.wallet.api.HotWalletApi;
import com.forecast.app.wallet.chain.JsonRpc;
import com.forecast.app.wallet.chain.NodeException;
import com.forecast.app.wallet.chain.TronHttp;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 热钱包余额告警（Amos 2026-10-17"余额告警现在就做"；技术方案/v1-提现方案.md 第 6 节）。
 * <ul>
 *   <li>每 {@code forecast.wallet.hot-balance.interval}（默认 5 分钟）查一次已配置的热钱包余额：以太坊体系（ERC20、BSC 共用一个地址）查 USDT 合约的
 *       {@code balanceOf}（{@code eth_call}）和手续费币（{@code eth_getBalance}）；TRON 查 TRC20 USDT（{@code /wallet/triggerconstantcontract}）和 TRX
 *       （{@code /wallet/getaccount}）。USDT 小数位用链配置里的 {@code usdt-decimals}。</li>
 *   <li>告警下限在后台"系统配置"页设置（{@code hotWallet.minUsdt.<链>}、{@code hotWallet.minGas.<链>}，{@link ConfigKey#HOT_MIN_USDT_ERC20} 等），
 *       <b>默认 0 = 不告警</b>（Amos 2026-10-17）：这一项不判断、告警项不出现，余额照常查询、后台照常显示。改了以后 5 秒内生效（系统配置的缓存），不用等下一次查询。</li>
 *   <li>下限大于 0 的项是一个 E8 告警项 {@code hot.<链>.usdt}、{@code hot.<链>.gas}：余额低于下限 → DOWN，邮件（{@code AlertMonitor}，异常、恢复各一封，30 分钟节流）里写
 *       哪条链、哪种币、当前余额、阈值、热钱包地址。<b>这是业务告警</b>：不进健康检查的 {@code status}，不会让部署回滚（约定 24）。</li>
 *   <li><b>只查配置了的</b>：链配置了节点和 USDT 合约、并且配置了这一体系的热钱包地址（{@code FC_WALLET_ETH_HOT_ADDRESS} / {@code FC_WALLET_TRON_HOT_ADDRESS}）。
 *       只用地址查余额，不需要私钥。</li>
 *   <li><b>查询失败不算余额低</b>：只记日志，保留上一次查到的结果（第一次就失败算正常）；节点问题由 {@code node.<链>} 告警负责。</li>
 *   <li>告警检查（{@link #evaluate()}）只读内存里的结果，不访问网络（约定 24）；访问节点只在定时任务里。</li>
 * </ul>
 * 后台对账页通过 {@link HotWalletApi} 显示当前余额、阈值和最后查询时间。
 */
@Component
@EnableConfigurationProperties(HotBalanceProperties.class)
public class HotBalanceMonitor implements AlertCheck, HotWalletApi {

  private static final Logger log = LoggerFactory.getLogger(HotBalanceMonitor.class);
  private static final Pattern ETH_ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");

  /** 一项的最新结果（不可变，整体替换） */
  private record Reading(BigDecimal balance, Instant checkedAt, String error) {
    static final Reading NONE = new Reading(null, null, null);
  }

  /** 要查的一条链 */
  private record Target(Chain chain, String address, String usdtContract, int usdtDecimals, JsonRpc rpc, TronHttp tron) {}

  /** 告警下限：0 表示这一项不告警 */
  @FunctionalInterface
  public interface Thresholds {
    BigDecimal min(Chain chain, boolean gas);
  }

  private final HotBalanceProperties props;
  private final Thresholds thresholds;
  private final Clock clock;
  private final Map<Chain, Target> targets = new EnumMap<>(Chain.class);
  private final Map<String, Reading> readings = new ConcurrentHashMap<>();
  private volatile Instant lastRun;

  @Autowired
  HotBalanceMonitor(WalletProperties wallet, HotWalletProperties hot, HotBalanceProperties props, SystemConfig config, Clock clock, ObjectMapper json) {
    this(wallet, hot.ethAddress(), hot.tronAddress(), props, (c, gas) -> config.decimal(configKey(c, gas), BigDecimal.ZERO), clock, json);
  }

  /** 每一项对应的系统配置键 */
  public static ConfigKey configKey(Chain c, boolean gas) {
    return switch (c) {
      case ERC20 -> gas ? ConfigKey.HOT_MIN_ETH : ConfigKey.HOT_MIN_USDT_ERC20;
      case BSC -> gas ? ConfigKey.HOT_MIN_BNB : ConfigKey.HOT_MIN_USDT_BSC;
      case TRC20 -> gas ? ConfigKey.HOT_MIN_TRX : ConfigKey.HOT_MIN_USDT_TRC20;
    };
  }

  /** 测试里直接构造（节点地址在 wallet 的链配置里，可以指向本地替身节点） */
  public HotBalanceMonitor(WalletProperties wallet, String ethAddress, String tronAddress, HotBalanceProperties props, Thresholds thresholds, Clock clock,
      ObjectMapper json) {
    this.props = props;
    this.thresholds = thresholds;
    this.clock = clock;
    for (Chain c : Chain.values()) {
      WalletProperties.ChainConfig cfg = wallet.chain(c);
      if (cfg == null || blank(cfg.rpcUrl()) || !cfg.scanReady()) continue;
      String address = c.family == Chain.Family.ETH ? ethAddress : tronAddress;
      if (blank(address)) continue;
      address = address.strip();
      String contract = cfg.usdtContract().strip();
      if (c.family == Chain.Family.ETH) {
        targets.put(c, new Target(c, address, contract, cfg.usdtDecimals(), new JsonRpc(cfg.rpcUrl().strip(), json), null));
      } else {
        targets.put(c, new Target(c, address, contract, cfg.usdtDecimals(), null, new TronHttp(cfg.rpcUrl().strip(), json)));
      }
    }
    if (!targets.isEmpty()) log.info("热钱包余额告警：检查 {}，每 {} 查一次", targets.keySet(), props.interval());
  }

  /** 每 30 秒看一次是否到了查询时间（间隔可配置，默认 5 分钟） */
  @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
  void tick() {
    if (!props.enabled() || targets.isEmpty()) return;
    Instant now = clock.instant();
    if (lastRun != null && Duration.between(lastRun, now).compareTo(props.interval()) < 0) return;
    try {
      refresh();
    } catch (RuntimeException e) {
      log.error("热钱包余额查询出错", e);
    }
  }

  /** 立即查一次所有已配置的热钱包（定时任务和测试调用） */
  public synchronized void refresh() {
    lastRun = clock.instant();
    for (Target t : targets.values()) {
      query(t, false);
      query(t, true);
    }
  }

  private void query(Target t, boolean gas) {
    String key = key(t.chain(), gas);
    String asset = gas ? HotBalanceProperties.gasAsset(t.chain()) : "USDT";
    try {
      BigInteger raw = t.chain().family == Chain.Family.ETH ? (gas ? evmNative(t) : evmUsdt(t)) : (gas ? trx(t) : tronUsdt(t));
      int decimals = gas ? HotBalanceProperties.gasDecimals(t.chain()) : t.usdtDecimals();
      BigDecimal balance = new BigDecimal(raw).movePointLeft(decimals);
      readings.put(key, new Reading(balance, clock.instant(), null));
    } catch (NodeException | IllegalArgumentException | IllegalStateException e) {
      Reading prev = readings.getOrDefault(key, Reading.NONE);
      readings.put(key, new Reading(prev.balance(), prev.checkedAt(), e.getMessage()));
      log.warn("热钱包余额查询失败（不算余额低，保留上次结果）chain={} asset={}：{}", t.chain(), asset, e.getMessage());
    }
  }

  // ---------- 以太坊体系 ----------

  private BigInteger evmUsdt(Target t) {
    requireEth(t.address());
    String data = EvmWithdrawSender.BALANCE_OF + "0".repeat(24) + t.address().substring(2).toLowerCase();
    String hex = t.rpc().call("eth_call", List.of(Map.of("to", t.usdtContract(), "data", data), "latest")).asString("");
    if (hex.isEmpty() || "0x".equals(hex)) throw new IllegalStateException("USDT 合约没有返回余额（合约地址不对？）");
    return JsonRpc.bigHex(hex);
  }

  private BigInteger evmNative(Target t) {
    requireEth(t.address());
    String hex = t.rpc().call("eth_getBalance", List.of(t.address(), "latest")).asString("");
    if (hex.isEmpty()) throw new IllegalStateException("节点没有返回余额");
    return JsonRpc.bigHex(hex);
  }

  private static void requireEth(String address) {
    if (!ETH_ADDRESS.matcher(address).matches()) throw new IllegalArgumentException("以太坊体系热钱包地址格式不对");
  }

  // ---------- TRON ----------

  private BigInteger tronUsdt(Target t) {
    String owner = TronWithdrawSender.hex41(t.address());
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("owner_address", owner);
    req.put("contract_address", TronWithdrawSender.hex41(t.usdtContract()));
    req.put("function_selector", "balanceOf(address)");
    req.put("parameter", "0".repeat(24) + owner.substring(2));
    req.put("visible", false);
    JsonNode r = t.tron().post("/wallet/triggerconstantcontract", req);
    String hex = r.path("constant_result").path(0).asString("");
    if (!r.path("result").path("result").asBoolean(false) || hex.isEmpty()) {
      throw new IllegalStateException("TRON 节点没有返回 USDT 余额：" + TronWithdrawSender.message(r.path("result").path("message").asString("")));
    }
    return new BigInteger(hex, 16);
  }

  /** 没有激活的账户节点返回空对象，余额就是 0 */
  private BigInteger trx(Target t) {
    JsonNode r = t.tron().post("/wallet/getaccount", Map.of("address", TronWithdrawSender.hex41(t.address()), "visible", false));
    if (r.has("Error")) throw new IllegalStateException("TRON 节点返回错误：" + r.path("Error").asString());
    return BigInteger.valueOf(r.path("balance").asLong(0));
  }

  // ---------- 告警和后台 ----------

  private BigDecimal threshold(Chain c, boolean gas) {
    BigDecimal v = thresholds.min(c, gas);
    return v == null || v.signum() < 0 ? BigDecimal.ZERO : v;
  }

  private static String key(Chain c, boolean gas) {
    return "hot." + c.name() + (gas ? ".gas" : ".usdt");
  }

  @Override
  public List<Item> evaluate() {
    List<Item> out = new ArrayList<>();
    for (Balance b : hotBalances()) {
      if (b.threshold().signum() <= 0) continue; // 下限 0 = 不告警，告警项不出现
      Chain c = Chain.valueOf(b.chain());
      String zh = b.gas() ? b.asset() + "（手续费币）" : b.asset();
      String detail;
      if (b.low()) {
        detail = c.name() + " 链热钱包 " + zh + " 余额 " + plain(b.balance()) + "，低于告警下限 " + plain(b.threshold()) + "（后台系统配置 " + configKey(c, b.gas()).key + "）；热钱包地址 " + b.address()
            + "；查询时间（UTC）" + b.checkedAt().truncatedTo(ChronoUnit.SECONDS) + "。请从冷钱包补充" + (b.gas() ? "手续费币" : " USDT")
            + "，余额不够时提现会停在\"待上链\"";
      } else {
        detail = b.balance() == null ? "还没有查到余额" : "余额 " + plain(b.balance()) + "，告警下限 " + plain(b.threshold());
      }
      out.add(new Item(key(c, b.gas()), c.name() + " 热钱包 " + b.asset() + " 余额不足" + (b.gas() ? "（手续费币）" : ""),
          c.name() + " hot wallet " + b.asset() + " balance low" + (b.gas() ? " (gas)" : ""), !b.low(), detail));
    }
    return out;
  }

  @Override
  public List<Balance> hotBalances() {
    List<Balance> out = new ArrayList<>();
    for (Target t : targets.values()) {
      for (boolean gas : new boolean[] {false, true}) {
        BigDecimal threshold = threshold(t.chain(), gas);
        Reading r = readings.getOrDefault(key(t.chain(), gas), Reading.NONE);
        boolean low = threshold.signum() > 0 && r.balance() != null && r.balance().compareTo(threshold) < 0;
        out.add(new Balance(t.chain().name(), gas ? HotBalanceProperties.gasAsset(t.chain()) : "USDT", gas, t.address(), r.balance(), threshold, low,
            r.checkedAt(), r.error()));
      }
    }
    return out;
  }

  private static String plain(BigDecimal v) {
    return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
