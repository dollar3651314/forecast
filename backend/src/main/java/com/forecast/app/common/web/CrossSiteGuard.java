package com.forecast.app.common.web;

import com.forecast.app.common.trace.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

/**
 * /api/ 的两道通用检查（安全审查 SEC-01、SEC-02，见 测试报告/v1-安全审查.md），在登录检查之前：
 * <ol>
 *   <li><b>路径里不能有分号</b>（400）：Spring 匹配接口时忽略分号后面的内容（{@code /api/admin;x/me} 当成 {@code /api/admin/me}），
 *       而 Nginx 和登录检查按原始路径判断，两边不一致就能绕过"后台接口只在后台域名开放"。本系统没有任何接口用到分号。</li>
 *   <li><b>写请求只接受本站页面发起</b>（403）：登录 Cookie 是 SameSite=Strict，但"同站"包括同一主域名下的所有子域名（测试环境的客户端和后台、
 *       以后的生产域名、同一主域名下的其他网站），这些网站的页面发起的表单提交会带上 Cookie。浏览器发的请求带 {@code Sec-Fetch-Site}，
 *       不是 same-origin（或 none，用户自己在地址栏打开）就拒绝；老浏览器没有这个头时看 {@code Origin}，和本站不同就拒绝（和 WebSocket 的同源检查同一个方法）。
 *       两个头都没有的是非浏览器的请求（部署脚本、测试），不受影响。读请求（GET、HEAD、OPTIONS）不检查：跨站读不到响应内容。</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CrossSiteGuard extends OncePerRequestFilter {

  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return !req.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    if (req.getRequestURI().indexOf(';') >= 0) {
      reject(res, HttpServletResponse.SC_BAD_REQUEST, "BAD_REQUEST", "请求格式不正确");
      return;
    }
    if (!SAFE_METHODS.contains(req.getMethod()) && !fromThisSite(req)) {
      reject(res, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "不接受其他网站发起的请求");
      return;
    }
    chain.doFilter(req, res);
  }

  static boolean fromThisSite(HttpServletRequest req) {
    String site = req.getHeader("Sec-Fetch-Site");
    if (site != null) return "same-origin".equalsIgnoreCase(site) || "none".equalsIgnoreCase(site);
    // 没有 Origin 时 isSameOrigin 返回 true（非浏览器的请求）；"null" 等解析不出主机的一律不同源
    return WebUtils.isSameOrigin(new ServletServerHttpRequest(req));
  }

  private static void reject(HttpServletResponse res, int status, String code, String message) throws IOException {
    res.setStatus(status);
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    res.setCharacterEncoding(StandardCharsets.UTF_8.name());
    res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"fields\":[]" + TraceIdFilter.jsonField() + "}");
  }
}
