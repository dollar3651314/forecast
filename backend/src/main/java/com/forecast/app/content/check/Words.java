package com.forecast.app.content.check;

import com.forecast.app.content.core.ContentMapper;
import com.forecast.app.content.core.ContentRows;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 词表检查（forecast S15）：禁用的说法（F-12、F-15、市场策略第 9 节）、倾向（EQ-8：不建议押哪边）、敏感题（需求第 4.1 节"不能涉及"）。
 * 词表在 {@code content_word}（后台可加，内置的不能删）。中文按包含匹配；英文按整词匹配（"war" 不会命中 "Warriors"），都不分大小写。
 */
@Component
public class Words {

  public static final String BANNED = "BANNED";
  public static final String TENDENCY = "TENDENCY";
  public static final String SENSITIVE = "SENSITIVE";
  public static final List<String> KINDS = List.of(BANNED, TENDENCY, SENSITIVE);

  private final ContentMapper mapper;

  Words(ContentMapper mapper) {
    this.mapper = mapper;
  }

  public List<String> list(String kind) {
    List<String> out = new ArrayList<>();
    for (ContentRows.Word w : mapper.words(kind)) out.add(w.word);
    return out;
  }

  /** 文中命中的词（按词表顺序，不重复） */
  public List<String> find(String text, String kind) {
    return find(text, list(kind));
  }

  public static List<String> find(String text, List<String> words) {
    Set<String> out = new LinkedHashSet<>();
    if (text == null || text.isBlank()) return List.of();
    String lower = text.toLowerCase(Locale.ROOT);
    for (String w : words) {
      if (w == null || w.isBlank()) continue;
      String lw = w.toLowerCase(Locale.ROOT);
      if (isAscii(lw)) {
        if (Pattern.compile("(?<![a-z0-9])" + Pattern.quote(lw) + "(?![a-z0-9])").matcher(lower).find()) out.add(w);
      } else if (lower.contains(lw)) {
        out.add(w);
      }
    }
    return List.copyOf(out);
  }

  static boolean isAscii(String s) {
    for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 127) return false;
    return true;
  }
}
