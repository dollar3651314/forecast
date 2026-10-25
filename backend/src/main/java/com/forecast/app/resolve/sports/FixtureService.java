package com.forecast.app.resolve.sports;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.id.Snowflake;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.resolve.api.SportsAdminApi;
import com.forecast.app.resolve.source.ScoreFixture;
import com.forecast.app.resolve.source.ScoreSource;
import com.forecast.app.resolve.source.SourceGateway;
import com.forecast.app.resolve.source.SourceReply;
import com.forecast.app.resolve.source.TheSportsDb;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 体育赛程（forecast S8；需求说明书 v1.3 第 4.2 节"从体育数据源拉取未来 7 天的赛程"；技术方案第 6.1 节 X-12）。
 * <ul>
 *   <li>{@link #sync}：从主源拉未来 7 天（UTC 日期，含今天）的赛程，按主源编号新增或更新（队名、开赛时间、状态）。开赛时间变了、这场比赛有还没结束的市场时，
 *       邮件提醒运营（E14：实际开赛时间以数据源为准，截止时间晚于新开赛时间的要提前截止）。每次同步顺便给几场还没确认对应的比赛找候选。</li>
 *   <li>{@link #searchCandidates}：在备源（TheSportsDB）里按联赛 + 日期、队名 + 日期找，按队名和开赛时间打分（{@link Candidates}），存前 5 个。</li>
 *   <li>{@link #confirmMapping}：运营从候选里确认对应（开赛以前；不自动确认）。</li>
 * </ul>
 * 外部调用都在事务外（C81），经 {@link SourceGateway}：超时、有上限的重试、熔断、限频排队、存档（同步的存档行没有市场）。
 */
@Service
class FixtureService {

  private static final Logger log = LoggerFactory.getLogger(FixtureService.class);
  /** 赛程一次最多翻几页（balldontlie 每页 100 场，7 天的 NBA 远不到） */
  static final int MAX_PAGES = 10;

  private final SportsMapper mapper;
  private final SportsSources sources;
  private final SportsProperties props;
  private final MarketApi markets;
  private final StaffAlertApi staff;
  private final Snowflake ids;
  private final Clock clock;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  FixtureService(SportsMapper mapper, SportsSources sources, SportsProperties props, MarketApi markets, StaffAlertApi staff, Snowflake ids, Clock clock,
      ObjectMapper json, PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.sources = sources;
    this.props = props;
    this.markets = markets;
    this.staff = staff;
    this.ids = ids;
    this.clock = clock;
    this.json = json;
    this.tx = new TransactionTemplate(tm);
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  // =====================================================================
  // 同步
  // =====================================================================

  /** 同步一个或两个品类（sport 为空 = 足球和 NBA）；不在事务里 */
  List<SportsAdminApi.SyncResult> sync(String sport, Duration queueWait) {
    List<String> sports = sport == null || sport.isBlank() ? SportsSources.SPORTS : List.of(sport(sport));
    List<SportsAdminApi.SyncResult> out = new ArrayList<>();
    for (String s : sports) out.add(syncOne(s, queueWait));
    return out;
  }

  static String sport(String s) {
    String v = s == null ? "" : s.strip().toUpperCase(Locale.ROOT);
    if (!SportsSources.SPORTS.contains(v)) throw new ValidationException(List.of(new ValidationException.Field("sport", "invalid")));
    return v;
  }

  private SportsAdminApi.SyncResult syncOne(String sport, Duration queueWait) {
    ScoreSource src = sources.primary(sport);
    if (!sources.usable(src)) {
      return new SportsAdminApi.SyncResult(sport, src.name(), "disabled", 0, 0, 0, 0, 0, sources.unusableReason(src));
    }
    LocalDate from = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    LocalDate to = from.plusDays(props.getHorizonDays());
    List<ScoreFixture> all = new ArrayList<>();
    String cursor = null;
    String error = null;
    String outcome = "ok";
    SourceGateway gw = sources.gateway();
    for (int page = 0; page < MAX_PAGES; page++) {
      SourceReply<ScoreSource.SchedulePage> r = gw.call(src.schedule(gw.config(src.key()), sport, from, to, cursor), List.of(), queueWait);
      if (!r.ok()) {
        outcome = r.outcome().name().toLowerCase(Locale.ROOT);
        error = r.error();
        break;
      }
      all.addAll(r.value().fixtures());
      cursor = r.value().nextCursor();
      if (cursor == null) break;
    }
    int created = 0;
    int updated = 0;
    int moved = 0;
    for (ScoreFixture f : all) {
      if (!sport.equals(f.sport())) continue;
      try {
        switch (upsert(src, f)) {
          case "created" -> created++;
          case "updated" -> updated++;
          case "moved" -> {
            updated++;
            moved++;
          }
          default -> {}
        }
      } catch (RuntimeException e) {
        log.error("赛程 {} {} 保存失败", src.name(), f.ref(), e);
      }
    }
    int searched = 0;
    if (sources.usable(sources.backup())) {
      for (SportsRows.Fixture f : mapper.needCandidates(now(), Math.max(0, props.getCandidateBatch()))) {
        if (!sport.equals(f.sport)) continue;
        searchCandidates(f, null, queueWait);
        searched++;
      }
    }
    log.info("赛程同步 {}（{}）：拿到 {} 场，新增 {}、更新 {}、开赛时间变了 {}，找候选 {} 场{}", sport, src.name(), all.size(), created, updated, moved, searched,
        error == null ? "" : "；" + error);
    return new SportsAdminApi.SyncResult(sport, src.name(), outcome, all.size(), created, updated, moved, searched, error);
  }

  /** 新增或更新一场（各自一条语句提交）；返回 created / updated / moved（开赛时间变了）/ same */
  private String upsert(ScoreSource src, ScoreFixture f) {
    LocalDateTime now = now();
    LocalDateTime kickoff = ldt(f.kickoffAt());
    SportsRows.Fixture old = mapper.fixtureByPrimary(src.name(), f.ref());
    if (old == null) {
      SportsRows.Fixture n = new SportsRows.Fixture();
      n.publicNo = ids.next();
      n.sport = f.sport();
      fill(n, f);
      n.originalKickoffAt = kickoff;
      n.primarySource = src.name();
      n.primaryRef = f.ref();
      n.fetchedAt = now;
      n.createdAt = now;
      n.updatedAt = now;
      try {
        mapper.insertFixture(n);
        return "created";
      } catch (DuplicateKeyException e) {
        old = mapper.fixtureByPrimary(src.name(), f.ref()); // 别的实例同时同步
        if (old == null) throw e;
      }
    }
    boolean moved = !kickoff.equals(old.kickoffAt);
    boolean changed = moved || !Objects.equals(old.homeName, f.homeName()) || !Objects.equals(old.awayName, f.awayName())
        || !Objects.equals(old.primaryStatus, f.status().name()) || !Objects.equals(old.primaryRawStatus, cut(f.rawStatus(), 40))
        || !Objects.equals(old.competitionCode, f.competitionCode());
    LocalDateTime oldKickoff = old.kickoffAt;
    fill(old, f);
    old.fetchedAt = now;
    old.updatedAt = now;
    mapper.updateFromSync(old);
    if (moved) alertKickoff(old, oldKickoff, kickoff, src);
    return moved ? "moved" : changed ? "updated" : "same";
  }

  private static void fill(SportsRows.Fixture n, ScoreFixture f) {
    n.competitionCode = f.competitionCode();
    n.competitionName = cut(f.competitionName(), 100);
    n.leagueRef = f.competitionCode() == null ? null : TheSportsDb.LEAGUES.get(f.competitionCode());
    n.homeName = cut(f.homeName(), 100);
    n.homeShort = cut(f.homeShort(), 60);
    n.awayName = cut(f.awayName(), 100);
    n.awayShort = cut(f.awayShort(), 60);
    n.kickoffAt = ldt(f.kickoffAt());
    n.primaryStatus = f.status().name();
    n.primaryRawStatus = cut(f.rawStatus(), 40);
  }

  /** 开赛时间变了：这场比赛有还没结束的市场时提醒运营（同一个新时间只提醒一次，E14） */
  private void alertKickoff(SportsRows.Fixture f, LocalDateTime oldKickoff, LocalDateTime newKickoff, ScoreSource src) {
    List<String> open = new ArrayList<>();
    for (SportsRows.FixtureMarket fm : mapper.fixtureMarkets(f.id)) {
      live(fm.marketId).filter(m -> !m.status().terminal()).ifPresent(m -> open.add(m.publicNo() + "（" + m.status().api() + "）"));
    }
    if (open.isEmpty()) return;
    if (mapper.markKickoffAlerted(f.id, newKickoff, now()) != 1) return;
    Map<String, String> v = new LinkedHashMap<>();
    v.put("fixture", f.homeName + " vs " + f.awayName);
    v.put("oldKickoff", SportsTemplates.DATE_HM.format(oldKickoff.toInstant(ZoneOffset.UTC)) + " UTC");
    v.put("newKickoff", SportsTemplates.DATE_HM.format(newKickoff.toInstant(ZoneOffset.UTC)) + " UTC");
    v.put("source", src.label());
    v.put("markets", String.join("、", open));
    log.warn("比赛 {} vs {} 的开赛时间从 {} 改成了 {}，关联市场 {}", f.homeName, f.awayName, oldKickoff, newKickoff, open);
    try {
      staff.notifyStaff("SPORTS_KICKOFF_CHANGED", v);
    } catch (RuntimeException e) {
      log.error("开赛时间变化的提醒发送失败", e);
    }
  }

  // =====================================================================
  // 候选和对应
  // =====================================================================

  /**
   * 找候选（不在事务里）。manualRef 不为空时只查这个编号，查到后合进已有的候选。
   *
   * @return 更新后的赛程行
   */
  SportsRows.Fixture searchCandidates(SportsRows.Fixture f, String manualRef, Duration queueWait) {
    TheSportsDb b = sources.backup();
    if (!sources.usable(b)) {
      mapper.setCandidates(f.id, f.candidates, cut("备源" + sources.unusableReason(b), 500), now());
      return mapper.fixture(f.id);
    }
    SourceGateway gw = sources.gateway();
    Candidates.Target target = new Candidates.Target(f.homeName, f.homeShort, f.awayName, f.awayShort, instant(f.kickoffAt));
    List<Candidates.Candidate> found = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    if (manualRef != null) {
      SourceReply<ScoreFixture> r = sources.fixture(b, f.sport, manualRef, List.of(), queueWait);
      if (r.ok()) found.add(Candidates.score(target, r.value()));
      else errors.add("查编号 " + manualRef + "：" + r.error());
      found.addAll(stored(f));
    } else {
      LocalDate date = f.kickoffAt.toLocalDate();
      if (f.leagueRef != null) {
        SourceReply<List<ScoreFixture>> r = gw.call(b.day(gw.config(b.key()), f.sport, f.leagueRef, date), List.of(), queueWait);
        if (r.ok()) r.value().forEach(c -> found.add(Candidates.score(target, c)));
        else errors.add("按联赛找：" + r.error());
      }
      if (!good(target, found)) {
        List<String[]> names = new ArrayList<>();
        names.add(new String[] {TeamNames.display(f.homeName), TeamNames.display(f.awayName)});
        if (f.homeShort != null && f.awayShort != null && !(f.homeShort.equals(names.get(0)[0]) && f.awayShort.equals(names.get(0)[1]))) {
          names.add(new String[] {f.homeShort, f.awayShort});
        }
        for (String[] n : names) {
          SourceReply<List<ScoreFixture>> r = gw.call(b.searchByNames(gw.config(b.key()), f.sport, n[0], n[1], date), List.of(), queueWait);
          if (r.ok()) r.value().forEach(c -> found.add(Candidates.score(target, c)));
          else errors.add("按队名找：" + r.error());
          if (good(target, found)) break;
        }
      }
    }
    List<Candidates.Candidate> ranked = Candidates.rank(target, found);
    String err = ranked.isEmpty() && !errors.isEmpty() ? cut(String.join("；", errors), 500) : ranked.isEmpty() ? "备源里没有找到这场比赛" : null;
    mapper.setCandidates(f.id, json.writeValueAsString(ranked), err, now());
    return mapper.fixture(f.id);
  }

  private static boolean good(Candidates.Target t, List<Candidates.Candidate> found) {
    return Candidates.rank(t, found).stream().anyMatch(Candidates.Candidate::suggested);
  }

  List<Candidates.Candidate> stored(SportsRows.Fixture f) {
    if (f.candidates == null || f.candidates.isBlank()) return List.of();
    return json.readValue(f.candidates, new TypeReference<List<Candidates.Candidate>>() {});
  }

  /** 确认对应（开赛以前；ref 必须是候选里的） */
  SportsRows.Fixture confirmMapping(long fixtureNo, String ref, long adminId) {
    SportsRows.Fixture f0 = byNo(fixtureNo);
    String r = ref == null ? "" : ref.strip();
    if (r.isEmpty()) throw new ValidationException(List.of(new ValidationException.Field("ref", "required")));
    return tx.execute(st -> {
      SportsRows.Fixture f = mapper.lockFixture(f0.id);
      LocalDateTime now = now();
      if (!f.kickoffAt.isAfter(now)) throw new BizException("FIXTURE_STARTED", "比赛已经开始，不能再改对应");
      Candidates.Candidate c = stored(f).stream().filter(x -> x.ref().equals(r)).findFirst()
          .orElseThrow(() -> new ValidationException(List.of(new ValidationException.Field("ref", "notCandidate"))));
      mapper.confirmMapping(f.id, c.source(), c.ref(), c.swapped(), cut(c.home(), 100), cut(c.away(), 100),
          c.kickoffAt() == null ? null : ldt(Instant.parse(c.kickoffAt())), adminId, now);
      log.info("比赛 {}（{} vs {}）确认对应 {} {}{}，管理员 {}", f.publicNo, f.homeName, f.awayName, c.source(), c.ref(), c.swapped() ? "（主客对调）" : "", adminId);
      return mapper.fixture(f.id);
    });
  }

  SportsRows.Fixture clearMapping(long fixtureNo, long adminId) {
    SportsRows.Fixture f0 = byNo(fixtureNo);
    return tx.execute(st -> {
      SportsRows.Fixture f = mapper.lockFixture(f0.id);
      if (!f.kickoffAt.isAfter(now())) throw new BizException("FIXTURE_STARTED", "比赛已经开始，不能再改对应");
      if (mapper.clearMapping(f.id, now()) != 1) throw new BizException("NOT_MAPPED", "这场比赛还没有确认对应");
      log.info("比赛 {} 取消对应，管理员 {}", f.publicNo, adminId);
      return mapper.fixture(f.id);
    });
  }

  SportsRows.Fixture byNo(long fixtureNo) {
    SportsRows.Fixture f = mapper.fixtureByNo(fixtureNo);
    if (f == null) throw new BizException("NOT_FOUND", "比赛不存在");
    return f;
  }

  // =====================================================================
  // 视图
  // =====================================================================

  SportsAdminApi.Page<SportsAdminApi.Fixture> list(String sport, String mapping, String from, String to, int page, int pageSize) {
    ValidationException.Collector errs = new ValidationException.Collector();
    String s = null;
    if (sport != null && !sport.isBlank()) {
      s = sport.strip().toUpperCase(Locale.ROOT);
      if (!SportsSources.SPORTS.contains(s)) errs.add("sport", "invalid");
    }
    String mp = mapping == null || mapping.isBlank() ? null : mapping.strip().toLowerCase(Locale.ROOT);
    if (mp != null && !mp.equals("confirmed") && !mp.equals("unconfirmed")) errs.add("mapping", "invalid");
    LocalDateTime f = parse(errs, "from", from, now());
    LocalDateTime t = parse(errs, "to", to, f.plusDays(props.getHorizonDays()));
    errs.throwIfAny();
    int p = Math.max(1, page);
    int size = Math.min(100, Math.max(1, pageSize));
    List<SportsAdminApi.Fixture> items = mapper.fixtures(s, mp, f, t, (p - 1) * size, size).stream().map(this::view).toList();
    return new SportsAdminApi.Page<>(items, mapper.countFixtures(s, mp, f, t), p, size);
  }

  private static LocalDateTime parse(ValidationException.Collector errs, String field, String v, LocalDateTime dflt) {
    if (v == null || v.isBlank()) return dflt;
    try {
      return ldt(Instant.parse(v.strip()));
    } catch (DateTimeParseException e) {
      errs.add(field, "invalid");
      return dflt;
    }
  }

  Optional<SportsAdminApi.Fixture> detail(long fixtureNo) {
    return Optional.ofNullable(mapper.fixtureByNo(fixtureNo)).map(this::view);
  }

  SportsAdminApi.Fixture view(SportsRows.Fixture f) {
    SportsAdminApi.Mapping mapping = f.backupRef == null ? null
        : new SportsAdminApi.Mapping(f.backupSource, f.backupRef, f.backupSwapped, f.backupHome, f.backupAway, iso(f.backupKickoffAt), f.mappingConfirmedBy,
            iso(f.mappingConfirmedAt));
    List<SportsAdminApi.Candidate> cands = stored(f).stream()
        .map(c -> new SportsAdminApi.Candidate(c.source(), c.ref(), c.home(), c.away(), c.kickoffAt(), c.league(), c.score(), c.swapped(), c.suggested())).toList();
    List<SportsAdminApi.MarketLink> links = new ArrayList<>();
    for (SportsRows.FixtureMarket fm : mapper.fixtureMarkets(f.id)) {
      live(fm.marketId).ifPresent(m -> links.add(new SportsAdminApi.MarketLink(String.valueOf(m.publicNo()), fm.play,
          fm.line == null ? null : fm.line.stripTrailingZeros().toPlainString(), m.status().api())));
    }
    return new SportsAdminApi.Fixture(String.valueOf(f.publicNo), f.sport, f.competitionCode, f.competitionName,
        new SportsAdminApi.Team(f.homeName, f.homeShort), new SportsAdminApi.Team(f.awayName, f.awayShort), iso(f.kickoffAt), iso(f.originalKickoffAt),
        !f.kickoffAt.equals(f.originalKickoffAt), f.primaryStatus.toLowerCase(Locale.ROOT), f.primaryRawStatus, f.primarySource, f.primaryRef, mapping, cands,
        iso(f.candidatesAt), f.candidatesError, links, iso(f.fetchedAt));
  }

  /** 没被删除的市场（findById 也会找到删除的草稿，按对外编号再找一次排除掉） */
  Optional<MarketApi.MarketRef> live(long marketId) {
    return markets.findById(marketId).flatMap(m -> markets.find(m.publicNo()));
  }

  /** 有效的市场（没取消、没作废） */
  static boolean active(MarketApi.MarketRef m) {
    return m.status() != MarketStatus.CANCELLED && m.status() != MarketStatus.VOIDED;
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

  static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
