package com.forecast.app.market.card;

import com.forecast.app.market.pub.PublicContent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 分享卡片的文字模板（forecast S14，{@code resources/content/cards.json}，三种语言，C6：文案不写死在代码里）。
 * 变量只由 {@link CardService} 用系统数据填；取不到的语言用英文，英文也没有时返回键名（测试会发现）。
 */
@Component
class CardTexts {

  private final JsonNode root;

  CardTexts(ObjectMapper json) {
    try (InputStream in = CardTexts.class.getResourceAsStream("/content/cards.json")) {
      if (in == null) throw new IllegalStateException("缺少 content/cards.json");
      this.root = json.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  String t(String lang, String path) {
    return t(lang, path, Map.of());
  }

  String t(String lang, String path, Map<String, String> vars) {
    String v = raw(PublicContent.lang(lang), path);
    if (v == null) v = raw("en", path);
    if (v == null) return path;
    for (Map.Entry<String, String> e : vars.entrySet()) v = v.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
    return v;
  }

  /** 有没有这个键（作废原因：系统代码有翻译，管理员写的原文没有） */
  boolean has(String lang, String path) {
    return raw(PublicContent.lang(lang), path) != null;
  }

  private String raw(String lang, String path) {
    JsonNode n = root.path(lang);
    for (String k : path.split("\\.")) n = n.path(k);
    return n.isString() ? n.asString() : null;
  }
}
