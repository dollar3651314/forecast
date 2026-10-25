package com.forecast.app.resolve.source;

import java.time.Instant;

/**
 * 网关一轮取数（最多 attempts 次调用）的结果（forecast S8）。
 *
 * @param value OK 时的解析结果
 * @param archiveId 最后一次调用的存档行（同时为多个市场存档时是第一行）；没有存档为空
 * @param httpStatus 最后一次调用的 HTTP 状态码，没有拿到响应为空
 * @param attempts 这一轮实际调用了几次
 */
public record SourceReply<T>(String source, SourceGateway.Outcome outcome, T value, String error, Long archiveId, String bodySha256, Instant fetchedAt,
    int attempts, Integer httpStatus) {

  public boolean ok() {
    return outcome == SourceGateway.Outcome.OK;
  }
}
