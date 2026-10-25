package com.forecast.app.notification.service;

/**
 * 测试环境标识（S20，部署方案第 7 节）：{@code forecast.env-label}（环境变量 {@code FC_ENV_LABEL}）非空时，
 * 邮件和推送的标题前加前缀，避免把测试环境发出的通知当成生产的。生产不设这个变量，标题不变。
 * <ul>
 *   <li>中文：{@code [测试] }；英文：{@code [TEST] }。</li>
 *   <li>中英文各一份的邮件（按邮箱发的）：两段标题各加各的前缀。</li>
 *   <li>后台手写的通知不知道是什么语言：{@code [测试/TEST] }。</li>
 * </ul>
 * 站内信不加：站内信只在页面里看，页面顶部已经有测试环境标识条。
 * 推送要加：推送显示在手机和电脑的系统通知栏里，看不到页面上的标识条；装成 PWA 后通知只显示应用名，不显示测试环境的域名。
 */
final class EnvLabel {

  static final String ZH = "[测试] ";
  static final String EN = "[TEST] ";
  static final String UNKNOWN = "[测试/TEST] ";

  private final boolean on;

  EnvLabel(String label) {
    this.on = label != null && !label.isBlank();
  }

  /** 按语言取前缀；{@code lang} 为空表示不知道语言。没有设置标识时返回空串 */
  String prefix(String lang) {
    if (!on) return "";
    if (lang == null) return UNKNOWN;
    return Render.EN.equals(Render.lang(lang)) ? EN : ZH;
  }

  /** 中文前缀（中英文各一份的邮件用） */
  String zh() {
    return on ? ZH : "";
  }

  /** 英文前缀（中英文各一份的邮件用） */
  String en() {
    return on ? EN : "";
  }
}
