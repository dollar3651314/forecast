package com.forecast.app.wallet.withdraw;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.account.api.KycStatus;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.id.Snowflake;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.FundsApi;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.notification.api.NotifyApi;
import com.forecast.app.common.push.UserEventsApi;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.api.AutoApprove;
import com.forecast.app.wallet.api.WithdrawAdminApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 提现（技术方案/v1-提现方案.md）。状态：
 * 冷静期 → 待审核 → 已审核待上链（大额先延迟 6 小时）→ 上链中 → 已完成；
 * 任何失败路径（撤销、驳回、紧急取消、确定没上链）都把冻结金额完整退回。
 * 每次状态变化都在锁住提现单之后按"从哪个状态到哪个状态"条件更新，并记一条提现事件。
 * <p>v2 提现自动审批（技术方案/v2-提现自动审批.md）：提交后预判一次（只做参考）；冷静期结束时在同一个事务里复判，已启用的规则全部通过（并且总开关打开）时由系统代替审核人员
 * "通过"（{@value #AUTO_ACTOR}），之后和人工通过完全一样（大额延迟 6 小时、上链）；否则转人工，和以前一样。规则只决定要不要人工看，不放宽任何限额。
 */
@Service
@EnableConfigurationProperties(WithdrawProperties.class)
public class WithdrawService implements WithdrawAdminApi {

  private static final Logger log = LoggerFactory.getLogger(WithdrawService.class);
  static final String BIZ_WITHDRAW = "WITHDRAW";
  /** 自动通过时提现事件的操作人（事件表 actor 列 VARCHAR(32)） */
  static final String AUTO_ACTOR = "system:auto-approve";
  private static final Pattern AMOUNT = Pattern.compile("\\d{1,12}(\\.\\d{1,8})?");

  private final WithdrawMapper mapper;
  private final LedgerApi ledger;
  private final FundsApi funds;
  private final AccountApi account;
  private final UserEventsApi events;
  private final NotifyApi notify;
  private final WithdrawSenders senders;
  private final WithdrawLimits limits;
  private final AutoApproveService auto;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Snowflake ids;

  WithdrawService(WithdrawMapper mapper, LedgerApi ledger, FundsApi funds, AccountApi account, UserEventsApi events, NotifyApi notify, WithdrawSenders senders,
      WithdrawLimits limits, AutoApproveService auto, TransactionTemplate tx, Clock clock, Snowflake ids) {
    this.mapper = mapper;
    this.ledger = ledger;
    this.funds = funds;
    this.account = account;
    this.events = events;
    this.notify = notify;
    this.senders = senders;
    this.limits = limits;
    this.auto = auto;
    this.tx = tx;
    this.clock = clock;
    this.ids = ids;
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  /** 今天（UTC）已经用掉的额度 */
  BigDecimal usedToday(long userId) {
    return mapper.usedSince(userId, now().toLocalDate().atStartOfDay());
  }

  // ---------- 客户 ----------

  /**
   * 提交提现（AC-17）。顺序：字段 → KYC → 白名单（存在、已生效）→ 最小、单笔 → 锁账户后查当日累计和可提金额 → 冻结。
   */
  WithdrawalRow submit(long userId, String whitelistId, String amountText) {
    ValidationException.Collector errs = new ValidationException.Collector();
    BigDecimal amount = null;
    String a = amountText == null ? "" : amountText.trim();
    if (a.isEmpty()) errs.add("amount", "required");
    else if (!AMOUNT.matcher(a).matches() || new BigDecimal(a).signum() <= 0) errs.add("amount", "positiveNumber");
    else if (new BigDecimal(a).scale() > 2) errs.add("amount", "amountDigits");
    else amount = new BigDecimal(a);
    Long wid = null;
    try {
      wid = whitelistId == null ? null : Long.valueOf(whitelistId);
    } catch (NumberFormatException e) {
      wid = null;
    }
    if (wid == null) errs.add("whitelistId", "selectAddress");
    errs.throwIfAny();

    if (account.kycStatus(userId) != KycStatus.APPROVED) throw new BizException("KYC_REQUIRED", "提现前需要通过身份认证");
    WhitelistRow w = mapper.whitelistById(userId, wid);
    if (w == null) throw new BizException("NOT_WHITELISTED", "只能提到白名单地址");
    if (w.activeAt.isAfter(now())) throw new BizException("WHITELIST_NOT_ACTIVE", "白名单地址添加满 24 小时后才能使用");
    if (amount.compareTo(props().minAmount()) < 0) throw new BizException("AMOUNT_BELOW_MIN", "低于最小提现金额");
    if (amount.compareTo(props().maxPerTx()) > 0) throw new BizException("AMOUNT_ABOVE_MAX", "超过单笔提现上限");
    Chain network = Chain.valueOf(w.network);
    BigDecimal fee = props().fee(network);
    if (amount.compareTo(fee) <= 0) throw new BizException("AMOUNT_BELOW_MIN", "金额不够付手续费");
    final BigDecimal amt = amount;

    WithdrawalRow r = tx.execute(st -> {
      ledger.lock(userId);
      // 当日累计必须严格小于上限（需求 4.4-4，和客户端一致）；锁住账户后再算，并发提交不会一起超过
      if (usedToday(userId).add(amt).compareTo(props().dailyLimit()) >= 0) throw new BizException("DAILY_LIMIT_EXCEEDED", "超过当日提现累计上限");
      if (amt.compareTo(funds.withdrawable(userId)) > 0) throw new BizException("INSUFFICIENT_BALANCE", "可提金额不足");
      WithdrawalRow n = new WithdrawalRow();
      n.publicNo = ids.next();
      n.userId = userId;
      n.network = network.name();
      n.address = w.address;
      n.amount = amt;
      n.fee = fee;
      n.status = "COOLING";
      n.createdAt = now();
      n.coolingEndsAt = n.createdAt.plus(props().cooling());
      mapper.insertWithdrawal(n);
      ledger.apply(new LedgerCommand(userId, Bucket.BALANCE, amt.negate(), LedgerEntryType.WITHDRAW_FREEZE, BIZ_WITHDRAW, String.valueOf(n.id), "提现冻结"));
      ledger.apply(new LedgerCommand(userId, Bucket.WITHDRAW_FROZEN, amt, LedgerEntryType.WITHDRAW_FREEZE, BIZ_WITHDRAW, String.valueOf(n.id), "提现冻结"));
      mapper.insertEvent(n.id, null, "COOLING", "user:" + userId, null);
      return n;
    });
    notify(r);
    // 提交时预判（不占额度、不决定结果，出错只记日志）
    auto.precheck(r.id);
    return r;
  }

  /** 冷静期内撤销（AC-18）：冻结立即退回；过了冷静期不能撤销 */
  WithdrawalRow cancel(long userId, long id) {
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = mapper.lock(id);
      if (w == null || w.userId != userId) throw new BizException("NOT_FOUND", "提现不存在");
      if (!"COOLING".equals(w.status) || !now().isBefore(w.coolingEndsAt)) throw new BizException("WITHDRAW_NOT_CANCELLABLE", "冷静期已过，不能撤销");
      move(w, "CANCELLED", "user:" + userId, null);
      refund(w);
      return w;
    });
    notify(r);
    return r;
  }

  // ---------- 后台 ----------

  @Override
  public void approve(long withdrawalId, long adminId) {
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = reviewing(withdrawalId);
      boolean large = w.amount.compareTo(props().largeAmount()) >= 0;
      LocalDateTime delay = large ? now().plus(props().largeDelay()) : null;
      mapper.setReview(w.id, adminId, now(), null, delay);
      move(w, large ? "DELAYED" : "APPROVED", "admin:" + adminId, large ? "大额，延迟到 " + delay : null);
      w.delayEndsAt = delay;
      return w;
    });
    notify(r);
    if ("APPROVED".equals(r.status)) send(r.id);
  }

  @Override
  public void reject(long withdrawalId, long adminId, String reason) {
    if (reason == null || reason.isBlank()) throw new ValidationException(java.util.List.of(new ValidationException.Field("reason", "required")));
    if (reason.length() > 500) throw new ValidationException(java.util.List.of(new ValidationException.Field("reason", "tooLong")));
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = reviewing(withdrawalId);
      mapper.setReview(w.id, adminId, now(), reason.trim(), null);
      move(w, "REJECTED", "admin:" + adminId, reason.trim());
      refund(w);
      w.rejectReason = reason.trim();
      return w;
    });
    notify(r);
  }

  @Override
  public void emergencyCancel(long withdrawalId, long adminId, String reason) {
    if (reason == null || reason.isBlank()) throw new ValidationException(java.util.List.of(new ValidationException.Field("reason", "required")));
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = mapper.lock(withdrawalId);
      if (w == null) throw new BizException("NOT_FOUND", "提现不存在");
      if (!"DELAYED".equals(w.status)) throw new BizException("WITHDRAW_NOT_CANCELLABLE", "只有延迟期内的大额提现可以紧急取消");
      move(w, "CANCELLED", "admin:" + adminId, "紧急取消：" + reason.trim());
      refund(w);
      return w;
    });
    notify(r);
  }

  private WithdrawalRow reviewing(long id) {
    WithdrawalRow w = mapper.lock(id);
    if (w == null) throw new BizException("NOT_FOUND", "提现不存在");
    if (!"REVIEWING".equals(w.status)) throw new BizException("WITHDRAW_NOT_REVIEWABLE", "只能审核待审核的提现");
    return w;
  }

  // ---------- 系统（定时任务） ----------

  /** 推进到期的状态：冷静期满 → 待审核；大额延迟满 → 待上链（修复 F1：直接进入上链，不依赖别的事件）；然后发送所有待上链的 */
  void advance() {
    LocalDateTime now = now();
    for (Long id : mapper.due("COOLING", "cooling_ends_at", now)) coolingEnded(id);
    for (Long id : mapper.due("DELAYED", "delay_ends_at", now)) systemMove(id, "DELAYED", "APPROVED", "延迟期结束，进入上链");
    for (Long id : mapper.withStatus("APPROVED")) send(id);
  }

  /**
   * 冷静期结束（v2 自动审批，方案第 3、5 节）：锁住提现单、确认还在冷静期 → 复判（总开关打开时锁全局锁，R2 / R3 在锁里现算）→ 冷静期 → 待审核（system）；
   * 复判全部通过时同一个事务里再 待审核 → 已审核待上链 / 延迟中（{@value #AUTO_ACTOR}），写 review_mode = AUTO。提交以后才上链（不在事务里访问节点）。
   * 复判整个出错时转人工（尽量记一条 ERROR 判定），不会因为出错而放行，也不会卡在冷静期。
   */
  void coolingEnded(long id) {
    WithdrawalRow r;
    try {
      // 配置在事务外一次读出：事务里第一条一致性读要发生在拿到全局锁之后，才能看到前一笔刚提交的自动通过
      AutoApprove.Settings s = auto.settings();
      r = tx.execute(st -> {
        WithdrawalRow w = mapper.lock(id);
        if (w == null || !"COOLING".equals(w.status) || now().isBefore(w.coolingEndsAt)) return null;
        AutoApproveService.Decision d = auto.decideFinal(w, s);
        move(w, "REVIEWING", "system", "冷静期结束");
        if (d.approved()) autoApprove(w, d);
        return w;
      });
    } catch (RuntimeException e) {
      log.error("提现 {} 冷静期结束时自动审批判定出错，转人工", id, e);
      r = coolingEndedManually(id, e);
    }
    if (r == null) return;
    notify(r);
    if ("APPROVED".equals(r.status)) send(r.id);
  }

  /** 自动通过：和人工通过一样，< 大额阈值直接待上链，≥ 大额阈值延迟 6 小时；审核人为空，事件操作人是 system:auto-approve */
  private void autoApprove(WithdrawalRow w, AutoApproveService.Decision d) {
    boolean large = w.amount.compareTo(props().largeAmount()) >= 0;
    LocalDateTime delay = large ? now().plus(props().largeDelay()) : null;
    auto.markApproved(w.id, now(), delay);
    String note = "自动审批通过（判定 #" + d.checkId() + "，配置第 " + d.configVersion() + " 版）" + (large ? "；大额，延迟到 " + delay : "");
    move(w, large ? "DELAYED" : "APPROVED", AUTO_ACTOR, note);
    w.delayEndsAt = delay;
  }

  /** 复判出错后的兜底：记一条 ERROR 判定并转人工；连这也失败就只转人工 */
  private WithdrawalRow coolingEndedManually(long id, RuntimeException cause) {
    try {
      AutoApprove.Settings s = auto.settings();
      return tx.execute(st -> {
        WithdrawalRow w = mapper.lock(id);
        if (w == null || !"COOLING".equals(w.status) || now().isBefore(w.coolingEndsAt)) return null;
        auto.recordError(w, s, "判定出错：" + cause.getClass().getSimpleName());
        move(w, "REVIEWING", "system", "冷静期结束（自动审批判定出错，转人工）");
        return w;
      });
    } catch (RuntimeException e) {
      log.error("提现 {} 记录自动审批出错失败，直接转人工", id, e);
      return tx.execute(st -> {
        WithdrawalRow w = mapper.lock(id);
        if (w == null || !"COOLING".equals(w.status)) return null;
        move(w, "REVIEWING", "system", "冷静期结束（自动审批判定出错，转人工）");
        return w;
      });
    }
  }

  private void systemMove(long id, String from, String to, String note) {
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = mapper.lock(id);
      if (w == null || !from.equals(w.status)) return null;
      move(w, to, "system", note);
      return w;
    });
    if (r != null) notify(r);
  }

  /** 待上链 → 交给广播器。没有广播器或广播器说"等一等"时留在待上链（W4）；确定没上链时失败退款 */
  void send(long id) {
    WithdrawalRow w = mapper.byId(id);
    if (w == null || !"APPROVED".equals(w.status)) return;
    var sender = senders.get(Chain.valueOf(w.network));
    if (sender.isEmpty()) {
      log.debug("{} 没有广播器，提现 {} 留在待上链", w.network, id);
      return;
    }
    WithdrawSender.Outcome out = sender.get().send(new WithdrawSender.Order(w.id, w.network, w.address, w.amount.subtract(w.fee)));
    switch (out) {
      case WithdrawSender.Sent s -> {
        WithdrawalRow r = tx.execute(st -> {
          WithdrawalRow l = mapper.lock(id);
          if (!"APPROVED".equals(l.status)) return null;
          mapper.setTx(id, s.txHash());
          move(l, "BROADCASTING", "system", s.txHash());
          return l;
        });
        if (r != null) notify(r);
      }
      case WithdrawSender.Wait wait -> log.warn("提现 {} 暂时不能上链：{}", id, wait.reason());
      case WithdrawSender.Rejected rej -> failed(id, "APPROVED", rej.reason());
    }
  }

  /** 上链中的提现查链上结果（S13b）：确认够了完成；确定转账没发生则失败退款；其他情况继续等（加价重发、过期重建在广播器里） */
  void track() {
    for (Long id : mapper.withStatus("BROADCASTING")) {
      WithdrawalRow w = mapper.byId(id);
      var sender = senders.get(Chain.valueOf(w.network));
      if (sender.isEmpty()) continue;
      switch (sender.get().check(id)) {
        case WithdrawSender.Confirmed c -> {
          mapper.setTx(id, c.txHash());
          confirmed(id);
        }
        case WithdrawSender.Failed f -> failed(id, "BROADCASTING", f.reason());
        case WithdrawSender.Pending p -> { }
      }
    }
  }

  /** 链上确认：上链中 → 已完成，扣掉提现冻结（S13b 的确认任务调用） */
  public void confirmed(long id) {
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = mapper.lock(id);
      if (w == null || !"BROADCASTING".equals(w.status)) return null;
      move(w, "COMPLETED", "system", null);
      mapper.setCompleted(id, now());
      ledger.apply(new LedgerCommand(w.userId, Bucket.WITHDRAW_FROZEN, w.amount.negate(), LedgerEntryType.WITHDRAW_PAYOUT, BIZ_WITHDRAW,
          String.valueOf(w.id), "提现完成（含手续费 " + w.fee.stripTrailingZeros().toPlainString() + "）"));
      return w;
    });
    if (r != null) notify(r);
  }

  /** 确定没有上链（广播被拒、TRON 交易过期查不到）：失败退款（AC-20）。只能从待上链或上链中进入 */
  public void failed(long id, String expectedStatus, String reason) {
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = mapper.lock(id);
      if (w == null || !expectedStatus.equals(w.status)) return null;
      move(w, "FAILED_REFUNDED", "system", reason);
      refund(w);
      return w;
    });
    if (r != null) {
      log.warn("提现 {} 上链失败，已退回：{}", id, reason);
      notify(r);
    }
  }

  // ---------- 公共 ----------

  private void move(WithdrawalRow w, String to, String actor, String note) {
    if (mapper.transit(w.id, w.status, to) != 1) throw new IllegalStateException("提现状态已变化：" + w.id);
    mapper.insertEvent(w.id, w.status, to, actor, note);
    String from = w.status;
    w.status = to;
    if (notifyCustomer(from, to, actor)) {
      Map<String, String> p = new LinkedHashMap<>();
      // 对外编号（雪花 ID，约定 25）：和客户端提现记录上显示的编号一致（体验优化 1021 UX-11，以前传的是内部自增 id）
      p.put("orderId", String.valueOf(w.publicNo));
      p.put("amount", w.amount.stripTrailingZeros().toPlainString());
      p.put("status", to);
      // 只有驳回把原因告诉客户；紧急取消、上链失败的原因是内部信息
      if ("REJECTED".equals(to) && note != null) p.put("reason", note);
      notify.toUser(w.userId, "WITHDRAW_STATUS", p);
    }
  }

  /**
   * 哪些状态变化通知客户（通知方案 N6）：客户自己的操作（提交、撤销）不通知，冷静期结束进入审核不通知；
   * 审核通过（含大额延迟）、驳回、开始上链、完成、失败退回、紧急取消都通知。延迟期满进入上链时不再通知"审核通过"，紧接着会通知"开始上链"。
   */
  static boolean notifyCustomer(String from, String to, String actor) {
    if (actor != null && actor.startsWith("user:")) return false;
    return switch (to) {
      case "APPROVED" -> "REVIEWING".equals(from);
      case "DELAYED", "REJECTED", "BROADCASTING", "COMPLETED", "FAILED_REFUNDED", "CANCELLED" -> true;
      default -> false;
    };
  }

  /** 退回：提现冻结 → 余额 */
  private void refund(WithdrawalRow w) {
    ledger.apply(new LedgerCommand(w.userId, Bucket.WITHDRAW_FROZEN, w.amount.negate(), LedgerEntryType.WITHDRAW_UNFREEZE, BIZ_WITHDRAW,
        String.valueOf(w.id), "提现退回"));
    ledger.apply(new LedgerCommand(w.userId, Bucket.BALANCE, w.amount, LedgerEntryType.WITHDRAW_UNFREEZE, BIZ_WITHDRAW, String.valueOf(w.id), "提现退回"));
  }

  /** 推给客户：{"type":"withdraw","status":"cooling","amount":"100"} 和账户变化 */
  private void notify(WithdrawalRow w) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("type", "withdraw");
    e.put("status", apiStatus(w.status));
    e.put("amount", w.amount.stripTrailingZeros().toPlainString());
    events.send(w.userId, e);
    events.send(w.userId, Map.of("type", "account"));
  }

  /** 数据库状态 → 客户端状态名（WithdrawStatus） */
  static String apiStatus(String s) {
    return switch (s) {
      case "FAILED_REFUNDED" -> "failedRefunded";
      default -> s.toLowerCase();
    };
  }

  /** 当前的提现参数（后台可改，S16a） */
  private WithdrawProperties props() {
    return limits.current();
  }
}
