package com.forecast.app.wallet.admin;

import com.forecast.app.wallet.api.WalletSignalsApi;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link WalletSignalsApi} 的实现（forecast S10）。只读；不依赖提现、自动审批的业务类（避免和 riskalert 互相依赖）。 */
@Service
class WalletSignals implements WalletSignalsApi {

  private final WalletSignalsMapper mapper;

  WalletSignals(WalletSignalsMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  @Transactional(readOnly = true)
  public List<DepositSource> depositSourcesAfter(long afterId, java.time.Duration lag, int limit) {
    return mapper.depositsAfter(afterId, Math.max(0, lag.toMillis()), limit).stream()
        .map(r -> new DepositSource(r.id, r.userId, r.chain, addressKey(r.chain, r.address), r.at, r.ripe)).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<WhitelistAddress> whitelistAfter(long afterId, int limit) {
    return mapper.whitelistAfter(afterId, limit).stream()
        .map(r -> new WhitelistAddress(r.id, r.userId, r.chain, addressKey(r.chain, r.address), r.at)).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<WithdrawalRequest> withdrawalsAfter(long afterId, int limit) {
    return mapper.withdrawalsAfter(afterId, limit).stream().map(r -> new WithdrawalRequest(r.id, r.publicNo, r.userId, r.amount, r.at)).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public java.util.Map<Long, java.time.LocalDateTime> firstCredited(java.util.Collection<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) return java.util.Map.of();
    java.util.Map<Long, java.time.LocalDateTime> out = new java.util.LinkedHashMap<>();
    for (WalletSignalsMapper.FirstCredit f : mapper.firstCredited(java.util.Set.copyOf(userIds))) out.put(f.userId, f.at);
    return out;
  }

  /** 以太坊体系（0x 开头）不分大小写，统一小写；TRON 的 Base58 区分大小写，原样 */
  static String addressKey(String chain, String address) {
    if (address == null) return "";
    String a = address.strip();
    return a.startsWith("0x") || a.startsWith("0X") ? a.toLowerCase(Locale.ROOT) : a;
  }
}
