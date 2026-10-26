package com.forecast.app.pool.api;

import java.util.List;

/**
 * 后台查看下注（forecast S4，需求说明书第 2.2 节 {@code bet:view}）。只有 admin 模块调用；权限点在 admin 模块。
 * 市场、选项、下注一律用对外编号（字符串）；金额是 2 位小数的字符串；时间是 ISO-8601 UTC（下注收到时间精确到毫秒，E14 核对用）。
 */
public interface PoolAdminApi {

  /** 一笔下注（后台） */
  record BetRow(String betNo, long userId, String optionNo, int seq, String amount, String receivedAt, String status, String refundReason,
      String clientReqId) {}

  /**
   * 一个选项的奖池（后台）。
   *
   * @param finalAmount 截止时定格的奖池，没截止时为 null
   */
  record OptionRow(String optionNo, int seq, String amount, int bettors, String finalAmount, Integer finalBettors) {}

  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  /**
   * 一个市场的下注明细：奖池汇总 + 一页下注（按收到时间倒序）。
   *
   * @param status 市场状态（小写）
   * @param activeTotal 有效下注合计（应当等于各选项奖池之和，需求第 3.9 节第 2 条）
   */
  record MarketBets(String marketNo, String status, String total, String activeTotal, int bettors, boolean frozen, List<OptionRow> options,
      Page<BetRow> bets) {}

  /**
   * @param optionNo 只看某个选项，可以为空
   * @param userId 只看某个客户，可以为空
   * @param status active / refunded，可以为空
   * @throws com.forecast.app.common.error.BizException NOT_FOUND：市场不存在或是草稿
   */
  MarketBets marketBets(long marketNo, String optionNo, Long userId, String status, int page, int pageSize);

  /**
   * 同上，可以按金额排序（S9：后台市场详情的"前 20 大下注"）。
   *
   * @param sort time（默认，收到时间倒序）/ amount（金额倒序）
   */
  MarketBets marketBets(long marketNo, String optionNo, Long userId, String status, String sort, int page, int pageSize);

  /**
   * 一段时间里的下注（仪表盘，S9）：收到时间在 [from, to) 的有效下注（退回的不算）。
   *
   * @param amount 下注额（2 位小数）
   * @param bets 笔数
   * @param bettors 下注人数（去重）
   */
  record BetStats(String amount, long bets, long bettors) {}

  BetStats betStats(java.time.Instant from, java.time.Instant to);
}
