package com.forecast.app.market.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 市场的公开数据（forecast S15，给自动化程序写文章、做 SEO 页面和数据卡用）：和客户端不登录时看到的完全一样（题目、选项、奖池合计、各选项人数和概率、
 * 预计赔率、时间），<b>没有任何客户的个人数据</b>（NF-16：这些数字会发给 Gemini 起草文字）。草稿、已删除的市场取不到。
 */
public interface MarketPublicApi {

  /**
   * 一个选项。
   *
   * @param amount 奖池（2 位小数的字符串）
   * @param pct 概率（"58.3"，没人押时 "—"）
   * @param mult 当前预计赔率（扣抽成后，"1.73"；没人押时为空）
   */
  record Option(String optionNo, int seq, I18nText label, String amount, int bettors, String pct, String mult) {}

  /**
   * 一个市场的公开快照。
   *
   * @param status 小写状态（listed、closed、settled……）
   * @param feeRate 费率百分比（"5"）
   * @param total 奖池合计
   * @param bettors 下注人数
   * @param winningOptionNo 已结算的结果
   * @param publicNo 市场编号（输出 JSON 时是字符串：雪花 ID 超过 JavaScript 能精确表示的范围，和 ClientViews 一致）
   */
  record Snapshot(@JsonSerialize(using = ToStringSerializer.class) long publicNo, MarketCategory category, I18nText title, String status, Instant openAt, Instant closeAt, Instant eventStartAt,
      Instant eventEndAt, boolean todaySlate, String feeRate, String total, int bettors, List<Option> options, String winningOptionNo, String sourcePrimary,
      Instant at) {}

  Optional<Snapshot> snapshot(long publicNo);

  /** 列入今日场次、事件开始时间在 [from, to) 的市场（不含草稿），按时间排；范围最多 48 小时 */
  List<Snapshot> slate(Instant from, Instant to);
}
