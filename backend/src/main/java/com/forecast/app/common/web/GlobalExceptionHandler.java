package com.forecast.app.common.web;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.RetryLaterException;
import com.forecast.app.common.error.ValidationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 把异常翻译成统一的 {@link ApiError}；内部错误不把细节返回给客户。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 错误码对应的 HTTP 状态；没有列出的业务错误一律 400 */
  private static final Map<String, HttpStatus> STATUS = Map.ofEntries(
      Map.entry("UNAUTHORIZED", HttpStatus.UNAUTHORIZED),
      Map.entry("USER_FROZEN", HttpStatus.FORBIDDEN),
      Map.entry("FORBIDDEN", HttpStatus.FORBIDDEN),
      Map.entry("IP_NOT_ALLOWED", HttpStatus.FORBIDDEN),
      Map.entry("ACCOUNT_DISABLED", HttpStatus.FORBIDDEN),
      Map.entry("PASSWORD_CHANGE_REQUIRED", HttpStatus.FORBIDDEN),
      Map.entry("LOGIN_LOCKED", HttpStatus.TOO_MANY_REQUESTS),
      Map.entry("CODE_TOO_FREQUENT", HttpStatus.TOO_MANY_REQUESTS),
      Map.entry("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS),
      Map.entry("EMAIL_TAKEN", HttpStatus.CONFLICT),
      // 带版本号的配置被别人先改了（提现自动审批规则），刷新后再改
      Map.entry("CONFIG_CHANGED", HttpStatus.CONFLICT),
      // v2 资金归集：这条链有进行中的批次，等它完成
      Map.entry("SWEEP_BATCH_ACTIVE", HttpStatus.CONFLICT),
      // forecast S3 市场：不是 17 条边之一、状态刚被别人改过、上架后不能改的内容、结算开始后不能改费率
      Map.entry("ILLEGAL_TRANSITION", HttpStatus.CONFLICT),
      Map.entry("MARKET_STATE_CHANGED", HttpStatus.CONFLICT),
      Map.entry("MARKET_NOT_EDITABLE", HttpStatus.CONFLICT),
      Map.entry("FEE_LOCKED", HttpStatus.CONFLICT),
      // forecast S4 下注：同一个请求号换了内容（不是重复提交，客户端要重新生成请求号）
      Map.entry("BET_REQUEST_REUSED", HttpStatus.CONFLICT),
      // forecast S5 结算：市场现在不能结算 / 作废（状态不对）、结算单不是结算异常、还有待处理的对账差异、状态刚被改过、差异已处理过
      Map.entry("MARKET_NOT_SETTLEABLE", HttpStatus.CONFLICT),
      Map.entry("MARKET_NOT_VOIDABLE", HttpStatus.CONFLICT),
      Map.entry("SETTLE_NOT_ABNORMAL", HttpStatus.CONFLICT),
      Map.entry("POOL_RECON_OPEN", HttpStatus.CONFLICT),
      Map.entry("SETTLE_STATE_CHANGED", HttpStatus.CONFLICT),
      Map.entry("ISSUE_ALREADY_RESOLVED", HttpStatus.CONFLICT),
      // S6 加密自动生成
      Map.entry("AUTOGEN_EXISTS", HttpStatus.CONFLICT),
      Map.entry("AUTOGEN_ENABLED", HttpStatus.CONFLICT),
      Map.entry("AUTOGEN_STATE_CHANGED", HttpStatus.CONFLICT),
      Map.entry("TEMPLATE_IN_USE", HttpStatus.CONFLICT),
      // S7 平台裁决：市场不在待裁决、已有待确认的提议、提议已经被确认 / 退回、在等待（E6 / E7）、没有在等待、自动裁决还在处理
      Map.entry("MARKET_NOT_RESOLVABLE", HttpStatus.CONFLICT),
      Map.entry("PROPOSAL_PENDING", HttpStatus.CONFLICT),
      Map.entry("PROPOSAL_CHANGED", HttpStatus.CONFLICT),
      Map.entry("RESOLVE_ON_HOLD", HttpStatus.CONFLICT),
      Map.entry("NOT_ON_HOLD", HttpStatus.CONFLICT),
      Map.entry("RESOLVE_AUTO_IN_PROGRESS", HttpStatus.CONFLICT),
      // S7 争议：不在公示期、已经提交过、没有待处理的争议；没有下注的客户不能提交（403）
      Map.entry("DISPUTE_CLOSED", HttpStatus.CONFLICT),
      Map.entry("DISPUTE_EXISTS", HttpStatus.CONFLICT),
      Map.entry("DISPUTE_NOT_OPEN", HttpStatus.CONFLICT),
      Map.entry("DISPUTE_NOT_BETTOR", HttpStatus.FORBIDDEN),
      // S7 补偿（E13）：不是已结算的市场、已经补偿过、没有人要补偿、结果和原来相同
      Map.entry("MARKET_NOT_COMPENSABLE", HttpStatus.CONFLICT),
      Map.entry("COMPENSATION_EXISTS", HttpStatus.CONFLICT),
      Map.entry("COMPENSATION_NOTHING", HttpStatus.CONFLICT),
      Map.entry("COMPENSATION_SAME_RESULT", HttpStatus.CONFLICT),
      // S11 #23 开赛提醒：市场已经开放 / 已结束，不能再订阅；订阅中的太多；还没有登录过（SQ-20，403；S16 起注册后的自动登录也算，SQ-27）
      Map.entry("REMINDER_NOT_AVAILABLE", HttpStatus.CONFLICT),
      Map.entry("REMINDER_TOO_MANY", HttpStatus.CONFLICT),
      Map.entry("REMINDER_LOGIN_REQUIRED", HttpStatus.FORBIDDEN),
      // S16 等候页（MF-8）：已经正式上线（不再收邮箱）、确认链接过期；上线通知已经发过（只能发一次）
      Map.entry("WAITLIST_CLOSED", HttpStatus.CONFLICT),
      Map.entry("WAITLIST_LINK_EXPIRED", HttpStatus.GONE),
      Map.entry("LAUNCH_ALREADY_SENT", HttpStatus.CONFLICT),
      // S16 Telegram 机器人（MF-7）：模板没有可预览的市场、频道编号不合法、审定时模板文字和预览的不是同一版
      Map.entry("TELEGRAM_NO_PREVIEW", HttpStatus.NOT_FOUND),
      Map.entry("TELEGRAM_TEMPLATE_CHANGED", HttpStatus.CONFLICT),
      Map.entry("NOT_FOUND", HttpStatus.NOT_FOUND));

  @ExceptionHandler(BizException.class)
  public ResponseEntity<ApiError> biz(BizException e) {
    if (e instanceof RetryLaterException r) {
      // 太频繁：返回还要等多少秒（响应体和 Retry-After 头），客户端按它倒计时
      return ResponseEntity.status(STATUS.getOrDefault(e.code(), HttpStatus.TOO_MANY_REQUESTS))
          .header(HttpHeaders.RETRY_AFTER, String.valueOf(r.cooldownSeconds()))
          .body(new ApiError(e.code(), e.getMessage(), List.of(), r.cooldownSeconds()));
    }
    return ResponseEntity.status(STATUS.getOrDefault(e.code(), HttpStatus.BAD_REQUEST)).body(ApiError.of(e.code(), e.getMessage()));
  }

  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<ApiError> fields(ValidationException e) {
    var fields = e.fields().stream().map(f -> new ApiError.FieldError(f.field(), f.code())).toList();
    return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", "请求字段不合法", fields));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
    var fields = e.getBindingResult().getFieldErrors().stream()
        .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage()))
        .toList();
    return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", "请求字段不合法", fields));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST", "请求格式不正确"));
  }

  /** 参数类型不对（例如路径里的 ID 不是数字）：客户端的错误，返回 400，不当成内部错误记 ERROR 日志（安全审查 SEC-05） */
  @ExceptionHandler(TypeMismatchException.class)
  public ResponseEntity<ApiError> typeMismatch(TypeMismatchException e) {
    return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST", "请求格式不正确"));
  }

  /** 框架自带状态码的错误（路径不存在 404、方法不支持 405 等）保持原状态码，不当成内部错误 */
  @ExceptionHandler(ErrorResponseException.class)
  public ResponseEntity<ApiError> framework(ErrorResponseException e) {
    return frameworkError(e);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> unexpected(Exception e) {
    if (e instanceof ErrorResponse er) return frameworkError(er);
    log.error("未处理的异常", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of("INTERNAL_ERROR", "服务暂时不可用"));
  }

  private static ResponseEntity<ApiError> frameworkError(ErrorResponse e) {
    int status = e.getStatusCode().value();
    String code = status == 404 ? "NOT_FOUND" : status == 405 ? "METHOD_NOT_ALLOWED" : status == 413 ? "FILE_TOO_LARGE" : "BAD_REQUEST";
    return ResponseEntity.status(e.getStatusCode()).body(ApiError.of(code, "请求无法处理"));
  }
}
