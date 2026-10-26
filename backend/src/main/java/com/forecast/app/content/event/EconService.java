package com.forecast.app.content.event;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.content.api.ContentAdminApi;
import com.forecast.app.content.check.Words;
import com.forecast.app.content.core.ContentLog;
import com.forecast.app.content.core.ContentMapper;
import com.forecast.app.content.core.ContentRows;
import com.forecast.app.content.core.Settings;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.market.api.MarketDraftApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 经济日历（forecast S15；自动化需求第 3.1、3.2 节、EQ-6：只做 FOMC 和美国 CPI；AC-100）。
 * <ol>
 *   <li>运营录入日程（FOMC 声明 / CPI 公布的时间，CPI 还要上一个月公布的同比 X）；官方日程页的自动抓取第一期没有做（页面结构没核实），见开发记录。</li>
 *   <li>发布前 {@code econ.leadDays}（默认 7）天，定时任务按题型模板生成<b>市场草稿</b>（品类"经济"、平台裁决、截止 = 发布前 1 小时、三种语言、一手来源链接）；
 *       草稿要有 market:publish 的人在后台检查后上架（AC-102：自动化程序只能建草稿，{@link MarketDraftApi}）。</li>
 *   <li>敏感题过滤（品类白名单 + 敏感词）：命中的不建草稿，记"已过滤"日志（AC-103）。</li>
 * </ol>
 * 状态只用带版本号的条件更新（{@code SCHEDULED → DRAFTED / FILTERED / FAILED / EXPIRED / CANCELLED}），定时任务和"立即生成"同时到时只有一个生效；
 * 建草稿本身是 market 的一个事务，先建草稿再改状态：状态没改成功（别人抢先）时会多一个草稿——用唯一的条件更新挡住：先把状态从 SCHEDULED 认领成
 * DRAFTING 再建（见 {@link #draft}）。
 */
@Service
public class EconService {

  public static final List<String> KINDS = List.of("FOMC", "CPI");
  static final Pattern PERIOD = Pattern.compile("20\\d{2}-(0[1-9]|1[0-2])");
  static final Pattern CPI_VALUE = Pattern.compile("-?\\d{1,2}\\.\\d");
  static final Pattern RATE = Pattern.compile("\\d{1,2}\\.\\d{1,2}");
  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final ContentMapper mapper;
  private final TemplateService templates;
  private final MarketDraftApi drafts;
  private final Words words;
  private final Settings settings;
  private final ContentLog contentLog;
  private final Clock clock;

  EconService(ContentMapper mapper, TemplateService templates, MarketDraftApi drafts, Words words, Settings settings, ContentLog contentLog, Clock clock) {
    this.mapper = mapper;
    this.templates = templates;
    this.drafts = drafts;
    this.words = words;
    this.settings = settings;
    this.contentLog = contentLog;
    this.clock = clock;
  }

  // =====================================================================
  // 录入
  // =====================================================================

  public List<ContentAdminApi.Econ> list() {
    LocalDateTime from = ldt(clock.instant().minus(Duration.ofDays(31)));
    return mapper.econs(from, 200).stream().map(this::view).toList();
  }

  public ContentAdminApi.Econ add(ContentAdminApi.EconInput in, long adminId) {
    ContentRows.Econ e = parse(in);
    LocalDateTime now = ldt(clock.instant());
    e.createdBy = adminId;
    e.createdAt = now;
    e.updatedAt = now;
    try {
      mapper.insertEcon(e);
    } catch (org.springframework.dao.DuplicateKeyException ex) {
      throw new ValidationException(List.of(new ValidationException.Field("releaseAt", "duplicate")));
    }
    contentLog.write("ECON", e.kind, String.valueOf(e.id), null, "OK", "录入 " + e.kind + " " + e.period + "，发布时间 " + TIME.format(inst(e.releaseAt)), 0, adminId);
    return view(mapper.econ(e.id));
  }

  public ContentAdminApi.Econ update(long id, ContentAdminApi.EconInput in, long adminId) {
    ContentRows.Econ old = get(id);
    if (!List.of("SCHEDULED", "FAILED", "EXPIRED").contains(old.status)) throw new BizException("ECON_STATE_CHANGED", "已经生成草稿或取消了，不能再改");
    ContentRows.Econ e = parse(in);
    if (in.version() == null) throw new ValidationException(List.of(new ValidationException.Field("version", "required")));
    e.id = id;
    e.version = in.version();
    e.updatedAt = ldt(clock.instant());
    try {
      if (mapper.updateEcon(e) != 1) throw new BizException("ECON_STATE_CHANGED", "刚被别人改过，请刷新");
    } catch (org.springframework.dao.DuplicateKeyException ex) {
      throw new ValidationException(List.of(new ValidationException.Field("releaseAt", "duplicate")));
    }
    contentLog.write("ECON", e.kind, String.valueOf(id), null, "OK", "修改 " + e.kind + " " + e.period, 0, adminId);
    return view(get(id));
  }

  public ContentAdminApi.Econ cancel(long id, long adminId) {
    ContentRows.Econ e = get(id);
    if (!List.of("SCHEDULED", "FAILED", "EXPIRED", "FILTERED").contains(e.status)) throw new BizException("ECON_STATE_CHANGED", "已经生成草稿，删草稿请到市场管理");
    if (mapper.casEcon(id, e.version, "CANCELLED", null, "人工取消", ldt(clock.instant())) != 1) throw new BizException("ECON_STATE_CHANGED", "刚被别人改过，请刷新");
    contentLog.write("ECON", e.kind, String.valueOf(id), null, "OK", "取消", 0, adminId);
    return view(get(id));
  }

  ContentRows.Econ get(long id) {
    ContentRows.Econ e = mapper.econ(id);
    if (e == null) throw new BizException("NOT_FOUND", "经济日历里没有这一条");
    return e;
  }

  private ContentRows.Econ parse(ContentAdminApi.EconInput in) {
    ValidationException.Collector errs = new ValidationException.Collector();
    if (in == null) throw new ValidationException(List.of(new ValidationException.Field("kind", "required")));
    String kind = in.kind() == null ? "" : in.kind().strip().toUpperCase(Locale.ROOT);
    if (!KINDS.contains(kind)) errs.add("kind", "invalid");
    String period = in.period() == null ? "" : in.period().strip();
    if (!PERIOD.matcher(period).matches()) errs.add("period", "invalid");
    Instant release = null;
    try {
      release = in.releaseAt() == null ? null : Instant.parse(in.releaseAt().strip());
    } catch (DateTimeParseException e) {
      // 下面报
    }
    if (release == null) errs.add("releaseAt", "invalid");
    else if (!release.minus(Duration.ofHours(1)).isAfter(clock.instant().plus(Duration.ofMinutes(10)))) errs.add("releaseAt", "tooSoon");
    String prev = in.prevValue() == null || in.prevValue().isBlank() ? null : in.prevValue().strip();
    if ("CPI".equals(kind) && (prev == null || !CPI_VALUE.matcher(prev).matches())) errs.add("prevValue", "cpiOneDecimal");
    if ("FOMC".equals(kind) && prev != null && !RATE.matcher(prev).matches()) errs.add("prevValue", "rate");
    String url = in.sourceUrl() == null || in.sourceUrl().isBlank() ? null : in.sourceUrl().strip();
    if (url != null && (!url.startsWith("https://") || url.length() > 300)) errs.add("sourceUrl", "https");
    errs.throwIfAny();
    ContentRows.Econ e = new ContentRows.Econ();
    e.kind = kind;
    e.period = period;
    e.releaseAt = ldt(release);
    e.prevValue = prev;
    e.sourceUrl = url;
    return e;
  }

  // =====================================================================
  // 生成草稿
  // =====================================================================

  /** 定时任务：到了"发布前 N 天"的生成草稿；返回生成了几个。认领后中断（进程退出）超过 10 分钟的退回 FAILED，可以人工"立即生成" */
  public int draftDue() {
    LocalDateTime now = ldt(clock.instant());
    mapper.failStuckEcon(now.minusMinutes(10), now);
    int lead = settings.integer(Settings.ECON_LEAD_DAYS, 7);
    int n = 0;
    for (ContentRows.Econ e : mapper.econDue(ldt(clock.instant().plus(Duration.ofDays(lead))), 50)) {
      if ("DRAFTED".equals(draft(e, null))) n++;
    }
    return n;
  }

  /** 后台"立即生成"（不等发布前 N 天） */
  public ContentAdminApi.Econ draftNow(long id, long adminId) {
    ContentRows.Econ e = get(id);
    if (!List.of("SCHEDULED", "FAILED").contains(e.status)) throw new BizException("ECON_STATE_CHANGED", "这一条已经生成过或取消了");
    draft(e, adminId);
    return view(get(id));
  }

  /**
   * 生成一个：先把状态认领成 DRAFTING（条件更新，抢不到就返回），再检查、建草稿、改成结果。建草稿失败（字段错误）→ FAILED（附原因）。
   *
   * @return 结果状态
   */
  String draft(ContentRows.Econ e, Long adminId) {
    LocalDateTime now = ldt(clock.instant());
    if (mapper.casEcon(e.id, e.version, "DRAFTING", null, null, now) != 1) return "BUSY";
    int v = e.version + 1;
    Instant release = inst(e.releaseAt);
    if (!release.minus(Duration.ofHours(1)).isAfter(clock.instant())) {
      mapper.casEcon(e.id, v, "EXPIRED", null, "已过截止时间（发布前 1 小时），来不及生成", now);
      contentLog.write("ECON_DRAFT", e.kind, String.valueOf(e.id), null, "SKIPPED", "已过截止时间，没有生成", 0, adminId);
      return "EXPIRED";
    }
    MarketAdminApi.DraftInput in = input(e);
    // 敏感题过滤：品类白名单（经济：FOMC、CPI，EQ-6）+ 敏感词（需求第 4.1 节）
    List<String> hit = Words.find(in.title().zhCN() + "\n" + in.title().en() + "\n" + in.rules().zhCN() + "\n" + in.rules().en(), words.list(Words.SENSITIVE));
    if (!hit.isEmpty()) {
      mapper.insertFilterIgnore("ECON", String.valueOf(e.id), in.title().zhCN(), "KEYWORD", String.join("、", hit), now);
      mapper.casEcon(e.id, v, "FILTERED", null, "命中敏感词：" + String.join("、", hit), now);
      contentLog.write("FILTER", e.kind, String.valueOf(e.id), null, "BLOCKED", "命中敏感词：" + String.join("、", hit), 0, adminId);
      return "FILTERED";
    }
    try {
      long no = drafts.createDraft(in);
      mapper.casEcon(e.id, v, "DRAFTED", no, null, ldt(clock.instant()));
      contentLog.write("ECON_DRAFT", e.kind, String.valueOf(no), null, "OK", "按模板生成市场草稿（" + e.kind + " " + e.period + "），等有上架权限的人检查上架", 0,
          adminId);
      return "DRAFTED";
    } catch (ValidationException ex) {
      String why = "字段不对：" + ex.fields().stream().map(f -> f.field() + " " + f.code()).toList();
      mapper.casEcon(e.id, v, "FAILED", null, why, ldt(clock.instant()));
      contentLog.write("ECON_DRAFT", e.kind, String.valueOf(e.id), null, "FAILED", why, 0, adminId);
      return "FAILED";
    } catch (RuntimeException ex) {
      mapper.casEcon(e.id, v, "FAILED", null, "生成失败：" + ex.getMessage(), ldt(clock.instant()));
      contentLog.write("ECON_DRAFT", e.kind, String.valueOf(e.id), null, "FAILED", String.valueOf(ex.getMessage()), 0, adminId);
      return "FAILED";
    }
  }

  /** 按模板拼市场草稿（三种语言；平台裁决；截止 = 发布前 1 小时；事件结束 = 发布后 1 小时，到时进入待裁决） */
  MarketAdminApi.DraftInput input(ContentRows.Econ e) {
    ContentRows.Template t = templates.row(e.kind);
    Map<String, String> v = vars(e);
    List<MarketAdminApi.OptionInput> opts = new ArrayList<>();
    for (ContentAdminApi.TemplateOption o : TemplateService.options(t)) {
      opts.add(new MarketAdminApi.OptionInput(text(o.label(), v), text(o.winCondition(), v)));
    }
    Instant release = inst(e.releaseAt);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MINUTES);
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("econKind", e.kind);
    ref.put("period", e.period);
    ref.put("econEventId", e.id);
    if (e.prevValue != null) ref.put("prevValue", e.prevValue);
    return new MarketAdminApi.DraftInput("MACRO", new I18nText(TemplateService.render(t.titleEn, v), TemplateService.render(t.titleZhCn, v),
        TemplateService.render(t.titleZhTw, v)), new I18nText(TemplateService.render(t.rulesEn, v), TemplateService.render(t.rulesZhCn, v),
            TemplateService.render(t.rulesZhTw, v)), "PLATFORM", t.sourcePrimary, t.sourceBackup, ref, now.toString(), release.minus(Duration.ofHours(1)).toString(),
        release.toString(), release.plus(Duration.ofHours(1)).toString(), null, null, null, opts, false);
  }

  private static I18nText text(ContentAdminApi.Text t, Map<String, String> v) {
    return new I18nText(TemplateService.render(t.en(), v), TemplateService.render(t.zhCN(), v), TemplateService.render(t.zhTW(), v));
  }

  static Map<String, String> vars(ContentRows.Econ e) {
    YearMonth ym = YearMonth.parse(e.period);
    Map<String, String> v = new LinkedHashMap<>();
    v.put("periodEn", ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear());
    v.put("periodZh", ym.getYear() + " 年 " + ym.getMonthValue() + " 月");
    v.put("releaseAt", TIME.format(inst(e.releaseAt)));
    String x = e.prevValue == null ? "" : new BigDecimal(e.prevValue).toPlainString();
    v.put("x", x);
    v.put("prevEn", e.prevValue == null ? "" : " (" + x + "%)");
    v.put("prevZh", e.prevValue == null ? "" : "（" + x + "%）");
    v.put("prevTw", e.prevValue == null ? "" : "（" + x + "%）");
    return v;
  }

  ContentAdminApi.Econ view(ContentRows.Econ e) {
    Instant release = inst(e.releaseAt);
    String marketStatus = e.marketNo == null ? null : drafts.status(e.marketNo).orElse(null);
    int lead = settings.integer(Settings.ECON_LEAD_DAYS, 7);
    return new ContentAdminApi.Econ(String.valueOf(e.id), e.kind, e.period, release.toString(), e.prevValue, e.sourceUrl, e.status,
        e.marketNo == null ? null : String.valueOf(e.marketNo), marketStatus, e.note, release.minus(Duration.ofDays(lead)).toString(),
        release.minus(Duration.ofHours(1)).toString(), e.version);
  }

  static Instant inst(LocalDateTime t) {
    return t.toInstant(ZoneOffset.UTC);
  }

  static LocalDateTime ldt(Instant i) {
    return LocalDateTime.ofInstant(i.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }
}
