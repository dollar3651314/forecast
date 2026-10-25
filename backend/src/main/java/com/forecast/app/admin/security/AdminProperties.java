package com.forecast.app.admin.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 后台参数（application.yml 的 forecast.admin，后台方案 G1、G2）。登录锁定次数和时长在系统配置里（后台可改）。
 *
 * @param sessionTtl 登录最长有效期
 * @param idleTimeout 不操作多久自动退出
 * @param cookieName 后台登录 Cookie 的名字（和客户的分开）
 * @param cookieSecure Cookie 是否只走 HTTPS
 * @param initUsername 第一个超级管理员的用户名（G1）；系统里已经有管理员时不起作用
 * @param initPassword 第一个超级管理员的密码（G1，只由 Amos 填进服务器）
 */
@ConfigurationProperties("forecast.admin")
record AdminProperties(@DefaultValue("8h") Duration sessionTtl, @DefaultValue("30m") Duration idleTimeout, @DefaultValue("fc_admin_session") String cookieName,
    @DefaultValue("true") boolean cookieSecure, String initUsername, String initPassword) {

  /** 不打印初始密码 */
  @Override
  public String toString() {
    return "AdminProperties[sessionTtl=" + sessionTtl + ", idleTimeout=" + idleTimeout + ", cookieName=" + cookieName + ", initUsername=" + initUsername
        + ", initPassword=" + (initPassword == null || initPassword.isEmpty() ? "(空)" : "(已设置)") + "]";
  }
}
