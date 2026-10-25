package com.forecast.app.resolve.api;

import java.util.List;
import java.util.Optional;

/**
 * 后台看自动裁决（forecast S6；S7 的裁决台"自动裁决失败"在这上面做；S8 起含体育市场的进度）。admin 模块负责权限点。编号一律字符串，时间 ISO-8601 UTC。
 */
public interface ResolveAdminApi {

  /**
   * 一个加密市场的自动裁决进度。
   *
   * @param status pending（等待取数，每分钟重试）/ resolving（结果已定，正在结算）/ resolved / manual（转人工：30 分钟仍不一致或拿不到）/ closed（市场已不在待裁决）
   * @param deadlineAt 过了这个时间还不行就转人工
   */
  record Task(String marketNo, String symbol, String windowStart, String windowEnd, String status, int attempts, String nextAttemptAt, String deadlineAt,
      String resultOptionNo, String lastReason, String lastAttemptAt, String updatedAt) {}

  /** 一条裁决记录（只增不改）：auto_result / auto_failed */
  record Record(long id, String kind, String optionNo, Object detail, String note, String actor, String time) {}

  /** 数据源的一次调用（原始返回存档，只增不改） */
  record Fetch(long id, String source, int attempt, String requestUrl, Integer httpStatus, String outcome, Object parsed, String error, String body,
      String bodySha256, String fetchedAt, int elapsedMs) {}

  /**
   * 一个体育市场的自动裁决进度（forecast S8）。
   *
   * @param status pending（等完赛 / 完赛后等 30 分钟 / 等两个来源一致）/ resolving / resolved / manual（转人工）/ closed
   * @param backupRef 备源的比赛编号（建进度时确认的对应），没有确认为空
   * @param settleAfterAt 完赛后等到这个时间才比较两个来源
   * @param giveUpAt 过了这个时间备源还拿不到或还没完赛 → 转人工
   * @param unfinishedDeadlineAt 过了这个时间主源还没报告完赛 → 转人工
   * @param actualStartAt 数据源报告的开赛时间（E14，交给结算）
   */
  record SportsTask(String marketNo, String fixtureNo, String sport, String play, String line, String primarySource, String primaryRef, String backupSource,
      String backupRef, boolean backupSwapped, String status, int attempts, String nextAttemptAt, String finishedSeenAt, String settleAfterAt, String giveUpAt,
      String unfinishedDeadlineAt, String resultOptionNo, String actualStartAt, String lastReason, String lastAttemptAt, String updatedAt) {}

  /**
   * 一个市场的裁决依据：进度、记录、最近的原始返回（新的在前，最多 100 条）。
   *
   * @param task 加密市场的进度（S6），别的为空
   * @param sports 体育市场的进度（S8），别的为空
   */
  record Evidence(String marketNo, Task task, List<Record> records, List<Fetch> fetches, SportsTask sports) {}

  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  Optional<Evidence> evidence(long marketNo);

  /** 转人工的（status = manual），新的在前 */
  Page<Task> manual(int page, int pageSize);
}
