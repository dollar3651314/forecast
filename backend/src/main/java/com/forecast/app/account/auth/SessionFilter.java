package com.forecast.app.account.auth;

import com.forecast.app.common.e2e.E2eGuard;
import com.forecast.app.common.trace.TraceIdFilter;
import com.forecast.app.common.web.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 客户端接口的登录检查：/api/ 下除公开接口和后台接口（/api/admin/，S16 另有一套）外，都要求有效会话。
 * 通过后把用户 ID 放进 {@link CurrentUser}，其他模块从那里读。
 */
@Component
class SessionFilter extends OncePerRequestFilter {

  /**
   * 不需要登录的接口。复制源的官网公开行情 /api/public/quotes 随行情模块删除（forecast S1）。
   */
  static final Set<String> PUBLIC = Set.of(
      "/api/health", "/api/auth/email-code", "/api/auth/register", "/api/auth/login", "/api/auth/logout", "/api/auth/password/reset",
      // forecast S11：#23 开赛提醒邮件里的退订链接（凭随机串只能退订那一条，ReminderController）
      "/api/reminders/unsubscribe",
      // forecast S13（MF-1）：带来源参数的访问（只记来源，不涉及账户；同源、按 IP 限流）
      "/api/attribution/visit",
      // forecast S13（MF-4、NF-14）：给 Nginx SSI / 搜索引擎的页面头部、正文、页面是否存在，robots.txt、站点地图——总是公开（关掉公开浏览时 robots 禁止收录）
      "/api/public/seo/head", "/api/public/seo/body", "/api/public/seo/exists", "/api/public/robots.txt", "/api/public/sitemap.xml");

  /**
   * forecast S13（MF-4，需求第 5.1 节虚线框、AC-84）：不登录也能看的接口（只有 GET）。开关 {@code forecast.public-browse.enabled}（默认开，
   * 需求 v1.3 要求打开）打开时：有有效会话就照常带上用户（详情里有本人的持仓），没有会话也放行（只返回公开数据）；关掉时和以前一样要登录。
   * 下注、我的下注、资产等其他接口照样要登录（401）。奖池推送 {@code /api/ws/markets} 的握手也在这里（推送内容不含个人数据）。
   * S14（MF-5）：分享卡片的图片 {@code /api/public/cards/**.png} 和分享信息 {@code /api/public/share}（登录了链接带分享人 ref，没登录不带）。
   */
  static final java.util.regex.Pattern PUBLIC_BROWSE = java.util.regex.Pattern.compile(
      "^/api/markets(/today|/\\d{1,19})?$|^/api/public/(results|weekly(/[^/]+)?|pages/how-to-play|share)$|^/api/ws/markets$"
          + "|^/api/public/cards/[a-z]+/[A-Za-z0-9_-]{1,32}\\.png$");

  private final SessionService sessions;
  private final AuthProperties props;
  private final boolean publicBrowse;
  /** 端到端测试模式（profile e2e，S19）：/api/e2e/** 不检查登录。其他配置下这些接口根本不存在，照常要求登录（返回 401） */
  private final boolean e2e;

  SessionFilter(SessionService sessions, AuthProperties props, Environment env) {
    this.sessions = sessions;
    this.props = props;
    this.publicBrowse = env.getProperty("forecast.public-browse.enabled", Boolean.class, true);
    this.e2e = env.acceptsProfiles(Profiles.of(E2eGuard.PROFILE));
  }

  static String token(HttpServletRequest req, String cookieName) {
    Cookie[] cookies = req.getCookies();
    if (cookies == null) return null;
    for (Cookie c : cookies) if (cookieName.equals(c.getName())) return c.getValue();
    return null;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    String p = req.getRequestURI();
    return !p.startsWith("/api/") || p.startsWith("/api/admin/") || PUBLIC.contains(p) || (e2e && p.startsWith(E2eGuard.PATH_PREFIX));
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    Long userId = sessions.resolve(token(req, props.cookieName()));
    if (userId == null && publicBrowse && "GET".equals(req.getMethod()) && PUBLIC_BROWSE.matcher(req.getRequestURI()).matches()) {
      chain.doFilter(req, res); // 不登录浏览：没有用户，接口只返回公开数据
      return;
    }
    if (userId == null) {
      res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      res.setContentType(MediaType.APPLICATION_JSON_VALUE);
      res.setCharacterEncoding(StandardCharsets.UTF_8.name());
      res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"未登录或登录已失效\",\"fields\":[]" + TraceIdFilter.jsonField() + "}");
      return;
    }
    CurrentUser.set(req, userId);
    chain.doFilter(req, res);
  }
}
