package com.forecast.app.wallet.withdraw;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.common.ratelimit.UserWriteLimited;
import com.forecast.app.common.web.CurrentUser;
import com.forecast.app.ledger.api.FundsApi;
import com.forecast.app.wallet.Chain;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 客户的提现接口（S13a），字段和客户端 WhitelistAddress、Withdrawal、WithdrawInfo 一致。 */
@RestController
class WithdrawController {

  record WhitelistView(String id, String network, String address, String label, String createdAt, String activeAt) {}

  record AddWhitelist(String network, String address, String label) {}

  record WithdrawalView(String id, String amount, String fee, String network, String address, String status, String createdAt, String coolingEndsAt,
      String delayEndsAt, String txHash, String rejectReason) {}

  record SubmitWithdraw(String whitelistId, String amount) {}

  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  private final WhitelistService whitelist;
  private final WithdrawService withdraws;
  private final WithdrawMapper mapper;
  private final WithdrawLimits limits;
  private final AccountApi account;
  private final FundsApi funds;

  WithdrawController(WhitelistService whitelist, WithdrawService withdraws, WithdrawMapper mapper, WithdrawLimits limits, AccountApi account,
      FundsApi funds) {
    this.whitelist = whitelist;
    this.withdraws = withdraws;
    this.mapper = mapper;
    this.limits = limits;
    this.account = account;
    this.funds = funds;
  }

  @GetMapping("/api/wallet/whitelist")
  List<WhitelistView> whitelist() {
    return whitelist.list(CurrentUser.id()).stream().map(WithdrawController::view).toList();
  }

  @UserWriteLimited
  @PostMapping("/api/wallet/whitelist")
  WhitelistView addWhitelist(@RequestBody AddWhitelist req) {
    return view(whitelist.add(CurrentUser.id(), req.network(), req.address(), req.label()));
  }

  @UserWriteLimited
  @DeleteMapping("/api/wallet/whitelist/{id}")
  void removeWhitelist(@PathVariable long id) {
    whitelist.remove(CurrentUser.id(), id);
  }

  /** 提现页需要的参数：KYC 状态、可提金额、今天已用、限额、手续费等 */
  @GetMapping("/api/wallet/withdraw-info")
  Map<String, Object> info() {
    long uid = CurrentUser.id();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kycStatus", account.kycStatus(uid).name().toLowerCase());
    m.put("available", money(funds.withdrawable(uid).max(BigDecimal.ZERO)));
    m.put("usedToday", money(withdraws.usedToday(uid)));
    m.put("minAmount", money(props().minAmount()));
    m.put("maxPerTx", money(props().maxPerTx()));
    m.put("dailyLimit", money(props().dailyLimit()));
    m.put("largeAmount", money(props().largeAmount()));
    m.put("largeDelayHours", props().largeDelay().toHours());
    m.put("coolingHours", props().cooling().toHours());
    Map<String, String> fees = new LinkedHashMap<>();
    for (Chain c : Chain.values()) fees.put(c.name(), money(props().fee(c)));
    m.put("fees", fees);
    m.put("maxWhitelist", props().maxWhitelist());
    return m;
  }

  @UserWriteLimited
  @PostMapping("/api/wallet/withdrawals")
  WithdrawalView submit(@RequestBody SubmitWithdraw req) {
    return view(withdraws.submit(CurrentUser.id(), req.whitelistId(), req.amount()));
  }

  @GetMapping("/api/wallet/withdrawals")
  Page<WithdrawalView> list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    long uid = CurrentUser.id();
    int p = Math.max(page, 1);
    int size = Math.min(Math.max(pageSize, 1), 100);
    return new Page<>(mapper.byUser(uid, (p - 1) * size, size).stream().map(WithdrawController::view).toList(), mapper.countByUser(uid), p, size);
  }

  /** 路径里的 id 是对外编号（雪花 ID，字符串）；不是自己的提现当成不存在 */
  @PostMapping("/api/wallet/withdrawals/{id}/cancel")
  WithdrawalView cancel(@PathVariable long id) {
    long uid = CurrentUser.id();
    Long internal = mapper.idByNo(uid, id);
    return view(withdraws.cancel(uid, internal == null ? -1 : internal));
  }

  static WhitelistView view(WhitelistRow r) {
    return new WhitelistView(String.valueOf(r.id), r.network, r.address, r.label, iso(r.createdAt), iso(r.activeAt));
  }

  static WithdrawalView view(WithdrawalRow r) {
    return new WithdrawalView(String.valueOf(r.publicNo), money(r.amount), money(r.fee), r.network, r.address, WithdrawService.apiStatus(r.status), iso(r.createdAt),
        iso(r.coolingEndsAt), iso(r.delayEndsAt), r.txHash, r.rejectReason);
  }

  static String money(BigDecimal v) {
    return v.setScale(2, java.math.RoundingMode.DOWN).toPlainString();
  }

  static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }

  /** 当前的提现参数（后台可改，S16a） */
  private WithdrawProperties props() {
    return limits.current();
  }
}
