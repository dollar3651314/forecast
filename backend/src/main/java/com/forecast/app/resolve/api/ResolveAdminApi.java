package com.forecast.app.resolve.api;

import java.util.List;
import java.util.Optional;

/**
 * 后台看自动裁决（forecast S6；S7 的裁决台"自动裁决失败"在这上面做）。admin 模块负责权限点。编号一律字符串，时间 ISO-8601 UTC。
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

  /** 一个市场的裁决依据：进度、记录、最近的原始返回（新的在前，最多 100 条） */
  record Evidence(String marketNo, Task task, List<Record> records, List<Fetch> fetches) {}

  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  Optional<Evidence> evidence(long marketNo);

  /** 转人工的（status = manual），新的在前 */
  Page<Task> manual(int page, int pageSize);
}
