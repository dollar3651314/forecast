package com.forecast.app.market.autogen;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.id.Snowflake;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.market.api.MarketAutogenAdminApi;
import com.forecast.app.market.api.MarketCategory;
import com.forecast.app.market.core.MarketService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 加密市场自动生成（forecast S6，需求说明书第 4.2 节、AC-28；MQ-1 节目化；技术方案/v1-架构方案.md 第 3.3 节 market_autogen、第 5.2 节 CryptoAutogenJob）。
 * <ul>
 *   <li>后台：自动生成模板和规则文本模板的增改、启用（market:publish 检查一次）、关闭（{@link MarketAutogenAdminApi}）。</li>
 *   <li>生成任务 {@link #generateDue()}：每个启用的模板算出现在该存在的窗口（{@link AutogenWindows}），没生成的逐个生成并<b>直接上架</b>
 *       （{@link MarketService#createGenerated}），每个窗口一个事务；同一窗口只生成一个（唯一键 (autogen_id, event_start_at)，多实例也不会重复）。</li>
 *   <li>每个窗口的事务先对模板行加共享锁：关闭（排他锁）提交以后不会再生成新的；已生成的照常（第 4.2 节）。</li>
 * </ul>
 */
@Service
public class AutogenService implements MarketAutogenAdminApi {

  private static final Logger log = LoggerFactory.getLogger(AutogenService.class);
  static final String KIND_CRYPTO = "CRYPTO_UPDOWN";
  static final Set<String> OUTCOMES = Set.of("UP", "DOWN");
  private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
  /** 后台预览几个窗口 */
  static final int PREVIEW = 4;
  /** 规则文本的长度上限（和市场相同） */
  static final int RULES_MAX = 10_000;

  private final AutogenMapper mapper;
  private final MarketService markets;
  private final SystemConfig config;
  private final Snowflake ids;
  private final Clock clock;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  AutogenService(AutogenMapper mapper, MarketService markets, SystemConfig config, Snowflake ids, Clock clock, ObjectMapper json, PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.markets = markets;
    this.config = config;
    this.ids = ids;
    this.clock = clock;
    this.json = json;
    this.tx = new TransactionTemplate(tm);
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  // =====================================================================
  // 生成任务
  // =====================================================================

  /**
   * 跑一轮：每个启用的模板，把现在该存在、还没生成的窗口生成并上架。按"条件已满足"计算，停机恢复后第一轮补上还能下注的窗口（截止已过的不补）。
   *
   * @return 这一轮生成了几个市场
   */
  public int generateDue() {
    int n = 0;
    for (AutogenRows.Autogen a : mapper.enabled()) {
      try {
        n += generate(a);
      } catch (RuntimeException e) {
        log.error("自动生成模板 {}（{} {}）这一轮失败，下一轮重试", a.publicNo, a.symbol, a.period, e);
      }
    }
    return n;
  }

  private int generate(AutogenRows.Autogen a) {
    AutogenRows.Template t = mapper.template(a.templateId);
    if (t == null) throw new IllegalStateException("规则文本模板不存在：" + a.templateId);
    // 启用时检查的是哪个版本的规则文本：后来改过（正常情况下启用中改不了）就不生成，等重新检查
    if (a.checkedTemplateVersion == null || a.checkedTemplateVersion != t.version) {
      log.error("自动生成模板 {} 启用时检查的规则文本版本 {} 和现在的 {} 不同，不生成；请关闭后重新检查启用", a.publicNo, a.checkedTemplateVersion, t.version);
      return 0;
    }
    Instant now = Instant.now(clock);
    List<AutogenWindows.Window> due = AutogenWindows.dueSlots(a.mode, period(a.period), slots(a.slots), a.rollingCount, Duration.ofMinutes(a.generateAheadMin),
        openLead(a.period), closeLead(), now);
    if (due.isEmpty()) return 0;
    Map<Instant, Long> existing = markets.generatedOf(a.id, due.get(0).start(), due.get(due.size() - 1).start());
    int n = 0;
    for (AutogenWindows.Window w : due) {
      if (existing.containsKey(w.start())) continue;
      try {
        Long no = tx.execute(st -> {
          AutogenRows.Autogen locked = mapper.lockForShare(a.id);
          if (locked == null || !locked.enabled || locked.version != a.version) return null; // 刚被关闭或改过：不再生成
          return markets.createGenerated(generated(locked, t, w));
        });
        if (no != null) {
          n++;
          log.info("自动生成：模板 {}（{} {}）窗口 {} → 市场 {}", a.publicNo, a.symbol, a.period, w.start(), no);
        }
      } catch (DuplicateKeyException e) {
        log.debug("模板 {} 窗口 {} 已经由别的实例生成", a.publicNo, w.start());
      }
    }
    return n;
  }

  /** 按模板和窗口渲染出一个市场 */
  private MarketService.Generated generated(AutogenRows.Autogen a, AutogenRows.Template t, AutogenWindows.Window w) {
    Map<String, String> vars = AutogenRender.vars(a.symbol, w.start(), w.end(), closeLead().toSeconds());
    List<TemplateOption> opts = options(t);
    List<MarketAdminApi.OptionInput> in = new ArrayList<>();
    Map<String, Object> seq = new LinkedHashMap<>();
    for (int i = 0; i < opts.size(); i++) {
      TemplateOption o = opts.get(i);
      in.add(new MarketAdminApi.OptionInput(AutogenRender.render(o.label(), vars), AutogenRender.render(o.winCondition(), vars)));
      seq.put(o.outcome(), i + 1);
    }
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("kind", KIND_CRYPTO);
    ref.put("symbol", a.symbol);
    ref.put("windowStart", w.start().toString());
    ref.put("windowEnd", w.end().toString());
    ref.put("options", seq);
    ref.put("autogenNo", String.valueOf(a.publicNo));
    return new MarketService.Generated(a.id, category(a.period), AutogenRender.render(title(t), vars), AutogenRender.render(rules(t), vars),
        AutogenRender.render(t.sourcePrimary, vars), AutogenRender.render(t.sourceBackup, vars), ref, w.openAt(), w.closeAt(), w.start(), w.end(), a.userCap, in,
        // S13（MF-3）：按场次表生成的自动列入今日场次
        "SCHEDULE".equals(a.mode));
  }

  // =====================================================================
  // 后台
  // =====================================================================

  @Override
  @Transactional(readOnly = true)
  public List<Autogen> list() {
    return mapper.list().stream().map(this::view).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Autogen> detail(long autogenNo) {
    return Optional.ofNullable(mapper.byPublicNo(autogenNo)).map(this::view);
  }

  @Override
  @Transactional
  public Autogen create(AutogenInput in, long adminId) {
    AutogenRows.Autogen a = parse(in, null);
    if (mapper.bySymbolPeriod(a.symbol, a.period) != null) throw exists();
    LocalDateTime now = now();
    a.publicNo = ids.next();
    a.createdBy = adminId;
    a.updatedBy = adminId;
    a.createdAt = now;
    a.updatedAt = now;
    try {
      mapper.insert(a);
    } catch (DuplicateKeyException e) {
      throw exists();
    }
    return view(mapper.byPublicNo(a.publicNo));
  }

  @Override
  @Transactional
  public Autogen update(long autogenNo, AutogenInput in, long adminId) {
    AutogenRows.Autogen old = lock(autogenNo);
    if (old.enabled) throw enabledError();
    AutogenRows.Autogen a = parse(in, old);
    AutogenRows.Autogen other = mapper.bySymbolPeriod(a.symbol, a.period);
    if (other != null && !other.id.equals(old.id)) throw exists();
    a.id = old.id;
    a.updatedBy = adminId;
    a.updatedAt = now();
    try {
      if (mapper.update(a) != 1) throw enabledError();
    } catch (DuplicateKeyException e) {
      throw exists();
    }
    return view(mapper.byPublicNo(autogenNo));
  }

  @Override
  @Transactional
  public Autogen enable(long autogenNo, boolean checked, long adminId) {
    AutogenRows.Autogen a = lock(autogenNo);
    if (a.enabled) return view(a);
    AutogenRows.Template t = mapper.lockTemplate(a.templateId);
    ValidationException.Collector errs = new ValidationException.Collector();
    // 第 4.2 节：模板启用前由有 market:publish 的人按第 4.1 节清单检查一次（勾选"已检查以上各项"）
    if (!checked) errs.add("checked", "required");
    List<ValidationException.Field> missing = t == null ? List.of(new ValidationException.Field("templateNo", "invalid")) : missingOf(a, t);
    for (ValidationException.Field f : missing) errs.add("sample." + f.field(), f.code());
    errs.throwIfAny();
    if (mapper.enable(a.id, adminId, t.version, now()) != 1) throw new BizException("AUTOGEN_STATE_CHANGED", "模板状态刚被别人改过，请刷新");
    log.info("自动生成模板 {}（{} {} {}）已启用，检查人 {}", a.publicNo, a.symbol, a.period, a.mode, adminId);
    return view(mapper.byPublicNo(autogenNo));
  }

  @Override
  @Transactional
  public Autogen disable(long autogenNo, long adminId) {
    AutogenRows.Autogen a = lock(autogenNo);
    if (!a.enabled) return view(a);
    if (mapper.disable(a.id, adminId, now()) != 1) throw new BizException("AUTOGEN_STATE_CHANGED", "模板状态刚被别人改过，请刷新");
    log.info("自动生成模板 {}（{} {}）已关闭：不再生成新的，已生成的照常", a.publicNo, a.symbol, a.period);
    return view(mapper.byPublicNo(autogenNo));
  }

  @Override
  @Transactional(readOnly = true)
  public List<Template> templates() {
    List<AutogenRows.Autogen> all = mapper.list();
    return mapper.templates().stream().map(t -> templateView(t, all)).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Template> template(long templateNo) {
    AutogenRows.Template t = mapper.templateByPublicNo(templateNo);
    return t == null ? Optional.empty() : Optional.of(templateView(t, mapper.list()));
  }

  @Override
  @Transactional
  public Template updateTemplate(long templateNo, TemplateInput in, long adminId) {
    AutogenRows.Template found = mapper.templateByPublicNo(templateNo);
    if (found == null) throw new BizException("NOT_FOUND", "规则文本模板不存在");
    AutogenRows.Template t = mapper.lockTemplate(found.id);
    if (mapper.list().stream().anyMatch(a -> a.enabled && a.templateId == t.id)) {
      throw new BizException("TEMPLATE_IN_USE", "有启用中的自动生成在用这个模板：先关闭，改完重新检查启用");
    }
    ValidationException.Collector errs = new ValidationException.Collector();
    String name = clean(in == null ? null : in.name());
    if (name.isEmpty()) errs.add("name", "required");
    else if (name.length() > 100) errs.add("name", "tooLong");
    I18nText title = norm(in == null ? null : in.title());
    I18nText rules = norm(in == null ? null : in.rules());
    text(errs, "title", title, 250);
    text(errs, "rules", rules, RULES_MAX);
    String primary = clean(in == null ? null : in.sourcePrimary());
    String backup = clean(in == null ? null : in.sourceBackup());
    single(errs, "sourcePrimary", primary, 250);
    single(errs, "sourceBackup", backup, 250);
    List<TemplateOption> opts = in == null || in.options() == null ? List.of() : in.options();
    Set<String> outcomes = new HashSet<>();
    for (int i = 0; i < opts.size(); i++) {
      TemplateOption o = opts.get(i);
      String outcome = o == null || o.outcome() == null ? "" : o.outcome().strip().toUpperCase(Locale.ROOT);
      if (!OUTCOMES.contains(outcome)) errs.add("options[" + i + "].outcome", "invalid");
      else if (!outcomes.add(outcome)) errs.add("options[" + i + "].outcome", "duplicate");
      text(errs, "options[" + i + "].label", norm(o == null ? null : o.label()), 150);
      text(errs, "options[" + i + "].winCondition", norm(o == null ? null : o.winCondition()), 1000);
    }
    // 加密涨跌：正好两个选项"涨""跌"（结束价 ≥ 开始价判涨，需求 E9）
    if (opts.size() != 2 || !outcomes.containsAll(OUTCOMES)) errs.add("options", "upDown");
    errs.throwIfAny();
    t.name = name;
    t.titleEn = title.en();
    t.titleZhCn = title.zhCN();
    t.titleZhTw = title.zhTW();
    t.rulesEn = rules.en();
    t.rulesZhCn = rules.zhCN();
    t.rulesZhTw = rules.zhTW();
    t.sourcePrimary = primary;
    t.sourceBackup = backup;
    t.options = json.writeValueAsString(opts.stream()
        .map(o -> new TemplateOption(o.outcome().strip().toUpperCase(Locale.ROOT), o.label().normalized(), o.winCondition().normalized())).toList());
    t.updatedBy = adminId;
    t.updatedAt = now();
    mapper.updateTemplate(t);
    return templateView(mapper.template(t.id), mapper.list());
  }

  // =====================================================================
  // 输入校验
  // =====================================================================

  /** 新建、修改的输入（一次列出全部错误，C56）；old 不为空时是修改，没填的项保留原值不行——全部按新输入（整体替换） */
  private AutogenRows.Autogen parse(AutogenInput in, AutogenRows.Autogen old) {
    ValidationException.Collector errs = new ValidationException.Collector();
    if (in == null) in = new AutogenInput(null, null, null, null, null, null, null, null);
    String symbol = clean(in.symbol()).toUpperCase(Locale.ROOT);
    if (symbol.isEmpty()) errs.add("symbol", "required");
    else if (!AutogenRender.SYMBOLS.contains(symbol)) errs.add("symbol", "invalid");
    String period = clean(in.period()).toUpperCase(Locale.ROOT);
    if (period.isEmpty()) errs.add("period", "required");
    else if (!period.equals("15M") && !period.equals("1H")) errs.add("period", "invalid");
    String mode = clean(in.mode()).toUpperCase(Locale.ROOT);
    if (mode.isEmpty()) errs.add("mode", "required");
    else if (!mode.equals("SCHEDULE") && !mode.equals("ROLLING")) errs.add("mode", "invalid");
    List<String> slots = new ArrayList<>();
    List<String> rawSlots = in.slots() == null ? List.of() : in.slots();
    if (rawSlots.size() > 96) errs.add("slots", "tooMany");
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < rawSlots.size(); i++) {
      // S12c：输入宽松（1:00、1:0、1、全角冒号、两边空格都规范成 HH:mm），错误码说清楚错在哪（后台按"场次第 n 行：…"显示）。
      // 后台前端 utils/market.ts 的 slotProblems 是同一套规则，只是提前提示；以这里为准。
      SlotParse p = parseSlot(rawSlots.get(i));
      String field = "slots[" + i + "]";
      if (p.slot() == null) {
        errs.add(field, p.code());
        continue;
      }
      AutogenWindows.Slot sl = p.slot();
      LocalTime t = sl.time();
      // 窗口按周期对齐：1 小时是整点，15 分钟是 00 / 15 / 30 / 45
      if (period.equals("1H") && t.getMinute() != 0) errs.add(field, "hourOnly");
      else if (period.equals("15M") && t.getMinute() % 15 != 0) errs.add(field, "quarterOnly");
      // 同一个时刻只能写一项（不同星期在同一项里选）
      else if (!seen.add(SLOT.format(t))) errs.add(field, "duplicate");
      slots.add(format(sl));
    }
    if (mode.equals("SCHEDULE") && rawSlots.isEmpty()) errs.add("slots", "required");
    slots.sort(String::compareTo);
    int rolling = in.rollingCount() == null ? (period.equals("15M") ? 4 : 3) : in.rollingCount();
    if (rolling < 1 || rolling > 96) errs.add("rollingCount", "invalid");
    int ahead = in.generateAheadMinutes() == null ? 1440 : in.generateAheadMinutes();
    if (ahead < 0 || ahead > 10_080) errs.add("generateAheadMinutes", "invalid");
    AutogenRows.Template t = null;
    if (in.templateNo() == null || in.templateNo().isBlank()) {
      t = mapper.templates().stream().filter(x -> KIND_CRYPTO.equals(x.kind)).findFirst().orElse(null);
      if (t == null) errs.add("templateNo", "required");
    } else {
      try {
        t = mapper.templateByPublicNo(Long.parseLong(in.templateNo().strip()));
      } catch (NumberFormatException e) {
        t = null;
      }
      if (t == null || !KIND_CRYPTO.equals(t.kind)) errs.add("templateNo", "invalid");
    }
    BigDecimal cap = null;
    if (in.userCap() != null && !in.userCap().isBlank()) {
      try {
        cap = new BigDecimal(in.userCap().strip());
        BigDecimal def = period.isEmpty() || errs.has("period") ? null : config.decimal(category(period).userCapKey, null);
        if (cap.signum() <= 0 || cap.stripTrailingZeros().scale() > 2) errs.add("userCap", "invalid");
        else if (def != null && cap.compareTo(def) > 0) errs.add("userCap", "aboveDefault");
      } catch (NumberFormatException e) {
        errs.add("userCap", "invalid");
      }
    }
    errs.throwIfAny();
    AutogenRows.Autogen a = new AutogenRows.Autogen();
    a.symbol = symbol;
    a.period = period;
    a.mode = mode;
    a.slots = json.writeValueAsString(slots);
    a.rollingCount = rolling;
    a.generateAheadMin = ahead;
    a.templateId = t.id;
    a.userCap = cap;
    return a;
  }

  /**
   * 场次表的一项（S13 补上星期几，需求第 4.2 节）："HH:mm" = 每天；"HH:mm@1,6,7" = 只在这些星期（ISO：1 周一 … 7 周日，按 UTC 日期）。
   * 格式不对返回 null。
   */
  static AutogenWindows.Slot slot(String s) {
    return parseSlot(s).slot();
  }

  /** 场次一项的解析结果：slot 不为空 = 能用；否则 code 是字段错误码（required / timeFormat / outOfRange / invalidDays） */
  record SlotParse(AutogenWindows.Slot slot, String code) {}

  /**
   * 时刻部分：时 1～2 位，分可省（= 00）或 1～2 位；冒号可以是半角或全角，冒号两边、整项两边可以有空格（含全角空格、不换行空格）。
   * 空白字符集和后台前端 utils/market.ts 的 SLOT_WS 一致（不用 \\s / strip，两种语言对 Unicode 空白的理解不一样）。
   */
  private static final String SLOT_WS = "[ \\t\\r\\n\\u00A0\\u3000]*";
  private static final Pattern SLOT_TIME = Pattern.compile(SLOT_WS + "([0-9]{1,2})(?:" + SLOT_WS + "[:：]" + SLOT_WS + "([0-9]{1,2}))?" + SLOT_WS);
  private static final Pattern SLOT_DAYS = Pattern.compile(SLOT_WS + "[1-7](?:" + SLOT_WS + "," + SLOT_WS + "[1-7]){0,6}" + SLOT_WS);
  private static final Pattern SLOT_BLANK = Pattern.compile(SLOT_WS);

  /**
   * 场次表的一项，宽松读入（S12c 测试环境反馈：填 "1:00" 被拒）："13:00"、"1:00"、"1:0"、"1"、" 01:00 " 都是 01:00 / 13:00；
   * 可以带星期 "13:00@1,6,7"。时 0～23、分 0～59，超出是 outOfRange。保存时一律写成 format 的标准写法。
   */
  static SlotParse parseSlot(String s) {
    if (s == null || SLOT_BLANK.matcher(s).matches()) return new SlotParse(null, "required");
    int at = s.indexOf('@');
    Matcher m = SLOT_TIME.matcher(at < 0 ? s : s.substring(0, at));
    if (!m.matches()) return new SlotParse(null, "timeFormat");
    int h = Integer.parseInt(m.group(1));
    int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
    if (h > 23 || min > 59) return new SlotParse(null, "outOfRange");
    java.util.EnumSet<java.time.DayOfWeek> days = java.util.EnumSet.noneOf(java.time.DayOfWeek.class);
    if (at >= 0) {
      String d = s.substring(at + 1);
      if (!SLOT_DAYS.matcher(d).matches()) return new SlotParse(null, "invalidDays");
      for (char c : d.toCharArray()) {
        if (c >= '1' && c <= '7' && !days.add(java.time.DayOfWeek.of(c - '0'))) return new SlotParse(null, "invalidDays"); // 同一天写了两次
      }
      if (days.size() == 7) days.clear(); // 七天都选 = 每天
    }
    return new SlotParse(new AutogenWindows.Slot(LocalTime.of(h, min), days), null);
  }

  /** 场次表一项的标准写法（星期从小到大；每天的不带 @） */
  static String format(AutogenWindows.Slot sl) {
    String t = SLOT.format(sl.time());
    if (sl.days().isEmpty()) return t;
    return t + "@" + sl.days().stream().sorted().map(d -> String.valueOf(d.getValue())).collect(java.util.stream.Collectors.joining(","));
  }

  private static void text(ValidationException.Collector errs, String field, I18nText t, int max) {
    single(errs, field + ".en", t.en(), max);
    single(errs, field + ".zhCN", t.zhCN(), max);
    single(errs, field + ".zhTW", t.zhTW(), max);
  }

  private static void single(ValidationException.Collector errs, String field, String v, int max) {
    if (v == null || v.isBlank()) errs.add(field, "required");
    else if (v.length() > max) errs.add(field, "tooLong");
    else if (!AutogenRender.unknown(v).isEmpty()) errs.add(field, "unknownVariable");
  }

  // =====================================================================
  // 视图和小工具
  // =====================================================================

  private Autogen view(AutogenRows.Autogen a) {
    AutogenRows.Template t = mapper.template(a.templateId);
    Instant now = Instant.now(clock);
    List<AutogenWindows.Window> up = AutogenWindows.upcoming(a.mode, period(a.period), slots(a.slots), a.mode.equals("ROLLING") ? a.rollingCount : PREVIEW,
        openLead(a.period), closeLead(), now);
    Map<Instant, Long> gen = up.isEmpty() ? Map.of() : markets.generatedOf(a.id, up.get(0).start(), up.get(up.size() - 1).start());
    List<Window> windows = up.stream().map(w -> new Window(w.start().toString(), w.end().toString(), w.openAt().toString(), w.closeAt().toString(),
        gen.containsKey(w.start()), gen.containsKey(w.start()) ? String.valueOf(gen.get(w.start())) : null)).toList();
    Sample sample = null;
    List<ValidationException.Field> missing = List.of();
    if (t != null && !up.isEmpty()) {
      MarketService.Generated g = generated(a, t, up.get(0));
      sample = new Sample(g.title(), g.rules(), g.sourcePrimary(), g.sourceBackup(), sampleOptions(t, g));
      missing = markets.generatedMissing(g);
    }
    return new Autogen(String.valueOf(a.publicNo), a.symbol, a.period, category(a.period).name(), a.mode.toLowerCase(Locale.ROOT), slots(a.slots).stream()
        .map(AutogenService::format).toList(), a.rollingCount, a.generateAheadMin, t == null ? null : String.valueOf(t.publicNo), a.userCap == null ? null
        : a.userCap.stripTrailingZeros().toPlainString(), a.enabled, a.checkedBy, iso(a.checkedAt), a.checkedTemplateVersion, a.version, iso(a.createdAt),
        iso(a.updatedAt), windows, sample, missing);
  }

  private List<SampleOption> sampleOptions(AutogenRows.Template t, MarketService.Generated g) {
    List<TemplateOption> opts = options(t);
    List<SampleOption> out = new ArrayList<>();
    for (int i = 0; i < opts.size() && i < g.options().size(); i++) {
      out.add(new SampleOption(opts.get(i).outcome(), g.options().get(i).label(), g.options().get(i).winCondition()));
    }
    return out;
  }

  /** 按下一个窗口渲染出来的市场缺哪些必填项（启用前的检查） */
  private List<ValidationException.Field> missingOf(AutogenRows.Autogen a, AutogenRows.Template t) {
    List<AutogenWindows.Window> up = AutogenWindows.upcoming(a.mode, period(a.period), slots(a.slots), 1, openLead(a.period), closeLead(), Instant.now(clock));
    if (up.isEmpty()) return List.of(new ValidationException.Field("slots", "required"));
    return markets.generatedMissing(generated(a, t, up.get(0)));
  }

  private Template templateView(AutogenRows.Template t, List<AutogenRows.Autogen> all) {
    List<AutogenRows.Autogen> users = all.stream().filter(a -> a.templateId == t.id).toList();
    return new Template(String.valueOf(t.publicNo), t.kind, t.name, title(t), rules(t), t.sourcePrimary, t.sourceBackup, options(t), t.version,
        users.stream().map(a -> String.valueOf(a.publicNo)).toList(), users.stream().anyMatch(a -> a.enabled), AutogenRender.VARIABLES, iso(t.updatedAt));
  }

  private List<TemplateOption> options(AutogenRows.Template t) {
    if (t.options == null || t.options.isBlank()) return List.of();
    List<TemplateOption> raw = json.readValue(t.options, new TypeReference<List<TemplateOption>>() {});
    return raw.stream().map(o -> new TemplateOption(o.outcome(), norm(o.label()), norm(o.winCondition()))).toList();
  }

  private static I18nText title(AutogenRows.Template t) {
    return new I18nText(t.titleEn, t.titleZhCn, t.titleZhTw);
  }

  private static I18nText rules(AutogenRows.Template t) {
    return new I18nText(t.rulesEn, t.rulesZhCn, t.rulesZhTw);
  }

  private List<AutogenWindows.Slot> slots(String jsonSlots) {
    if (jsonSlots == null || jsonSlots.isBlank()) return List.of();
    List<String> raw = json.readValue(jsonSlots, new TypeReference<List<String>>() {});
    List<AutogenWindows.Slot> out = new ArrayList<>();
    for (String s : raw) {
      AutogenWindows.Slot t = slot(s);
      if (t != null) out.add(t);
    }
    return out;
  }

  static MarketCategory category(String period) {
    return "15M".equals(period) ? MarketCategory.CRYPTO_15M : MarketCategory.CRYPTO_1H;
  }

  static Duration period(String period) {
    return "15M".equals(period) ? Duration.ofMinutes(15) : Duration.ofHours(1);
  }

  /** 开放提前量（参数设置，RQ-13：15 分钟 60 分钟、1 小时 180 分钟） */
  private Duration openLead(String period) {
    return "15M".equals(period) ? Duration.ofMinutes(config.integer(ConfigKey.CRYPTO_OPEN_LEAD_15M, 60))
        : Duration.ofMinutes(config.integer(ConfigKey.CRYPTO_OPEN_LEAD_1H, 180));
  }

  /** 截止提前量（参数设置，第 3.7 节：窗口开始前 30 秒） */
  private Duration closeLead() {
    return Duration.ofSeconds(config.integer(ConfigKey.CRYPTO_CLOSE_LEAD_SECONDS, 30));
  }

  private AutogenRows.Autogen lock(long autogenNo) {
    AutogenRows.Autogen a = mapper.lockByPublicNo(autogenNo);
    if (a == null) throw new BizException("NOT_FOUND", "自动生成模板不存在");
    return a;
  }

  private static I18nText norm(I18nText t) {
    return (t == null ? I18nText.EMPTY : t).normalized();
  }

  private static String clean(String s) {
    return s == null ? "" : s.strip();
  }

  private static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }

  private static BizException exists() {
    return new BizException("AUTOGEN_EXISTS", "同一币种同一周期已经有一个自动生成模板，请修改它");
  }

  private static BizException enabledError() {
    return new BizException("AUTOGEN_ENABLED", "启用中的自动生成模板不能修改：先关闭，改完重新检查启用");
  }
}
