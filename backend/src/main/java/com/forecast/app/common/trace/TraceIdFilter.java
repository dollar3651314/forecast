package com.forecast.app.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每个 HTTP 请求一个追踪号（见 {@link TraceId}）。排在所有过滤器最前面，跨站检查、限流、登录检查拒绝的请求也带追踪号。
 * 响应头在进入后面的过滤器之前就设好（拒绝、出错、正常返回都有）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    String id = TraceId.acceptOrGenerate(req.getHeader(TraceId.HEADER));
    req.setAttribute(TraceId.ATTRIBUTE, id);
    res.setHeader(TraceId.HEADER, id);
    MDC.put(TraceId.MDC_KEY, id);
    try {
      chain.doFilter(req, res);
    } finally {
      MDC.remove(TraceId.MDC_KEY);
    }
  }

  /** 过滤器自己写错误响应体时用：{@code ,"traceId":"…"}（没有追踪号时为空字符串）。追踪号只含字母、数字和 ._-，不需要转义 */
  public static String jsonField() {
    String id = TraceId.current();
    return id == null ? "" : ",\"traceId\":\"" + id + "\"";
  }
}
