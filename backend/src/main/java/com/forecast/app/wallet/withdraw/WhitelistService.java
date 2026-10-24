package com.forecast.app.wallet.withdraw;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.address.Base58;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 提现白名单（需求 4.4-4）：每人最多 10 个，新地址 24 小时后生效，地址格式按网络校验。 */
@Service
class WhitelistService {

  private static final Pattern ETH = Pattern.compile("0x[0-9a-fA-F]{40}");

  private final WithdrawMapper mapper;
  private final WithdrawLimits limits;
  private final Clock clock;

  WhitelistService(WithdrawMapper mapper, WithdrawLimits limits, Clock clock) {
    this.mapper = mapper;
    this.limits = limits;
    this.clock = clock;
  }

  List<WhitelistRow> list(long userId) {
    return mapper.whitelist(userId);
  }

  @Transactional
  WhitelistRow add(long userId, String network, String address, String label) {
    ValidationException.Collector errs = new ValidationException.Collector();
    Chain chain = null;
    try {
      chain = network == null ? null : Chain.valueOf(network);
    } catch (IllegalArgumentException e) {
      chain = null;
    }
    if (chain == null) errs.add("network", "invalid");
    String a = address == null ? "" : address.trim();
    if (a.isEmpty()) errs.add("address", "required");
    else if (chain != null && !validAddress(chain, a)) errs.add("address", "addressInvalid");
    String l = label == null ? "" : label.trim();
    if (l.isEmpty()) errs.add("label", "required");
    else if (l.length() > 32) errs.add("label", "tooLong");
    errs.throwIfAny();

    if (mapper.countWhitelist(userId) >= props().maxWhitelist()) throw new BizException("WHITELIST_FULL", "白名单已满");
    LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    WhitelistRow r = new WhitelistRow();
    r.userId = userId;
    r.network = chain.name();
    r.address = a;
    r.addressKey = chain.family == Chain.Family.ETH ? a.toLowerCase() : a;
    r.label = l;
    r.createdAt = now;
    r.activeAt = now.plus(props().whitelistDelay());
    try {
      mapper.insertWhitelist(r);
    } catch (DuplicateKeyException e) {
      throw new BizException("WHITELIST_DUPLICATE", "地址已在白名单里");
    }
    return r;
  }

  @Transactional
  void remove(long userId, long id) {
    if (mapper.deleteWhitelist(userId, id) != 1) throw new BizException("NOT_FOUND", "白名单地址不存在");
  }

  /** 以太坊体系：0x + 40 位十六进制；TRON：Base58Check、0x41 开头、21 字节 */
  static boolean validAddress(Chain chain, String a) {
    if (chain.family == Chain.Family.ETH) return ETH.matcher(a).matches();
    try {
      byte[] p = Base58.decodeChecked(a);
      return p.length == 21 && p[0] == 0x41;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** 当前的提现参数（后台可改，S16a） */
  private WithdrawProperties props() {
    return limits.current();
  }
}
