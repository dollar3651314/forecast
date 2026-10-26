package com.forecast.app.content.draft;

import com.forecast.app.content.check.NumberCheck;
import com.forecast.app.content.check.Words;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 草稿的检查（forecast S15，自动化需求第 4.3 节写作红线、AC-107、AC-108）：生成后、每次保存、发布前都跑一遍。
 * <ul>
 *   <li>数字核对：标题和正文里每个数字都要能在"引用的数据"里找到（{@link NumberCheck}）；</li>
 *   <li>禁用的说法（F-12、F-15）、倾向（EQ-8）：词表 {@link Words}；</li>
 *   <li>提到 Polymarket 时要有"非 Polymarket 官方"的说明（F-11）；</li>
 *   <li>长度上限（X 的帖子按平台限制）；标题、正文不能为空。</li>
 * </ul>
 * 任何一项不通过，这条草稿就不能发布。禁用词、倾向词在人工保存时直接拒绝保存（{@code CONTENT_BANNED_WORDS}）。
 */
@Component
public class DraftChecks {

  /**
   * 检查结果（存进 {@code content_draft.checks}，后台按它标红）。
   *
   * @param numbers 对不上的数字（field：title / body）
   * @param maxLength 正文长度上限，actualLength 实际长度
   */
  public record Result(List<Number> numbers, List<String> banned, List<String> tendency, boolean polymarketNoteMissing, int maxLength, int actualLength,
      List<String> missing) {

    public boolean ok() {
      return numbers.isEmpty() && banned.isEmpty() && tendency.isEmpty() && !polymarketNoteMissing && actualLength <= maxLength && missing.isEmpty();
    }

    /** 禁用词或倾向词（这种不能保存，起草时重新生成一次） */
    public boolean wordProblem() {
      return !banned.isEmpty() || !tendency.isEmpty();
    }

    /** 给人看的一句话 */
    public String summary() {
      List<String> s = new ArrayList<>();
      if (!missing.isEmpty()) s.add("缺少" + String.join("、", missing));
      if (!numbers.isEmpty()) s.add("有 " + numbers.size() + " 个数字在引用的数据里找不到：" + String.join("、", numbers.stream().map(Number::text).toList()));
      if (!banned.isEmpty()) s.add("有禁用的说法：" + String.join("、", banned));
      if (!tendency.isEmpty()) s.add("写了倾向：" + String.join("、", tendency));
      if (polymarketNoteMissing) s.add("提到 Polymarket 但没有写\"非 Polymarket 官方\"");
      if (actualLength > maxLength) s.add("正文 " + actualLength + " 字，超过上限 " + maxLength + " 字");
      return String.join("；", s);
    }
  }

  public record Number(String field, String text, int start, int end) {}

  private final Words words;

  DraftChecks(Words words) {
    this.words = words;
  }

  public Result check(String kind, String lang, String title, String body, List<Ref> refs) {
    List<String> texts = new ArrayList<>();
    for (Ref r : refs) {
      texts.add(r.value());
      texts.add(r.label());
      texts.add(r.labelEn());
      texts.add(r.at());
    }
    Set<BigDecimal> known = NumberCheck.numbersIn(texts);
    List<Number> nums = new ArrayList<>();
    for (NumberCheck.Hit h : NumberCheck.check(title, known)) nums.add(new Number("title", h.text(), h.start(), h.end()));
    for (NumberCheck.Hit h : NumberCheck.check(body, known)) nums.add(new Number("body", h.text(), h.start(), h.end()));
    String all = (title == null ? "" : title) + "\n" + (body == null ? "" : body);
    List<String> banned = words.find(all, Words.BANNED);
    List<String> tendency = words.find(all, Words.TENDENCY);
    boolean en = "en".equals(lang);
    List<String> missing = new ArrayList<>();
    if (title == null || title.isBlank()) missing.add("标题");
    if (body == null || body.isBlank()) missing.add("正文");
    return new Result(nums, banned, tendency, polymarketNoteMissing(all), Prompts.maxLength(kind, en), body == null ? 0 : body.codePointCount(0, body.length()),
        missing);
  }

  static boolean polymarketNoteMissing(String text) {
    String l = text.toLowerCase(Locale.ROOT);
    if (!l.contains("polymarket")) return false;
    return !(text.contains("非 Polymarket 官方") || text.contains("非Polymarket官方") || l.contains("not affiliated with polymarket")
        || l.contains("unofficial"));
  }
}
