package com.forecast.app.wallet.deposit;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.account.api.KycStatus;
import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.ledger.api.CustomerFlowApi;
import com.forecast.app.notification.api.NotifyApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 通知 #7（forecast S9，需求第 7 节、第 3.10 节）：一笔充值入账后，累计充值（扣冲正）<b>第一次超过</b> KYC 阈值、客户还没通过身份认证时，
 * 告诉客户"需要认证才能继续下注"。口径和下注的拒单检查一致（{@code pool.bet.BetService}：累计充值 &gt; 阈值且没通过认证 → KYC_REQUIRED）。
 * 在入账的事务里调用（入账回滚就不发）；只在跨过阈值的那一笔发，之后的充值不再发。
 */
@Component
class KycThresholdNotice {

  private final AccountApi accounts;
  private final CustomerFlowApi flows;
  private final SystemConfig config;
  private final NotifyApi notify;

  KycThresholdNotice(AccountApi accounts, CustomerFlowApi flows, SystemConfig config, NotifyApi notify) {
    this.accounts = accounts;
    this.flows = flows;
    this.config = config;
    this.notify = notify;
  }

  /** @return 是否发了 */
  boolean afterCredit(long userId, BigDecimal amount) {
    if (accounts.kycStatus(userId) == KycStatus.APPROVED) return false;
    BigDecimal threshold = config.decimal(ConfigKey.KYC_DEPOSIT_THRESHOLD, new BigDecimal("1000"));
    BigDecimal after = flows.netDeposits(userId);
    BigDecimal before = after.subtract(amount);
    if (!(before.compareTo(threshold) <= 0 && after.compareTo(threshold) > 0)) return false;
    Map<String, String> v = new LinkedHashMap<>();
    v.put("threshold", money(threshold));
    v.put("total", money(after));
    notify.toUser(userId, "KYC_REQUIRED", v);
    return true;
  }

  static String money(BigDecimal v) {
    return v.setScale(2, RoundingMode.DOWN).toPlainString();
  }
}
