package com.forecast.app.market.api;

/** 三种语言的文字（RQ-18：英文、简体中文、繁體中文）；客户端按自己的语言选，取不到用英文。 */
public record I18nText(String en, String zhCN, String zhTW) {

  public static final I18nText EMPTY = new I18nText("", "", "");

  /** null 变成空串、去掉首尾空白 */
  public I18nText normalized() {
    return new I18nText(clean(en), clean(zhCN), clean(zhTW));
  }

  public boolean complete() {
    return !en.isBlank() && !zhCN.isBlank() && !zhTW.isBlank();
  }

  private static String clean(String s) {
    return s == null ? "" : s.strip();
  }
}
