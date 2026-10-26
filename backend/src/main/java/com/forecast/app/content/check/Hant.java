package com.forecast.app.content.check;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 简体 → 繁体（台湾用字）的固定规则转换（forecast S15，自动化需求 EX-9、第 4.5 节：网站页面三语，繁体由简体按固定规则自动转换，不调用 AI）。
 * 转换表 {@code resources/content/s2tw.txt} 由 OpenCC 的词典生成（Apache-2.0，文件开头有说明）：先按短语最长匹配，再按单字。
 * 第一次用时加载（约 1.3 万条，约 2 MB 内存）。转换后的文字同样要过禁用词检查（调用方负责）。
 */
public final class Hant {

  private static volatile Hant instance;

  private final Map<Character, String> chars;
  private final Map<String, String> phrases;
  private final int maxLen;

  private Hant(Map<Character, String> chars, Map<String, String> phrases, int maxLen) {
    this.chars = chars;
    this.phrases = phrases;
    this.maxLen = maxLen;
  }

  public static String convert(String s) {
    if (s == null || s.isEmpty()) return s;
    return get().apply(s);
  }

  static Hant get() {
    Hant h = instance;
    if (h == null) {
      synchronized (Hant.class) {
        if (instance == null) instance = load();
        h = instance;
      }
    }
    return h;
  }

  private String apply(String s) {
    StringBuilder out = new StringBuilder(s.length());
    int i = 0;
    while (i < s.length()) {
      boolean hit = false;
      for (int len = Math.min(maxLen, s.length() - i); len >= 2; len--) {
        String v = phrases.get(s.substring(i, i + len));
        if (v != null) {
          out.append(v);
          i += len;
          hit = true;
          break;
        }
      }
      if (hit) continue;
      char c = s.charAt(i);
      String v = chars.get(c);
      out.append(v != null ? v : String.valueOf(c));
      i++;
    }
    return out.toString();
  }

  private static Hant load() {
    Map<Character, String> chars = new HashMap<>(5000);
    Map<String, String> phrases = new HashMap<>(12000);
    int max = 2;
    try (InputStream in = Hant.class.getResourceAsStream("/content/s2tw.txt")) {
      if (in == null) throw new IllegalStateException("缺少 content/s2tw.txt");
      BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while ((line = r.readLine()) != null) {
        if (line.isEmpty() || line.startsWith("#")) continue;
        String[] p = line.split("\t");
        if (p.length != 3) continue;
        if ("C".equals(p[0]) && p[1].length() == 1) chars.put(p[1].charAt(0), p[2]);
        else if ("P".equals(p[0])) {
          phrases.put(p[1], p[2]);
          max = Math.max(max, p[1].length());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new Hant(chars, phrases, max);
  }
}
