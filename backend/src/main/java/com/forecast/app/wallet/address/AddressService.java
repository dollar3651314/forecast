package com.forecast.app.wallet.address;

import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.SweepEvmProperties;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.wallet.api.WalletApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 充值地址。两种方案（表 {@code wallet_address} 的 {@code scheme}，迁移 V28）：
 * <ul>
 *   <li>{@code XPUB}（充值方案 D1）：用 Amos 配置的账户级 xpub 按 BIP44 派生，序号 = 用户 ID。服务器只有公钥，动不了地址里的钱。TRON 一直用它。</li>
 *   <li>{@code FORWARDER}（v2 归集，技术方案/v2-半自动归集.md 第 4.4、4.13 节）：以太坊体系配好了合约地址参数（{@link ForwarderSchemes}）时，
 *       地址 = BitGo 工厂的 CREATE2 地址（{@link Create2}），里面的钱只能去 parent（Safe）。服务器同样动不了这些钱。</li>
 * </ul>
 * 每个用户每个体系同时只有一个"正在用"（ACTIVE）的地址，客户端只显示它；换成合约地址时原来的地址改成"停用"（RETIRED），
 * 继续扫块 {@code retiredScanDays} 天、照常入账（SW-9）。合约地址参数没配时：已经有合约地址的保持不变，没有地址的用 xpub 派生。
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

  AddressService(AddressMapper mapper, WalletProperties props, SweepEvmProperties sweep, ForwarderSchemes schemes, Clock clock, TransactionTemplate tx) {
    this.mapper = mapper;
    this.props = props;
    this.sweep = sweep;
    this.schemes = schemes;
    this.clock = clock;
    this.tx = tx;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  @Override
  @Transactional
  public void allocateAddresses(long userId) {
    if (userId <= 0 || userId > Integer.MAX_VALUE) throw new IllegalArgumentException("用户 ID 超出派生序号范围");
    for (Chain.Family f : Chain.Family.values()) {
      Optional<ForwarderScheme> scheme = f == Chain.Family.ETH ? schemes.current() : Optional.empty();
      if (scheme.isPresent()) {
        useForwarder(userId, scheme.get());
        continue;
      }
      if (mapper.lockActive(userId, f.name()) != null) continue; // 已经有正在用的地址（可能是合约地址：配置去掉后保持不变）
      String xpub = xpub(f);
      if (xpub == null) {
        log.warn("没有配置 {} 的 xpub，暂不分配充值地址 user={}", f, userId);
        continue;
      }
      int index = (int) userId;
      String address = derive(f, xpub, index);
      mapper.insertActive(userId, f.name(), XPUB, 0, index, address, Addresses.key(f, address), null);
    }
  }

  /** 以太坊体系换成这一套合约地址：原来正在用的地址停用（继续扫块 N 天），新地址启用。已经是这一套时什么也不做 */
  private void useForwarder(long userId, ForwarderScheme s) {
    String family = Chain.Family.ETH.name();
    AddressMapper.AddressRow active = mapper.lockActive(userId, family);
    if (active != null && FORWARDER.equals(active.scheme()) && active.schemeVer() == s.ver()) return;
    if (active != null) mapper.retire(active.id(), now());
    String salt = Create2.salt(userId);
    String address = Create2.forwarderAddress(s.factory(), s.implementation(), s.parent(), s.feeAddress(), salt);
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

  /** 用户在这条链上正在用的充值地址；还没分配（或者以太坊体系还不是当前这一套合约地址）时先分配 */
  @Transactional
  public Optional<String> addressOf(long userId, Chain chain) {
    Optional<AddressMapper.AddressRow> a = active(userId, chain.family);
    boolean stale = chain.family == Chain.Family.ETH && schemes.current().map(s -> a.isEmpty() || !FORWARDER.equals(a.get().scheme())
        || a.get().schemeVer() != s.ver()).orElse(false);
    if (a.isPresent() && !stale) return a.map(AddressMapper.AddressRow::address);
    allocateAddresses(userId);
    return active(userId, chain.family).map(AddressMapper.AddressRow::address);
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

  private String xpub(Chain.Family f) {
    String x = f == Chain.Family.ETH ? props.ethXpub() : props.tronXpub();
    return x == null || x.isBlank() ? null : x;
  }

  static String derive(Chain.Family f, String xpub, int index) {
    var point = Bip32.receiving(xpub, index);
    return f == Chain.Family.ETH ? Addresses.eth(point) : Addresses.tron(point);
  }
}
