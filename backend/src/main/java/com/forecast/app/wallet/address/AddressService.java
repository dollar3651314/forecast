package com.forecast.app.wallet.address;

import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.SweepEvmProperties;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.wallet.api.WalletApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 充值地址（开发记录/钱包-合约地址方案.md）。每个体系只有一种方案（表 {@code wallet_address} 的 {@code scheme}）：
 * <ul>
 *   <li>以太坊体系（ERC20、BEP20 共用一个地址）<b>只用</b> {@code FORWARDER}：地址 = BitGo 工厂的 CREATE2 地址（{@link Create2}，
 *       参数见 {@link ForwarderSchemes}），里面的钱只能去 parent（Safe 冷钱包），服务器动不了。合约地址参数没配齐或检查不通过时
 *       这两条链<b>"暂未开通"</b>：不分配、客户端不显示地址，<b>不退回 xpub 派生</b>（Amos 2026-10-25）。</li>
 *   <li>TRON 用 {@code XPUB}（充值方案 D1，方案 B）：Amos 配置的账户级 xpub 按 BIP44 派生，序号 = 用户 ID，服务器只有公钥。
 *       TRON 不是 EVM，不能用 BitGo 转发合约和 CREATE2 公式，归集走离线签名（下一阶段加租能量）。</li>
 * </ul>
 * 每个用户每个体系同时只有一个"正在用"（ACTIVE）的地址，客户端只显示它；换一套合约参数时原来的地址改成"停用"（RETIRED），
 * 继续扫块 {@code retiredScanDays} 天、照常入账（SW-9）。表里以前留下的以太坊体系 {@code XPUB} 地址（只可能来自改方案之前的测试环境）
 * 不再显示给客户，但仍然扫块入账：配好合约参数后由 {@link #migrateAll()} 停用、继续扫块 {@code retiredScanDays} 天。
 */
@Service
@EnableConfigurationProperties({WalletProperties.class, SweepEvmProperties.class})
public class AddressService implements WalletApi {

  private static final Logger log = LoggerFactory.getLogger(AddressService.class);
  public static final String XPUB = "XPUB";
  public static final String FORWARDER = "FORWARDER";

  private final AddressMapper mapper;
  private final WalletProperties props;
  private final SweepEvmProperties sweep;
  private final ForwarderSchemes schemes;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final LedgerApi ledger;

  AddressService(AddressMapper mapper, WalletProperties props, SweepEvmProperties sweep, ForwarderSchemes schemes, Clock clock, TransactionTemplate tx,
      LedgerApi ledger) {
    this.mapper = mapper;
    this.props = props;
    this.sweep = sweep;
    this.schemes = schemes;
    this.clock = clock;
    this.tx = tx;
    this.ledger = ledger;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  /**
   * 分配充值地址（注册时、第一次打开充值页时）。很多客户同时调用时不能互相锁住（SQ-8）：
   * <b>不对可能不存在的行做加锁读</b>。可重复读下 {@code SELECT … FOR UPDATE} 查不存在的 {@code active_key} 加的是间隙锁，
   * 新客户的键都落在唯一索引同一个间隙里，两个事务各拿一个间隙锁（互相兼容），再各自 INSERT 要插入意向锁时都被对方挡住 → 死锁。
   * 现在先普通读；没有就直接 {@code INSERT IGNORE}，靠唯一键 {@code uk_wallet_address_active} 保证每人每体系只有一个正在用的地址
   * （同一客户的几个请求同时到时，后来的插入被唯一键挡住、什么也不做）。插入只要插入意向锁，不同客户之间互不阻塞。
   */
  @Override
  @Transactional
  public void allocateAddresses(long userId) {
    if (userId <= 0 || userId > Integer.MAX_VALUE) throw new IllegalArgumentException("用户 ID 超出派生序号范围");
    // 以太坊体系只用合约地址：参数没配好时不分配（"暂未开通"），不退回 xpub
    schemes.current().ifPresent(s -> useForwarder(userId, s));
    Chain.Family tron = Chain.Family.TRON;
    if (mapper.findActive(userId, tron.name()) != null) return;
    String xpub = props.tronXpub();
    if (xpub == null || xpub.isBlank()) {
      log.warn("没有配置 TRON 的 xpub，暂不分配 TRC20 充值地址 user={}", userId);
      return;
    }
    int index = (int) userId;
    String address = deriveTron(xpub, index);
    mapper.insertActive(userId, tron.name(), XPUB, 0, index, address, Addresses.key(tron, address), null); // 别的请求刚插了同一个：唯一键挡住，返回 0
  }

  /** 这条链能不能充值（有地址可以给客户）：以太坊体系要合约地址参数配好，TRON 要 xpub */
  public boolean open(Chain chain) {
    if (chain.family == Chain.Family.ETH) return schemes.current().isPresent();
    return props.tronXpub() != null && !props.tronXpub().isBlank();
  }

  private static boolean isCurrent(AddressMapper.AddressRow a, ForwarderScheme s) {
    return a != null && FORWARDER.equals(a.scheme()) && a.schemeVer() == s.ver();
  }

  /**
   * 以太坊体系换成这一套合约地址：原来正在用的地址停用（继续扫块 N 天），新地址启用。已经是这一套时什么也不做。
   * 新客户（还没有地址）直接插入，不加锁读（SQ-8）；只有确实有一个旧地址要停用时，才对那一行（已存在）加排他锁。
   */
  private void useForwarder(long userId, ForwarderScheme s) {
    String family = Chain.Family.ETH.name();
    AddressMapper.AddressRow seen = mapper.findActive(userId, family);
    if (isCurrent(seen, s)) return;
    String salt = Create2.salt(userId);
    String address = Create2.forwarderAddress(s.factory(), s.implementation(), s.parent(), s.feeAddress(), salt);
    if (seen == null) {
      if (mapper.insertActive(userId, family, FORWARDER, s.ver(), (int) userId, address, address.toLowerCase(), salt) == 1) return;
      // 被唯一键挡住：别的请求刚给这个客户插了（读最新提交的那一行，已存在，只是行锁），或者以前停用过同一套参数的地址（下面重新启用）
      if (isCurrent(mapper.shareActive(userId, family), s)) return;
    }
    AddressMapper.AddressRow active = mapper.lockActive(userId, family);
    if (isCurrent(active, s)) return;
    if (active != null) mapper.retire(active.id(), now());
    if (mapper.insertActive(userId, family, FORWARDER, s.ver(), (int) userId, address, address.toLowerCase(), salt) == 0) {
      mapper.reactivate(userId, family, FORWARDER, s.ver());
    }
    if (active != null) log.info("以太坊体系充值地址换成合约地址（第 {} 套）user={}，原地址 {} 停用，继续扫块 {} 天", s.ver(), userId, active.address(), sweep.retiredScanDays());
  }

  /**
   * 启动时把已有用户的以太坊体系地址换成当前这一套合约地址（SW-9：测试环境已发出的地址停用、继续扫块 30 天）。没配合约地址参数时什么也不做。
   *
   * @return 换了多少个用户
   */
  public int migrateAll() {
    Optional<ForwarderScheme> s = schemes.current();
    if (s.isEmpty()) return 0;
    int n = 0;
    long after = 0;
    while (true) {
      List<Long> users = mapper.usersToMigrate(Chain.Family.ETH.name(), s.get().ver(), after, 500);
      if (users.isEmpty()) break;
      for (long uid : users) {
        tx.executeWithoutResult(st -> useForwarder(uid, s.get()));
        n++;
        after = uid;
      }
    }
    if (n > 0) log.info("以太坊体系充值地址换成合约地址：{} 个用户", n);
    return n;
  }

  /**
   * 用户在这条链上正在用的充值地址；还没分配（或者以太坊体系还不是当前这一套合约地址）时先分配。
   * 以太坊体系的合约地址参数没配好时为空（"暂未开通"），即使表里有以前的地址也不给客户显示。
   * <p>不包在一个大事务里（SQ-8）：可重复读的快照建在第一次读时，同一客户另一个请求刚提交的地址在快照里看不到，分配完再读会读成"没有"。
   * 现在每次读都是最新提交的数据；分配单独一个事务，事务开头先锁这个客户的账户行（{@link LedgerApi#lock}，下注、提现也从这一行排队）：
   * 同一客户几个请求同时到时排队分配，后面的读到前面刚提交的地址就不再插入。不锁的话，两个插入同时撞上唯一键的事务会在主键末尾各留一个间隙锁，
   * 再插下一个体系时互相等待，被 InnoDB 判死锁。锁账户行以后不再碰别的锁，充值、提现锁了账户行也不碰本表，没有环。
   * 万一仍被判死锁（例如账户行被长事务占着以外的情况），重试，最多 3 次、间隔逐次加长。
   */
  public Optional<String> addressOf(long userId, Chain chain) {
    if (!open(chain)) return Optional.empty();
    Optional<AddressMapper.AddressRow> a = active(userId, chain.family);
    boolean stale = chain.family == Chain.Family.ETH && schemes.current().map(s -> a.isEmpty() || !FORWARDER.equals(a.get().scheme())
        || a.get().schemeVer() != s.ver()).orElse(false);
    if (a.isPresent() && !stale) return a.map(AddressMapper.AddressRow::address);
    for (int attempt = 1; ; attempt++) {
      try {
        tx.executeWithoutResult(st -> {
          ledger.lock(userId); // 同一客户的分配排队（锁定读不建快照，下面的普通读看得到前一个请求刚提交的地址）
          allocateAddresses(userId);
        });
        break;
      } catch (PessimisticLockingFailureException e) {
        if (attempt >= ALLOCATE_ATTEMPTS) throw e;
        log.info("分配充值地址被判死锁回滚（同一客户的请求同时到），第 {} 次重试 user={}", attempt, userId);
        sleepQuietly(20L * attempt);
      }
    }
    return active(userId, chain.family).map(AddressMapper.AddressRow::address);
  }

  static final int ALLOCATE_ATTEMPTS = 3;

  private static void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 链上收款地址属于哪个用户：正在用的地址，或停用不超过 retiredScanDays 天的老地址 */
  public Optional<Long> userOf(Chain chain, String address) {
    if (address == null) return Optional.empty();
    return Optional.ofNullable(mapper.userOf(chain.family.name(), Addresses.key(chain.family, address), now().minusDays(sweep.retiredScanDays())));
  }

  /**
   * 我们的一个充值地址（不管停用多久）。扫块用它识别"从我们的地址转出"（归集、或者不是归集的异常转出）。
   *
   * @param schemeVer 合约地址是第几套参数；xpub 地址为 0
   */
  public record OwnAddress(long id, long userId, String scheme, int schemeVer, String address, String salt, String status, boolean deployed,
      LocalDateTime retiredAt) {
    public boolean forwarder() {
      return FORWARDER.equals(scheme);
    }
  }

  public Optional<OwnAddress> owner(Chain chain, String address) {
    if (address == null) return Optional.empty();
    AddressMapper.AddressRow r = mapper.ownerOf(chain.family.name(), Addresses.key(chain.family, address));
    return Optional.ofNullable(r).map(AddressService::own);
  }

  /** 这个地址收到的充值还入不入账：正在用的，或停用不超过 retiredScanDays 天（和 {@link #userOf} 同一口径） */
  public boolean accepts(OwnAddress a) {
    return "ACTIVE".equals(a.status()) || a.retiredAt() != null && !a.retiredAt().isBefore(now().minusDays(sweep.retiredScanDays()));
  }

  /** 合约转发地址第一次部署了（归集时顺便部署） */
  public void markDeployed(long addressId) {
    mapper.markDeployed(addressId, now());
  }

  static OwnAddress own(AddressMapper.AddressRow r) {
    return new OwnAddress(r.id(), r.userId(), r.scheme(), r.schemeVer(), r.address(), r.salt(), r.status(), r.deployedAt() != null, r.retiredAt());
  }

  private Optional<AddressMapper.AddressRow> active(long userId, Chain.Family family) {
    return mapper.byUser(userId).stream().filter(r -> r.family().equals(family.name()) && "ACTIVE".equals(r.status())).findFirst();
  }

  /** TRON 充值地址：账户级 xpub → /0/序号（以太坊体系不用 xpub，见类说明） */
  static String deriveTron(String xpub, int index) {
    return Addresses.tron(Bip32.receiving(xpub, index));
  }
}
