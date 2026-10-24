package com.forecast.app.common.web;

import com.forecast.app.common.trace.TraceId;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 所有接口出错时的统一格式。code 是稳定的错误码，前端按它显示翻译好的原因；message 只给开发排查看。
 * 字段错误一次全部返回（C56）。cooldownSeconds 只在"操作太频繁"（429，例如验证码 60 秒内重发）时出现：还要等多少秒。
 * traceId 是这个请求的追踪号（和响应头 {@code X-Request-Id} 相同，E9）：客户报问题时提供，运维按它查日志；没有时不返回。
 */
public record ApiError(String code, String message, List<FieldError> fields, @JsonInclude(JsonInclude.Include.NON_NULL) Long cooldownSeconds,
    @JsonInclude(JsonInclude.Include.NON_NULL) String traceId) {

  public record FieldError(String field, String code) {}

  /** traceId 取当前请求的追踪号 */
  public ApiError(String code, String message, List<FieldError> fields, Long cooldownSeconds) {
    this(code, message, fields, cooldownSeconds, TraceId.current());
  }

  public ApiError(String code, String message, List<FieldError> fields) {
    this(code, message, fields, null);
  }

  public static ApiError of(String code, String message) {
    return new ApiError(code, message, List.of());
  }
}
