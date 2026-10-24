package com.forecast.app.common.ratelimit;

import com.forecast.app.common.trace.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 按 IP 限流（功能取舍对照表 P-05 的"全局 IP"层），在跨站检查之后、登录检查之前，没登录的洪水请求也挡得住：
 * <ul>
 *   <li>{@code /api/**}：同一 IP 每分钟最多 {@code ipPerMinute} 次（默认 600），不含 {@code /api/health}（部署脚本、外部探测要一直能调）；</li>
 *   <li>{@code /api/ws/**}（行情、交易推送的 WebSocket 握手）：单独计数，每分钟最多 {@code wsHandshakePerMinute} 次（默认 120），
 *       防止反复重连刷连接；只数握手，已经建立的长连接不受影响。</li>
 * </ul>
 * IP 是 {@code request.getRemoteAddr()}：Nginx 用 {@code $remote_addr} 覆盖 X-Forwarded-For 传来，后端只信任内网代理
 * （{@code server.forward-headers-strategy: native}），客户端自己带的头不起作用。超限返回 429 {@code RATE_LIMITED}，带 Retry-After。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class IpRateLimitFilter extends OncePerRequestFilter {

  static final Duration MINUTE = Duration.ofMinutes(1);

  private final RateLimiter limiter;

  public IpRateLimitFilter(RateLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    String p = req.getRequestURI();
    return !p.startsWith("/api/") || p.equals("/api/health");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    RateLimitProperties props = limiter.props();
    String ip = req.getRemoteAddr();
    long wait = req.getRequestURI().startsWith("/api/ws/")
        ? limiter.hit("ws", ip, props.wsHandshakePerMinute(), MINUTE)
        : limiter.hit("ip", ip, props.ipPerMinute(), MINUTE);
    if (wait > 0) {
      res.setStatus(429);
      res.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(wait));
      res.setContentType(MediaType.APPLICATION_JSON_VALUE);
      res.setCharacterEncoding(StandardCharsets.UTF_8.name());
      res.getWriter().write("{\"code\":\"" + RateLimiter.CODE + "\",\"message\":\"请求太频繁，请稍后再试\",\"fields\":[],\"cooldownSeconds\":" + wait + TraceIdFilter.jsonField() + "}");
      return;
    }
    chain.doFilter(req, res);
  }
}
