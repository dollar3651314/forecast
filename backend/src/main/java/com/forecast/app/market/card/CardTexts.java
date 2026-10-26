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

  /**
   * 一种语言的全部模板文字的指纹（forecast S16，自动发布的模板审定，需求第 6.3 节"已审定的模板改了任何一个字，就变回草稿"）：
   * 这种语言下任何一个字改了都会变（卡片之间共用品类、状态、选项行等文字，所以按整个语言算，宁可多要求审定一次）。
   */
  String fingerprint(String kind, String lang) {
    String l = PublicContent.lang(lang);
    String canonical = canonical(root.path(l));
    try {
      byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest((kind + "|" + l + "|" + canonical).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(d);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 键排好序的文本形式（和 JSON 文件里的顺序、空白无关） */
  private static String canonical(JsonNode n) {
    if (n.isObject()) {
      java.util.TreeMap<String, String> m = new java.util.TreeMap<>();
      for (Map.Entry<String, JsonNode> e : n.properties()) m.put(e.getKey(), canonical(e.getValue()));
      StringBuilder sb = new StringBuilder("{");
      m.forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
      return sb.append('}').toString();
    }
    return n.isString() ? n.asString() : n.toString();
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
