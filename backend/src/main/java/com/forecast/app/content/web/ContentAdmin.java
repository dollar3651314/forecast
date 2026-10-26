package com.forecast.app.content.web;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.content.ai.GeminiClient;
import com.forecast.app.content.api.ContentAdminApi;
import com.forecast.app.content.check.Words;
import com.forecast.app.content.core.ContentLog;
import com.forecast.app.content.core.ContentMapper;
import com.forecast.app.content.core.ContentProperties;
import com.forecast.app.content.core.ContentRows;
import com.forecast.app.content.core.Settings;
import com.forecast.app.content.core.Workers;
import com.forecast.app.content.draft.DraftService;
import com.forecast.app.content.event.CandidateService;
import com.forecast.app.content.event.EconService;
import com.forecast.app.content.event.TemplateService;
import com.forecast.app.content.page.PageService;
import com.forecast.app.content.tool.ToolService;
import com.forecast.app.resolve.api.MarketDataApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 后台"内容中心"的实现（forecast S15，{@link ContentAdminApi}）：把草稿箱、事件候选、模板、页面、工具、日志、用量和设置接到一起。
 * 权限点、高危确认、审计在 admin 模块（{@code admin/ops/ContentOps}）。
 */
@Service
class ContentAdmin implements ContentAdminApi {

  private final DraftService drafts;
  private final EconService econ;
  private final CandidateService candidates;
  private final TemplateService templates;
  private final PageService pages;
  private final ToolService tools;
  private final ContentMapper mapper;
  private final Settings settings;
  private final Words words;
  private final GeminiClient gemini;
  private final ContentProperties props;
  private final MarketDataApi data;
  private final Workers workers;
  private final ContentLog contentLog;
  private final Clock clock;

  ContentAdmin(DraftService drafts, EconService econ, CandidateService candidates, TemplateService templates, PageService pages, ToolService tools,
      ContentMapper mapper, Settings settings, Words words, GeminiClient gemini, ContentProperties props, MarketDataApi data, Workers workers,
      ContentLog contentLog, Clock clock) {
    this.drafts = drafts;
    this.econ = econ;
    this.candidates = candidates;
    this.templates = templates;
    this.pages = pages;
    this.tools = tools;
    this.mapper = mapper;
    this.settings = settings;
    this.words = words;
    this.gemini = gemini;
    this.props = props;
    this.data = data;
    this.workers = workers;
    this.contentLog = contentLog;
    this.clock = clock;
  }

  // ---------- 草稿箱 ----------

  @Override
  public Page<Draft> drafts(String status, String kind, String lang, int page, int pageSize) {
    int size = Math.max(1, Math.min(100, pageSize));
    int p = Math.max(1, page);
    String st = blank(status) ? null : status.strip().toUpperCase(java.util.Locale.ROOT);
    String k = blank(kind) ? null : kind.strip().toUpperCase(java.util.Locale.ROOT);
    String l = blank(lang) ? null : lang.strip();
    List<Draft> items = mapper.drafts(st, k, l, (p - 1) * size, size).stream().map(drafts::view).toList();
    return new Page<>(items, mapper.countDrafts(st, k, l), p, size);
  }

  @Override
  public List<Draft> group(long draftNo) {
    ContentRows.Draft d = drafts.get(draftNo);
    return mapper.group(d.groupNo).stream().map(drafts::view).toList();
  }

  @Override
  public List<Draft> generate(GenerateInput in, long adminId) {
    return drafts.generate(in, adminId).stream().map(drafts::view).toList();
  }

  @Override
  public Draft edit(long draftNo, EditInput in, long adminId) {
    return drafts.view(drafts.edit(draftNo, in, adminId));
  }

  @Override
  public Draft publish(long draftNo, long adminId) {
    return drafts.view(drafts.publish(draftNo, adminId));
  }

  @Override
  public Draft reject(long draftNo, String reason, long adminId) {
    return drafts.view(drafts.reject(draftNo, reason, adminId));
  }

  @Override
  public Draft retry(long draftNo, long adminId) {
    return drafts.view(drafts.retry(draftNo, adminId));
  }

  @Override
  public Draft copied(long draftNo, long adminId) {
    return drafts.view(drafts.copied(draftNo, adminId));
  }

  // ---------- 用量和设置 ----------

  @Override
  public Status status() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    ContentRows.AiDay d = mapper.aiDay(today);
    int limit = settings.integer(Settings.AI_DAILY_LIMIT, 300);
    int cap = gemini.cap(GeminiClient.Purpose.CONTENT);
    long monthCalls = 0;
    long tin = 0;
    long tout = 0;
    for (ContentRows.AiDay x : mapper.aiDays(today.withDayOfMonth(1), today)) {
      monthCalls += x.used;
      tin += x.tokensIn;
      tout += x.tokensOut;
    }
    BigDecimal cost = monthCost(tin, tout);
    int alertLine = settings.integer(Settings.AI_COST_ALERT, 10);
    boolean costAlert = alertLine > 0 && cost.compareTo(BigDecimal.valueOf(alertLine)) >= 0;
    String state;
    if (!props.isEnabled() || !settings.flag(Settings.AI_DRAFTS)) state = "disabled";
    else if (!gemini.configured()) state = "unconfigured";
    else if (gemini.paused()) state = "paused";
    else if (d != null && d.used >= cap) state = "quota";
    else state = "ok";
    LocalDateTime monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
    Instant until = gemini.breaker().openUntil();
    return new Status(props.isEnabled(), gemini.configured(), state, props.getGemini().getModel(), d == null ? 0 : d.used, limit, cap,
        d == null ? 0 : d.contentUsed, d == null ? 0 : d.scoreUsed, d == null ? 0 : d.failures, monthCalls, cost.toPlainString(), String.valueOf(alertLine),
        costAlert, settings.flag(Settings.STOPPED), (int) mapper.countPagesOn(today), settings.integer(Settings.PAGES_DAILY, 5),
        mapper.countArticleGroups(monday), settings.integer(Settings.ARTICLES_WEEKLY, 2), data.polymarket(null).enabled(), workers.busy(),
        until == null ? null : until.toString(), settings.all());
  }

  /** 按付费档价格估算本月费用（美元，2 位小数；免费档实际 0） */
  BigDecimal monthCost(long tokensIn, long tokensOut) {
    BigDecimal m = BigDecimal.valueOf(1_000_000);
    return BigDecimal.valueOf(tokensIn).multiply(props.getGemini().getPriceIn()).divide(m, 6, RoundingMode.HALF_UP)
        .add(BigDecimal.valueOf(tokensOut).multiply(props.getGemini().getPriceOut()).divide(m, 6, RoundingMode.HALF_UP)).setScale(2, RoundingMode.HALF_UP);
  }

  @Override
  public Status updateSettings(Map<String, String> values, long adminId) {
    if (values == null || values.isEmpty()) throw new ValidationException(List.of(new ValidationException.Field("values", "required")));
    ValidationException.Collector errs = new ValidationException.Collector();
    for (Map.Entry<String, String> e : values.entrySet()) {
      int[] range = Settings.RANGES.get(e.getKey());
      if (range == null || Settings.STOPPED.equals(e.getKey())) {
        errs.add(e.getKey(), "unknown");
        continue;
      }
      try {
        int v = Integer.parseInt(Objects.toString(e.getValue(), "").strip());
        if (v < range[0] || v > range[1]) errs.add(e.getKey(), "range");
      } catch (NumberFormatException ex) {
        errs.add(e.getKey(), "invalid");
      }
    }
    errs.throwIfAny();
    List<String> changed = new ArrayList<>();
    for (Map.Entry<String, String> e : values.entrySet()) {
      String v = String.valueOf(Integer.parseInt(e.getValue().strip()));
      String old = settings.set(e.getKey(), v, adminId);
      if (!v.equals(old)) changed.add(e.getKey() + "：" + old + " → " + v);
    }
    contentLog.write("SETTING", null, null, null, "OK", changed.isEmpty() ? "没有变化" : String.join("；", changed), 0, adminId);
    return status();
  }

  @Override
  public Status setStopped(boolean stopped, long adminId) {
    settings.set(Settings.STOPPED, stopped ? "1" : "0", adminId);
    contentLog.write(stopped ? "STOP" : "RESUME", null, null, null, "OK", stopped ? "紧急停止：发布、复制全部暂停" : "恢复发布", 0, adminId);
    return status();
  }

  @Override
  public List<Word> words(String kind) {
    String k = blank(kind) ? null : kind.strip().toUpperCase(java.util.Locale.ROOT);
    return mapper.words(k).stream().map(ContentAdmin::wordView).toList();
  }

  @Override
  public Word addWord(String kind, String word, long adminId) {
    String k = kind == null ? "" : kind.strip().toUpperCase(java.util.Locale.ROOT);
    String w = word == null ? "" : word.strip();
    ValidationException.Collector errs = new ValidationException.Collector();
    if (!Words.KINDS.contains(k)) errs.add("kind", "invalid");
    if (w.isEmpty() || w.length() > 64) errs.add("word", "length");
    errs.throwIfAny();
    mapper.insertWord(k, w, adminId, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    contentLog.write("WORD", k, w, null, "OK", "加词", 0, adminId);
    return mapper.words(k).stream().filter(x -> x.word.equalsIgnoreCase(w)).findFirst().map(ContentAdmin::wordView).orElseThrow();
  }

  @Override
  public Word deleteWord(long id) {
    ContentRows.Word w = mapper.word(id);
    if (w == null) throw new BizException("NOT_FOUND", "词不存在");
    if (w.builtIn) throw new BizException("WORD_BUILT_IN", "内置的词不能删");
    mapper.deleteWord(id);
    contentLog.write("WORD", w.kind, w.word, null, "OK", "删词", 0, null);
    return wordView(w);
  }

  static Word wordView(ContentRows.Word w) {
    return new Word(String.valueOf(w.id), w.kind, w.word, w.builtIn);
  }

  // ---------- 事件候选 ----------

  @Override
  public List<Econ> econ() {
    return econ.list();
  }

  @Override
  public Econ addEcon(EconInput in, long adminId) {
    return econ.add(in, adminId);
  }

  @Override
  public Econ updateEcon(long id, EconInput in, long adminId) {
    return econ.update(id, in, adminId);
  }

  @Override
  public Econ cancelEcon(long id, long adminId) {
    return econ.cancel(id, adminId);
  }

  @Override
  public Econ draftEcon(long id, long adminId) {
    if (!props.isEnabled()) throw new BizException("CONTENT_DISABLED", "自动化程序已关闭");
    return econ.draftNow(id, adminId);
  }

  @Override
  public Candidates candidates() {
    return candidates.candidates();
  }

  // ---------- 模板 ----------

  @Override
  public List<Template> templates() {
    return templates.list();
  }

  @Override
  public Template updateTemplate(String code, TemplateInput in, long adminId) {
    return templates.update(code, in, adminId);
  }

  // ---------- 页面 ----------

  @Override
  public Pages pages() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    return new Pages(pages.pages(false, 200).stream().map(ContentAdmin::pageRow).toList(), (int) mapper.countPagesOn(today),
        settings.integer(Settings.PAGES_DAILY, 5));
  }

  @Override
  public PageRow refreshBtc(long adminId) {
    if (!props.isEnabled()) throw new BizException("CONTENT_DISABLED", "自动化程序已关闭");
    if (pages.refreshBtc().isEmpty()) {
      ContentRows.Page p = pages.page(PageService.BTC_SLUG);
      if (p == null) throw new BizException("PAGE_LIMIT", "今天新增页面已到上限，或 BTC 数据取不到");
      throw new BizException("DATA_UNAVAILABLE", "BTC 行情数据暂时取不到（币安、OKX 都不可用）");
    }
    return pageRow(pages.page(PageService.BTC_SLUG));
  }

  @Override
  public PageRow setPageVisible(String slug, boolean visible, long adminId) {
    pages.setVisible(slug, visible);
    contentLog.write("PAGE", null, slug, null, "OK", visible ? "显示" : "隐藏", 0, adminId);
    return pageRow(pages.page(slug));
  }

  static PageRow pageRow(ContentRows.Page p) {
    List<String> intro = new ArrayList<>();
    if (p.introZhCn != null) intro.add("zh");
    if (p.introEn != null) intro.add("en");
    List<String> body = new ArrayList<>();
    if (p.bodyZhCn != null) body.add("zh");
    if (p.bodyEn != null) body.add("en");
    return new PageRow(p.slug, p.kind, p.marketNo == null ? null : String.valueOf(p.marketNo), p.titleZhCn, p.titleEn, intro, body,
        p.dataAt == null ? null : p.dataAt.toInstant(ZoneOffset.UTC).toString(), p.createdDay.toString(), p.status, "/insights/" + p.slug);
  }

  // ---------- 工具 ----------

  @Override
  public ToolResult dataCard(String kind, String marketNo, String lang) {
    return tools.dataCard(kind, marketNo, lang);
  }

  @Override
  public ToolResult compare(String marketNo, String viewpoint, String polymarketSlug, String lang, long adminId) {
    return tools.compare(marketNo, viewpoint, polymarketSlug, lang, adminId);
  }

  // ---------- 日志 ----------

  @Override
  public Page<LogRow> logs(String from, String to, String action, int page, int pageSize) {
    int size = Math.max(1, Math.min(200, pageSize));
    int p = Math.max(1, page);
    LocalDateTime f = day(from, "from", false);
    LocalDateTime t = day(to, "to", true);
    String a = blank(action) ? null : action.strip().toUpperCase(java.util.Locale.ROOT);
    List<LogRow> items = mapper.logs(f, t, a, (p - 1) * size, size).stream().map(ContentAdmin::logRow).toList();
    return new Page<>(items, mapper.countLogs(f, t, a), p, size);
  }

  @Override
  public String logsCsv(String from, String to, String action) {
    LocalDateTime f = day(from, "from", false);
    LocalDateTime t = day(to, "to", true);
    String a = blank(action) ? null : action.strip().toUpperCase(java.util.Locale.ROOT);
    StringBuilder sb = new StringBuilder("﻿");
    row(sb, List.of("时间（UTC）", "动作", "种类", "编号", "语言", "结果", "说明", "Gemini 调用次数", "操作人"));
    for (ContentRows.Log l : mapper.logs(f, t, a, 0, 10_000)) {
      row(sb, List.of(l.createdAt.toInstant(ZoneOffset.UTC).toString(), l.action, Objects.toString(l.kind, ""), Objects.toString(l.ref, ""),
          Objects.toString(l.lang, ""), l.outcome, l.detail, String.valueOf(l.aiCalls), l.adminId == null ? "系统" : String.valueOf(l.adminId)));
    }
    return sb.toString();
  }

  private static void row(StringBuilder sb, List<String> cells) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) sb.append(',');
      String s = cells.get(i);
      if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
      if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) s = "\"" + s.replace("\"", "\"\"") + "\"";
      sb.append(s);
    }
    sb.append("\r\n");
  }

  static LogRow logRow(ContentRows.Log l) {
    return new LogRow(String.valueOf(l.id), l.action, l.kind, l.ref, l.lang, l.outcome, l.detail, l.aiCalls, l.adminId,
        l.createdAt.toInstant(ZoneOffset.UTC).toString());
  }

  private static LocalDateTime day(String v, String field, boolean end) {
    if (blank(v)) return null;
    try {
      LocalDate d = LocalDate.parse(v.strip());
      return end ? d.plusDays(1).atStartOfDay() : d.atStartOfDay();
    } catch (DateTimeParseException e) {
      throw new ValidationException(List.of(new ValidationException.Field(field, "invalid")));
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
