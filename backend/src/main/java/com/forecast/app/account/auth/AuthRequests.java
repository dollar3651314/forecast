package com.forecast.app.account.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 认证接口的请求体。校验失败的 message 是错误码，所有字段的错误一次返回（C56），前端按错误码显示翻译。
 * 密码规则沿用现有系统：长度 8–64，不限制复杂度（功能地图 A-01）。
 */
final class AuthRequests {
  private AuthRequests() {}

  /** 客户密码：8–64 位，至少包含一个字母和一个数字（前后端一致，Amos 2026-10-16 确认） */
  static final String PASSWORD_MIX = "(?s)(?=.*[A-Za-z])(?=.*\\d).*";

  record SendCode(
      @NotBlank(message = "REQUIRED") @Email(message = "EMAIL_INVALID") @Size(max = 254, message = "EMAIL_INVALID") String email,
      @NotNull(message = "REQUIRED") EmailCodeService.Purpose purpose) {}

  record Register(
      @NotBlank(message = "REQUIRED") @Email(message = "EMAIL_INVALID") @Size(max = 254, message = "EMAIL_INVALID") String email,
      @NotBlank(message = "REQUIRED") @Pattern(regexp = "\\s*\\d{6}\\s*", message = "CODE_FORMAT") String code,
      @NotBlank(message = "REQUIRED") @Size(min = 8, max = 64, message = "PASSWORD_LENGTH") @Pattern(regexp = PASSWORD_MIX, message = "PASSWORD_MIX") String password,
      @NotBlank(message = "REQUIRED") @Size(max = 64, message = "TOO_LONG") String firstName,
      @Size(max = 64, message = "TOO_LONG") String middleName,
      @NotBlank(message = "REQUIRED") @Size(max = 64, message = "TOO_LONG") String lastName,
      @NotNull(message = "REQUIRED") @Past(message = "BIRTH_DATE_INVALID") LocalDate birthDate,
      @NotBlank(message = "REQUIRED") @Pattern(regexp = "[A-Z]{2}", message = "NATIONALITY_INVALID") String nationality,
      // forecast S11（需求 P8）：勾选"已年满 18 周岁并同意服务条款"；没勾（false 或没传）一律拒绝
      @NotNull(message = "ADULT_REQUIRED") @AssertTrue(message = "ADULT_REQUIRED") Boolean adultConfirmed,
      // forecast S13（MF-1）：浏览器里记的来源（可以没有）；不合法的值当成没有，不影响注册（account/attribution/AttributionService）
      AttributionBody attribution) {

    /** S13 以前的写法（没有来源） */
    Register(String email, String code, String password, String firstName, String middleName, String lastName, LocalDate birthDate, String nationality,
        Boolean adultConfirmed) {
      this(email, code, password, firstName, middleName, lastName, birthDate, nationality, adultConfirmed, null);
    }
  }

  /** 一次带来源参数的访问：链接上的 src、cmp、ref、start 原文和浏览器记下的时间（ISO） */
  record Touch(String src, String cmp, String ref, String start, String at) {}

  /** 首次来源（第一次带参数访问，30 天内）和最近一次来源（注册时的值，需求第 5.2 节、AC-83） */
  record AttributionBody(Touch first, Touch last) {}

  record Login(@NotBlank(message = "REQUIRED") String email, @NotBlank(message = "REQUIRED") String password) {}

  record ResetPassword(
      @NotBlank(message = "REQUIRED") @Email(message = "EMAIL_INVALID") String email,
      @NotBlank(message = "REQUIRED") @Pattern(regexp = "\\s*\\d{6}\\s*", message = "CODE_FORMAT") String code,
      @NotBlank(message = "REQUIRED") @Size(min = 8, max = 64, message = "PASSWORD_LENGTH") @Pattern(regexp = PASSWORD_MIX, message = "PASSWORD_MIX") String password) {}

  record ChangePassword(
      @NotBlank(message = "REQUIRED") String currentPassword,
      @NotBlank(message = "REQUIRED") @Size(min = 8, max = 64, message = "PASSWORD_LENGTH") @Pattern(regexp = PASSWORD_MIX, message = "PASSWORD_MIX") String newPassword) {}

  /** 登录后返回给前端的信息（令牌在 Cookie 里，不在响应体里） */
  record SessionView(String userId, String email) {}
}
