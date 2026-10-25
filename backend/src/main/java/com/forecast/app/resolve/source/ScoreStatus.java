package com.forecast.app.resolve.source;

/**
 * 比赛状态（三个体育数据源统一后的口径，forecast S8；需求第 3.6、4.3 节）。各家原始状态怎么对应见各适配器。
 */
public enum ScoreStatus {
  /** 还没开始（含时间待定） */
  SCHEDULED,
  /** 进行中（含中场、加时、点球大战进行中） */
  LIVE,
  /** 完赛（足球再看 {@link ScoreFixture#regularTime()}：加时或点球结束的不是 90 分钟比分） */
  FINISHED,
  /** 延期（E6） */
  POSTPONED,
  /** 中断 / 腰斩（E7） */
  SUSPENDED,
  /** 取消（E5） */
  CANCELLED,
  /** 判定结果（判负、弃权）：不按场上比分，转人工 */
  AWARDED,
  /** 认不出的状态：当作还没好，下一轮再看 */
  UNKNOWN
}
