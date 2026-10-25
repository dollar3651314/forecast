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

  /** 一条通知：发给谁、模板变量 */
  record Notice(long userId, Map<String, String> params) {}

  /**
   * 一批客户各发一条同一个模板的通知（forecast S5：结算、退款一次几千到上万人）。结果和逐个调用 {@link #toUser} <b>完全一样</b>
   * （站内信、每个渠道的通知记录、推送和邮件任务、提交后推给在线的客户），只是批量写库（一批几条语句）。同样在业务事务里调用，业务回滚就不发。
   */
  void toUsers(String template, java.util.List<Notice> notices);
}
