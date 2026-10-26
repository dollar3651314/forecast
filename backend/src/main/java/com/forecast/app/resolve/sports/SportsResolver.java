package com.forecast.app.resolve.sports;

import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.resolve.api.ResolveAdminApi;
import com.forecast.app.resolve.api.ResultHandler;
import com.forecast.app.resolve.platform.CaseBook;
import com.forecast.app.resolve.platform.ResolveDesk;
import com.forecast.app.resolve.source.ScoreFixture;
import com.forecast.app.resolve.source.ScoreSource;
import com.forecast.app.resolve.source.SourceGateway;
import com.forecast.app.resolve.source.SourceReply;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 体育市场的自动裁决（forecast S8；需求说明书 v1.3 第 3.6 节 E5～E7、E14，第 4.3 节；RQ-15；技术方案第 5.2 节 SportsResolver、第 6.2 节）。
 * <ol>
 *   <li>市场（足球、NBA，裁决方式自动）进入待裁决时建进度（{@link #onPending}，market 的同一事务）：记下比赛、玩法、两个来源的编号（当时确认的对应）。
 *       自动裁决参数不全、备源的比赛没有确认对应（只有一个来源）→ 直接转人工。</li>
 *   <li>{@link #resolveDue}（任务每 30 秒，按进度的下一次时间，默认每 5 分钟看一次）：同一场比赛的几个市场一起处理，<b>在事务外</b>取主源（每个市场各存一行原始返回）：
 *     <ul>
 *       <li>还没完赛 → 下一轮；事件结束 6 小时后还没完赛 → 转人工；</li>
 *       <li>延期 → 转人工并自动标记 <b>E6 等待</b>（等到原定开赛后 48 小时，没解除系统作废）；中断 / 腰斩 → 转人工并标记 <b>E7 等待</b>；取消、判定结果、
 *           足球加时点球 → 转人工；</li>
 *       <li>完赛：第一次看到时开始<b>等 30 分钟</b>；等完再取主源（仍是完赛）和备源比较（{@link SportsDecision#compare}）：
 *           比分一致 → 按玩法定结果、写裁决记录 AUTO_RESULT → 调 settle 结算（实际开赛时间按数据源交给结算，E14）；
 *           比分不一致、状态不一致 → 转人工；备源拿不到或还没完赛 → 再等最多 30 分钟，仍不行转人工。</li>
 *     </ul>
 *   </li>
 *   <li>转人工：进度 MANUAL + 裁决记录 AUTO_FAILED（带两个来源的数据和存档行）+ S7 裁决台的进度（"自动裁决失败"）+ 员工告警 {@code SPORTS_AUTO_FAILED}；
 *       市场留在待裁决，由人按首选来源提议、确认、公示，或作废。</li>
 * </ol>
 * 进度行的每次改动都是带旧状态和版本号的条件更新；结算三道防重复：多实例、重复执行只结算一次。取数之前先认领（S14，{@code SportsMapper.claim}），
 * 同时到的两个触发只有一个去取数。
 */
@Service
public class SportsResolver {

  private static final Logger log = LoggerFactory.getLogger(SportsResolver.class);
  /** {@code source_ref.kind} */
  public static final String KIND = "SPORTS";
  static final String AUTO = "AUTO";
  static final String KIND_RESULT = "AUTO_RESULT";
  static final String KIND_FAILED = "AUTO_FAILED";
  static final int BATCH = 50;

  private final SportsMapper mapper;
  private final MarketApi markets;
  private final SportsSources sources;
  private final SportsProperties props;
  private final ObjectProvider<ResultHandler> handler;
  private final StaffAlertApi staff;
  private final CaseBook cases;
  private final ResolveDesk desk;
  private final Clock clock;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  SportsResolver(SportsMapper mapper, MarketApi markets, SportsSources sources, SportsProperties props, ObjectProvider<ResultHandler> handler,
      StaffAlertApi staff, CaseBook cases, ResolveDesk desk, Clock clock, ObjectMapper json, PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.markets = markets;
    this.sources = sources;
    this.props = props;
    this.handler = handler;
    this.staff = staff;
    this.cases = cases;
    this.desk = desk;
    this.clock = clock;
    this.json = json;
    this.tx = new TransactionTemplate(tm);
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  // =====================================================================
  // 进入待裁决
  // =====================================================================

  /** 是不是体育自动裁决的市场（足球、NBA，裁决方式自动） */
  public static boolean handles(MarketApi.AutoRef m) {
    return (m.category().name().equals(ScoreSource.FOOTBALL) || m.category().name().equals(ScoreSource.NBA))
        && m.resolutionMode() == com.forecast.app.market.api.ResolutionMode.AUTO;
  }

  /**
   * 市场进入待裁决时建进度（<b>在 market 的状态跳转事务里</b>，{@code ResolveMarketListener} 调用；重复调用没有效果）。
   * 参数不全或只有一个来源的直接建成"转人工"、进裁决台，事务提交后邮件告警。
   */
  public void onPending(MarketApi.AutoRef m) {
    LocalDateTime now = now();
    SportsRows.Task t = new SportsRows.Task();
    t.marketId = m.marketId();
    t.sport = m.category().name();
    t.kickoffAt = ldt(m.eventStartAt());
    t.closeAt = markets.platformRef(m.marketId()).map(r -> ldt(r.closeAt())).orElse(t.kickoffAt);
    t.nextAttemptAt = now;
    t.unfinishedDeadlineAt = now.plus(props.getUnfinishedLimit());
    t.createdAt = now;
    t.updatedAt = now;
    String bad = fill(t, m);
    if (bad == null) {
      t.status = "PENDING";
      mapper.insertTaskIgnore(t);
      return;
    }
    // 参数不全、只有一个来源：直接转人工
    t.status = "MANUAL";
    t.lastReason = bad;
    if (t.play == null) t.play = "-";
    if (t.primarySource == null) t.primarySource = "-";
    if (t.primaryRef == null) t.primaryRef = "-";
    if (mapper.insertTaskIgnore(t) != 1) return;
    cases.open(m.marketId(), CaseBook.AUTO_FAILED, m.category());
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("reason", bad);
    d.put("sourceRef", m.sourceRef());
    insertRecord(m.marketId(), KIND_FAILED, null, null, d, bad, now);
    log.warn("市场 {} 不能体育自动裁决，直接转人工：{}", m.publicNo(), bad);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        alert(m, bad, null);
      }
    });
  }

  /** 按 source_ref 和赛程填进度；不能自动裁决时返回原因 */
  private String fill(SportsRows.Task t, MarketApi.AutoRef m) {
    Map<String, Object> ref = m.sourceRef();
    String incomplete = "市场的自动裁决参数（比赛、玩法、结果和选项的对应）不全，不能自动裁决";
    if (ref == null || !KIND.equals(ref.get("kind"))) return incomplete;
    Play play = Play.of(str(ref.get("play")));
    if (play == null || !play.sport.equals(t.sport)) return incomplete;
    t.play = play.name();
    if (play.needsLine) {
      try {
        t.line = new BigDecimal(str(ref.get("line")));
      } catch (RuntimeException e) {
        return incomplete;
      }
      if (!Play.validLine(t.line)) return incomplete;
    }
    for (String o : play.outcomes) if (optionOf(m, o) == null) return incomplete;
    SportsRows.Fixture f;
    try {
      f = mapper.fixtureByNo(Long.parseLong(str(ref.get("fixtureNo"))));
    } catch (RuntimeException e) {
      return incomplete;
    }
    if (f == null || !f.primarySource.equals(ref.get("primarySource")) || !f.primaryRef.equals(ref.get("primaryRef"))) return incomplete;
    t.fixtureId = f.id;
    t.primarySource = f.primarySource;
    t.primaryRef = f.primaryRef;
    t.backupSource = f.backupSource;
    t.backupRef = f.backupRef;
    t.backupSwapped = f.backupSwapped;
    if (f.backupRef == null) return "备用来源的比赛没有确认对应：只有一个来源，不能自动结算（RQ-15）";
    return null;
  }

  // =====================================================================
  // 定时任务
  // =====================================================================

  /**
   * 跑一轮：到时间的市场按比赛分组处理（不在事务里）。
   *
   * @return 市场 id → waiting / retry / settled / settling / manual / closed / resolved / error …
   */
  public Map<Long, String> resolveDue() {
    Map<Long, String> out = new LinkedHashMap<>();
    Map<String, List<SportsRows.Task>> groups = new LinkedHashMap<>();
    for (long id : mapper.dueTasks(now(), BATCH)) {
      SportsRows.Task t = mapper.task(id);
      if (t != null) groups.computeIfAbsent(t.primarySource + ":" + t.primaryRef, k -> new ArrayList<>()).add(t);
    }
    for (List<SportsRows.Task> g : groups.values()) {
      try {
        out.putAll(resolveGroup(g));
      } catch (RuntimeException e) {
        log.error("比赛 {} 的体育自动裁决这一轮失败，下一轮重试", g.get(0).primaryRef, e);
        for (SportsRows.Task t : g) out.putIfAbsent(t.marketId, "error");
      }
    }
    return out;
  }

  /** 处理一个市场（到时间才处理；测试直接调用） */
  public String resolveOne(long marketId) {
    SportsRows.Task t = mapper.task(marketId);
    if (t == null) return "none";
    return resolveGroup(List.of(t)).getOrDefault(marketId, "none");
  }

  private Map<Long, String> resolveGroup(List<SportsRows.Task> group) {
    Map<Long, String> out = new LinkedHashMap<>();
    LocalDateTime now = now();
    List<SportsRows.Task> pending = new ArrayList<>();
    Map<Long, MarketApi.AutoRef> refs = new LinkedHashMap<>();
    for (SportsRows.Task t : group) {
      if (!"PENDING".equals(t.status) && !"RESOLVING".equals(t.status)) {
        out.put(t.marketId, t.status.toLowerCase(Locale.ROOT));
        continue;
      }
      if (t.nextAttemptAt.isAfter(now)) {
        out.put(t.marketId, "notDue");
        continue;
      }
      if ("RESOLVING".equals(t.status)) {
        out.put(t.marketId, finish(t));
        continue;
      }
      MarketApi.AutoRef m = markets.autoRef(t.marketId).orElse(null);
      if (m == null || m.status() != MarketStatus.PENDING_RESOLUTION) {
        mapper.close(t.marketId, "市场已不在待裁决（" + (m == null ? "不存在" : m.status().api()) + "）", now);
        out.put(t.marketId, "closed");
        continue;
      }
      // S14：先认领这一次尝试再取数（同时到的另一个触发认领不到，不取数、不多存原始返回）
      if (mapper.claim(t.marketId, t.version, now, now.plus(props.getClaimTtl())) != 1) {
        out.put(t.marketId, "busy");
        continue;
      }
      t.version++;
      refs.put(t.marketId, m);
      pending.add(t);
    }
    if (pending.isEmpty()) return out;
    SportsRows.Task first = pending.get(0);
    ScoreSource ps = sources.byName(first.primarySource);
    SourceReply<ScoreFixture> pr = sources.fixture(ps, first.sport, first.primaryRef, pending.stream().map(t -> t.marketId).toList(), null);
    // 等完 30 分钟、主源仍是完赛的，要看备源：同一场比赛的几个市场一起取一次
    List<SportsRows.Task> needBackup = new ArrayList<>();
    for (SportsRows.Task t : pending) {
      if (pr.ok() && t.finishedSeenAt != null && !now.isBefore(t.settleAfterAt) && t.backupRef != null
          && SportsDecision.onPrimary(t.sport, pr.value()).kind() == SportsDecision.Kind.FINISHED) {
        needBackup.add(t);
      }
    }
    SourceReply<ScoreFixture> br = null;
    if (!needBackup.isEmpty()) {
      SportsRows.Task b0 = needBackup.get(0);
      br = sources.fixture(sources.byName(b0.backupSource), b0.sport, b0.backupRef, needBackup.stream().map(t -> t.marketId).toList(), null);
    }
    for (SportsRows.Task t : pending) {
      try {
        out.put(t.marketId, step(t, refs.get(t.marketId), pr, needBackup.contains(t) ? br : null));
      } catch (RuntimeException e) {
        log.error("市场 {} 体育自动裁决这一轮失败，下一轮重试", t.marketId, e);
        out.put(t.marketId, "error");
      }
    }
    return out;
  }

  private String step(SportsRows.Task t, MarketApi.AutoRef m, SourceReply<ScoreFixture> pr, SourceReply<ScoreFixture> br) {
    LocalDateTime now = now();
    if (pr.outcome() == SourceGateway.Outcome.DISABLED) {
      return manual(t, m, "首选来源" + sources.unusableReason(sources.byName(t.primarySource)), null, detail(t, null, pr, null), now);
    }
    if (!pr.ok()) return waitOrGiveUp(t, m, "首选来源拿不到：" + pr.error(), detail(t, null, pr, null), now);
    ScoreFixture p = pr.value();
    SportsDecision.Verdict v1 = SportsDecision.onPrimary(t.sport, p);
    switch (v1.kind()) {
      case WAIT:
        return waitOrGiveUp(t, m, v1.reason(), detail(t, v1, pr, null), now);
      case MANUAL:
        return manual(t, m, v1.reason(), null, detail(t, v1, pr, null), now);
      case HOLD_POSTPONED:
        return manual(t, m, v1.reason(), "POSTPONED", detail(t, v1, pr, null), now);
      case HOLD_ABANDONED:
        return manual(t, m, v1.reason(), "ABANDONED", detail(t, v1, pr, null), now);
      default:
        break;
    }
    // 完赛：第一次看到时开始等 30 分钟（需求第 4.3 节）
    if (t.finishedSeenAt == null) {
      LocalDateTime settleAfter = now.plus(props.getFinishWait());
      String why = v1.reason() + "，等 " + props.getFinishWait().toMinutes() + " 分钟再和备用来源比较";
      if (mapper.finishedSeen(t.marketId, t.version, now, settleAfter, settleAfter.plus(props.getGiveUpAfter()), cut(why), now) != 1) return "raced";
      log.info("市场 {} 首选来源报告完赛 {}:{}，{} 以后比较两个来源", m.publicNo(), p.homeScore(), p.awayScore(), settleAfter);
      return "waiting";
    }
    if (now.isBefore(t.settleAfterAt)) {
      mapper.retryLater(t.marketId, t.version, t.settleAfterAt, cut("完赛后等 30 分钟"), now);
      return "waiting";
    }
    if (t.backupRef == null) return manual(t, m, "备用来源的比赛没有确认对应：只有一个来源，不能自动结算（RQ-15）", null, detail(t, v1, pr, null), now);
    if (br == null) return waitOrGiveUp(t, m, "这一轮没有取备用来源", detail(t, v1, pr, null), now);
    if (br.outcome() == SourceGateway.Outcome.DISABLED) {
      return manual(t, m, "备用来源" + sources.unusableReason(sources.byName(t.backupSource)), null, detail(t, v1, pr, br), now);
    }
    Play play = Play.valueOf(t.play);
    SportsDecision.Verdict v2 = SportsDecision.compare(play, t.line, p, br.ok() ? br.value() : null, br.error(), t.backupSwapped, instant(t.closeAt),
        props.getKickoffTolerance());
    Map<String, Object> d = detail(t, v2, pr, br);
    switch (v2.kind()) {
      case SETTLE: {
        Long optionId = optionOf(m, v2.outcome());
        if (optionId == null) return manual(t, m, "市场的自动裁决参数里没有 " + v2.outcome() + " 对应的选项", null, d, now);
        LocalDateTime start = ldt(v2.actualStartAt());
        Boolean ok = tx.execute(st -> {
          if (mapper.toResolving(t.marketId, t.version, optionId, start, now.plus(props.getPollInterval()), cut(v2.reason()), now) != 1) return false;
          insertRecord(t.marketId, KIND_RESULT, optionId, start, d, v2.reason(), now);
          return true;
        });
        if (!Boolean.TRUE.equals(ok)) return "raced";
        log.info("市场 {} 体育自动裁决：{}（{}）", m.publicNo(), v2.outcome(), v2.reason());
        return finish(mapper.task(t.marketId));
      }
      case WAIT:
        return waitOrGiveUp(t, m, v2.reason(), d, now);
      default:
        return manual(t, m, v2.reason(), null, d, now);
    }
  }

  /** 还不能定：没到时间就下一轮，过了时间转人工（还没完赛的按"事件结束 + 6 小时"，已完赛的按"等完 + 30 分钟"） */
  private String waitOrGiveUp(SportsRows.Task t, MarketApi.AutoRef m, String reason, Map<String, Object> d, LocalDateTime now) {
    LocalDateTime deadline = t.finishedSeenAt == null ? t.unfinishedDeadlineAt : t.giveUpAt;
    if (!now.isBefore(deadline)) {
      String why = reason + "（等到 " + iso(deadline) + " 仍不行）";
      return manual(t, m, why, null, d, now);
    }
    LocalDateTime next = now.plus(props.getPollInterval());
    if (next.isAfter(deadline)) next = deadline;
    mapper.retryLater(t.marketId, t.version, next, cut(reason), now);
    return "retry";
  }

  /** 结果已定：调 settle 结算；结算失败下一轮再调 */
  private String finish(SportsRows.Task t) {
    LocalDateTime now = now();
    MarketApi.AutoRef m = markets.autoRef(t.marketId).orElse(null);
    if (m == null || m.status() != MarketStatus.PENDING_RESOLUTION) {
      mapper.toResolved(t.marketId, "市场已是 " + (m == null ? "不存在" : m.status().api()), now);
      return m != null && m.status() == MarketStatus.SETTLED ? "settled" : "resolved";
    }
    ResultHandler h = handler.getIfAvailable();
    if (h == null) {
      mapper.resolvingLater(t.marketId, now.plus(props.getPollInterval()), "没有结算模块", now);
      return "settleFailed";
    }
    try {
      h.settle(t.marketId, t.resultOptionId, instant(t.actualStartAt), AUTO);
    } catch (RuntimeException e) {
      mapper.resolvingLater(t.marketId, now.plus(props.getPollInterval()), cut("结算失败：" + e.getMessage()), now);
      log.error("市场 {} 体育自动裁决后结算失败，下一轮重试", m.publicNo(), e);
      return "settleFailed";
    }
    mapper.toResolved(t.marketId, "已触发结算", now());
    MarketStatus after = markets.autoRef(t.marketId).map(MarketApi.AutoRef::status).orElse(null);
    return after == MarketStatus.SETTLED ? "settled" : after == MarketStatus.VOIDED ? "voided" : "settling";
  }

  /**
   * 转人工：PENDING → MANUAL，写 AUTO_FAILED，进裁决台（"自动裁决失败"）；holdKind 不为空时同一个事务里标记 E6 / E7 等待。提交后邮件告警。
   */
  private String manual(SportsRows.Task t, MarketApi.AutoRef m, String reason, String holdKind, Map<String, Object> d, LocalDateTime now) {
    String[] result = {"manual"};
    boolean[] held = {false};
    Boolean ok = tx.execute(st -> {
      MarketApi.PlatformRef pm = markets.lockPlatform(t.marketId);
      if (pm.status() != MarketStatus.PENDING_RESOLUTION) {
        mapper.close(t.marketId, "市场已不在待裁决（" + pm.status().api() + "）", now);
        result[0] = "closed";
        return true;
      }
      if (mapper.toManual(t.marketId, t.version, cut(reason), now) != 1) return false;
      Map<String, Object> detail = new LinkedHashMap<>(d);
      if (holdKind != null) detail.put("hold", holdKind);
      insertRecord(t.marketId, KIND_FAILED, null, null, detail, reason, now);
      cases.open(t.marketId, CaseBook.AUTO_FAILED, m.category());
      if (holdKind != null) {
        Map<String, Object> hd = new LinkedHashMap<>();
        hd.put("by", "SPORTS_SOURCE");
        hd.put("source", t.primarySource);
        hd.put("ref", t.primaryRef);
        held[0] = desk.systemHold(t.marketId, holdKind, reason, hd);
      }
      return true;
    });
    if (!Boolean.TRUE.equals(ok)) return "raced";
    if ("closed".equals(result[0])) return "closed";
    log.warn("市场 {} 体育自动裁决转人工{}：{}", m.publicNo(), held[0] ? "（已标记 " + holdKind + " 等待）" : "", reason);
    alert(m, reason, held[0] ? holdKind : null);
    return held[0] ? "manual_" + holdKind.toLowerCase(Locale.ROOT) : "manual";
  }

  void alert(MarketApi.AutoRef m, String reason, String hold) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("marketNo", String.valueOf(m.publicNo()));
    p.put("market", m.title().zhCN());
    p.put("marketEn", m.title().en());
    p.put("reason", reason == null || reason.isBlank() ? "-" : reason);
    p.put("hold", hold == null ? "-" : "POSTPONED".equals(hold) ? "已自动标记 E6 延期等待：原定开赛后 48 小时内完赛的解除等待后按实际结果裁决，到时间没解除系统作废、全额退款。"
        : "已自动标记 E7 腰斩等待：48 小时内官方宣布比分有效的解除等待后按官方比分裁决，到时间没解除系统作废、全额退款。");
    p.put("holdEn", hold == null ? "-" : "POSTPONED".equals(hold)
        ? "Marked as postponed (E6): if the match is completed within 48 hours of the original kick-off, release the hold and resolve it; otherwise it is voided automatically."
        : "Marked as abandoned (E7): if the official score is declared valid within 48 hours, release the hold and resolve it; otherwise it is voided automatically.");
    try {
      staff.notifyStaff("SPORTS_AUTO_FAILED", p);
    } catch (RuntimeException e) {
      log.error("市场 {} 转人工的告警邮件发送失败", m.publicNo(), e);
    }
  }

  /** 结果 → 选项（source_ref.options：结果 → 选项序号） */
  static Long optionOf(MarketApi.AutoRef m, String outcome) {
    Object map = m.sourceRef() == null ? null : m.sourceRef().get("options");
    if (!(map instanceof Map<?, ?> opts) || !(opts.get(outcome) instanceof Number seq)) return null;
    return m.options().stream().filter(o -> o.seq() == seq.intValue()).findFirst().map(MarketApi.OptionRef::optionId).orElse(null);
  }

  /** 裁决记录里的依据：比赛、玩法、两个来源的状态和比分、存档行 */
  private Map<String, Object> detail(SportsRows.Task t, SportsDecision.Verdict v, SourceReply<ScoreFixture> pr, SourceReply<ScoreFixture> br) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("sport", t.sport);
    d.put("play", t.play);
    d.put("line", t.line == null ? null : t.line.stripTrailingZeros().toPlainString());
    SportsRows.Fixture f = mapper.fixture(t.fixtureId);
    d.put("fixtureNo", f == null ? null : String.valueOf(f.publicNo));
    d.put("home", f == null ? null : f.homeName);
    d.put("away", f == null ? null : f.awayName);
    d.put("result", v == null ? null : v.outcome());
    d.put("homeScore", v == null ? null : v.homeScore());
    d.put("awayScore", v == null ? null : v.awayScore());
    d.put("actualStartAt", v == null || v.actualStartAt() == null ? null : v.actualStartAt().toString());
    d.put("basis", t.primarySource);
    d.put("reason", v == null ? null : v.reason());
    List<Map<String, Object>> list = new ArrayList<>();
    if (pr != null) list.add(source(pr, t.primaryRef, false, v != null && v.kind() == SportsDecision.Kind.SETTLE));
    if (br != null) list.add(source(br, t.backupRef, t.backupSwapped, v != null && v.kind() == SportsDecision.Kind.SETTLE));
    d.put("sources", list);
    return d;
  }

  private Map<String, Object> source(SourceReply<ScoreFixture> r, String ref, boolean swapped, boolean used) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("source", r.source());
    s.put("label", sources.byName(r.source()).label());
    s.put("ref", ref);
    s.put("swapped", swapped);
    s.put("outcome", r.outcome().name());
    s.put("used", used);
    if (r.value() != null) {
      s.put("status", r.value().status().name());
      s.put("rawStatus", r.value().rawStatus());
      s.put("regularTime", r.value().regularTime());
      s.put("homeScore", r.value().homeScore());
      s.put("awayScore", r.value().awayScore());
      s.put("kickoffAt", r.value().kickoffAt() == null ? null : r.value().kickoffAt().toString());
    }
    s.put("error", r.error());
    s.put("fetchId", r.archiveId());
    s.put("sha256", r.bodySha256());
    s.put("fetchedAt", r.fetchedAt() == null ? null : r.fetchedAt().toString());
    s.put("attempts", r.attempts());
    return s;
  }

  private void insertRecord(long marketId, String kind, Long optionId, LocalDateTime actualStart, Map<String, Object> detail, String note, LocalDateTime now) {
    SportsRows.Record r = new SportsRows.Record();
    r.marketId = marketId;
    r.kind = kind;
    r.optionId = optionId;
    r.actualStartAt = actualStart;
    r.detail = json.writeValueAsString(detail);
    r.note = note == null || note.length() <= 2000 ? note : note.substring(0, 2000);
    r.actor = AUTO;
    r.createdAt = now;
    mapper.insertRecord(r);
  }

  // =====================================================================
  // 后台
  // =====================================================================

  /** 后台裁决依据里的体育进度；不是体育自动裁决的市场为 null */
  public ResolveAdminApi.SportsTask view(long marketId, String marketNo, Map<Long, MarketApi.OptionRef> opts) {
    SportsRows.Task t = mapper.task(marketId);
    if (t == null) return null;
    SportsRows.Fixture f = mapper.fixture(t.fixtureId);
    String option = t.resultOptionId == null ? null : opts.containsKey(t.resultOptionId) ? String.valueOf(opts.get(t.resultOptionId).optionNo())
        : markets.optionsById(List.of(t.resultOptionId)).values().stream().findFirst().map(o -> String.valueOf(o.optionNo())).orElse(null);
    return new ResolveAdminApi.SportsTask(marketNo, f == null ? null : String.valueOf(f.publicNo), t.sport, t.play,
        t.line == null ? null : t.line.stripTrailingZeros().toPlainString(), t.primarySource, t.primaryRef, t.backupSource, t.backupRef, t.backupSwapped,
        t.status.toLowerCase(Locale.ROOT), t.attempts, iso(t.nextAttemptAt), iso(t.finishedSeenAt), iso(t.settleAfterAt), iso(t.giveUpAt),
        iso(t.unfinishedDeadlineAt), option, iso(t.actualStartAt), t.lastReason, iso(t.lastAttemptAt), iso(t.updatedAt));
  }

  static String str(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  static LocalDateTime ldt(Instant i) {
    return i == null ? null : LocalDateTime.ofInstant(i.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  static Instant instant(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC);
  }

  static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }

  static String cut(String s) {
    return s == null || s.length() <= 500 ? s : s.substring(0, 500);
  }
}
