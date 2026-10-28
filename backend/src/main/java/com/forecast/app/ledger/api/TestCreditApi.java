package com.forecast.app.ledger.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 测试入金（forecast S17A，开发记录/S17-测试入金和人工调账.md 第 1 节）：测试环境的充值合约还没部署时，由后台给客户的可用余额加钱，
 * 好把下注到提现的整条流程测完。<b>只在测试环境存在</b>：判断依据是现有的环境标识 {@code forecast.env-label}（环境变量 {@code FC_ENV_LABEL}），
 * 等于 {@code test} 才可用；生产不设这个变量，没有能在生产上打开它的开关。
 * <ul>
 *   <li>账本：客户 {@link LedgerEntryType#TEST_CREDIT}（可用 +x），平台"测试入金"账户 {@link LedgerEntryType#TEST_CREDIT_EXPENSE}（−x，可以为负），
 *       同一事务，账本保持平衡；幂等键 {@code TEST_CREDIT : 测试入金单 id}；测试入金单只增不改。</li>
 *   <li>请求号：同一个请求号只入一次，再来一次返回第一次的结果（{@link Result#duplicate()}）；同一个请求号换了客户或金额 409。</li>
 *   <li>单笔上限：参数 {@code adjust.testCreditMax}（默认 10,000 USDT）。</li>
 *   <li>风控和提现：测试入金按"充值"算（{@link CustomerFlowApi#depositsSince}、KYC 阈值、提现自动审批 R8 和"充了不玩就提"都把它当充值），
 *       好让测试时能走到风控；它不是链上充值，钱包对账不看它。</li>
 * </ul>
 */
public interface TestCreditApi {

  /** 这个环境能不能用测试入金（{@code forecast.env-label} 是 {@code test}） */
  boolean enabled();

  /** 单笔上限（参数设置） */
  BigDecimal maxAmount();

  /**
   * 给客户入一笔测试金（一个事务：锁客户账户 → 查请求号 → 写入金单 → 客户记账 → 平台记账）。
   * 不是测试环境时抛 {@code NOT_FOUND}（调用方的接口在生产本来就不存在，这里再挡一次）。
   *
   * @param amount 大于 0，最多 8 位小数，不超过单笔上限
   * @param requestId 请求号 {@code [A-Za-z0-9._-]{8,64}}
   */
  Result credit(long userId, BigDecimal amount, String reason, String requestId, long adminId);

  /**
   * 一次测试入金的结果。
   *
   * @param duplicate 这个请求号之前已经入过（这次没有再入）
   */
  record Result(long creditNo, long userId, BigDecimal amount, BigDecimal availableBefore, BigDecimal availableAfter, String reason, long createdBy,
      LocalDateTime createdAt, boolean duplicate) {}

  /** 按对外编号查一笔 */
  Optional<Result> find(long creditNo);
}
