package com.forecast.app.growth.board;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 推广功能的文字（forecast S16，{@code resources/content/growth.json}，三种语言，C6：文案不写死在代码里）：数据页给搜索引擎的部分、Telegram 机器人的回复。
 * {@code {brand}} 自动换成品牌名（{@code forecast.public.brand-name}）；其他变量由调用方用系统数据填。取不到的语言用英文。
 */
@Component
public class GrowthTexts {

  public static final List<String> LANGS = List.of("en", "zh-CN", "zh-TW");

  private final JsonNode root;
  private final String brand;

  GrowthTexts(ObjectMapper json, @Value("${forecast.public.brand-name:Quick Come}") String brand) {
    this.brand = brand;
    try (InputStream in = GrowthTexts.class.getResourceAsStream("/content/growth.json")) {
      if (in == null) throw new IllegalStateException("缺少 content/growth.json");
      this.root = json.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public String brand() {
    return brand;
  }

  /** en / zh-CN / zh-TW（其他 zh 按简体，其余英文） */
  public static String lang(String l) {
    if (l == null || l.isBlank()) return "en";
    String s = l.strip().replace('_', '-').toLowerCase(java.util.Locale.ROOT);
    if (s.equals("zh-tw") || s.equals("zh-hk") || s.equals("zh-mo") || s.startsWith("zh-hant")) return "zh-TW";
    if (s.startsWith("zh")) return "zh-CN";
    return "en";
  }

  public String t(String lang, String path) {
    return t(lang, path, Map.of());
  }

  public String t(String lang, String path, Map<String, String> vars) {
    String v = raw(lang(lang), path);
    if (v == null) v = raw("en", path);
    if (v == null) return path;
    v = v.replace("{brand}", brand);
    for (Map.Entry<String, String> e : vars.entrySet()) v = v.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
    return v;
  }

  /** 原始 JSON（测试核对三种语言的键一致、没有禁用词） */
  public JsonNode root() {
    return root;
  }

  private String raw(String lang, String path) {
    JsonNode n = root.path(lang);
    for (String k : path.split("\\.")) n = n.path(k);
    return n.isString() ? n.asString() : null;
  }
}
