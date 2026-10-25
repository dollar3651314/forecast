package com.forecast.app.ledger.service;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.money.Amounts;
import com.forecast.app.ledger.api.AccountKind;
import com.forecast.app.ledger.api.AccountSnapshot;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.ledger.api.LedgerQueryApi;
import com.forecast.app.ledger.api.LedgerResult;
import com.forecast.app.ledger.api.PlatformLedgerApi;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LedgerApi}、{@link PlatformLedgerApi} 的实现：先锁账户行，再查幂等、检查类型规则和余额、改金额、写账本，全部在一个事务里。
 * 账户金额只在这里改（{@code updateBucket}），而且每次都和一条账本记录一起写，所以"账本合计 = 余额"在每次提交后都成立。
 */
@Service
class LedgerService implements LedgerApi, PlatformLedgerApi, LedgerQueryApi {

  private final LedgerMapper mapper;

  LedgerService(LedgerMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  @Transactional
  public void openAccount(long userId) {
    mapper.insertAccountIfAbsent(userId);
  }

  // ---------- 客户账户 ----------

  @Override
  @Transactional
  public LedgerResult apply(LedgerCommand c) {
    Posting p = validate(AccountKind.USER, c.bucket(), c.amount(), c.type(), c.bizType(), c.bizId());
    AccountRow acc = mapper.lockAccount(c.userId());
    if (acc == null) throw new BizException("ACCOUNT_NOT_FOUND", "账户不存在：" + c.userId());
    return post(acc, p, c.remark(), c.refMarketId());
  }

  @Override
  @Transactional
  public List<LedgerResult> applyAll(List<LedgerCommand> commands) {
    // 先检查全部，再按用户 id 升序锁住涉及的账户（第 4.2 节的锁顺序），最后逐条记账：任何一条失败，整个事务回滚
    List<Posting> postings = new ArrayList<>();
    for (LedgerCommand c : commands) postings.add(validate(AccountKind.USER, c.bucket(), c.amount(), c.type(), c.bizType(), c.bizId()));
    Map<Long, AccountRow> locked = new HashMap<>();
    commands.stream().map(LedgerCommand::userId).distinct().sorted(Comparator.naturalOrder()).forEach(uid -> {
      AccountRow a = mapper.lockAccount(uid);
      if (a == null) throw new BizException("ACCOUNT_NOT_FOUND", "账户不存在：" + uid);
      locked.put(uid, a);
    });
    List<LedgerResult> out = new ArrayList<>();
    for (int i = 0; i < commands.size(); i++) {
      LedgerCommand c = commands.get(i);
      // 每条都重新读一次锁住的行（同一账户的前一条已经改过金额；行锁已经持有，不会再等）
      AccountRow acc = mapper.lockAccount(c.userId());
      out.add(post(acc, postings.get(i), c.remark(), c.refMarketId()));
    }
    return out;
  }

  /** 批量记账每条 SQL 最多带多少个账户 / 记录 */
  static final int BATCH_CHUNK = 500;

  /**
   * 批量记账（{@link LedgerApi#applyBatch}）：和 {@link #applyAll} 的保证相同，用批量 SQL 做。
   * <ol>
   *   <li>先检查全部（金额、类型规则）；</li>
   *   <li>按用户 id 升序锁住全部账户（一条语句锁一段，段与段之间也是升序）；</li>
   *   <li>查出这批幂等键已有的记录：已有的不再记（换了账户或金额抛错）；同一批里重复的键同样只记一次；</li>
   *   <li>在内存里按给出的顺序算每条记录之后的余额（value_after 连续），任何一项为负（补偿支出账户的可用除外，这里只有客户账户）就整批拒绝；</li>
   *   <li>每个账户一行更新三项，记录多行插入。</li>
   * </ol>
   */
  @Override
  @Transactional
  public List<Boolean> applyBatch(List<LedgerCommand> commands) {
    if (commands == null || commands.isEmpty()) return List.of();
    List<Posting> postings = new ArrayList<>(commands.size());
    for (LedgerCommand c : commands) postings.add(validate(AccountKind.USER, c.bucket(), c.amount(), c.type(), c.bizType(), c.bizId()));
    List<Long> users = commands.stream().map(LedgerCommand::userId).distinct().sorted().toList();
    Map<Long, AccountRow> byUser = new HashMap<>();
    for (List<Long> chunk : chunks(users)) for (AccountRow a : mapper.lockAccounts(chunk)) byUser.put(a.userId(), a);
    for (long uid : users) if (!byUser.containsKey(uid)) throw new BizException("ACCOUNT_NOT_FOUND", "账户不存在：" + uid);
    // 已有的幂等键：来源业务 → 单号 → (类型:项 → 已有记录)
    Map<String, Map<String, LedgerMapper.EntryKeyRow>> existing = new HashMap<>();
    Map<String, List<String>> idsByType = new LinkedHashMap<>();
    for (Posting p : postings) idsByType.computeIfAbsent(p.bizType(), k -> new ArrayList<>()).add(p.bizId());
    for (Map.Entry<String, List<String>> e : idsByType.entrySet()) {
      for (List<String> chunk : chunks(e.getValue().stream().distinct().toList())) {
        for (LedgerMapper.EntryKeyRow r : mapper.findEntries(e.getKey(), chunk)) {
          existing.computeIfAbsent(e.getKey(), k -> new HashMap<>()).put(r.bizId() + "\u0000" + r.entryType() + "\u0000" + r.bucket(), r);
        }
      }
    }
    Map<Long, BigDecimal[]> values = new LinkedHashMap<>();
    Map<String, LedgerEntry> fresh = new HashMap<>();
    List<LedgerEntry> inserts = new ArrayList<>();
    List<Boolean> out = new ArrayList<>(commands.size());
    for (int i = 0; i < commands.size(); i++) {
      LedgerCommand c = commands.get(i);
      Posting p = postings.get(i);
      AccountRow acc = byUser.get(c.userId());
      String key = p.bizId() + "\u0000" + p.type().name() + "\u0000" + p.bucket().name();
      LedgerMapper.EntryKeyRow old = existing.getOrDefault(p.bizType(), Map.of()).get(key);
      LedgerEntry same = fresh.get(p.bizType() + "\u0000" + key);
      if (old != null || same != null) {
        long accountId = old != null ? old.accountId() : same.accountId;
        BigDecimal amount = old != null ? old.amount() : same.amount;
        if (accountId != acc.id() || amount.compareTo(p.amount()) != 0) {
          throw new IllegalStateException("幂等键冲突：" + p.bizType() + ":" + p.bizId() + ":" + p.type() + ":" + p.bucket() + " 已经记过另一个账户或另一个金额");
        }
        out.add(false);
        continue;
      }
      BigDecimal[] v = values.computeIfAbsent(acc.id(), k -> new BigDecimal[] {acc.available(), acc.betLocked(), acc.withdrawFrozen()});
      int slot = p.bucket().ordinal();
      BigDecimal after = v[slot].add(p.amount());
      if (after.signum() < 0) throw new BizException("INSUFFICIENT_FUNDS", "余额不足");
      v[slot] = after;
      LedgerEntry e = new LedgerEntry();
      e.accountId = acc.id();
      e.bucket = p.bucket().name();
      e.entryType = p.type().name();
      e.amount = p.amount();
      e.valueAfter = after;
      e.bizType = p.bizType();
      e.bizId = p.bizId();
      e.refMarketId = c.refMarketId();
      e.remark = c.remark();
      inserts.add(e);
      fresh.put(p.bizType() + "\u0000" + key, e);
      out.add(true);
    }
    List<AccountUpdate> updates = new ArrayList<>();
    values.forEach((id, v) -> updates.add(new AccountUpdate(id, v[0], v[1], v[2])));
    for (List<AccountUpdate> chunk : chunks(updates)) mapper.updateAccounts(chunk);
    for (List<LedgerEntry> chunk : chunks(inserts)) mapper.insertEntries(chunk);
    return out;
  }

  private static <T> List<List<T>> chunks(List<T> all) {
    List<List<T>> out = new ArrayList<>();
    for (int i = 0; i < all.size(); i += BATCH_CHUNK) out.add(all.subList(i, Math.min(all.size(), i + BATCH_CHUNK)));
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public AccountSnapshot snapshot(long userId) {
    return toSnapshot(mapper.findAccount(userId), userId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountSnapshot lock(long userId) {
    return toSnapshot(mapper.lockAccount(userId), userId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockAll(java.util.Collection<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) return;
    List<Long> users = userIds.stream().distinct().sorted().toList();
    int found = 0;
    for (List<Long> chunk : chunks(users)) found += mapper.lockAccounts(chunk).size();
    if (found != users.size()) throw new BizException("ACCOUNT_NOT_FOUND", "有账户不存在（锁到 " + found + " 个，要 " + users.size() + " 个）");
  }

  private static AccountSnapshot toSnapshot(AccountRow a, long userId) {
    if (a == null) throw new BizException("ACCOUNT_NOT_FOUND", "账户不存在：" + userId);
    return new AccountSnapshot(userId, a.available(), a.betLocked(), a.withdrawFrozen());
  }

  // ---------- 平台账户 ----------

  @Override
  @Transactional
  public LedgerResult apply(PlatformLedgerApi.Command c) {
    if (c.account() == null || c.account() == AccountKind.USER) throw new IllegalArgumentException("平台记账只能用平台账户：" + c.account());
    Posting p = validate(c.account(), Bucket.AVAILABLE, c.amount(), c.type(), c.bizType(), c.bizId());
    AccountRow acc = mapper.lockPlatformAccount(c.account().name());
    if (acc == null) throw new IllegalStateException("平台账户不存在（迁移 V6 应已建好）：" + c.account());
    return post(acc, p, c.remark(), c.refMarketId());
  }

  @Override
  @Transactional(readOnly = true)
  public BigDecimal balance(AccountKind account) {
    if (account == null || account == AccountKind.USER) throw new IllegalArgumentException("不是平台账户：" + account);
    AccountRow a = mapper.findPlatformAccount(account.name());
    if (a == null) throw new IllegalStateException("平台账户不存在（迁移 V6 应已建好）：" + account);
    return a.available();
  }

  // ---------- 记账 ----------

  /** 检查过的一笔账（还没锁账户） */
  private record Posting(AccountKind kind, Bucket bucket, BigDecimal amount, LedgerEntryType type, String bizType, String bizId) {}

  /** 金额、幂等键、类型规则（哪种账户、哪一项、加还是减）：不符合是调用方的程序错误 */
  private static Posting validate(AccountKind kind, Bucket bucket, BigDecimal rawAmount, LedgerEntryType type, String bizType, String bizId) {
    if (bucket == null || type == null || rawAmount == null) throw new IllegalArgumentException("必须提供项、类型和金额");
    BigDecimal amount = Amounts.normalize(rawAmount);
    if (amount.signum() == 0) throw new IllegalArgumentException("变动金额不能为 0");
    if (bizType == null || bizType.isBlank() || bizId == null || bizId.isBlank()) throw new IllegalArgumentException("必须提供来源业务和单号（幂等键）");
    if (!type.allows(kind, bucket, amount.signum())) {
      throw new IllegalArgumentException("流水类型 " + type + " 不能在 " + kind + " 账户的 " + bucket + " 上记" + (amount.signum() > 0 ? "增加" : "减少")
          + "（规则见 LedgerEntryType）");
    }
    return new Posting(kind, bucket, amount, type, bizType, bizId);
  }

  /** 账户行已锁：查幂等 → 检查不为负 → 改金额 → 写账本 */
  private LedgerResult post(AccountRow acc, Posting p, String remark, Long refMarketId) {
    LedgerRow existing = mapper.findEntry(p.bizType(), p.bizId(), p.type().name(), p.bucket().name());
    if (existing != null) {
      if (existing.accountId() != acc.id() || existing.amount().compareTo(p.amount()) != 0) {
        throw new IllegalStateException("幂等键冲突：" + p.bizType() + ":" + p.bizId() + ":" + p.type() + ":" + p.bucket() + " 已经记过另一个账户或另一个金额");
      }
      return new LedgerResult(existing.id(), existing.valueAfter(), true);
    }

    BigDecimal after = acc.get(p.bucket()).add(p.amount());
    boolean mayGoNegative = p.bucket() == Bucket.AVAILABLE && acc.accountKind().mayGoNegative();
    if (after.signum() < 0 && !mayGoNegative) throw new BizException("INSUFFICIENT_FUNDS", "余额不足");

    mapper.updateBucket(acc.id(), column(p.bucket()), after);
    LedgerEntry e = new LedgerEntry();
    e.accountId = acc.id();
    e.bucket = p.bucket().name();
    e.entryType = p.type().name();
    e.amount = p.amount();
    e.valueAfter = after;
    e.bizType = p.bizType();
    e.bizId = p.bizId();
    e.refMarketId = refMarketId;
    e.remark = remark;
    mapper.insertEntry(e);
    return new LedgerResult(e.id, after, false);
  }

  /** 列名只来自这个固定映射，不拼接外部输入 */
  private static String column(Bucket b) {
    return switch (b) {
      case AVAILABLE -> "available";
      case BET_LOCKED -> "bet_locked";
      case WITHDRAW_FROZEN -> "withdraw_frozen";
    };
  }

  // ---------- 对账查询 ----------

  @Override
  @Transactional(readOnly = true)
  public Map<String, BigDecimal> totals(String bizType, LedgerEntryType type, Bucket bucket) {
    Map<String, BigDecimal> m = new HashMap<>();
    for (LedgerMapper.KeyAmount k : mapper.totals(bizType, type.name(), bucket.name())) m.put(k.key(), k.amount());
    return m;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, BigDecimal> withdrawFrozenByUser() {
    Map<Long, BigDecimal> m = new HashMap<>();
    for (LedgerMapper.KeyAmount k : mapper.withdrawFrozen()) m.put(Long.valueOf(k.key()), k.amount());
    return m;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Mismatch> mismatches() {
    List<Mismatch> out = new ArrayList<>();
    for (LedgerMapper.BalanceCheckRow r : mapper.balanceMismatches()) {
      AccountKind kind = AccountKind.valueOf(r.kind);
      if (r.sumAvailable.compareTo(r.available) != 0) out.add(new Mismatch(r.id, kind, r.userId, Bucket.AVAILABLE, r.sumAvailable, r.available));
      if (r.sumBetLocked.compareTo(r.betLocked) != 0) out.add(new Mismatch(r.id, kind, r.userId, Bucket.BET_LOCKED, r.sumBetLocked, r.betLocked));
      if (r.sumWithdrawFrozen.compareTo(r.withdrawFrozen) != 0) {
        out.add(new Mismatch(r.id, kind, r.userId, Bucket.WITHDRAW_FROZEN, r.sumWithdrawFrozen, r.withdrawFrozen));
      }
      if (r.unknownBuckets > 0) out.add(new Mismatch(r.id, kind, r.userId, null, BigDecimal.valueOf(r.unknownBuckets), BigDecimal.ZERO));
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<TypeBucket, BigDecimal> marketTotals(long marketId) {
    Map<TypeBucket, BigDecimal> m = new LinkedHashMap<>();
    for (LedgerMapper.MarketTotalRow r : mapper.marketTotals(marketId)) {
      m.put(new TypeBucket(LedgerEntryType.valueOf(r.entryType), Bucket.valueOf(r.bucket)), r.amount);
    }
    return m;
  }
}
