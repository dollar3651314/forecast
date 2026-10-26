package com.forecast.app.riskalert.scan;

import com.forecast.app.account.api.AccountSignalsApi;
import com.forecast.app.pool.api.PoolApi;
import com.forecast.app.riskalert.core.RiskMapper;
import com.forecast.app.riskalert.core.RiskRules;
import com.forecast.app.wallet.api.WalletSignalsApi;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 风控扫描（forecast S10）：从各模块的 api 按 id 往后读新数据，交给 {@link RiskRules}，进度记在 {@code riskalert_cursor}。
 * 派彩完成（"新账户押中冷门"）不在这里扫，由 {@link PayoutDoneListener} 在结算提交后处理。
 * <ul>
 *   <li>来源：设备号和 IP（account）、充值来源地址、提现白名单、提现申请（wallet）、下注（pool）。</li>
 *   <li><b>一行一个事务</b>（处理 + 推进进度）：一行出错只记日志、跳过这一行（告警丢一条，扫描不会卡住）。</li>
 *   <li><b>等进行中的事务提交</b>：写入时间晚于"现在 − scan-lag"的行先不处理，这一轮到它为止；id 小但提交晚的行下一轮还会读到。
 *       写入时间比现在还晚 1 分钟以上的（端到端测试把时钟拨回去以后）直接处理，不让扫描停住。scan-lag = 0 时不等（后端测试、端到端测试）。</li>
 *   <li>重复处理没有副作用：线索、关联、告警都有唯一键，邮件只在新记告警时发。</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(RiskProperties.class)
public class RiskScanner {

  private static final Logger log = LoggerFactory.getLogger(RiskScanner.class);
  private static final Duration FUTURE = Duration.ofMinutes(1);

  private final RiskMapper mapper;
  private final RiskRules rules;
  private final AccountSignalsApi accounts;
  private final WalletSignalsApi wallet;
  private final PoolApi pool;
  private final RiskProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  RiskScanner(RiskMapper mapper, RiskRules rules, AccountSignalsApi accounts, WalletSignalsApi wallet, PoolApi pool, RiskProperties props,
      PlatformTransactionManager tm, Clock clock) {
    this.mapper = mapper;
    this.rules = rules;
    this.accounts = accounts;
    this.wallet = wallet;
    this.pool = pool;
    this.props = props;
    this.tx = new TransactionTemplate(tm);
    this.clock = clock;
  }

  /** 一轮各来源处理了几行 */
  public record Report(int devices, int deposits, int whitelist, int withdrawals, int bets) {

    public int total() {
      return devices + deposits + whitelist + withdrawals + bets;
    }
  }

  /** 扫一轮全部来源（关联的线索在前，下注和结算在后：同一轮里新建的关联马上能用来查对押） */
  public Report scanAll() {
    LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    LocalDateTime before = now.minus(props.getScanLag());
    int n = props.getBatch();
    int devices = byId("devices", before, now, id -> accounts.devicesAfter(id, n), AccountSignalsApi.DeviceSignal::id, AccountSignalsApi.DeviceSignal::at,
        d -> {
          rules.onSignal("DEVICE", d.deviceHash(), d.userId(), d.at());
          rules.onSignal("IP", d.ip(), d.userId(), d.at());
        });
    int deposits = byId("deposits", null, now, id -> wallet.depositSourcesAfter(id, props.getScanLag(), n), WalletSignalsApi.DepositSource::id, null,
        d -> rules.onSignal("DEPOSIT_FROM", d.chain() + ":" + d.fromAddress(), d.userId(), d.at()), WalletSignalsApi.DepositSource::ripe);
    int whitelist = byId("whitelist", before, now, id -> wallet.whitelistAfter(id, n), WalletSignalsApi.WhitelistAddress::id,
        WalletSignalsApi.WhitelistAddress::at, w -> rules.onSignal("WITHDRAW_TO", w.network() + ":" + w.addressKey(), w.userId(), w.at()));
    int withdrawals = byId("withdrawals", before, now, id -> wallet.withdrawalsAfter(id, n), WalletSignalsApi.WithdrawalRequest::id,
        WalletSignalsApi.WithdrawalRequest::at, rules::onWithdrawal);
    int bets = byId("bets", before, now, id -> pool.betsAfter(id, n), PoolApi.BetSignal::id, PoolApi.BetSignal::receivedAt, rules::onBet);
    Report r = new Report(devices, deposits, whitelist, withdrawals, bets);
    if (devices + deposits + whitelist + withdrawals + bets > 0) log.debug("风控扫描：{}", r);
    return r;
  }

  private <T> int byId(String name, LocalDateTime before, LocalDateTime now, Function<Long, List<T>> load, ToLongFunction<T> id,
      Function<T, LocalDateTime> at, Consumer<T> handle) {
    return byId(name, before, now, load, id, at, handle, null);
  }

  /**
   * 按 id 往后扫一个来源。ripe 为空时按写入时间判断（at ≤ before，或比现在还晚 1 分钟以上）。
   */
  private <T> int byId(String name, LocalDateTime before, LocalDateTime now, Function<Long, List<T>> load, ToLongFunction<T> id,
      Function<T, LocalDateTime> at, Consumer<T> handle, java.util.function.Predicate<T> ripe) {
    RiskMapper.CursorRow c = mapper.cursor(name);
    long cursor = c == null ? 0 : c.lastId;
    int done = 0;
    for (T row : load.apply(cursor)) {
      boolean ok = props.getScanLag().isZero() || (ripe != null ? ripe.test(row) : isRipe(at.apply(row), before, now));
      if (!ok) break;
      long rowId = id.applyAsLong(row);
      try {
        tx.executeWithoutResult(st -> {
          handle.accept(row);
          mapper.moveCursor(name, rowId);
        });
      } catch (RuntimeException e) {
        log.error("风控扫描 {} 第 {} 行出错，跳过", name, rowId, e);
        mapper.moveCursor(name, rowId);
      }
      done++;
    }
    return done;
  }

  static boolean isRipe(LocalDateTime at, LocalDateTime before, LocalDateTime now) {
    return at == null || !at.isAfter(before) || at.isAfter(now.plus(FUTURE));
  }
}
