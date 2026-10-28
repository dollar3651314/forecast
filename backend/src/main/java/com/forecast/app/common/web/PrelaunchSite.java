package com.forecast.app.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 对外预热域名（forecast S18，P1：{@code www.quickcome.online} 根路径显示等候页）。
 * <p>判断只靠 Nginx 设的请求头 {@value #HEADER}：{@code deploy/nginx/site.conf.template} 按"哪一个 server 块接的请求"（{@code map $server_name}）给预热域名设
 * {@value #PRELAUNCH}，其他域名一律设成空（{@code proxy.inc} 的 {@code proxy_set_header} 覆盖客户端自己带的同名头，伪造不了）。
 * 不按 Host 字符串判断，前端也不按域名判断（任务要求：靠服务器配置）。
 * <p>用在：健康检查不返回测试环境标识 {@code env}（客户端就不显示标识条、也不留空白，和生产同一条路）；搜索引擎版本把根路径当成数据页、
 * canonical 指向预热域名自己（{@code market/pub/PublicController}）。
 */
public final class PrelaunchSite {

  /** Nginx 传给后端的请求头 */
  public static final String HEADER = "X-Fc-Site";
  /** 预热域名的值 */
  public static final String PRELAUNCH = "prelaunch";

  private PrelaunchSite() {}

  /** 这个请求是不是从预热域名进来的（没有请求时按"不是"） */
  public static boolean is(HttpServletRequest req) {
    return req != null && PRELAUNCH.equals(req.getHeader(HEADER));
  }
}
