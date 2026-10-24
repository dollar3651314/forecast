package com.forecast.app.notification.api;

import java.util.Map;

/**
 * 通知客户（技术方案/v1-通知方案.md）。调用方只说"发生了什么"（模板编号和变量）；站内信、推送、邮件发不发、用什么语言由通知模块决定。
 * <p>在业务事务里调用：通知和业务一起提交，业务回滚就不发。
 */
public interface NotifyApi {

  /**
   * @param template 模板编号，例如 {@code DEPOSIT_CREDITED}（变量 amount、chain），模板和变量见 V14 迁移脚本
   * @param params 模板变量；枚举值（例如提现状态、买卖方向）原样传，通知模块按语言翻译
   */
  void toUser(long userId, String template, Map<String, String> params);
}
