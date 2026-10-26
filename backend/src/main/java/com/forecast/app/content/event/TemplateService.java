package com.forecast.app.content.event;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.content.api.ContentAdminApi;
import com.forecast.app.content.check.Words;
import com.forecast.app.content.core.ContentLog;
import com.forecast.app.content.core.ContentMapper;
import com.forecast.app.content.core.ContentRows;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 经济日历的题型模板（forecast S15，自动化需求第 3.2 节、EX-7）：FOMC（降息 / 维持 / 加息，以目标区间上限和会前比较，覆盖所有可能）、
 * 美国 CPI（高于 / 等于 / 低于 X%，以首次公布的 CPI-U 同比为准）。三种语言，后台能改（C6，content:edit），保存时检查禁用词、变量、选项数。
 */
@Service
public class TemplateService {

  /** 每个模板能用的变量 */
  public static final Map<String, List<String>> VARIABLES = Map.of("FOMC", List.of("periodEn", "periodZh", "releaseAt", "prevEn", "prevZh", "prevTw"), "CPI",
      List.of("periodEn", "periodZh", "releaseAt", "x"));
  static final Pattern VAR = Pattern.compile("\\{([A-Za-z]+)}");
  static final ObjectMapper JSON = new ObjectMapper();

  private final ContentMapper mapper;
  private final Words words;
  private final ContentLog contentLog;
  private final Clock clock;

  TemplateService(ContentMapper mapper, Words words, ContentLog contentLog, Clock clock) {
    this.mapper = mapper;
    this.words = words;
    this.contentLog = contentLog;
    this.clock = clock;
  }

  public List<ContentAdminApi.Template> list() {
    return mapper.templates().stream().map(TemplateService::view).toList();
  }

  public ContentRows.Template row(String code) {
    ContentRows.Template t = mapper.template(code);
    if (t == null) throw new BizException("NOT_FOUND", "模板不存在");
    return t;
  }

  static List<ContentAdminApi.TemplateOption> options(ContentRows.Template t) {
    return JSON.readValue(t.options, new TypeReference<List<ContentAdminApi.TemplateOption>>() {});
  }

  static ContentAdminApi.Template view(ContentRows.Template t) {
    return new ContentAdminApi.Template(t.code, new ContentAdminApi.Text(t.titleEn, t.titleZhCn, t.titleZhTw),
        new ContentAdminApi.Text(t.rulesEn, t.rulesZhCn, t.rulesZhTw), options(t), t.sourcePrimary, t.sourceBackup, t.version, t.updatedBy,
        t.updatedAt == null ? null : t.updatedAt.toInstant(ZoneOffset.UTC).toString(), VARIABLES.getOrDefault(t.code, List.of()));
  }

  public ContentAdminApi.Template update(String code, ContentAdminApi.TemplateInput in, long adminId) {
    ContentRows.Template old = row(code);
    ValidationException.Collector errs = new ValidationException.Collector();
    if (in == null) throw new ValidationException(List.of(new ValidationException.Field("title", "required")));
    Set<String> vars = Set.copyOf(VARIABLES.getOrDefault(code, List.of()));
    List<String> texts = new ArrayList<>();
    text(errs, "title", in.title(), vars, 300, texts);
    text(errs, "rules", in.rules(), vars, 3000, texts);
    List<ContentAdminApi.TemplateOption> opts = in.options() == null ? List.of() : in.options();
    if (opts.size() != options(old).size()) errs.add("options", "count");
    for (int i = 0; i < opts.size(); i++) {
      text(errs, "options." + i + ".label", opts.get(i).label(), vars, 100, texts);
      text(errs, "options." + i + ".winCondition", opts.get(i).winCondition(), vars, 300, texts);
    }
    if (blank(in.sourcePrimary()) || !in.sourcePrimary().strip().startsWith("https://")) errs.add("sourcePrimary", "https");
    if (blank(in.sourceBackup()) || !in.sourceBackup().strip().startsWith("https://")) errs.add("sourceBackup", "https");
    if (in.version() == null) errs.add("version", "required");
    errs.throwIfAny();
    List<String> bad = new ArrayList<>(Words.find(String.join("\n", texts), words.list(Words.BANNED)));
    bad.addAll(Words.find(String.join("\n", texts), words.list(Words.SENSITIVE)));
    if (!bad.isEmpty()) throw new BizException("CONTENT_BANNED_WORDS", "模板里有不能用的词：" + String.join("、", bad));
    ContentRows.Template t = new ContentRows.Template();
    t.code = code;
    t.titleEn = in.title().en().strip();
    t.titleZhCn = in.title().zhCN().strip();
    t.titleZhTw = in.title().zhTW().strip();
    t.rulesEn = in.rules().en().strip();
    t.rulesZhCn = in.rules().zhCN().strip();
    t.rulesZhTw = in.rules().zhTW().strip();
    t.options = JSON.writeValueAsString(opts);
    t.sourcePrimary = in.sourcePrimary().strip();
    t.sourceBackup = in.sourceBackup().strip();
    t.version = in.version();
    t.updatedBy = adminId;
    t.updatedAt = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    if (mapper.updateTemplate(t) != 1) throw new BizException("CONTENT_TEMPLATE_CHANGED", "模板刚被别人改过，请刷新");
    contentLog.write("TEMPLATE", code, code, null, "OK", "改模板（只影响之后生成的草稿）", 0, adminId);
    return view(row(code));
  }

  private static void text(ValidationException.Collector errs, String field, ContentAdminApi.Text t, Set<String> vars, int max, List<String> out) {
    if (t == null || blank(t.en()) || blank(t.zhCN()) || blank(t.zhTW())) {
      errs.add(field, "required");
      return;
    }
    for (String s : List.of(t.en(), t.zhCN(), t.zhTW())) {
      if (s.strip().length() > max) errs.add(field, "tooLong");
      Matcher m = VAR.matcher(s);
      while (m.find()) {
        if (!vars.contains(m.group(1))) {
          errs.add(field, "unknownVariable");
          break;
        }
      }
      out.add(s);
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  /** 套变量（不认识的变量原样留着；保存时已经检查过） */
  static String render(String s, Map<String, String> vars) {
    Matcher m = VAR.matcher(s);
    StringBuilder sb = new StringBuilder();
    while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(vars.getOrDefault(m.group(1), m.group(0))));
    m.appendTail(sb);
    return sb.toString();
  }
}
