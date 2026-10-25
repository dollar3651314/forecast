package com.forecast.app.resolve.source;

import java.time.Instant;
import java.util.Map;

/**
 * 原始返回存档（技术方案第 6.3 节：每次调用（成功、失败、超时）都写一行 {@code resolve_source_fetch}，含原文 sha256）。
 * 实现在 {@code resolve/auto/DbSourceArchive}（不在事务里调用，每行自己提交）。
 */
public interface SourceArchive {

  /**
   * @param httpStatus 没有拿到响应为空
   * @param body 原文（太大的不存，为空）
   * @param bodySha256 原文的 sha256（十六进制小写）
   * @param outcome OK / NOT_READY / FAILED / CIRCUIT_OPEN
   */
  record Entry(Long marketId, String source, int attempt, String requestUrl, Integer httpStatus, String body, String bodySha256, String outcome,
      Map<String, Object> parsed, String error, Instant fetchedAt, int elapsedMs) {}

  /** 写一行，返回 id；失败抛异常（调用方当作这次取数失败：没有存档的数据不用来结算） */
  long save(Entry e);
}
