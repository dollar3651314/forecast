package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 模板变量替换、枚举值翻译、缺变量的行不显示。 */
class RenderTest {

  @Test
  void 变量替换_枚举值按语言翻译_没有翻译的原样() {
    assertThat(Render.fill("{symbol} {side} {lots} 手", Map.of("symbol", "XAUUSD", "side", "BUY", "lots", "0.1"), false)).isEqualTo("XAUUSD 买入 0.1 手");
    assertThat(Render.fill("{symbol} {side}", Map.of("symbol", "XAUUSD", "side", "SELL"), true)).isEqualTo("XAUUSD Sell");
    assertThat(Render.fill("状态：{status}", Map.of("status", "SOMETHING_NEW"), false)).isEqualTo("状态：SOMETHING_NEW");
    assertThat(Render.fill("价格 {price}", Map.of("price", "$1\\2"), false)).as("值里的特殊字符原样").isEqualTo("价格 $1\\2");
  }

  @Test
  void 某一行缺变量时整行不显示() {
    // 复制源旧模板 KYC_RESULT 的写法（forecast 基线不建这个模板）；用来测"缺变量的行不显示"和状态词翻译
    String t = "您的身份认证{result}。\n驳回原因：{reason}";
    assertThat(Render.fill(t, Map.of("result", "APPROVED"), false)).isEqualTo("您的身份认证已通过，现在可以提现。");
    assertThat(Render.fill(t, Map.of("result", "REJECTED", "reason", "照片模糊"), false)).isEqualTo("您的身份认证未通过。\n驳回原因：照片模糊");
    assertThat(Render.fill(t, Map.of("result", "REJECTED", "reason", " "), false)).isEqualTo("您的身份认证未通过。");
  }

  @Test
  void 语言只支持中英文_其他按中文() {
    assertThat(Render.lang("en")).isEqualTo("en");
    assertThat(Render.lang("zh-CN")).isEqualTo("zh-CN");
    assertThat(Render.lang("fr")).isEqualTo("zh-CN");
    assertThat(Render.lang(null)).isEqualTo("zh-CN");
  }
}
