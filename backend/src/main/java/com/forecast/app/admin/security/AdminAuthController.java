package com.forecast.app.admin.security;

import com.forecast.app.common.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 后台登录、退出、改密码。会话令牌只放在 HttpOnly Cookie 里（页面脚本读不到），响应里不返回。 */
@RestController
class AdminAuthController {

  record LoginBody(String username, String password) {}

  record PasswordBody(String oldPassword, String newPassword) {}

  /** 字段和原型 AdminSession 一致（令牌在 Cookie 里，不在响应里） */
  record SessionView(long adminId, String username, String displayName, boolean mustChangePassword) {}

  private final AdminAuthService auth;
  private final AdminProperties props;
  private final RateLimiter limiter;

  AdminAuthController(AdminAuthService auth, AdminProperties props, RateLimiter limiter) {
    this.auth = auth;
    this.props = props;
    this.limiter = limiter;
  }

  /**
   * 登录。同一 IP 在 {@code forecast.rate-limit.admin-login-window}（默认 15 分钟）内最多请求 {@code admin-login-per-ip} 次（默认 20 次，成功失败都算），
   * 超过返回 429 {@code RATE_LIMITED}（安全审查 SEC-15：原来只按账号锁定，换着用户名试不受限制）。超限的请求不查库、不记审计（防止刷审计日志）。
   */
  @AdminPublic
  @PostMapping("/api/admin/auth/login")
  SessionView login(@RequestBody LoginBody body, HttpServletRequest req, HttpServletResponse res) {
    limiter.check("admin-login", req.getRemoteAddr(), limiter.props().adminLoginPerIp(), limiter.props().adminLoginWindow());
    AdminAuthService.LoginResult r = auth.login(body == null ? null : body.username(), body == null ? null : body.password(), req.getRemoteAddr());
    res.addHeader(HttpHeaders.SET_COOKIE, cookie(r.token(), props.sessionTtl()));
    return new SessionView(r.adminId(), r.username(), r.displayName(), r.mustChangePassword());
  }

  @AdminLoginOnly
  @PostMapping("/api/admin/auth/logout")
  void logout(HttpServletResponse res) {
    auth.logout(AdminContext.current());
    res.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO));
  }

  /** 改密码后其他设备上的登录全部退出，当前设备换一个新令牌 */
  @AdminLoginOnly
  @PostMapping("/api/admin/auth/change-password")
  void changePassword(@RequestBody PasswordBody body, HttpServletRequest req, HttpServletResponse res) {
    AdminContext ctx = AdminContext.current();
    auth.changePassword(ctx, body == null ? null : body.oldPassword(), body == null ? null : body.newPassword());
    res.addHeader(HttpHeaders.SET_COOKIE, cookie(auth.reissue(ctx.adminId(), req.getRemoteAddr()), props.sessionTtl()));
  }

  /** HttpOnly、SameSite=Strict、只发给 /api/admin */
  private String cookie(String value, Duration maxAge) {
    return ResponseCookie.from(props.cookieName(), value).httpOnly(true).secure(props.cookieSecure()).sameSite("Strict").path("/api/admin").maxAge(maxAge)
        .build().toString();
  }
}
