package com.forecast.app.wallet.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.common.health.AlertProperties;
import com.forecast.app.support.MutableClock;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.wallet.chain.NodeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 扫块停滞、链节点连不上的告警（E8，AC2-20）。只检查配置了的链；状态只在内存里，单元测试。 */
class ChainMonitorTest {

  final MutableClock clock = new MutableClock();
  final ChainClients clients = new ChainClients();
  final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

  /** ERC20 配了合约（12 秒一个块），BSC 没配合约，TRC20 配了合约（3 秒一个块） */
  final WalletProperties props = new WalletProperties(null, BigDecimal.ONE, Map.of(
      Chain.ERC20, new WalletProperties.ChainConfig("http://node", "0xdAC17F958D2ee523a2206206994597C13D831ec7", 6, 12, 12, 20, null),
      Chain.BSC, new WalletProperties.ChainConfig("http://node", "", 18, 15, 3, 50, null),
      Chain.TRC20, new WalletProperties.ChainConfig("http://node", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", 6, 19, 3, 10, null)));

  final ChainMonitor monitor = new ChainMonitor(props, clients, AlertProperties.defaults(), clock, metrics);

  Optional<AlertCheck.Item> item(String name) {
    return monitor.evaluate().stream().filter(i -> i.name().equals(name)).findFirst();
  }

  boolean ok(String name) {
    return item(name).orElseThrow().ok();
  }

  @Test
  void 只检查配置了合约并且有链客户端的链_测试环境没配链时没有任何项() {
    assertThat(monitor.evaluate()).as("还没有链客户端（没配节点）").isEmpty();
    clients.register(Chain.ERC20, new FakeChain(100));
    clients.register(Chain.BSC, new FakeChain(100));
    assertThat(monitor.evaluate()).extracting(AlertCheck.Item::name).as("BSC 没配合约，不扫块也不检查").containsExactly("node.ERC20", "scan.ERC20");
    assertThat(monitor.evaluate()).allMatch(AlertCheck.Item::ok);
  }

  @Test
  void 连续3次连不上节点就告警_一次成功就恢复() {
    clients.register(Chain.TRC20, new FakeChain(100));
    NodeException e = new NodeException.Unreachable("timeout", null);
    monitor.nodeFailed(Chain.TRC20, e);
    monitor.nodeFailed(Chain.TRC20, e);
    assertThat(ok("node.TRC20")).isTrue();
    monitor.nodeFailed(Chain.TRC20, e);
    assertThat(ok("node.TRC20")).isFalse();
    assertThat(item("node.TRC20").orElseThrow().detail()).contains("连续 3 次").contains("Unreachable");
    assertThat(metrics.get("fc.chain.node.failures").tag("chain", "TRC20").gauge().value()).isEqualTo(3);
    monitor.nodeOk(Chain.TRC20, 101);
    assertThat(ok("node.TRC20")).isTrue();
  }

  @Test
  void 超过10分钟没推进算扫块停滞_推进或追上就恢复() {
    clients.register(Chain.ERC20, new FakeChain(100));
    monitor.scanned(Chain.ERC20, 100, 100);
    clock.advance(Duration.ofMinutes(10));
    assertThat(ok("scan.ERC20")).as("10 分钟整").isTrue();
    clock.advance(Duration.ofSeconds(1));
    assertThat(ok("scan.ERC20")).isFalse();
    assertThat(item("scan.ERC20").orElseThrow().detail()).contains("10 分钟没有推进");
    monitor.scanned(Chain.ERC20, 100, 100);
    assertThat(ok("scan.ERC20")).as("已经追上节点的最新区块也算推进（没有新区块）").isTrue();
  }

  @Test
  void 落后的区块折合超过10分钟算停滞() {
    clients.register(Chain.ERC20, new FakeChain(100));
    clients.register(Chain.TRC20, new FakeChain(100));
    monitor.scanned(Chain.ERC20, 1000, 1050); // 落后 50 块 × 12 秒 = 10 分钟
    assertThat(ok("scan.ERC20")).isTrue();
    monitor.scanned(Chain.ERC20, 1001, 1052); // 51 块 × 12 秒 > 10 分钟
    assertThat(ok("scan.ERC20")).isFalse();
    assertThat(item("scan.ERC20").orElseThrow().detail()).contains("落后 51 个区块");
    assertThat(metrics.get("fc.chain.scan.lag").tag("chain", "ERC20").gauge().value()).isEqualTo(51);
    monitor.scanned(Chain.TRC20, 5000, 5150); // TRON 3 秒一块，150 块 = 7.5 分钟
    assertThat(ok("scan.TRC20")).isTrue();
  }
}
