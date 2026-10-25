package com.forecast.app.admin.security;

import com.forecast.app.common.error.BizException;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 当前请求的管理员（{@link AdminInterceptor} 设置）。ip 是 Nginx 传来的真实客户端 IP（{@code request.getRemoteAddr()}，
 * 由 {@code server.forward-headers-strategy} 只从内网代理接受），不读客户端自己带的请求头。
 */
public record AdminContext(long adminId, String username, String ip, boolean superAdmin, Set<Permission> permissions, long sessionId) {

  static final String ATTR = "fc.admin";
  static final String IP_ATTR = "fc.admin.ip";

  public boolean has(Permission p) {
    return superAdmin || permissions.contains(p);
  }

  /** 当前管理员；没有登录时抛 UNAUTHORIZED（拦截器已检查，正常不会走到） */
  public static AdminContext current() {
    return find().orElseThrow(() -> new BizException("UNAUTHORIZED", "未登录"));
  }

  public static Optional<AdminContext> find() {
    RequestAttributes a = RequestContextHolder.getRequestAttributes();
    return a != null && a.getAttribute(ATTR, RequestAttributes.SCOPE_REQUEST) instanceof AdminContext c ? Optional.of(c) : Optional.empty();
  }

  /** 当前请求的真实 IP（登录前也有） */
  public static String requestIp() {
    RequestAttributes a = RequestContextHolder.getRequestAttributes();
    return a != null && a.getAttribute(IP_ATTR, RequestAttributes.SCOPE_REQUEST) instanceof String s ? s : null;
  }
}
