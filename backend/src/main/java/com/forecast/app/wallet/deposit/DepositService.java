package com.forecast.app.wallet.deposit;

import com.forecast.app.common.health.AlertProperties;
import com.forecast.app.common.id.Snowflake;
import com.forecast.app.notification.api.NotifyApi;
import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.FundsApi;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.common.push.UserEventsApi;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.Confirmations;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.wallet.address.AddressService;
import com.forecast.app.wallet.chain.NodeException;
import com.forecast.app.wallet.sweep.SweepOutflows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 扫块和入账（充值方案第 2 节）。每条链：
 * <ol>
 *   <li>扫块：从上次扫到的区块往后，查配置好的 USDT 合约的转账，收款地址是我们的就记一条充值（待确认 / 低于最小充值金额）</li>
 *   <li>确认：确认数够了，再核对一次区块哈希没变（没被回滚），入账；变了就作废</li>
 *   <li>复查：入账后的 2 倍确认数内区块哈希变了 → 冲正，余额不够扣的挂账并通知风控（F4）</li>
 * </ol>
 * 只认配置好的 USDT 合约（修复 F3）：没有配置合约的链不扫块；客户端返回的事件合约不对也不记，打日志（AC-08）。
 * <p>充值记录每次变化（发现、确认数变化、入账、作废、冲正）都在写库以后推给本人
 * {"type":"deposit","status":"pending","network":"ERC20","amount":"100","confirmations":1}（修复 E2E-2：原来只有入账时才有推送，
 * 客户端看不到"待确认"）。推送失败不影响扫块和入账。
 */
@Service
public class DepositService {

  private static final Logger log = LoggerFactory.getLogger(DepositService.class);
  static final String BIZ_DEPOSIT = "DEPOSIT";

  private final DepositMapper mapper;
  private final ChainClients clients;
  private final AddressService addresses;
  private final LedgerApi ledger;
  private final WalletProperties props;
  private final TransactionTemplate tx;
  private final FundsApi funds;
  private final StaffAlertApi staff;
  private final NotifyApi notify;
  private final Confirmations confirmations;
  private final UserEventsApi events;
  private final ChainMonitor monitor;
  private final Snowflake ids;
  /** v2 归集：从我们的充值地址转出的 USDT 交给它区分"归集"和"不是归集的转出"；测试里可以为空 */
  private final SweepOutflows outflows;
  /** 通知 #7（S9）：累计充值第一次超过 KYC 阈值；测试里可以为空 */
  private final KycThresholdNotice kycNotice;

  /** 测试用：不关心告警和对外编号的生成器时（用默认参数各建一个） */
  DepositService(DepositMapper mapper, ChainClients clients, AddressService addresses, LedgerApi ledger, WalletProperties props, TransactionTemplate tx,
      FundsApi funds, StaffAlertApi staff, NotifyApi notify, Confirmations confirmations, UserEventsApi events) {
    this(mapper, clients, addresses, ledger, props, tx, funds, staff, notify, confirmations, events,
        new ChainMonitor(props, clients, AlertProperties.defaults(), Clock.systemUTC(), null), Snowflake.shared(1), null, null);
  }

  @Autowired
  DepositService(DepositMapper mapper, ChainClients clients, AddressService addresses, LedgerApi ledger, WalletProperties props, TransactionTemplate tx,
      FundsApi funds, StaffAlertApi staff, NotifyApi notify, Confirmations confirmations, UserEventsApi events, ChainMonitor monitor, Snowflake ids,
      SweepOutflows outflows, KycThresholdNotice kycNotice) {
    this.mapper = mapper;
    this.clients = clients;
    this.addresses = addresses;
    this.ledger = ledger;
    this.props = props;
    this.tx = tx;
    this.funds = funds;
    this.staff = staff;
    this.notify = notify;
    this.confirmations = confirmations;
    this.events = events;
    this.monitor = monitor;
    this.ids = ids;
    this.outflows = outflows;
    this.kycNotice = kycNotice;
  }

  /** 每 15 秒：每条链先扫块再确认 */
  @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
  void tick() {
    for (Chain c : Chain.values()) {
      try {
        run(c);
      } catch (RuntimeException e) {
        log.error("{} 扫块或入账失败", c, e);
      }
    }
  }

  /**
   * 扫块 + 确认一次。没有客户端或没有配置 USDT 合约时什么也不做。
   * 结果告诉 {@link ChainMonitor}（E8 告警）：访问节点出错（{@link NodeException}）算节点失败；扫完一轮记下扫到的区块。
   */
  public void run(Chain chain) {
    WalletProperties.ChainConfig cfg = props.chain(chain);
    Optional<ChainClient> client = clients.get(chain);
    if (cfg == null || !cfg.scanReady() || client.isEmpty()) return;
    try {
      long head = client.get().headBlock();
      monitor.nodeOk(chain, head);
      long cursor = scan(chain, cfg, client.get(), head);
      confirm(chain, cfg, client.get(), head);
      recheck(chain, cfg, client.get(), head);
      monitor.scanned(chain, cursor, head);
    } catch (NodeException e) {
      monitor.nodeFailed(chain, e);
      throw e;
    }
  }

  /**
   * 入账后继续核对 2 倍确认数的区块：区块哈希变了（极少见的深度回滚）就冲正（修复 F4）。
   */
  private void recheck(Chain chain, WalletProperties.ChainConfig cfg, ChainClient client, long head) {
    for (DepositMapper.DepositRow d : mapper.recentCredited(chain.name(), head - 2L * confirmations.of(chain))) {
      Optional<String> hash = client.blockHash(d.blockNumber());
      if (hash.isPresent() && hash.get().equalsIgnoreCase(d.blockHash())) continue;
      reverse(d);
    }
  }

  /**
   * 冲正（AC-09）：只扣客户的可用余额（S2 口径：不动下注锁定和提现冻结——下注中的钱属于奖池，提现冻结的钱在出账的路上），
   * 可用余额不会变负；没扣到的差额记挂账，通知风控人员。
   */
  void reverse(DepositMapper.DepositRow d) {
    BigDecimal shortfall = tx.execute(st -> {
      ledger.lock(d.userId());
      if (mapper.markReversed(d.id()) != 1) return null;
      BigDecimal free = funds.withdrawable(d.userId()).max(BigDecimal.ZERO).setScale(8, RoundingMode.DOWN);
      BigDecimal take = d.amount().min(free);
      if (take.signum() > 0) {
        ledger.apply(new LedgerCommand(d.userId(), Bucket.AVAILABLE, take.negate(), LedgerEntryType.DEPOSIT_REVERSAL, BIZ_DEPOSIT,
            d.chain() + ":" + d.txHash() + ":" + d.logIndex(), "充值被链上回滚，冲正 " + d.chain()));
      }
      BigDecimal rest = d.amount().subtract(take);
      if (rest.signum() > 0) mapper.insertSuspense(d.userId(), d.id(), rest, "冲正时可用余额不足");
      return rest;
    });
    if (shortfall == null) return;
    push(d.userId(), Chain.valueOf(d.chain()), "REVERSED", d.amount(), d.confirmations());
    log.warn("充值被链上回滚，已冲正 chain={} tx={} user={} amount={} 挂账={}", d.chain(), d.txHash(), d.userId(), d.amount(), shortfall);
    if (shortfall.signum() > 0) {
      staff.notifyStaff("DEPOSIT_SUSPENSE", Map.of("chain", d.chain(), "txHash", d.txHash(), "userId", String.valueOf(d.userId()),
          "amount", d.amount().toPlainString(), "shortfall", shortfall.toPlainString()));
    }
  }

  /** 客户看到的一条充值记录；no 是对外编号（雪花 ID，旧数据等于内部 id） */
  public record DepositRecord(long no, Chain chain, String txHash, BigDecimal amount, String status, int confirmations, java.time.LocalDateTime createdAt) {}

  /** 客户的充值记录（最近的在前） */
  public java.util.List<DepositRecord> history(long userId, int limit) {
    return mapper.byUser(userId, limit).stream()
        .map(d -> new DepositRecord(d.publicNo(), Chain.valueOf(d.chain()), d.txHash(), d.amount(), d.status(), d.confirmations(), d.createdAt())).toList();
  }

  /** 扫一批区块，返回扫到的区块号 */
  private long scan(Chain chain, WalletProperties.ChainConfig cfg, ChainClient client, long head) {
    Long last = mapper.cursor(chain.name());
    if (last == null) last = (cfg.startBlock() != null ? cfg.startBlock() : head) - 1;
    if (last >= head) return last;
    long from = last + 1;
    long to = Math.min(head, last + cfg.scanBatch());
    for (ChainClient.TokenTransfer t : client.transfers(from, to, cfg.usdtContract())) {
      if (!sameContract(chain, t.contract(), cfg.usdtContract())) {
        log.warn("忽略非配置的代币合约 chain={} contract={} tx={}", chain, t.contract(), t.txHash());
        continue;
      }
      // v2 归集：从我们的地址转出（归集，或者不是归集的异常转出），交给 SweepOutflows 区分、记录、告警；不影响入账
      if (outflows != null) {
        addresses.owner(chain, t.from()).ifPresent(o -> outflows.record(chain, o, t.txHash(), t.logIndex(), t.blockNumber(), t.to(), t.rawAmount(), cfg.usdtDecimals()));
      }
      Optional<AddressService.OwnAddress> dest = addresses.owner(chain, t.to());
      if (dest.isEmpty()) continue;
      if (!addresses.accepts(dest.get())) {
        log.warn("停用超过期限的老充值地址收到 USDT，不入账，需要人工处理 chain={} address={} user={} tx={}", chain, dest.get().address(), dest.get().userId(),
            t.txHash());
        continue;
      }
      Optional<Long> user = Optional.of(dest.get().userId());
      BigDecimal amount = new BigDecimal(t.rawAmount()).movePointLeft(cfg.usdtDecimals()).setScale(8, RoundingMode.DOWN);
      String status = amount.compareTo(props.minDeposit()) < 0 ? "BELOW_MIN" : "PENDING";
      long uid = user.get();
      // 低于最小金额的：记录和通知 #4（只发站内信，S9）在同一个事务里，记录已存在（重复扫到）就不再发
      Boolean inserted = tx.execute(st -> {
        boolean ins = mapper.insertIgnore(ids.next(), chain.name(), t.txHash(), t.logIndex(), t.blockNumber(), t.blockHash(), t.from(), t.to(), uid, amount,
            status) == 1;
        if (ins && "BELOW_MIN".equals(status)) {
          notify.toUser(uid, "DEPOSIT_BELOW_MIN", Map.of("amount", amount.stripTrailingZeros().toPlainString(), "chain", chain.name(), "min",
              props.minDeposit().stripTrailingZeros().toPlainString()));
        }
        return ins;
      });
      if (Boolean.TRUE.equals(inserted)) {
        log.info("发现充值 chain={} tx={} user={} amount={} status={}", chain, t.txHash(), user.get(), amount, status);
        // 低于最小充值金额的不会再变，这里就推；待确认的由同一次 run 里紧接着的 confirm 推（新记录的确认数是 0，一定会变），
        // 避免同一笔推两次。插入是自动提交的，客户端收到后重新拉取时记录已经能查到
        if ("BELOW_MIN".equals(status)) push(user.get(), chain, status, amount, (int) Math.max(0, head - t.blockNumber() + 1));
      }
    }
    mapper.saveCursor(chain.name(), to);
    return to;
  }

  private void confirm(Chain chain, WalletProperties.ChainConfig cfg, ChainClient client, long head) {
    for (DepositMapper.DepositRow d : mapper.pending(chain.name())) {
      int conf = (int) Math.max(0, head - d.blockNumber() + 1);
      if (conf < confirmations.of(chain)) {
        mapper.updateConfirmations(d.id(), conf);
        // 确认数变了才推（包括刚发现的：库里是 0），客户端刷新进度（例如 3/12）
        if (conf != d.confirmations()) push(d.userId(), chain, "PENDING", d.amount(), conf);
        continue;
      }
      Optional<String> hash = client.blockHash(d.blockNumber());
      if (hash.isEmpty() || !hash.get().equalsIgnoreCase(d.blockHash())) {
        if (mapper.finish(d.id(), "DROPPED", conf) == 1) {
          log.warn("充值所在区块被回滚，作废 chain={} tx={}", chain, d.txHash());
          push(d.userId(), chain, "DROPPED", d.amount(), conf);
        }
        continue;
      }
      credit(d, conf);
    }
  }

  /** 状态改成已入账和记账在同一个事务里；幂等键 链:交易:日志序号 */
  private void credit(DepositMapper.DepositRow d, int conf) {
    Boolean done = tx.execute(st -> {
      if (mapper.finish(d.id(), "CREDITED", conf) != 1) return false;
      ledger.apply(new LedgerCommand(d.userId(), Bucket.AVAILABLE, d.amount(), LedgerEntryType.DEPOSIT, BIZ_DEPOSIT,
          d.chain() + ":" + d.txHash() + ":" + d.logIndex(), "充值 " + d.chain()));
      // 到账通知（AC-07），和入账同一事务
      notify.toUser(d.userId(), "DEPOSIT_CREDITED", Map.of("amount", d.amount().stripTrailingZeros().toPlainString(), "chain", d.chain()));
      // 通知 #7（S9）：累计充值第一次超过 KYC 阈值、还没认证
      if (kycNotice != null) kycNotice.afterCredit(d.userId(), d.amount());
      return true;
    });
    if (!Boolean.TRUE.equals(done)) return;
    // 事务提交以后再推：充值记录和余额都变了
    push(d.userId(), Chain.valueOf(d.chain()), "CREDITED", d.amount(), conf);
    send(d.userId(), Map.of("type", "account"));
    log.info("充值入账 chain={} tx={} user={} amount={}", d.chain(), d.txHash(), d.userId(), d.amount());
  }

  /**
   * 推给本人：充值记录变了。status 和充值记录接口（WalletController）的口径一致：pending / credited / dropped / belowMin / reversed。
   * 推送只是提醒客户端重新拉取充值记录，失败只打日志。
   */
  private void push(long userId, Chain chain, String dbStatus, BigDecimal amount, int conf) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("type", "deposit");
    e.put("status", apiStatus(dbStatus));
    e.put("network", chain.name());
    e.put("amount", amount.stripTrailingZeros().toPlainString());
    e.put("confirmations", conf);
    send(userId, e);
  }

  private void send(long userId, Map<String, Object> e) {
    try {
      events.send(userId, e);
    } catch (RuntimeException ex) {
      log.debug("充值推送失败 user={}", userId, ex);
    }
  }

  /** 数据库状态 → 客户端状态名（DepositStatus） */
  public static String apiStatus(String s) {
    return switch (s) {
      case "BELOW_MIN" -> "belowMin";
      default -> s.toLowerCase();
    };
  }

  /** 以太坊体系合约地址不区分大小写；TRON 原样比较 */
  private static boolean sameContract(Chain chain, String a, String b) {
    if (a == null || b == null) return false;
    return chain.family == Chain.Family.ETH ? a.equalsIgnoreCase(b) : a.equals(b);
  }
}
