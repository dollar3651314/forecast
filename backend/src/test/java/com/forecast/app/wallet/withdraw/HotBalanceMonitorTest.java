package com.forecast.app.wallet.withdraw;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.support.MutableClock;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.wallet.api.HotWalletApi;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 热钱包余额告警（2026-10-17）：本地 HTTP 替身节点（格式按 JSON-RPC 和 TRON 全节点接口，2026-10-17 和 Sepolia、BSC 测试网、Nile 的真实返回核对过）。
 * 下限默认 0 不告警；大于 0 后余额低于下限 → DOWN、补钱后 → UP、改回 0 → 告警项消失；查询失败不误报；没配置的链不查；告警说明写清链、币、余额、下限、地址。
 */
class HotBalanceMonitorTest {

  static final String ETH_HOT = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf";
  static final String TRON_HOT = "TUiRdTFS88JN9k1joQ5Hw6zgBBP9MtLobc";
  static final String USDT_ERC20 = "0xdAC17F958D2ee523a2206206994597C13D831ec7";
  static final String USDT_BSC = "0x55d398326f99059fF775485246999027B3197955";
  static final String USDT_TRC20 = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

  HttpServer server;
  String base;
  final List<String> requests = new CopyOnWriteArrayList<>();
  /** 链上余额（最小单位） */
  final Map<String, BigInteger> balances = new ConcurrentHashMap<>();
  /** 这些路径返回 HTTP 500（节点连不上） */
  final Set<String> broken = ConcurrentHashMap.newKeySet();
  final MutableClock clock = new MutableClock();
  final ObjectMapper json = new ObjectMapper();
  /** 后台系统配置里的下限（测试里直接改） */
  final Map<String, BigDecimal> mins = new ConcurrentHashMap<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      String path = ex.getRequestURI().getPath();
      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requests.add(path + " " + body);
      String chain = path.split("/")[1];
      String resp = broken.contains(chain) ? null : respond(chain, path, body);
      byte[] out = (resp == null ? "boom" : resp).getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(resp == null ? 500 : 200, out.length);
      ex.getResponseBody().write(out);
      ex.close();
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    balances.put("eth.usdt", BigInteger.valueOf(812_500_000L)); // 812.5 USDT（6 位）
    balances.put("eth.gas", new BigInteger("5000000000000000")); // 0.005 ETH
    balances.put("bsc.usdt", new BigInteger("2000").multiply(BigInteger.TEN.pow(18))); // 2000 USDT（18 位）
    balances.put("bsc.gas", new BigInteger("30000000000000000")); // 0.03 BNB
    balances.put("tron.usdt", BigInteger.valueOf(5_000_000_000L)); // 5000 USDT
    balances.put("tron.gas", BigInteger.valueOf(50_000_000L)); // 50 TRX
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private String respond(String chain, String path, String body) {
    if (chain.equals("tron")) {
      if (path.endsWith("/wallet/getaccount")) return "{\"address\":\"41a614f803b6fd780986a42c78ec9c7f77e6ded13c\",\"balance\":" + balances.get("tron.gas") + "}";
      if (path.endsWith("/wallet/triggerconstantcontract")) {
        if (balances.get("tron.usdt").signum() < 0) return "{\"result\":{\"code\":\"CONTRACT_VALIDATE_ERROR\",\"message\":\"636f6e7472616374\"}}";
        return "{\"result\":{\"result\":true},\"energy_used\":935,\"constant_result\":[\"" + pad(balances.get("tron.usdt")) + "\"]}";
      }
      return null;
    }
    if (body.contains("eth_call")) return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" + pad(balances.get(chain + ".usdt")) + "\"}";
    if (body.contains("eth_getBalance")) return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" + balances.get(chain + ".gas").toString(16) + "\"}";
    return null;
  }

  private static String pad(BigInteger v) {
    String h = v.toString(16);
    return "0".repeat(64 - h.length()) + h;
  }

  WalletProperties wallet(Chain... chains) {
    Map<Chain, WalletProperties.ChainConfig> m = new EnumMap<>(Chain.class);
    for (Chain c : chains) {
      switch (c) {
        case ERC20 -> m.put(c, new WalletProperties.ChainConfig(base + "/eth", USDT_ERC20, 6, 12, 12, 20, null));
        case BSC -> m.put(c, new WalletProperties.ChainConfig(base + "/bsc", USDT_BSC, 18, 15, 3, 50, null));
        case TRC20 -> m.put(c, new WalletProperties.ChainConfig(base + "/tron", USDT_TRC20, 6, 19, 3, 10, null));
      }
    }
    return new WalletProperties(null, BigDecimal.ONE, m);
  }

  HotBalanceMonitor monitor(WalletProperties wallet, String eth, String tron) {
    return new HotBalanceMonitor(wallet, eth, tron, HotBalanceProperties.defaults(),
        (c, gas) -> mins.getOrDefault(HotBalanceMonitor.configKey(c, gas).key, BigDecimal.ZERO), clock, json);
  }

  void setMin(Chain c, boolean gas, String v) {
    mins.put(HotBalanceMonitor.configKey(c, gas).key, new BigDecimal(v));
  }

  static AlertCheck.Item item(List<AlertCheck.Item> items, String name) {
    return items.stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("没有告警项 " + name + "：" + items));
  }

  @Test
  void 默认下限0_照常查余额_不告警_告警项不出现() {
    HotBalanceMonitor m = monitor(wallet(Chain.ERC20, Chain.BSC, Chain.TRC20), ETH_HOT, TRON_HOT);
    m.refresh();
    assertThat(m.evaluate()).as("下限都是 0：不告警").isEmpty();
    List<HotWalletApi.Balance> list = m.hotBalances();
    assertThat(list).hasSize(6);
    assertThat(list).allMatch(b -> b.threshold().signum() == 0 && !b.low());
    assertThat(list).extracting(b -> b.chain() + " " + b.asset() + " " + b.balance().stripTrailingZeros().toPlainString())
        .containsExactly("ERC20 USDT 812.5", "ERC20 ETH 0.005", "BSC USDT 2000", "BSC BNB 0.03", "TRC20 USDT 5000", "TRC20 TRX 50");
    assertThat(list.getFirst().address()).isEqualTo(ETH_HOT);
    assertThat(list.get(4).address()).isEqualTo(TRON_HOT);
    assertThat(list.getFirst().checkedAt()).isEqualTo(clock.instant());
  }

  @Test
  void 下限大于0_余额低于下限DOWN_补钱后UP_改回0告警项消失_告警说明写清楚() {
    HotBalanceMonitor m = monitor(wallet(Chain.ERC20, Chain.BSC, Chain.TRC20), ETH_HOT, TRON_HOT);
    m.refresh();
    setMin(Chain.ERC20, false, "1000");
    setMin(Chain.ERC20, true, "0.02");
    setMin(Chain.BSC, false, "1000");
    setMin(Chain.BSC, true, "0.01");
    setMin(Chain.TRC20, false, "1000");
    setMin(Chain.TRC20, true, "1000");
    List<AlertCheck.Item> items = m.evaluate();
    assertThat(items).extracting(AlertCheck.Item::name)
        .containsExactly("hot.ERC20.usdt", "hot.ERC20.gas", "hot.BSC.usdt", "hot.BSC.gas", "hot.TRC20.usdt", "hot.TRC20.gas");
    AlertCheck.Item usdt = item(items, "hot.ERC20.usdt");
    assertThat(usdt.ok()).isFalse();
    assertThat(usdt.titleZh()).isEqualTo("ERC20 热钱包 USDT 余额不足");
    assertThat(usdt.titleEn()).isEqualTo("ERC20 hot wallet USDT balance low");
    assertThat(usdt.detail()).contains("ERC20", "USDT", "余额 812.5", "低于告警下限 1000", "hotWallet.minUsdt.ERC20", "热钱包地址 " + ETH_HOT, "2026-10-14T10:00:00Z");
    AlertCheck.Item eth = item(items, "hot.ERC20.gas");
    assertThat(eth.ok()).isFalse();
    assertThat(eth.titleZh()).isEqualTo("ERC20 热钱包 ETH 余额不足（手续费币）");
    assertThat(eth.detail()).contains("ETH（手续费币） 余额 0.005", "低于告警下限 0.02", ETH_HOT);
    assertThat(item(items, "hot.BSC.usdt").ok()).as("BSC 18 位小数：2000 ≥ 1000").isTrue();
    assertThat(item(items, "hot.BSC.gas").ok()).isTrue();
    assertThat(item(items, "hot.TRC20.usdt").ok()).isTrue();
    AlertCheck.Item trx = item(items, "hot.TRC20.gas");
    assertThat(trx.ok()).isFalse();
    assertThat(trx.detail()).contains("TRC20", "TRX（手续费币） 余额 50", "低于告警下限 1000", TRON_HOT);
    assertThat(m.hotBalances().stream().filter(HotWalletApi.Balance::low).count()).isEqualTo(3);

    // 补钱后下一次查询恢复
    balances.put("eth.usdt", BigInteger.valueOf(1_500_000_000L));
    balances.put("tron.gas", BigInteger.valueOf(2_000_000_000L));
    clock.advance(Duration.ofMinutes(5));
    m.refresh();
    assertThat(item(m.evaluate(), "hot.ERC20.usdt").ok()).isTrue();
    assertThat(item(m.evaluate(), "hot.TRC20.gas").ok()).isTrue();
    assertThat(item(m.evaluate(), "hot.ERC20.gas").ok()).isFalse();

    // 下限改回 0：不用等下一次查询，告警项立即消失
    setMin(Chain.ERC20, true, "0");
    assertThat(m.evaluate()).extracting(AlertCheck.Item::name).doesNotContain("hot.ERC20.gas").hasSize(5);
    assertThat(m.hotBalances().get(1).low()).isFalse();
  }

  @Test
  void 查询失败不算余额低_保留上次结果_记下原因() {
    HotBalanceMonitor m = monitor(wallet(Chain.ERC20, Chain.TRC20), ETH_HOT, TRON_HOT);
    setMin(Chain.ERC20, false, "1000");
    setMin(Chain.TRC20, false, "1000");
    broken.add("eth");
    balances.put("tron.usdt", BigInteger.valueOf(-1)); // TRON 节点返回错误（result 不是 true）
    m.refresh();
    List<AlertCheck.Item> items = m.evaluate();
    assertThat(items).as("第一次就失败：算正常，不误报").allMatch(AlertCheck.Item::ok);
    HotWalletApi.Balance b = m.hotBalances().getFirst();
    assertThat(b.balance()).isNull();
    assertThat(b.error()).contains("HTTP 500").doesNotContain("127.0.0.1");
    assertThat(m.hotBalances().get(2).error()).contains("TRON 节点没有返回 USDT 余额");

    // 查到一次低余额后节点又断了：保留上次结果（仍在告警），不会因为查不到就"恢复"
    broken.clear();
    balances.put("tron.usdt", BigInteger.valueOf(5_000_000_000L));
    m.refresh();
    assertThat(item(m.evaluate(), "hot.ERC20.usdt").ok()).isFalse();
    broken.add("eth");
    clock.advance(Duration.ofMinutes(5));
    m.refresh();
    assertThat(item(m.evaluate(), "hot.ERC20.usdt").ok()).isFalse();
    assertThat(m.hotBalances().getFirst().balance()).isEqualByComparingTo("812.5");
    assertThat(m.hotBalances().getFirst().checkedAt()).isEqualTo(clock.instant().minus(Duration.ofMinutes(5)));
    assertThat(m.hotBalances().getFirst().error()).isNotNull();
  }

  @Test
  void 只查配置了的链和热钱包地址() {
    // 只配了 ERC20：BSC、TRON 不查
    HotBalanceMonitor m = monitor(wallet(Chain.ERC20), ETH_HOT, TRON_HOT);
    setMin(Chain.BSC, false, "1000");
    setMin(Chain.TRC20, false, "1000");
    m.refresh();
    assertThat(requests).allMatch(r -> r.startsWith("/eth "));
    assertThat(m.hotBalances()).extracting(HotWalletApi.Balance::chain).containsOnly("ERC20");

    // 链都配了，但没有热钱包地址：什么都不查
    requests.clear();
    HotBalanceMonitor none = monitor(wallet(Chain.ERC20, Chain.BSC, Chain.TRC20), null, " ");
    none.refresh();
    none.tick();
    assertThat(requests).isEmpty();
    assertThat(none.hotBalances()).isEmpty();
    assertThat(none.evaluate()).isEmpty();

    // 配了节点没配合约（scanReady 为 false）：不查
    Map<Chain, WalletProperties.ChainConfig> noContract = Map.of(Chain.ERC20, new WalletProperties.ChainConfig(base + "/eth", " ", 6, 12, 12, 20, null));
    HotBalanceMonitor nc = monitor(new WalletProperties(null, BigDecimal.ONE, noContract), ETH_HOT, TRON_HOT);
    nc.refresh();
    assertThat(requests).isEmpty();
  }

  @Test
  void 定时任务按间隔查询() {
    HotBalanceMonitor m = monitor(wallet(Chain.ERC20), ETH_HOT, null);
    m.tick();
    int first = requests.size();
    assertThat(first).isEqualTo(2);
    clock.advance(Duration.ofMinutes(4));
    m.tick();
    assertThat(requests).as("没到 5 分钟不查").hasSize(first);
    clock.advance(Duration.ofMinutes(1));
    m.tick();
    assertThat(requests).hasSize(first * 2);
  }
}
