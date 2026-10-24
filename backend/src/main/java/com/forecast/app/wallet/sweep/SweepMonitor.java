package com.forecast.app.wallet.sweep;

import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.SweepEvmProperties;
import com.forecast.app.wallet.address.ForwarderSchemes;
import com.forecast.app.wallet.api.SweepSettings;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 资金归集的定时任务和告警（v2 第一阶段；方案第 4.9 节；约定 24：<b>只是业务告警，不进健康检查的 status</b>，归集出错不会让部署回滚）。
 * <ul>
 *   <li>每 15 秒推进批次（补发、查确认、加价重发）；每 {@code balance-interval}（默认 5 分钟）查发起账户余额和待归集金额。</li>
 *   <li>告警项（{@link #evaluate()} 只读内存）：{@code sweep.config}（合约地址参数或发起账户配错了：只在配置了其中任何一项时出现）、
 *       {@code sweep.operator.<链>}（发起账户余额低于下限，下限 0 = 不告警）、{@code sweep.failed.<链>}（最近一批失败 / 部分成功 / 卡住）、
 *       {@code sweep.outflow}（有不是归集的转出还没在对账页处理）。</li>
 * </ul>
 */
@Component
class SweepMonitor implements AlertCheck {

  private static final Logger log = LoggerFactory.getLogger(SweepMonitor.class);

  private final SweepService service;
  private final SweepMapper mapper;
  private final SweepEvmProperties props;
  private final ForwarderSchemes schemes;
  private final SweepOperator operator;
  private final SystemConfig config;
  private final Clock clock;
  private volatile Instant lastRefresh;
  private final Map<Chain, SweepMapper.BatchRow> lastFinished = new java.util.concurrent.ConcurrentHashMap<>();
  private final Map<Chain, SweepMapper.BatchRow> active = new java.util.concurrent.ConcurrentHashMap<>();
  private volatile long openOutflows;

  SweepMonitor(SweepService service, SweepMapper mapper, SweepEvmProperties props, ForwarderSchemes schemes, SweepOperator operator, SystemConfig config,
      Clock clock) {
    this.service = service;
    this.mapper = mapper;
    this.props = props;
    this.schemes = schemes;
    this.operator = operator;
    this.clock = clock;
    this.config = config;
  }

  @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
  void tick() {
    if (!props.schedulerEnabled() || !enabled()) return;
    try {
      service.advance();
    } catch (RuntimeException e) {
      log.error("推进归集批次出错", e);
    }
    Instant now = clock.instant();
    try {
      if (lastRefresh == null || Duration.between(lastRefresh, now).compareTo(props.balanceInterval()) >= 0) {
        lastRefresh = now;
        service.refresh();
      }
      loadStats();
    } catch (RuntimeException e) {
      log.error("查询归集状态出错", e);
    }
  }

  /** 配置了合约地址或发起账户才需要跑 */
  private boolean enabled() {
    return props.contractFieldsSet() > 0 || operator.anyConfigured();
  }

  /** 查余额、待归集金额（访问节点），再读最近的批次和异常转出（测试调用） */
  void refresh() {
    service.refresh();
    loadStats();
  }

  /** 从数据库读最近的批次、进行中的批次和没处理的异常转出，放进内存给告警检查用 */
  void loadStats() {
    for (Chain c : List.of(Chain.ERC20, Chain.BSC)) {
      SweepMapper.BatchRow b = mapper.lastFinished(c.name());
      if (b != null) lastFinished.put(c, b);
      else lastFinished.remove(c);
      SweepMapper.BatchRow a = mapper.activeBatch(c.name());
      if (a != null) active.put(c, a);
      else active.remove(c);
    }
    openOutflows = mapper.openOutflows();
  }

  @Override
  public List<Item> evaluate() {
    List<Item> out = new ArrayList<>();
    if (!enabled()) return out;
    String problem = schemes.problem().orElse(null);
    if (problem == null && schemes.current().isPresent()) problem = operator.problem().orElse(null);
    else if (problem == null && operator.anyConfigured() && operator.problem().isPresent()) problem = operator.problem().get();
    out.add(new Item("sweep.config", "资金归集配置有问题", "Sweep configuration problem", problem == null,
        problem == null ? "正常" : problem + "。以太坊、BSC 归集不能执行（不影响充值、提现和健康检查）"));
    SweepSettings s = SweepSettings.parse(config.snapshot());
    for (Chain c : List.of(Chain.ERC20, Chain.BSC)) {
      BigDecimal min = s.operatorMin().getOrDefault(c.name(), BigDecimal.ZERO);
      BigDecimal bal = service.operatorBalance(c);
      if (min.signum() > 0) {
        boolean low = bal != null && bal.compareTo(min) < 0;
        out.add(new Item("sweep.operator." + c.name(), c.name() + " 归集发起账户余额不足", c.name() + " sweep operator balance low", !low,
            low ? c.name() + " 发起账户 " + operator.address().orElse("") + " 余额 " + SweepSettings.plain(bal) + " " + SweepService.gasAsset(c) + "，低于告警下限 "
                + SweepSettings.plain(min) + "（归集设置页）。请补充手续费币，余额不够时归集会失败" : "余额 " + (bal == null ? "还没查到" : SweepSettings.plain(bal))));
      }
      SweepMapper.BatchRow b = lastFinished.get(c);
      SweepMapper.BatchRow a = active.get(c);
      boolean stuck = a != null && service.stuck(a.id);
      boolean bad = b != null && (SweepService.FAILED.equals(b.status) || SweepService.PARTIAL.equals(b.status));
      if (b != null || stuck) {
        out.add(new Item("sweep.failed." + c.name(), c.name() + " 资金归集失败", c.name() + " sweep failed", !bad && !stuck,
            stuck ? "批次 " + a.publicNo + " 加价重发后仍未上链" : bad ? "最近一批 " + b.publicNo + "：" + b.status + "（" + b.failReason + "）" : "正常"));
      }
    }
    if (schemes.current().isPresent() || openOutflows > 0) {
      out.add(new Item("sweep.outflow", "充值地址有不是归集的转出", "Unexpected outflow from deposit addresses", openOutflows == 0,
          openOutflows == 0 ? "正常" : openOutflows + " 笔不是系统归集的转出还没处理（后台对账页 SWEEP_UNKNOWN_OUTFLOW / SWEEP_DEST_MISMATCH）"));
    }
    return out;
  }
}
