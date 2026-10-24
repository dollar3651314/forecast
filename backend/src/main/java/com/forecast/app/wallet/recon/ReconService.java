package com.forecast.app.wallet.recon;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.ledger.api.LedgerQueryApi;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.api.WalletReconApi;
import com.forecast.app.wallet.chain.NodeException;
import com.forecast.app.wallet.deposit.ChainClients;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 对账（需求 4.4-7，S14）：钱包记录、账本、链上三方对一遍，不一致的记成问题、通知风控人员，由人工处理后标记已处理（修复 F5）。
 * 只读，不自动改任何金额。规则见 技术方案/v1-数据库设计.md 第 16 节。
 */
@Service
class ReconService implements WalletReconApi {

  private static final Logger log = LoggerFactory.getLogger(ReconService.class);
  static final Set<String> IN_FLIGHT = Set.of("COOLING", "REVIEWING", "APPROVED", "DELAYED", "BROADCASTING");
  static final Set<String> REFUNDED = Set.of("CANCELLED", "REJECTED", "FAILED_REFUNDED");
  /** 已入账的充值，近 7 天的再去链上核对一次区块哈希（入账后 2 倍确认数内的由充值服务自己复查） */
  static final Duration CHAIN_RECHECK = Duration.ofDays(7);

  private final ReconMapper mapper;
  private final LedgerQueryApi ledger;
  private final ChainClients clients;
  private final StaffAlertApi staff;
  private final TransactionTemplate tx;
  private final Clock clock;

  ReconService(ReconMapper mapper, LedgerQueryApi ledger, ChainClients clients, StaffAlertApi staff, TransactionTemplate tx, Clock clock) {
    this.mapper = mapper;
    this.ledger = ledger;
    this.clients = clients;
    this.staff = staff;
    this.tx = tx;
    this.clock = clock;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  /** 发现的一条差异 */
  record Finding(String kind, String refId, Long userId, BigDecimal expected, BigDecimal actual, String detail) {}


  @Override
  public int run() {
    List<Finding> found = new ArrayList<>();
    checkDeposits(found);
    checkWithdrawals(found);
    checkSweeps(found);
    int fresh = 0;
    for (Finding f : found) {
      // 归集的三类是链上已经发生的事实，处理过（RESOLVED）以后不再重复列出；其他类型只看有没有待处理的
      boolean permanent = f.kind().startsWith("SWEEP_");
      if ((permanent ? mapper.issueCount(f.kind(), f.refId()) : mapper.openIssueCount(f.kind(), f.refId())) > 0) continue;
      mapper.insertIssue(f.kind(), f.refId(), f.userId(), f.expected(), f.actual(), f.detail());
      fresh++;
    }
    if (fresh > 0) {
      log.warn("对账发现 {} 个新问题", fresh);
      staff.notifyStaff("RECON_ISSUES", Map.of("count", String.valueOf(fresh)));
    }
    return fresh;
  }

  /** 充值：钱包记录 ↔ 账本 ↔ 链上；挂账 */
  private void checkDeposits(List<Finding> out) {
    Map<String, BigDecimal> credited = ledger.totals("DEPOSIT", LedgerEntryType.DEPOSIT, Bucket.BALANCE);
    Map<String, BigDecimal> reversed = ledger.totals("DEPOSIT", LedgerEntryType.DEPOSIT_REVERSAL, Bucket.BALANCE);
    Map<Long, BigDecimal> suspenseByDeposit = new HashMap<>();
    for (ReconMapper.SuspenseRow s : mapper.suspenses()) {
      suspenseByDeposit.merge(s.depositId(), s.amount(), BigDecimal::add);
      if ("OPEN".equals(s.status())) {
        out.add(new Finding("SUSPENSE_OPEN", String.valueOf(s.id()), s.userId(), BigDecimal.ZERO, s.amount(), "充值冲正时余额不够扣的差额，待处理（充值 #" + s.depositId() + "）"));
      }
    }
    Set<String> known = new HashSet<>();
    LocalDateTime recheckFrom = now().minus(CHAIN_RECHECK);
    for (ReconMapper.DepositRow d : mapper.creditedDeposits()) {
      String key = d.chain() + ":" + d.txHash() + ":" + d.logIndex();
      known.add(key);
      BigDecimal in = credited.getOrDefault(key, BigDecimal.ZERO);
      if (in.compareTo(d.amount()) != 0) {
        out.add(new Finding("DEPOSIT_LEDGER_MISMATCH", key, d.userId(), d.amount(), in, "充值记录的金额和账本入账不一致"));
      }
      if ("REVERSED".equals(d.status())) {
        BigDecimal back = reversed.getOrDefault(key, BigDecimal.ZERO).negate().add(suspenseByDeposit.getOrDefault(d.id(), BigDecimal.ZERO));
        if (back.compareTo(d.amount()) != 0) {
          out.add(new Finding("DEPOSIT_REVERSAL_MISMATCH", key, d.userId(), d.amount(), back, "冲正金额 + 挂账 应该等于充值金额"));
        }
      } else if (!d.createdAt().isBefore(recheckFrom)) {
        chainCheck(d, key, out);
      }
    }
    for (Map.Entry<String, BigDecimal> e : credited.entrySet()) {
      if (!known.contains(e.getKey())) {
        out.add(new Finding("DEPOSIT_UNKNOWN_CREDIT", e.getKey(), null, BigDecimal.ZERO, e.getValue(), "账本里有充值入账，钱包里没有对应的链上充值"));
      }
    }
  }

  /** 已入账的充值，区块哈希还和入账时一样吗（链上被深度回滚、超出充值服务复查范围时在这里发现） */
  private void chainCheck(ReconMapper.DepositRow d, String key, List<Finding> out) {
    var client = clients.get(Chain.valueOf(d.chain()));
    if (client.isEmpty()) return;
    try {
      Optional<String> hash = client.get().blockHash(d.blockNumber());
      if (hash.isEmpty() || !hash.get().equalsIgnoreCase(d.blockHash())) {
        out.add(new Finding("DEPOSIT_NOT_ON_CHAIN", key, d.userId(), d.amount(), BigDecimal.ZERO, "入账时的区块哈希和链上现在的不一致"));
      }
    } catch (NodeException | IllegalStateException e) {
      log.warn("对账时查不到链上区块 {}：{}", key, e.getMessage());
    }
  }

  /**
   * v2 资金归集（技术方案/v2-半自动归集.md 第 4.8 节）：归集不写账本、不改客户余额，这里只核对链上事实。
   * <ul>
   *   <li>{@code SWEEP_UNKNOWN_OUTFLOW}：合约充值地址转出的 USDT 去了冷钱包，但交易不是系统发起的归集（例如 Safe 手动 flushTokens）。</li>
   *   <li>{@code SWEEP_DEST_MISMATCH}：合约充值地址转出的 USDT 去了不是冷钱包的地方（合约不应该允许）。</li>
   *   <li>{@code SWEEP_AMOUNT_MISMATCH}：归集批次记的金额和扫块看到的 Transfer 事件对不上（扫块已经扫过那个区块以后才比）。</li>
   * </ul>
   * 正常的归集转出（{@code wallet_sweep_outflow.kind = SWEEP}）不是问题。
   */
  private void checkSweeps(List<Finding> out) {
    for (ReconMapper.OutflowRow o : mapper.sweepOutflows()) {
      String ref = o.chain() + ":" + o.txHash() + ":" + o.logIndex();
      if ("DEST_MISMATCH".equals(o.kind())) {
        out.add(new Finding("SWEEP_DEST_MISMATCH", ref, o.userId(), BigDecimal.ZERO, o.amount(), "充值地址 " + o.fromAddress() + " 的 USDT 转去了不是冷钱包的地址 " + o.toAddress()));
      } else {
        out.add(new Finding("SWEEP_UNKNOWN_OUTFLOW", ref, o.userId(), BigDecimal.ZERO, o.amount(),
            "充值地址 " + o.fromAddress() + " 的 USDT 转到冷钱包，但不是系统发起的归集（可能是 Safe 手动归集）"));
      }
    }
    for (ReconMapper.SweptRow r : mapper.sweptItems()) {
      BigDecimal seen = r.outflow() == null ? BigDecimal.ZERO : r.outflow();
      if (seen.compareTo(r.swept()) != 0) {
        out.add(new Finding("SWEEP_AMOUNT_MISMATCH", r.chain() + ":" + r.batchNo() + ":" + r.itemNo(), r.userId(), r.swept(), seen,
            "归集批次 " + r.batchNo() + " 第 " + r.itemNo() + " 个地址记的金额和扫块看到的转出不一致"));
      }
    }
  }

  /** 提现：每笔的账本记录和状态一致；每个用户的提现冻结 = 进行中的提现合计 */
  private void checkWithdrawals(List<Finding> out) {
    Map<String, BigDecimal> freeze = ledger.totals("WITHDRAW", LedgerEntryType.WITHDRAW_FREEZE, Bucket.WITHDRAW_FROZEN);
    Map<String, BigDecimal> unfreeze = ledger.totals("WITHDRAW", LedgerEntryType.WITHDRAW_UNFREEZE, Bucket.WITHDRAW_FROZEN);
    Map<String, BigDecimal> payout = ledger.totals("WITHDRAW", LedgerEntryType.WITHDRAW_PAYOUT, Bucket.WITHDRAW_FROZEN);
    Map<Long, BigDecimal> inFlight = new HashMap<>();
    for (ReconMapper.WithdrawalRow w : mapper.withdrawals()) {
      String id = String.valueOf(w.id());
      BigDecimal f = freeze.getOrDefault(id, BigDecimal.ZERO);
      BigDecimal u = unfreeze.getOrDefault(id, BigDecimal.ZERO).negate();
      BigDecimal p = payout.getOrDefault(id, BigDecimal.ZERO).negate();
      BigDecimal zero = BigDecimal.ZERO;
      boolean ok;
      if (IN_FLIGHT.contains(w.status())) {
        ok = f.compareTo(w.amount()) == 0 && u.signum() == 0 && p.signum() == 0;
        inFlight.merge(w.userId(), w.amount(), BigDecimal::add);
      } else if ("COMPLETED".equals(w.status())) {
        ok = f.compareTo(w.amount()) == 0 && u.signum() == 0 && p.compareTo(w.amount()) == 0;
      } else if (REFUNDED.contains(w.status())) {
        ok = f.compareTo(w.amount()) == 0 && u.compareTo(w.amount()) == 0 && p.compareTo(zero) == 0;
      } else {
        ok = false;
      }
      if (!ok) {
        out.add(new Finding("WITHDRAW_LEDGER_MISMATCH", id, w.userId(), w.amount(), f.subtract(u).subtract(p),
            "状态 " + w.status() + "：冻结 " + f.toPlainString() + "、退回 " + u.toPlainString() + "、转出 " + p.toPlainString()));
      }
    }
    Map<Long, BigDecimal> frozen = ledger.withdrawFrozenByUser();
    Set<Long> users = new HashSet<>(frozen.keySet());
    users.addAll(inFlight.keySet());
    for (Long u : users) {
      BigDecimal expected = inFlight.getOrDefault(u, BigDecimal.ZERO);
      BigDecimal actual = frozen.getOrDefault(u, BigDecimal.ZERO);
      if (expected.compareTo(actual) != 0) {
        out.add(new Finding("WITHDRAW_FROZEN_MISMATCH", String.valueOf(u), u, expected, actual, "账户的提现冻结应该等于进行中的提现合计"));
      }
    }
  }

  @Override
  public List<Issue> issues(String status, int limit) {
    return mapper.issues(status == null ? "OPEN" : status, Math.min(Math.max(limit, 1), 500));
  }

  @Override
  public List<Issue> issues(String status, int offset, int limit) {
    return mapper.issuePage(status == null || status.isBlank() ? null : status, Math.max(0, offset), Math.min(Math.max(limit, 1), 500));
  }

  @Override
  public long countIssues(String status) {
    return mapper.countIssues(status == null || status.isBlank() ? null : status);
  }

  @Override
  public void resolve(long issueId, long adminId, String note) {
    if (note == null || note.isBlank()) throw new ValidationException(List.of(new ValidationException.Field("note", "required")));
    if (note.length() > 500) throw new ValidationException(List.of(new ValidationException.Field("note", "tooLong")));
    tx.executeWithoutResult(st -> {
      Issue i = mapper.lockIssue(issueId);
      if (i == null) throw new BizException("NOT_FOUND", "对账问题不存在");
      if (mapper.resolveIssue(issueId, adminId, note.trim(), now()) != 1) throw new BizException("ISSUE_NOT_OPEN", "只有待处理的问题可以标记为已处理");
      if ("SUSPENSE_OPEN".equals(i.kind())) mapper.resolveSuspense(Long.parseLong(i.refId()), now(), note.trim());
    });
  }
}
