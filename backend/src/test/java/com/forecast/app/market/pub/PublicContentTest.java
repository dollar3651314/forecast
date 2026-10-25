package com.forecast.app.market.pub;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 公开页面的文字（forecast S13，{@code resources/content/public-pages.json}）：三种语言的键和段落数一致；没有诱导收益的说法（F-12、F-15，
 * 和通知模板的 S9NotifyFlowTest.BANNED 同一份意思）；变量只用 PublicContent 提供的；HTML 转义；语言写法统一。
 */
class PublicContentTest {

  static final List<String> BANNED = List.of("稳赚", "必中", "保本", "翻倍", "胜者瓜分", "最后入场", "别错过", "恭喜", "穩賺", "勝者瓜分", "最後入場", "別錯過",
      "guaranteed", "risk-free", "risk free", "sure win", "double your", "last chance", "don't miss");
  static final List<String> VARS = List.of("{brand}", "{minWithdraw}", "{fee}", "{coolingHours}", "{whitelistHours}", "{largeThreshold}", "{largeDelayHours}",
      "{kycThreshold}", "{time}", "{week}", "{markets}", "{settled}", "{voided}", "{pool}", "{payout}");

  static JsonNode root() throws Exception {
    try (InputStream in = PublicContentTest.class.getResourceAsStream("/content/public-pages.json")) {
      return new ObjectMapper().readTree(in);
    }
  }

  static void paths(JsonNode n, String prefix, List<String> out) {
    if (n.isObject()) n.properties().forEach(e -> paths(e.getValue(), prefix + "." + e.getKey(), out));
    else if (n.isArray()) {
      out.add(prefix + "[" + n.size() + "]");
      for (int i = 0; i < n.size(); i++) paths(n.get(i), prefix + "[" + i + "]", out);
    } else out.add(prefix);
  }

  static void texts(JsonNode n, List<String> out) {
    if (n.isObject()) n.properties().forEach(e -> texts(e.getValue(), out));
    else if (n.isArray()) n.forEach(x -> texts(x, out));
    else if (n.isString()) out.add(n.asString());
  }

  @Test
  void 三种语言结构一致_没有诱导词_变量都认识() throws Exception {
    JsonNode r = root();
    List<String> en = new ArrayList<>();
    paths(r.get("en"), "", en);
    for (String l : PublicContent.LANGS) {
      List<String> p = new ArrayList<>();
      paths(r.get(l), "", p);
      assertThat(p).as(l).isEqualTo(en);
      List<String> t = new ArrayList<>();
      texts(r.get(l), t);
      for (String s : t) {
        for (String b : BANNED) assertThat(s.toLowerCase(Locale.ROOT)).as(l + "：" + s).doesNotContain(b.toLowerCase(Locale.ROOT));
        String rest = s;
        for (String v : VARS) rest = rest.replace(v, "");
        assertThat(rest).as("变量以外的大括号：" + s).doesNotContain("{").doesNotContain("}");
      }
    }
  }

  @Test
  void 语言写法统一_转义_页面地址() {
    assertThat(PublicContent.lang("zh-hk")).isEqualTo("zh-TW");
    assertThat(PublicContent.lang("zh-Hant-TW")).isEqualTo("zh-TW");
    assertThat(PublicContent.lang("zh")).isEqualTo("zh-CN");
    assertThat(PublicContent.lang("zh_CN")).isEqualTo("zh-CN");
    assertThat(PublicContent.lang("fr")).isEqualTo("en");
    assertThat(PublicContent.lang(null)).isEqualTo("en");
    assertThat(SeoPages.esc("<a href=\"x\">'&")).isEqualTo("&lt;a href=&quot;x&quot;&gt;&#39;&amp;");
    assertThat(SeoPages.Page.of("/markets/1?src=tg&lang=zh-TW").lang()).isEqualTo("zh-TW");
    assertThat(SeoPages.Page.of("/markets/1?src=tg&lang=zh-TW").path()).isEqualTo("/markets/1");
    assertThat(SeoPages.Page.of("/markets/1").explicitLang()).isFalse();
    assertThat(SeoPages.Page.of("%%%").path()).isEqualTo("/");
  }
}
