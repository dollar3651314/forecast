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
 * 简体 → 繁体（台湾用语）的固定规则转换（forecast S15，自动化需求 EX-9、第 4.5 节：网站页面三语，繁体由简体按固定规则自动转换，不调用 AI）。
 * S18（P3 + P9，台湾第一期）：从 OpenCC 的 s2tw（只换字形）改成 s2twp（再换成台湾用语，例如 數據 → 資料、默認 → 預設），再加一张自定义词表：
 * <ol>
 *   <li>自定义词表 {@code resources/content/tw-terms.txt}（简体 → 最终的台湾繁体，市场可以直接改；例如 美联储 → 聯準會、加息 25 个基点 → 升息 1 碼），
 *       最长匹配，命中的部分不再经过下面两步——所以它也能挡住 OpenCC 换错的地方；</li>
 *   <li>其余部分先按 {@code s2tw.txt}（OpenCC 的 STCharacters、STPhrases、TWVariants，S15）转成台湾用字；</li>
 *   <li>再按 {@code tw-phrases.txt}（OpenCC 的 TWPhrasesIT、TWPhrasesName、TWPhrasesOther，{@code scripts/gen-tw-phrases.mjs} 生成）换成台湾用语。</li>
 * </ol>
 * 第 2、3 步和 opencc-js 1.0.5 的 cn → twp 一样是两遍最长匹配。仓库根目录的 {@code scripts/tw-terms.mjs} 是同一套规则的 Node 版本（市场改词表后自查、
 * 研发做繁体文案的台湾用语替换），两边用同一组用例 {@code backend/src/test/resources/content/tw-vectors.json}。
 * 第一次用时加载（约 1.4 万条，约 2 MB 内存）。转换后的文字同样要过禁用词检查（调用方负责）。
 */
public final class Hant {

  private static volatile Hant instance;

  private final Table terms;
  private final Table st;
  private final Table tw;

  private Hant(Table terms, Table st, Table tw) {
    this.terms = terms;
    this.st = st;
    this.tw = tw;
  }

  /** 简体 → 台湾繁体（自定义词表 → 简转繁 → 台湾用语） */
  public static String convert(String s) {
    if (s == null || s.isEmpty()) return s;
    return get().apply(s);
  }

  static Hant get() {
    Hant h = instance;
    if (h == null) {
      synchronized (Hant.class) {
        if (instance == null) instance = new Hant(Table.load("/content/tw-terms.txt", true), Table.load("/content/s2tw.txt", false), Table.load("/content/tw-phrases.txt", false));
        h = instance;
      }
    }
    return h;
  }

  private String apply(String s) {
    StringBuilder out = new StringBuilder(s.length());
    StringBuilder gap = new StringBuilder();
    int i = 0;
    while (i < s.length()) {
      int len = terms.longest(s, i);
      if (len > 0) {
        flush(gap, out);
        out.append(terms.phrases.get(s.substring(i, i + len)));
        i += len;
      } else {
        gap.append(s.charAt(i));
        i++;
      }
    }
    flush(gap, out);
    return out.toString();
  }

  private void flush(StringBuilder gap, StringBuilder out) {
    if (gap.isEmpty()) return;
    out.append(tw.replace(st.replace(gap.toString())));
    gap.setLength(0);
  }

  /** 一张转换表：短语（最长匹配优先）+ 单字 */
  private static final class Table {
    final Map<Character, String> chars;
    final Map<String, String> phrases;
    final int maxLen;

    Table(Map<Character, String> chars, Map<String, String> phrases, int maxLen) {
      this.chars = chars;
      this.phrases = phrases;
      this.maxLen = maxLen;
    }

    /** 从 i 开始能匹配的最长短语的长度（单字表里的字也算长度 1）；没有返回 0 */
    int longest(String s, int i) {
      for (int len = Math.min(maxLen, s.length() - i); len >= 1; len--) {
        if (phrases.containsKey(s.substring(i, i + len))) return len;
      }
      return 0;
    }

    String replace(String s) {
      StringBuilder out = new StringBuilder(s.length());
      int i = 0;
      while (i < s.length()) {
        int len = 0;
        for (int l = Math.min(maxLen, s.length() - i); l >= 2; l--) {
          if (phrases.containsKey(s.substring(i, i + l))) {
            len = l;
            break;
          }
        }
        if (len > 0) {
          out.append(phrases.get(s.substring(i, i + len)));
          i += len;
          continue;
        }
        char c = s.charAt(i);
        String v = chars.get(c);
        if (v == null) v = phrases.get(String.valueOf(c));
        out.append(v != null ? v : String.valueOf(c));
        i++;
      }
      return out.toString();
    }

    /**
     * @param plain true：自定义词表（简体<TAB>繁体，单字也当短语）；false：OpenCC 生成的表（C 单字 / P 短语<TAB>…）
     */
    static Table load(String path, boolean plain) {
      Map<Character, String> chars = new HashMap<>();
      Map<String, String> phrases = new HashMap<>();
      int max = 1;
      try (InputStream in = Hant.class.getResourceAsStream(path)) {
        if (in == null) throw new IllegalStateException("缺少 " + path);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
          if (line.isBlank() || line.startsWith("#")) continue;
          String[] p = line.split("\t");
          if (plain) {
            if (p.length < 2 || p[0].isEmpty()) continue;
            phrases.put(p[0], p[1]);
            max = Math.max(max, p[0].length());
          } else {
            if (p.length != 3) continue;
            if ("C".equals(p[0]) && p[1].length() == 1) chars.put(p[1].charAt(0), p[2]);
            else if ("P".equals(p[0])) {
              phrases.put(p[1], p[2]);
              max = Math.max(max, p[1].length());
            }
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return new Table(chars, phrases, max);
    }
  }
}
