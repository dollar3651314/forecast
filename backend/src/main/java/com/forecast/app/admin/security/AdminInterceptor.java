package com.forecast.app.admin.security;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.error.BizException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 后台接口的三道关（后台方案一图看懂）：
 * <ol>
 *   <li>IP 白名单（系统配置 {@code admin.ipWhitelist}，为空不限制）：只认 {@code request.getRemoteAddr()}，也就是 Nginx 传来的真实 IP，
 *       客户端自己带的 {@code X-Forwarded-For} 不起作用（只有内网代理传来的才会被 Tomcat 采用）。</li>
 *   <li>登录：后台自己的 Cookie。</li>
 *   <li>权限点：接口上的 {@link RequiresPermission}；超级管理员拥有全部。<b>没有声明的接口一律 403</b>（默认拒绝）。</li>
 * </ol>
 * 首次登录、重置密码后只能调用 {@link AdminLoginOnly} 的接口（改密码、当前信息、退出）。
 */
@Component
class AdminInterceptor implements HandlerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(AdminInterceptor.class);

  private final SystemConfig config;
  private final AdminAuthService auth;
  private final AdminProperties props;

  AdminInterceptor(SystemConfig config, AdminAuthService auth, AdminProperties props) {
    this.config = config;
    this.auth = auth;
    this.props = props;
  }

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    String ip = req.getRemoteAddr();
    req.setAttribute(AdminContext.IP_ATTR, ip);
    String list = config.text(ConfigKey.ADMIN_IP_WHITELIST, "");
    if (!list.isBlank()) {
      var ranges = IpRanges.parse(list);
      if (ranges.isEmpty() || !ranges.get().contains(ip)) {
        log.warn("后台请求 IP 不在白名单：{} {}", ip, req.getRequestURI());
        throw new BizException("IP_NOT_ALLOWED", "当前网络不能访问后台");
      }
    }
    if (!(handler instanceof HandlerMethod hm)) return true;
    if (hm.hasMethodAnnotation(AdminPublic.class)) return true;
    AdminContext ctx = auth.authenticate(token(req), ip).orElseThrow(() -> new BizException("UNAUTHORIZED", "未登录或登录已失效"));
    req.setAttribute(AdminContext.ATTR, ctx);
    if (hm.hasMethodAnnotation(AdminLoginOnly.class)) return true;
    if (auth.mustChangePassword(ctx.adminId())) throw new BizException("PASSWORD_CHANGE_REQUIRED", "请先修改密码");
    RequiresPermission rp = hm.getMethodAnnotation(RequiresPermission.class);
    if (rp == null) {
      log.error("后台接口没有声明权限点，已拒绝：{}", hm);
      throw new BizException("FORBIDDEN", "没有权限");
    }
    if (!ctx.has(rp.value())) throw new BizException("FORBIDDEN", "没有权限");
    return true;
  }

  private String token(HttpServletRequest req) {
    if (req.getCookies() == null) return null;
    for (Cookie c : req.getCookies()) if (props.cookieName().equals(c.getName())) return c.getValue();
    return null;
  }
}
