package com.forecast.app.ledger.api;

import static com.forecast.app.ledger.api.AccountKind.PLATFORM_COMPENSATION;
import static com.forecast.app.ledger.api.AccountKind.PLATFORM_REVENUE;
import static com.forecast.app.ledger.api.AccountKind.PLATFORM_TEST_CREDIT;
import static com.forecast.app.ledger.api.AccountKind.USER;
import static com.forecast.app.ledger.api.Bucket.AVAILABLE;
import static com.forecast.app.ledger.api.Bucket.BET_LOCKED;
import static com.forecast.app.ledger.api.Bucket.WITHDRAW_FROZEN;

import java.util.Map;

/**
 * 账本记录的业务类型（技术方案/v1-架构方案.md 第 3.2 节"流水类型和账户变动"，需求说明书第 3.9 节）。新增业务时在这里加，不复用已有含义。
 * <p>每种类型写死了"用在哪种账户、改哪一项、只能加还是只能减"，{@link LedgerApi}、{@link PlatformLedgerApi} 记账前检查，
 * 不符合的直接拒绝（程序错误，抛 {@link IllegalArgumentException}），不会写进账本。
 * <p>同一笔业务要动两项时（例如下注：可用 −b、下注锁定 +b），两条记录用同一个类型和同一个幂等键，靠 bucket 区分。
 * 下注、结算、退款、补偿的业务逻辑在 S4（pool）、S5（settle）、S7（补偿），S2 只定义类型和规则。
 */
public enum LedgerEntryType {
  // ---------- 充值、提现（沿用复制源，"余额"换成可用余额） ----------
  /** 充值入账：可用 + */
  DEPOSIT(USER, Map.of(AVAILABLE, 1)),
  /** 充值入账后链上回滚的冲正：只扣可用余额（不动下注锁定和提现冻结），扣不到的部分记挂账 */
  DEPOSIT_REVERSAL(USER, Map.of(AVAILABLE, -1)),
  /** 提现冻结：可用 −、提现冻结 + */
  WITHDRAW_FREEZE(USER, Map.of(AVAILABLE, -1, WITHDRAW_FROZEN, 1)),
  /** 提现退回（驳回、撤销、确定没上链）：提现冻结 −、可用 + */
  WITHDRAW_UNFREEZE(USER, Map.of(WITHDRAW_FROZEN, -1, AVAILABLE, 1)),
  /** 提现出账（链上确认）：提现冻结 − */
  WITHDRAW_PAYOUT(USER, Map.of(WITHDRAW_FROZEN, -1)),

  // ---------- 彩池（S4 下注、S5 结算和退款、S7 补偿） ----------
  /** 下注锁定：可用 −b、下注锁定 +b（幂等键 BET : 下注 id） */
  BET_LOCK(USER, Map.of(AVAILABLE, -1, BET_LOCKED, 1)),
  /** 结算·赢：解除赢方持仓的下注锁定 −b（本金和盈利由 PAYOUT 打进可用） */
  BET_SETTLE(USER, Map.of(BET_LOCKED, -1)),
  /** 结算·输：输方持仓的下注锁定 −b，这部分钱进了赢方的派彩 */
  BET_LOST(USER, Map.of(BET_LOCKED, -1)),
  /** 派彩：可用 +(b + G)（本金 + 盈利，抽成另记 FEE） */
  PAYOUT(USER, Map.of(AVAILABLE, 1)),
  /** 抽成：可用 −F（对赢家的盈利部分收，和平台的 FEE_INCOME 对应） */
  FEE(USER, Map.of(AVAILABLE, -1)),
  /** 退款（取消、作废、晚于开赛的下注、员工下注）：下注锁定 −b、可用 +b */
  REFUND(USER, Map.of(BET_LOCKED, -1, AVAILABLE, 1)),
  /** 补偿（E13，已结算后发现裁错）：可用 +x，和平台的 COMPENSATION_EXPENSE 对应 */
  COMPENSATION(USER, Map.of(AVAILABLE, 1)),

  // ---------- 测试入金（forecast S17A，只在测试环境 FC_ENV_LABEL=test 存在；不是真实充值） ----------
  /** 测试入金：可用 +x（幂等键 TEST_CREDIT : 测试入金单 id），和平台的 TEST_CREDIT_EXPENSE 对应 */
  TEST_CREDIT(USER, Map.of(AVAILABLE, 1)),

  // ---------- 平台账户 ----------
  /** 平台收入·抽成：平台收入账户 + */
  FEE_INCOME(PLATFORM_REVENUE, Map.of(AVAILABLE, 1)),
  /** 平台收入·结算尾差 D（需求 3.5、RQ-7）：平台收入账户 + */
  SETTLE_DUST(PLATFORM_REVENUE, Map.of(AVAILABLE, 1)),
  /** 平台补偿支出：补偿支出账户 −（可以为负） */
  COMPENSATION_EXPENSE(PLATFORM_COMPENSATION, Map.of(AVAILABLE, -1)),
  /** 测试入金的对手方：测试入金账户 −（可以为负，余额 = −累计测试入金；S17A） */
  TEST_CREDIT_EXPENSE(PLATFORM_TEST_CREDIT, Map.of(AVAILABLE, -1));

  /** 用在哪种账户 */
  public final AccountKind accountKind;
  private final Map<Bucket, Integer> legs;

  LedgerEntryType(AccountKind accountKind, Map<Bucket, Integer> legs) {
    this.accountKind = accountKind;
    this.legs = legs;
  }

  /** 这个类型能不能在 kind 账户的 bucket 这一项上记一笔符号为 signum（1 加、−1 减）的账 */
  public boolean allows(AccountKind kind, Bucket bucket, int signum) {
    Integer s = legs.get(bucket);
    return kind == accountKind && s != null && s == signum;
  }

  /** 这个类型会改哪几项，以及每一项的方向（1 加、−1 减） */
  public Map<Bucket, Integer> legs() {
    return legs;
  }
}
