package com.forecast.app.wallet.web;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.web.CurrentUser;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.Confirmations;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.wallet.address.AddressService;
import com.forecast.app.wallet.deposit.DepositService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 钱包接口：充值地址（S12a，字段和客户端 DepositAddress 一致）、充值记录（S12b）。 */
@RestController
class WalletController {

  record DepositAddressView(String network, String address, int confirmations, int etaMinutes, String minDeposit) {}

  /** 充值记录。status：pending 待确认 / credited 已到账 / dropped 已作废 / belowMin 低于最小充值金额 / reversed 已冲正 */
  record DepositView(String id, String network, String txHash, String amount, String status, int confirmations, int requiredConfirmations, String time) {}

  private final AddressService addresses;
  private final DepositService deposits;
  private final WalletProperties props;
  private final Confirmations confirmations;

  WalletController(AddressService addresses, DepositService deposits, WalletProperties props, Confirmations confirmations) {
    this.addresses = addresses;
    this.deposits = deposits;
    this.props = props;
    this.confirmations = confirmations;
  }

  /** 最近 50 条充值记录（S12b） */
  @GetMapping("/api/wallet/deposits")
  List<DepositView> deposits() {
    return deposits.history(CurrentUser.id(), 50).stream().map(d -> {
      WalletProperties.ChainConfig cfg = props.chain(d.chain());
      int required = cfg == null ? 0 : confirmations.of(d.chain());
      String status = DepositService.apiStatus(d.status());
      return new DepositView(String.valueOf(d.no()), d.chain().name(), d.txHash(), d.amount().stripTrailingZeros().toPlainString(), status,
          Math.min(d.confirmations(), required), required, d.createdAt().toInstant(java.time.ZoneOffset.UTC).toString());
    }).toList();
  }

  /**
   * 已开通的链的充值地址、需要的确认数、预计到账分钟数、最小充值金额（AC-06、AC-41）。
   * <b>没开通的链不出现在列表里</b>，客户端那条链显示"暂未开通"：ERC20、BEP20 只用合约地址，合约地址参数没配好时不开通（不退回 xpub，
   * 开发记录/钱包-合约地址方案.md）；TRC20 要配了 xpub。三条链都没开通时返回 {@code WALLET_NOT_READY}（和以前一样）。
   */
  @GetMapping("/api/wallet/addresses")
  List<DepositAddressView> addresses() {
    long uid = CurrentUser.id();
    List<DepositAddressView> out = new ArrayList<>();
    for (Chain c : Chain.values()) {
      WalletProperties.ChainConfig cfg = props.chain(c);
      String address = addresses.addressOf(uid, c).orElse(null);
      if (address == null) continue;
      int conf = cfg == null ? 0 : confirmations.of(c);
      int eta = cfg == null ? 0 : (int) Math.ceil(conf * cfg.blockSeconds() / 60.0);
      out.add(new DepositAddressView(c.name(), address, conf, eta, props.minDeposit().stripTrailingZeros().toPlainString()));
    }
    if (out.isEmpty()) throw new BizException("WALLET_NOT_READY", "充值地址暂不可用");
    return out;
  }
}
