package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 模板变量替换、枚举值翻译、缺变量的行不显示。 */
class RenderTest {

  @Test
  void 变量替换_枚举值按语言翻译_没有翻译的原样() {
    assertThat(Render.fill("{symbol} {side} {lots} 手", Map.of("symbol", "XAUUSD", "side", "BUY", "lots", "0.1"), 0)).isEqualTo("XAUUSD 买入 0.1 手");
    assertThat(Render.fill("{symbol} {side}", Map.of("symbol", "XAUUSD", "side", "SELL"), 1)).isEqualTo("XAUUSD Sell");
    assertThat(Render.fill("状态：{status}", Map.of("status", "SOMETHING_NEW"), 0)).isEqualTo("状态：SOMETHING_NEW");
    assertThat(Render.fill("价格 {price}", Map.of("price", "$1\\2"), 0)).as("值里的特殊字符原样").isEqualTo("价格 $1\\2");
  }

  @Test
  void 某一行缺变量时整行不显示() {
    // 复制源旧模板 KYC_RESULT 的写法（forecast 基线不建这个模板）；用来测"缺变量的行不显示"和状态词翻译
    String t = "您的身份认证{result}。\n驳回原因：{reason}";
    assertThat(Render.fill(t, Map.of("result", "APPROVED"), 0)).isEqualTo("您的身份认证已通过，现在可以提现。");
    assertThat(Render.fill(t, Map.of("result", "REJECTED", "reason", "照片模糊"), 0)).isEqualTo("您的身份认证未通过。\n驳回原因：照片模糊");
    assertThat(Render.fill(t, Map.of("result", "REJECTED", "reason", " "), 0)).isEqualTo("您的身份认证未通过。");
  }

  @Test
  void 语言_简体繁体英文_其他按简体() {
    assertThat(Render.lang("en")).isEqualTo("en");
    assertThat(Render.lang("en-US")).isEqualTo("en");
    assertThat(Render.lang("zh-CN")).isEqualTo("zh-CN");
    assertThat(Render.lang("zh-TW")).isEqualTo("zh-TW");
    assertThat(Render.lang("zh-HK")).isEqualTo("zh-TW");
    assertThat(Render.lang("zh-Hant-TW")).isEqualTo("zh-TW");
    assertThat(Render.lang("fr")).isEqualTo("zh-CN");
    assertThat(Render.lang(null)).isEqualTo("zh-CN");
  }

  @Test
  void 繁体_用繁体文字和名字Tw的变量_没有繁体文字时按简体() {
    Rows.TemplateRow t = new Rows.TemplateRow();
    t.titleZh = "「{market}」{refundKind}";
    t.bodyZh = "原因：{refundReason}";
    t.titleEn = "\"{marketEn}\" {refundKind}";
    t.bodyEn = "Reason: {refundReason}";
    t.titleTw = "「{market}」{refundKind}";
    t.bodyTw = "原因：{refundReason}";
    Map<String, String> p = Map.of("market", "美国 CPI", "marketEn", "US CPI", "marketTw", "美國 CPI", "refundKind", "VOIDED", "refundReason", "NO_WINNER");
    assertThat(Render.render(t, p, "zh-TW")).isEqualTo(new Render.Text("「美國 CPI」已作廢", "原因：沒有人押結果對應的選項，按規則作廢"));
    assertThat(Render.render(t, p, "zh-CN")).isEqualTo(new Render.Text("「美国 CPI」已作废", "原因：没有人押结果对应的选项，按规则作废"));
    assertThat(Render.render(t, p, "en").title()).isEqualTo("\"US CPI\" was voided");
    // 没有 marketTw 时繁体用简体的值
    assertThat(Render.render(t, Map.of("market", "美国 CPI", "refundKind", "CANCELLED", "refundReason", "x"), "zh-TW").title()).isEqualTo("「美国 CPI」已取消");
    // 员工模板没有繁体：按简体
    t.titleTw = "";
    t.bodyTw = "";
    assertThat(Render.render(t, p, "zh-TW").title()).isEqualTo("「美国 CPI」已作废");
  }
}
