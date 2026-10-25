package com.forecast.app.wallet.deposit;

import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.common.health.AlertProperties;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.WalletProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 扫块和链节点的告警（E8，功能取舍对照表 F-30）。{@link DepositService#run} 每次扫块时把结果告诉它，它只记在内存里：
 * <ul>
 *   <li>{@code node.<链>}：连续 {@code forecast.alert.node-failures}（默认 3）次连不上节点（扫块每 15 秒一次，约 45 秒）→ DOWN；一次成功就恢复。</li>
 *   <li>{@code scan.<链>}：超过 {@code forecast.alert.scan-stall}（默认 10 分钟）没有推进（扫到了新区块或者已经追上），
 *       或者落后的区块数 × 出块间隔超过这个时间 → DOWN。节点连不上时扫块也停滞，两项都会 DOWN。</li>
 * </ul>
 * <b>只检查配置了的链</b>（配置了 USDT 合约、并且有链客户端，和扫块的条件一样）；测试环境没配链时没有这两项，不会误报。
 * 指标：{@code fc_chain_scan_lag_blocks}（扫块落后的区块数）、{@code fc_chain_node_failures}（连续失败次数）。
 */
@Component
public class ChainMonitor implements AlertCheck {

  /** 一条链的状态。字段只在扫块线程里写，健康检查读到的是某一刻的值，不需要更严格的同步 */
  private static final class State {
    volatile int failures;
    volatile String lastError;
    volatile long head = -1;
    volatile long cursor = -1;
    volatile Instant progressAt;
  }

  private final WalletProperties wallet;
  private final ChainClients clients;
  private final AlertProperties props;
  private final Clock clock;
  private final Map<Chain, State> states = new EnumMap<>(Chain.class);

  @Autowired
  public ChainMonitor(WalletProperties wallet, ChainClients clients, AlertProperties props, Clock clock, MeterRegistry metrics) {
    this.wallet = wallet;
    this.clients = clients;
    this.props = props;
    this.clock = clock;
    Instant now = clock.instant();
    for (Chain c : Chain.values()) {
      State s = new State();
      s.progressAt = now;
      states.put(c, s);
      if (metrics == null) continue;
      Gauge.builder("fc.chain.scan.lag", () -> s.head < 0 || s.cursor < 0 ? Double.NaN : Math.max(0, s.head - s.cursor))
          .description("扫块落后的区块数（节点最新区块 - 已扫到的区块）").baseUnit("blocks").tag("chain", c.name()).register(metrics);
      Gauge.builder("fc.chain.node.failures", () -> s.failures).description("连续连不上节点的次数").tag("chain", c.name()).register(metrics);
    }
  }

  /** 拿到了节点的最新区块 */
  void nodeOk(Chain chain, long head) {
    State s = states.get(chain);
    s.failures = 0;
    s.lastError = null;
    s.head = head;
  }

  /** 访问节点失败（连不上、超时、节点报错） */
  void nodeFailed(Chain chain, RuntimeException e) {
    State s = states.get(chain);
    s.failures++;
    s.lastError = e.getClass().getSimpleName();
  }

  /** 这一轮扫块结束：已经扫到 cursor（追上了或者往前推进了都算推进） */
  void scanned(Chain chain, long cursor, long head) {
    State s = states.get(chain);
    if (cursor > s.cursor || cursor >= head) s.progressAt = clock.instant();
    s.cursor = cursor;
    s.head = head;
  }

  private boolean configured(Chain c) {
    WalletProperties.ChainConfig cfg = wallet.chain(c);
    return cfg != null && cfg.scanReady() && clients.get(c).isPresent();
  }

  @Override
  public List<Item> evaluate() {
    Instant now = clock.instant();
    List<Item> out = new ArrayList<>();
    for (Chain c : Chain.values()) {
      if (!configured(c)) continue;
      State s = states.get(c);
      boolean nodeOk = s.failures < props.nodeFailures();
      out.add(new Item("node." + c.name(), c.name() + " 链节点连不上", c.name() + " node unreachable", nodeOk,
          nodeOk ? "正常" : "连续 " + s.failures + " 次连不上节点（最近一次：" + s.lastError + "）"));
      Duration idle = Duration.between(s.progressAt, now);
      long lag = s.head >= 0 && s.cursor >= 0 ? Math.max(0, s.head - s.cursor) : 0;
      long lagSeconds = (long) Math.ceil(lag * wallet.chain(c).blockSeconds());
      boolean stalled = idle.compareTo(props.scanStall()) > 0;
      boolean behind = lagSeconds > props.scanStall().toSeconds();
      String detail = stalled ? "扫块已经 " + idle.toMinutes() + " 分钟没有推进（扫到 " + s.cursor + "，节点最新 " + s.head + "）"
          : behind ? "扫块落后 " + lag + " 个区块（约 " + lagSeconds / 60 + " 分钟）" : "正常";
      out.add(new Item("scan." + c.name(), c.name() + " 扫块停滞", c.name() + " block scanning stalled", !stalled && !behind, detail));
    }
    return out;
  }
}
