package com.forecast.app.resolve.sports;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.resolve.api.SportsAdminApi;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 体育的规则文本模板（forecast S8；需求第 4.2 节"自动填队名、时间、规则模板"，三种语言 RQ-18）。每个玩法一个（{@code resolve_sports_template}，V16 有研发初稿）。
 * 变量见 {@link #VARIABLES}；时间一律 UTC（第 3.7 节第 1 条）。改模板只影响之后生成的草稿。
 */
@Component
class SportsTemplates {

  /**
   * {home}、{away} 队名（数据源的英文名；三种语言里都用它，运营可以在草稿里改）；{competition} 赛事名；{date} 开赛的 UTC 日期；{kickoff} 开赛 HH:mm；
   * {closeTime} 截止 yyyy-MM-dd HH:mm；{line} 分界线；{primarySource}、{backupSource} 来源；{finishWaitMinutes} 完赛后等几分钟
   */
  static final List<String> VARIABLES = List.of("home", "away", "competition", "date", "kickoff", "closeTime", "line", "primarySource", "backupSource",
      "finishWaitMinutes");
  private static final Set<String> KNOWN = Set.copyOf(VARIABLES);
  private static final Pattern VAR = Pattern.compile("\\{([A-Za-z]+)}");
  static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
  static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
  static final DateTimeFormatter DATE_HM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

  private final SportsMapper mapper;
  private final ObjectMapper json;
  private final Clock clock;

  SportsTemplates(SportsMapper mapper, ObjectMapper json, Clock clock) {
    this.mapper = mapper;
    this.json = json;
    this.clock = clock;
  }

  /** 模板里的一个选项 */
  record Option(String outcome, I18nText label, I18nText winCondition) {}

  record Loaded(SportsRows.Template row, List<Option> options) {}

  Loaded load(Play play) {
    SportsRows.Template t = mapper.template(play.name());
    if (t == null) throw new BizException("NOT_FOUND", "玩法 " + play.name() + " 的规则文本模板不存在");
    return new Loaded(t, options(t));
  }

  List<Option> options(SportsRows.Template t) {
    return json.readValue(t.options, new TypeReference<List<Option>>() {});
  }

  static Map<String, String> vars(String home, String away, String competition, Instant kickoff, Instant close, String line, String primary, String backup,
      long finishWaitMinutes) {
    Map<String, String> v = new LinkedHashMap<>();
    v.put("home", home);
    v.put("away", away);
    v.put("competition", competition == null ? "" : competition);
    v.put("date", DATE.format(kickoff));
    v.put("kickoff", HM.format(kickoff));
    v.put("closeTime", DATE_HM.format(close));
    v.put("line", line == null ? "" : line);
    v.put("primarySource", primary);
    v.put("backupSource", backup);
    v.put("finishWaitMinutes", String.valueOf(finishWaitMinutes));
    return v;
  }

  static String render(String text, Map<String, String> vars) {
    if (text == null) return "";
    Matcher m = VAR.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String v = vars.get(m.group(1));
      m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group() : v));
    }
    m.appendTail(out);
    return out.toString();
  }

  static I18nText render(I18nText t, Map<String, String> vars) {
    return new I18nText(render(t.en(), vars), render(t.zhCN(), vars), render(t.zhTW(), vars));
  }

  static List<String> unknown(String text) {
    List<String> out = new ArrayList<>();
    if (text == null) return out;
    Matcher m = VAR.matcher(text);
    while (m.find()) if (!KNOWN.contains(m.group(1))) out.add(m.group(1));
    return out;
  }

  // ---------------- 后台 ----------------

  List<SportsAdminApi.Template> list() {
    return mapper.templates().stream().map(this::view).toList();
  }

  SportsAdminApi.Template view(SportsRows.Template t) {
    List<SportsAdminApi.TemplateOption> opts = options(t).stream().map(o -> new SportsAdminApi.TemplateOption(o.outcome(), o.label(), o.winCondition())).toList();
    return new SportsAdminApi.Template(t.kind, t.name, new I18nText(t.titleEn, t.titleZhCn, t.titleZhTw), new I18nText(t.rulesEn, t.rulesZhCn, t.rulesZhTw),
        t.sourcePrimary, t.sourceBackup, opts, t.version, t.updatedBy, t.updatedAt == null ? null : t.updatedAt.toInstant(ZoneOffset.UTC).toString(),
        VARIABLES);
  }

  /** 改模板：一次列出全部错误（C56）；不认识的变量、选项不是这个玩法的结果、缺语言都报错 */
  @Transactional
  SportsAdminApi.Template update(String kind, SportsAdminApi.TemplateInput in, long adminId) {
    Play play = Play.of(kind);
    if (play == null) throw new BizException("NOT_FOUND", "没有这个玩法的模板：" + kind);
    SportsRows.Template old = mapper.template(play.name());
    if (old == null) throw new BizException("NOT_FOUND", "没有这个玩法的模板：" + kind);
    ValidationException.Collector errs = new ValidationException.Collector();
    SportsAdminApi.TemplateInput x = in == null ? new SportsAdminApi.TemplateInput(null, null, null, null, null, null) : in;
    String name = x.name() == null ? "" : x.name().strip();
    if (name.isEmpty()) errs.add("name", "required");
    else if (name.length() > 100) errs.add("name", "tooLong");
    I18nText title = x.title() == null ? I18nText.EMPTY : x.title().normalized();
    I18nText rules = x.rules() == null ? I18nText.EMPTY : x.rules().normalized();
    langs(errs, "title", title, 300);
    langs(errs, "rules", rules, 10_000);
    String sp = x.sourcePrimary() == null ? "" : x.sourcePrimary().strip();
    String sb = x.sourceBackup() == null ? "" : x.sourceBackup().strip();
    if (sp.isEmpty()) errs.add("sourcePrimary", "required");
    else if (sp.length() > 300) errs.add("sourcePrimary", "tooLong");
    if (sb.isEmpty()) errs.add("sourceBackup", "required");
    else if (sb.length() > 300) errs.add("sourceBackup", "tooLong");
    List<SportsAdminApi.TemplateOption> opts = x.options() == null ? List.of() : x.options();
    List<String> outcomes = opts.stream().map(o -> o == null || o.outcome() == null ? "" : o.outcome().strip().toUpperCase(java.util.Locale.ROOT)).toList();
    if (!outcomes.equals(play.outcomes)) errs.add("options", "mustBe:" + String.join(",", play.outcomes));
    List<Option> norm = new ArrayList<>();
    for (int i = 0; i < opts.size(); i++) {
      SportsAdminApi.TemplateOption o = opts.get(i);
      I18nText label = o == null || o.label() == null ? I18nText.EMPTY : o.label().normalized();
      I18nText win = o == null || o.winCondition() == null ? I18nText.EMPTY : o.winCondition().normalized();
      langs(errs, "options[" + i + "].label", label, 200);
      langs(errs, "options[" + i + "].winCondition", win, 1000);
      norm.add(new Option(outcomes.get(i), label, win));
    }
    List<String> texts = new ArrayList<>(List.of(title.en(), title.zhCN(), title.zhTW(), rules.en(), rules.zhCN(), rules.zhTW(), sp, sb));
    for (Option o : norm) {
      texts.addAll(List.of(o.label().en(), o.label().zhCN(), o.label().zhTW(), o.winCondition().en(), o.winCondition().zhCN(), o.winCondition().zhTW()));
    }
    List<String> bad = texts.stream().flatMap(t -> unknown(t).stream()).distinct().toList();
    if (!bad.isEmpty()) errs.add("variables", "unknown:" + String.join(",", bad));
    errs.throwIfAny();
    SportsRows.Template t = new SportsRows.Template();
    t.kind = play.name();
    t.name = name;
    t.titleEn = title.en();
    t.titleZhCn = title.zhCN();
    t.titleZhTw = title.zhTW();
    t.rulesEn = rules.en();
    t.rulesZhCn = rules.zhCN();
    t.rulesZhTw = rules.zhTW();
    t.sourcePrimary = sp;
    t.sourceBackup = sb;
    t.options = json.writeValueAsString(norm);
    t.updatedBy = adminId;
    t.updatedAt = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
    if (mapper.updateTemplate(t, old.version) != 1) throw new BizException("TEMPLATE_CHANGED", "模板刚被别人改过，请刷新");
    return view(mapper.template(play.name()));
  }

  private static void langs(ValidationException.Collector errs, String field, I18nText t, int max) {
    for (String[] p : new String[][] {{"en", t.en()}, {"zhCN", t.zhCN()}, {"zhTW", t.zhTW()}}) {
      if (p[1] == null || p[1].isBlank()) errs.add(field + "." + p[0], "required");
      else if (p[1].length() > max) errs.add(field + "." + p[0], "tooLong");
    }
  }
}
