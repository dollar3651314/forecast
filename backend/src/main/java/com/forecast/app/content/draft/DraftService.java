package com.forecast.app.content.draft;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.id.Snowflake;
import com.forecast.app.content.ai.GeminiClient;
import com.forecast.app.content.api.ContentAdminApi;
import com.forecast.app.content.check.Words;
import com.forecast.app.content.core.ContentLog;
import com.forecast.app.content.core.ContentMapper;
import com.forecast.app.content.core.ContentProperties;
import com.forecast.app.content.core.ContentRows;
import com.forecast.app.content.core.Settings;
import com.forecast.app.content.core.Workers;
import com.forecast.app.content.page.BtcStats;
import com.forecast.app.content.page.PageService;
import com.forecast.app.market.api.MarketCategory;
import com.forecast.app.market.api.MarketPublicApi;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 草稿箱（forecast S15；自动化需求第 4 节、EQ-1、EQ-8、AC-106～AC-111）。
 * <ol>
 *   <li><b>生成</b>：先拼"引用的数据"（{@link DataRefs}，外部取数在事务外），一次生成中文、英文两条（状态 GENERATING），再交给自动化程序的线程池起草
 *       （{@link Workers}，不占接口线程）。每条：Gemini 起草 → 解析 JSON → 检查（{@link DraftChecks}）；不合格的重新起草一次；还不合格：禁用词 / 倾向 /
 *       格式不对 → 生成失败（附原因），只是数字对不上 / 太长 → 待审核但标红、不能发布（人改正以后能发布，AC-107）。</li>
 *   <li><b>审核</b>：编辑（禁用词不保存）、发布（检查通过、没过期、没有紧急停止）、驳回、重新起草、复制。<b>没有任何自动发布</b>（EQ-1）。</li>
 *   <li><b>发布到哪里</b>：文章、页面说明发布在网站（{@link PageService}）；赛前分析显示在焦点战页；X / Telegram 帖子、行情短文审核通过后只能复制、由人手动发；
 *       回复草稿只能复制（程序里没有把内容发到别人帖子下、私信、别人群里的接口，AC-110）。</li>
 *   <li><b>过期</b>：赛前分析到开赛、行情短文和帖子、回复 24 小时后不能发布（AC-111）。</li>
 * </ol>
 */
@Service
public class DraftService {

  public static final List<String> KINDS = List.of("PREVIEW", "BRIEF", "SEO_INTRO", "ARTICLE", "POST_X", "POST_TG", "REPLY");
  /** 只能复制、由人手动发的 */
  static final Set<String> COPY_ONLY = Set.of("BRIEF", "POST_X", "POST_TG", "REPLY");
  /** 审核以后能复制的（赛前分析也能贴到 X、Telegram） */
  static final Set<String> COPYABLE = Set.of("PREVIEW", "BRIEF", "POST_X", "POST_TG", "REPLY");
  static final Set<MarketCategory> SPORTS = Set.of(MarketCategory.FOOTBALL, MarketCategory.NBA);
  static final Set<MarketCategory> CRYPTO = Set.of(MarketCategory.CRYPTO_15M, MarketCategory.CRYPTO_1H);
  static final ObjectMapper JSON = new ObjectMapper();
  /** BTC 统计最多用多旧的（行情短文、BTC 页说明） */
  static final Duration BTC_MAX_AGE = Duration.ofHours(2);

  private final ContentMapper mapper;
  private final ContentProperties props;
  private final Settings settings;
  private final ContentLog contentLog;
  private final GeminiClient gemini;
  private final DataRefs dataRefs;
  private final DraftChecks checks;
  private final Words words;
  private final PageService pages;
  private final MarketPublicApi markets;
  private final Workers workers;
  private final Snowflake ids;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final String clientUrl;

  DraftService(ContentMapper mapper, ContentProperties props, Settings settings, ContentLog contentLog, GeminiClient gemini, DataRefs dataRefs,
      DraftChecks checks, Words words, PageService pages, MarketPublicApi markets, Workers workers, Snowflake ids, Clock clock, PlatformTransactionManager tm,
      @Value("${forecast.client-url:}") String clientUrl) {
    this.mapper = mapper;
    this.props = props;
    this.settings = settings;
    this.contentLog = contentLog;
    this.gemini = gemini;
    this.dataRefs = dataRefs;
    this.checks = checks;
    this.words = words;
    this.pages = pages;
    this.markets = markets;
    this.workers = workers;
    this.ids = ids;
    this.clock = clock;
    this.tx = new TransactionTemplate(tm);
    this.clientUrl = clientUrl == null ? "" : clientUrl.strip().replaceAll("/+$", "");
  }

  // =====================================================================
  // 生成
  // =====================================================================

  /** 要生成什么：种类 + 目标 + 引用的数据 + 过期时间 */
  record Plan(String kind, Long marketNo, String pageSlug, String topic, List<Ref> refs, Instant expiresAt) {}

  /** AI 起草现在能不能用；不能用时的原因（后台显示、日志） */
  public Optional<String> aiUnavailable() {
    if (!props.isEnabled()) return Optional.of("自动化程序已关闭（FC_CONTENT_ENABLED）");
    if (!settings.flag(Settings.AI_DRAFTS)) return Optional.of("AI 起草已关闭（内容中心 → 用量和设置）");
    if (!gemini.configured()) return Optional.of("Gemini 未配置（FC_GEMINI_API_KEY）");
    if (gemini.paused()) return Optional.of("Gemini 暂停中（连续失败或被限流）");
    if (!gemini.contentAvailable()) return Optional.of("今日额度已用完");
    return Optional.empty();
  }

  /** 后台手动生成 */
  public List<ContentRows.Draft> generate(ContentAdminApi.GenerateInput in, long adminId) {
    if (!props.isEnabled()) throw new BizException("CONTENT_DISABLED", "自动化程序已关闭");
    Plan plan = plan(in);
    Optional<String> no = aiUnavailable();
    if (no.isPresent()) {
      contentLog.write("GENERATE", plan.kind(), target(plan), null, "SKIPPED", no.get(), 0, adminId);
      throw new BizException("AI_UNAVAILABLE", no.get());
    }
    return start(plan, adminId);
  }

  /** 定时任务生成（每日草稿包）：AI 不能用、今天已经生成过的跳过；返回生成了几组 */
  public boolean generateSystem(String kind, long marketNo) {
    LocalDateTime since = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).atStartOfDay();
    if (mapper.countMarketDrafts(marketNo, kind, since) > 0) return false;
    if (aiUnavailable().isPresent()) return false;
    try {
      start(plan(new ContentAdminApi.GenerateInput(kind, String.valueOf(marketNo), null, null, null)), null);
      return true;
    } catch (BizException | ValidationException e) {
      contentLog.write("GENERATE", kind, String.valueOf(marketNo), null, "SKIPPED", e.getMessage(), 0, null);
      return false;
    }
  }

  /** 回复草稿（工具"大 V 对照图"用）：引用的数据由工具给（市场 + 观点 + 可选的 Polymarket） */
  public List<ContentRows.Draft> generateReply(long marketNo, String viewpoint, List<Ref> refs, long adminId) {
    Instant now = clock.instant();
    return start(new Plan("REPLY", marketNo, null, viewpoint, refs, now.plus(Duration.ofHours(24))), adminId);
  }

  Plan plan(ContentAdminApi.GenerateInput in) {
    String kind = in == null || in.kind() == null ? "" : in.kind().strip().toUpperCase(java.util.Locale.ROOT);
    if (!KINDS.contains(kind) || "REPLY".equals(kind)) throw new ValidationException(List.of(new ValidationException.Field("kind", "invalid")));
    Instant now = clock.instant();
    switch (kind) {
      case "PREVIEW", "BRIEF", "POST_X", "POST_TG" -> {
        MarketPublicApi.Snapshot s = market(in.marketNo());
        List<Ref> refs = new ArrayList<>(dataRefs.market(s));
        Instant expires = now.plus(Duration.ofHours(24));
        if ("PREVIEW".equals(kind)) {
          if (!SPORTS.contains(s.category())) throw new ValidationException(List.of(new ValidationException.Field("marketNo", "notSports")));
          if (s.eventStartAt() == null || !s.eventStartAt().isAfter(now)) throw new BizException("MARKET_STARTED", "比赛已经开始，赛前分析不能再写");
          expires = s.eventStartAt();
        }
        if ("BRIEF".equals(kind)) {
          if (!CRYPTO.contains(s.category())) throw new ValidationException(List.of(new ValidationException.Field("marketNo", "notCrypto")));
          refs.addAll(dataRefs.btc(btc()));
        }
        return new Plan(kind, s.publicNo(), null, null, refs, expires);
      }
      case "SEO_INTRO" -> {
        ContentRows.Page p = in.pageSlug() == null ? null : pages.page(in.pageSlug().strip());
        if (p == null) throw new BizException("NOT_FOUND", "页面不存在");
        List<Ref> refs = new ArrayList<>();
        if ("FOCUS".equals(p.kind)) refs.addAll(dataRefs.market(market(String.valueOf(p.marketNo))));
        else if ("BTC_STATS".equals(p.kind)) refs.addAll(dataRefs.btc(btc()));
        else throw new ValidationException(List.of(new ValidationException.Field("pageSlug", "notDataPage")));
        return new Plan(kind, p.marketNo, p.slug, null, refs, null);
      }
      default -> { // ARTICLE
        String topic = in.topic() == null ? "" : in.topic().strip();
        if (topic.length() < 2 || topic.length() > 200) throw new ValidationException(List.of(new ValidationException.Field("topic", "length")));
        List<String> bad = Words.find(topic, words.list(Words.SENSITIVE));
        if (!bad.isEmpty()) throw new BizException("CONTENT_BANNED_WORDS", "题目涉及不能写的内容：" + String.join("、", bad));
        LocalDateTime monday = LocalDate.ofInstant(now, ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
        int limit = settings.integer(Settings.ARTICLES_WEEKLY, 2);
        if (mapper.countArticleGroups(monday) >= limit) throw new BizException("ARTICLE_LIMIT", "这一周的文章已经有 " + limit + " 篇（每周最多 " + limit + " 篇）");
        return new Plan(kind, null, null, topic, dataRefs.rules(now), null);
      }
    }
  }

  private MarketPublicApi.Snapshot market(String no) {
    long n;
    try {
      n = Long.parseLong(no == null ? "" : no.strip());
    } catch (NumberFormatException e) {
      throw new ValidationException(List.of(new ValidationException.Field("marketNo", "required")));
    }
    return markets.snapshot(n).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在或还是草稿"));
  }

  private BtcStats btc() {
    return pages.ensureBtc(BTC_MAX_AGE).orElseThrow(() -> new BizException("DATA_UNAVAILABLE", "BTC 行情数据暂时取不到（币安、OKX 都不可用），这类草稿不生成"));
  }

  private static String target(Plan p) {
    return p.marketNo() != null ? String.valueOf(p.marketNo()) : p.pageSlug() != null ? p.pageSlug() : p.topic();
  }

  /** 建中文、英文两条（GENERATING），交给线程池起草 */
  List<ContentRows.Draft> start(Plan plan, Long adminId) {
    long group = ids.next();
    String refs = JSON.writeValueAsString(plan.refs());
    LocalDateTime now = now();
    List<ContentRows.Draft> rows = tx.execute(st -> {
      List<ContentRows.Draft> out = new ArrayList<>();
      for (String lang : List.of("zh", "en")) {
        ContentRows.Draft d = new ContentRows.Draft();
        d.draftNo = ids.next();
        d.groupNo = group;
        d.kind = plan.kind();
        d.lang = lang;
        d.title = "";
        d.body = "";
        d.refs = refs;
        d.status = "GENERATING";
        d.marketNo = plan.marketNo();
        d.pageSlug = plan.pageSlug();
        d.topic = plan.topic() == null ? null : cut(plan.topic(), 300);
        d.expiresAt = plan.expiresAt() == null ? null : LocalDateTime.ofInstant(plan.expiresAt().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        d.createdBy = adminId;
        d.createdAt = now;
        d.updatedAt = now;
        mapper.insertDraft(d);
        out.add(d);
      }
      return out;
    });
    contentLog.write("GENERATE", plan.kind(), String.valueOf(group), null, "OK", "开始起草（中文、英文）：" + target(plan), 0, adminId);
    for (ContentRows.Draft d : rows) submit(d);
    return rows.stream().map(d -> mapper.draft(d.draftNo)).toList();
  }

  private void submit(ContentRows.Draft d) {
    if (!workers.submit("起草 " + d.kind + " " + d.lang + " " + d.draftNo, () -> write(d.draftNo))) {
      mapper.finishDraft(d.id, "FAILED", "", "", null, false, "自动化程序排队已满，稍后重试", 0, now());
    }
  }

  /** 起草一条（线程池里跑；外部调用在事务外） */
  void write(long draftNo) {
    ContentRows.Draft d = mapper.draft(draftNo);
    if (d == null || !"GENERATING".equals(d.status)) return;
    List<Ref> refs = refs(d);
    boolean en = "en".equals(d.lang);
    List<String> banned = words.list(Words.BANNED);
    int calls = 0;
    String feedback = null;
    Parsed last = null;
    DraftChecks.Result lastCheck = null;
    for (int round = 1; round <= 2; round++) {
      String prompt = Prompts.build(d.kind, en, refs, d.topic, banned);
      if (feedback != null) prompt += (en ? "\nThe previous draft was rejected because: " : "\n上一稿没有通过，原因：") + feedback + "\n";
      GeminiClient.Result r = gemini.generate(GeminiClient.Purpose.CONTENT, prompt, true, d.kind + " " + d.lang + " " + d.draftNo);
      calls += r.calls();
      if (!r.ok()) {
        finish(d, "FAILED", last, lastCheck, "Gemini：" + r.error(), calls);
        return;
      }
      Parsed p = parse(r.text());
      if (p == null) {
        feedback = en ? "the output was not the required JSON object" : "返回的不是要求的 JSON";
        continue;
      }
      DraftChecks.Result c = checks.check(d.kind, d.lang, p.title(), p.body(), refs);
      last = p;
      lastCheck = c;
      if (c.ok()) {
        finish(d, "PENDING", p, c, null, calls);
        return;
      }
      feedback = c.summary();
    }
    if (last == null) {
      finish(d, "FAILED", null, null, "两次起草返回的格式都不对", calls);
    } else if (lastCheck.wordProblem() || !lastCheck.missing().isEmpty()) {
      finish(d, "FAILED", last, lastCheck, "重新起草后仍不合格：" + lastCheck.summary(), calls);
    } else {
      // 只是数字对不上 / 太长 / 少了 Polymarket 说明：留给人改（标红，不能发布）
      finish(d, "PENDING", last, lastCheck, null, calls);
    }
  }

  private void finish(ContentRows.Draft d, String status, Parsed p, DraftChecks.Result c, String reason, int calls) {
    String checksJson = c == null ? null : JSON.writeValueAsString(c);
    mapper.finishDraft(d.id, status, p == null ? "" : cut(p.title(), 300), p == null ? "" : p.body(), checksJson, c != null && c.ok(), reason == null ? null
        : cut(reason, 500), calls, now());
    String detail = "PENDING".equals(status) ? (c != null && c.ok() ? "待审核，检查通过" : "待审核，检查不通过：" + (c == null ? "" : c.summary())) : "生成失败：" + reason;
    contentLog.write("GENERATE", d.kind, String.valueOf(d.draftNo), d.lang, "PENDING".equals(status) ? "OK" : "FAILED", detail, calls, d.createdBy);
  }

  record Parsed(String title, String body) {}

  /** 模型返回的 JSON（可能包在 ``` 里） */
  static Parsed parse(String text) {
    if (text == null) return null;
    String s = text.strip();
    if (s.startsWith("```")) {
      int nl = s.indexOf('\n');
      s = nl < 0 ? "" : s.substring(nl + 1);
      int end = s.lastIndexOf("```");
      if (end >= 0) s = s.substring(0, end);
    }
    try {
      JsonNode n = JSON.readTree(s.strip());
      String title = n.path("title").asString("").strip();
      String body = n.path("body").asString("").strip();
      if (body.isEmpty()) return null;
      return new Parsed(title, body);
    } catch (RuntimeException e) {
      return null;
    }
  }

  static List<Ref> refs(ContentRows.Draft d) {
    return JSON.readValue(d.refs, new TypeReference<List<Ref>>() {});
  }

  // =====================================================================
  // 审核
  // =====================================================================

  public ContentRows.Draft get(long draftNo) {
    ContentRows.Draft d = mapper.draft(draftNo);
    if (d == null) throw new BizException("NOT_FOUND", "草稿不存在");
    return d;
  }

  public ContentRows.Draft edit(long draftNo, ContentAdminApi.EditInput in, long adminId) {
    ContentRows.Draft d = get(draftNo);
    if (!"PENDING".equals(d.status) && !"FAILED".equals(d.status)) throw new BizException("DRAFT_STATE_CHANGED", "这条草稿现在不能改");
    if (expired(d)) throw new BizException("DRAFT_EXPIRED", "草稿已过期");
    String title = in == null || in.title() == null ? "" : in.title().strip();
    String body = in == null || in.body() == null ? "" : in.body().strip();
    ValidationException.Collector errs = new ValidationException.Collector();
    if (title.isEmpty()) errs.add("title", "required");
    if (title.length() > 300) errs.add("title", "tooLong");
    if (body.isEmpty()) errs.add("body", "required");
    if (body.length() > 20_000) errs.add("body", "tooLong");
    if (in == null || in.version() == null) errs.add("version", "required");
    errs.throwIfAny();
    String all = title + "\n" + body;
    List<String> bad = new ArrayList<>(words.find(all, Words.BANNED));
    bad.addAll(words.find(all, Words.TENDENCY));
    if (!bad.isEmpty()) {
      contentLog.write("EDIT", d.kind, String.valueOf(draftNo), d.lang, "BLOCKED", "有禁用的说法或倾向，没有保存：" + String.join("、", bad), 0, adminId);
      throw new BizException("CONTENT_BANNED_WORDS", "有禁用的说法或倾向，没有保存：" + String.join("、", bad));
    }
    DraftChecks.Result c = checks.check(d.kind, d.lang, title, body, refs(d));
    if (mapper.editDraft(d.id, in.version(), title, body, JSON.writeValueAsString(c), c.ok(), adminId, now()) != 1) {
      throw new BizException("DRAFT_STATE_CHANGED", "草稿刚被别人改过，请刷新");
    }
    contentLog.write("EDIT", d.kind, String.valueOf(draftNo), d.lang, "OK", c.ok() ? "保存，检查通过" : "保存，检查不通过：" + c.summary(), 0, adminId);
    return get(draftNo);
  }

  public ContentRows.Draft publish(long draftNo, long adminId) {
    ContentRows.Draft d = get(draftNo);
    if ("REPLY".equals(d.kind)) throw new BizException("DRAFT_COPY_ONLY", "回复草稿只能复制、由人手动回复，没有发布");
    if (settings.flag(Settings.STOPPED)) throw new BizException("PUBLISH_STOPPED", "已按下紧急停止，发布暂停");
    if (!"PENDING".equals(d.status)) throw new BizException("DRAFT_STATE_CHANGED", "只有待审核的草稿能发布");
    if (expired(d)) {
      mapper.moveDraft(d.id, d.version, "PENDING", "EXPIRED", null, null, now());
      throw new BizException("DRAFT_EXPIRED", "草稿已过期，不能发布");
    }
    // 发布前再检查一遍（词表可能刚加了词）
    DraftChecks.Result c = checks.check(d.kind, d.lang, d.title, d.body, refs(d));
    if (!c.ok()) {
      contentLog.write("PUBLISH", d.kind, String.valueOf(draftNo), d.lang, "BLOCKED", c.summary(), 0, adminId);
      throw new BizException("DRAFT_CHECK_FAILED", "检查没有通过，不能发布：" + c.summary());
    }
    String where = switch (d.kind) {
      case "SEO_INTRO" -> {
        pages.publishIntro(d.pageSlug, d.lang, d.body);
        yield "网站页面说明 /insights/" + d.pageSlug;
      }
      case "ARTICLE" -> "网站文章 /insights/" + pages.publishArticle(d.groupNo, d.lang, d.title, d.body);
      case "PREVIEW" -> "焦点战页 /insights/" + PageService.FOCUS_PREFIX + d.marketNo + "（也可以复制到 X、Telegram）";
      default -> "审核通过，可以复制后手动发到 " + ("POST_TG".equals(d.kind) ? "Telegram" : "POST_X".equals(d.kind) ? "X" : "X、Telegram");
    };
    if (mapper.moveDraft(d.id, d.version, "PENDING", "PUBLISHED", null, adminId, now()) != 1) {
      throw new BizException("DRAFT_STATE_CHANGED", "草稿刚被别人改过，请刷新");
    }
    contentLog.write("PUBLISH", d.kind, String.valueOf(draftNo), d.lang, "OK", where, 0, adminId);
    return get(draftNo);
  }

  public ContentRows.Draft reject(long draftNo, String reason, long adminId) {
    ContentRows.Draft d = get(draftNo);
    String r = reason == null ? "" : reason.strip();
    if (r.isEmpty()) throw new ValidationException(List.of(new ValidationException.Field("reason", "required")));
    if (r.length() > 500) throw new ValidationException(List.of(new ValidationException.Field("reason", "tooLong")));
    String from = d.status;
    if (!"PENDING".equals(from) && !"FAILED".equals(from)) throw new BizException("DRAFT_STATE_CHANGED", "只有待审核、生成失败的草稿能驳回");
    if (mapper.moveDraft(d.id, d.version, from, "REJECTED", r, adminId, now()) != 1) throw new BizException("DRAFT_STATE_CHANGED", "草稿刚被别人改过，请刷新");
    contentLog.write("REJECT", d.kind, String.valueOf(draftNo), d.lang, "OK", "驳回：" + r, 0, adminId);
    return get(draftNo);
  }

  public ContentRows.Draft retry(long draftNo, long adminId) {
    ContentRows.Draft d = get(draftNo);
    if (!"FAILED".equals(d.status) && !"REJECTED".equals(d.status)) throw new BizException("DRAFT_STATE_CHANGED", "只有生成失败、驳回的草稿能重新起草");
    if (expired(d)) throw new BizException("DRAFT_EXPIRED", "草稿已过期");
    Optional<String> no = aiUnavailable();
    if (no.isPresent()) throw new BizException("AI_UNAVAILABLE", no.get());
    if (mapper.moveDraft(d.id, d.version, d.status, "GENERATING", null, adminId, now()) != 1) throw new BizException("DRAFT_STATE_CHANGED", "草稿刚被别人改过，请刷新");
    contentLog.write("RETRY", d.kind, String.valueOf(draftNo), d.lang, "OK", "重新起草", 0, adminId);
    submit(mapper.draft(draftNo));
    return get(draftNo);
  }

  public ContentRows.Draft copied(long draftNo, long adminId) {
    ContentRows.Draft d = get(draftNo);
    if (!canCopy(d)) throw new BizException("DRAFT_STATE_CHANGED", settings.flag(Settings.STOPPED) ? "已按下紧急停止" : "这条草稿现在不能复制（要先审核通过）");
    contentLog.write("COPY", d.kind, String.valueOf(draftNo), d.lang, "OK", "复制文字" + (link(d) == null ? "" : "和链接"), 0, adminId);
    return d;
  }

  /** 过期：定时任务调用；起草中断的（GENERATING 超过 10 分钟）标成失败 */
  public int expire() {
    LocalDateTime now = now();
    int n = mapper.expireDrafts(now);
    int stuck = mapper.failStuck(now.minusMinutes(10), now);
    if (n > 0) contentLog.write("EXPIRE", null, null, null, "OK", n + " 条草稿过期", 0, null);
    if (stuck > 0) contentLog.write("EXPIRE", null, null, null, "FAILED", stuck + " 条起草中断，标成生成失败", 0, null);
    return n;
  }

  // =====================================================================
  // 视图
  // =====================================================================

  boolean expired(ContentRows.Draft d) {
    return d.expiresAt != null && !d.expiresAt.toInstant(ZoneOffset.UTC).isAfter(clock.instant());
  }

  boolean canPublish(ContentRows.Draft d) {
    return !"REPLY".equals(d.kind) && "PENDING".equals(d.status) && d.checkOk && !expired(d) && !settings.flag(Settings.STOPPED);
  }

  boolean canCopy(ContentRows.Draft d) {
    if (!COPYABLE.contains(d.kind) || settings.flag(Settings.STOPPED)) return false;
    if ("REPLY".equals(d.kind)) return "PENDING".equals(d.status) && d.checkOk && !expired(d);
    return "PUBLISHED".equals(d.status) && !expired(d);
  }

  /** 复制时附带的链接（帖子、行情短文、赛前分析：市场链接，带来源参数） */
  String link(ContentRows.Draft d) {
    if (d.marketNo == null || "SEO_INTRO".equals(d.kind) || "REPLY".equals(d.kind)) return null;
    String src = "POST_TG".equals(d.kind) ? "tg" : "x";
    return clientUrl + "/m/" + d.marketNo + "?src=" + src + "&cmp=post-" + d.kind.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
  }

  String siteUrl(ContentRows.Draft d) {
    return switch (d.kind) {
      case "SEO_INTRO" -> "/insights/" + d.pageSlug;
      case "ARTICLE" -> "/insights/" + PageService.ARTICLE_PREFIX + Long.toString(d.groupNo, 36);
      case "PREVIEW" -> "/insights/" + PageService.FOCUS_PREFIX + d.marketNo;
      default -> null;
    };
  }

  public ContentAdminApi.Draft view(ContentRows.Draft d) {
    List<ContentAdminApi.RefView> refs = refs(d).stream().map(r -> new ContentAdminApi.RefView(r.key(), r.label(), r.labelEn(), r.value(), r.source(), r.at())).toList();
    ContentAdminApi.Checks c = null;
    if (d.checks != null) {
      DraftChecks.Result r = JSON.readValue(d.checks, DraftChecks.Result.class);
      c = new ContentAdminApi.Checks(r.numbers().stream().map(n -> new ContentAdminApi.NumberHit(n.field(), n.text(), n.start(), n.end())).toList(), r.banned(),
          r.tendency(), r.polymarketNoteMissing(), r.maxLength(), r.actualLength(), r.missing(), r.summary());
    }
    return new ContentAdminApi.Draft(String.valueOf(d.draftNo), String.valueOf(d.groupNo), d.kind, d.lang, d.title, d.body, refs, c, d.checkOk, d.status,
        d.failReason, d.rejectReason, d.marketNo == null ? null : String.valueOf(d.marketNo), d.pageSlug, d.topic, iso(d.expiresAt), expired(d), d.aiCalls,
        iso(d.createdAt), d.createdBy, iso(d.publishedAt), d.publishedBy, d.version, canPublish(d), COPY_ONLY.contains(d.kind), canCopy(d), link(d),
        "PUBLISHED".equals(d.status) ? siteUrl(d) : null);
  }

  static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }

  private static String cut(String s, int n) {
    return s == null || s.length() <= n ? s : s.substring(0, n);
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }
}
