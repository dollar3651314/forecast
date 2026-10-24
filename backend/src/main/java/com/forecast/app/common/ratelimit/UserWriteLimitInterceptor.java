package com.forecast.app.common.ratelimit;

import com.forecast.app.common.web.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 按客户限流（功能取舍对照表 P-05 的"交易"层）：接口方法标了 {@link UserWriteLimited} 时，按当前登录客户计数。
 * 在所有过滤器（含登录检查）之后执行，所以能拿到客户 ID；超限抛 {@code RetryLaterException}，统一错误处理返回 429 {@code RATE_LIMITED} 和 Retry-After。
 * 没有登录的请求在登录检查就被拦下（401），不会走到这里。
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
class UserWriteLimitInterceptor implements HandlerInterceptor, WebMvcConfigurer {

  private final RateLimiter limiter;

  UserWriteLimitInterceptor(RateLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns("/api/**");
  }

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    if (handler instanceof HandlerMethod m && m.hasMethodAnnotation(UserWriteLimited.class)) {
      CurrentUser.of(req).ifPresent(id -> limiter.check("user", String.valueOf(id), limiter.props().userWritePerMinute(), IpRateLimitFilter.MINUTE));
    }
    return true;
  }
}
