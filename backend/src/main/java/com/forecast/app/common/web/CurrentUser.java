package com.forecast.app.common.web;

import com.forecast.app.common.error.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 当前登录的客户。登录检查由 account 模块的过滤器完成，其他模块只从这里读用户 ID，不直接接触会话。
 */
public final class CurrentUser {

  static final String ATTR = "fc.userId";

  private CurrentUser() {}

  public static void set(HttpServletRequest request, long userId) {
    request.setAttribute(ATTR, userId);
  }

  /** 指定请求上的用户 ID（WebSocket 握手时用）；没有登录时为空。 */
  public static java.util.Optional<Long> of(HttpServletRequest request) {
    return request.getAttribute(ATTR) instanceof Long id ? java.util.Optional.of(id) : java.util.Optional.empty();
  }

  /**
   * 当前请求的用户 ID；没登录时为空（forecast S13：不登录也能看的接口用，例如市场详情——登录了带上本人的持仓，没登录只有公开数据）。
   */
  public static java.util.Optional<Long> optional() {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    Object v = attrs == null ? null : attrs.getAttribute(ATTR, RequestAttributes.SCOPE_REQUEST);
    return v instanceof Long id ? java.util.Optional.of(id) : java.util.Optional.empty();
  }

  /** 当前请求的用户 ID；没有登录时抛 UNAUTHORIZED（过滤器已拦截，正常不会走到这里）。 */
  public static long id() {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    Object v = attrs == null ? null : attrs.getAttribute(ATTR, RequestAttributes.SCOPE_REQUEST);
    if (v instanceof Long id) return id;
    throw new BizException("UNAUTHORIZED", "未登录");
  }
}
