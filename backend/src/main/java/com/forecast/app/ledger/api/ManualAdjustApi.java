package com.forecast.app.ledger.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 人工调账（forecast S17B，需求说明书 v1.5 第 21 节，"Amos 2026-10-27：A、B 两个都要"）：后台给客户人工加款、扣款，生产可用。
 * <ul>
 *   <li>账本：加款 {@link LedgerEntryType#MANUAL_CREDIT}（可用 +x）+ 平台"人工调账"账户 {@link LedgerEntryType#MANUAL_ADJUST_OUT}（−x）；
 *       扣款 {@link LedgerEntryType#MANUAL_DEBIT}（可用 −x）+ {@link LedgerEntryType#MANUAL_ADJUST_IN}（+x）。同一事务，账本平衡；调账单只增不改。</li>
 *   <li>扣款只扣可用余额，不能超过可用余额（{@code ADJUST_INSUFFICIENT}），下注锁定和提现冻结不动，余额不会变负。</li>
 *   <li>请求号：同一个请求号只调一次（{@link Adjustment#duplicate()}），换了内容 409 {@code ADJUST_REQUEST_REUSED}。</li>
 *   <li>单笔上限（参数 {@code adjust.manualMax}）：超过时要 {@link Request#overLimitConfirmed()}，否则 {@code ADJUST_OVER_LIMIT_UNCONFIRMED}（SQ-57）。</li>
 *   <li>同一事务里给客户发通知 #24 {@code BALANCE_ADJUSTED}（站内信 + 邮件）。</li>
 *   <li>"充值未到账补入"的加款当充值算（{@link CustomerFlowApi#depositsSince}），其他类别不算（SQ-58）。</li>
 * </ul>
 */
public interface ManualAdjustApi {

  enum Direction {
    /** 人工加款 */
    CREDIT,
    /** 人工扣款 */
    DEBIT
  }

  enum Category {
    /** 充值未到账补入（只能加款；风控和提现当充值算） */
    DEPOSIT_MISSING(Direction.CREDIT),
    /** 误入账扣回（只能扣款） */
    WRONG_CREDIT(Direction.DEBIT),
    /** 其他（加款、扣款都行） */
    OTHER(null);

    private final Direction only;

    Category(Direction only) {
      this.only = only;
    }

    /** 这个类别能不能用在这个方向上 */
    public boolean allows(Direction d) {
      return only == null || only == d;
    }
  }

  /**
   * 一次调整的请求（字段的格式检查在调用方，这里再检查一次业务规则）。
   *
   * @param refNo 参考号（链上交易哈希或工单号），可以为空
   * @param overLimitConfirmed 超过单笔上限时，操作人是否再勾了一次"我确认超过单笔上限"
   */
  record Request(long userId, Direction direction, Category category, BigDecimal amount, String refNo, String reason, String requestId,
      boolean overLimitConfirmed, long adminId) {}

  /**
   * 一笔调整。
   *
   * @param overLimit 超过了单笔上限（操作人确认过）
   * @param duplicate 这个请求号之前已经调过（这次没有再调）
   */
  record Adjustment(long adjustNo, long userId, Direction direction, Category category, BigDecimal amount, BigDecimal availableBefore,
      BigDecimal availableAfter, String refNo, String reason, boolean overLimit, long createdBy, LocalDateTime createdAt, boolean duplicate) {}

  /** 单笔上限（参数设置） */
  BigDecimal maxAmount();

  /** 调整（一个事务：锁客户账户 → 查请求号 → 检查 → 写调账单 → 客户记账 → 平台记账 → 通知 #24） */
  Adjustment adjust(Request request);

  /**
   * 列表的筛选条件；为空的不限。
   *
   * @param from 含；@param to 不含（按调整时间，UTC）
   */
  record Query(Long userId, Direction direction, Category category, LocalDateTime from, LocalDateTime to) {}

  /** 新的在前 */
  List<Adjustment> list(Query q, int offset, int limit);

  long count(Query q);

  // ---------- 风控（riskalert 读，S17B 新告警"24 小时内人工加款累计超过阈值"） ----------

  /** 一笔人工加款（风控扫描用，按 id 往后读） */
  record CreditSignal(long id, long adjustNo, long userId, BigDecimal amount, LocalDateTime at) {}

  /** id 大于 afterId 的人工加款，按 id 升序，最多 limit 条 */
  List<CreditSignal> creditsAfter(long afterId, int limit);

  /** 这个客户在 [from, to]（两头都含）里、id ≤ upToId 的人工加款合计 */
  BigDecimal creditedBetween(long userId, LocalDateTime from, LocalDateTime to, long upToId);
}
