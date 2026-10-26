package com.forecast.app.resolve.sports;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.resolve.api.SportsAdminApi;
import com.forecast.app.resolve.source.ScoreSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 从赛程一键生成市场草稿（forecast S8；需求说明书 v1.3 第 4.2 节：选比赛和玩法一键生成草稿，自动填队名、时间、规则模板；大小球、大小分的分界线必须是 .5（E8）；
 * 运营检查后提交上架，由有 market:publish 的人上架。MQ-10：冷启动期默认勾选大小球 2.5、NBA 大小分——"默认勾选"是页面的事（S9），接口按请求的玩法生成）。
 * <ul>
 *   <li>时间：开放下注 = 现在（上架即开放）；截止 = 开赛 − 参数 {@code sports.closeLeadMinutes.*}（默认 5 分钟）；事件开始 = 开赛；
 *       事件结束 = 开赛 + {@code sports.eventMinutes.*}（足球 120、NBA 180 分钟，到了进入待裁决、开始看数据源是否完赛）。</li>
 *   <li>裁决方式自动；来源、题目、规则、选项按玩法的模板渲染（三种语言）；费率、单客户上限按品类默认值（和手工新建一样）。</li>
 *   <li>{@code source_ref}：{@code {kind: SPORTS, sport, play, line, fixtureNo, primarySource, primaryRef, options: {结果: 选项序号}}}，自动裁决按它判定。
 *       备源的对应不写进草稿：进入待裁决时按当时确认的对应建进度（所以生成草稿以后、开赛以前确认对应也来得及）。</li>
 * </ul>
 * 整个生成在一个事务里：先锁赛程行（同一场比赛同时生成排队），再查重复（同一玩法同一分界线已经有有效市场的不再生成），再逐个建草稿（market 的 createDraft 加入这个事务）。
 */
@Service
class SportsDrafts {

  private static final Logger log = LoggerFactory.getLogger(SportsDrafts.class);

  private final SportsMapper mapper;
  private final FixtureService fixtures;
  private final SportsTemplates templates;
  private final SportsSources sources;
  private final SportsProperties props;
  private final MarketAdminApi marketAdmin;
  private final MarketApi markets;
  private final SystemConfig config;
  private final Clock clock;
  private final TransactionTemplate tx;

  SportsDrafts(SportsMapper mapper, FixtureService fixtures, SportsTemplates templates, SportsSources sources, SportsProperties props,
      MarketAdminApi marketAdmin, MarketApi markets, SystemConfig config, Clock clock, PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.fixtures = fixtures;
    this.templates = templates;
    this.sources = sources;
    this.props = props;
    this.marketAdmin = marketAdmin;
    this.markets = markets;
    this.config = config;
    this.clock = clock;
    this.tx = new TransactionTemplate(tm);
  }

  record Want(Play play, BigDecimal line) {}

  SportsAdminApi.Generated generate(long fixtureNo, List<SportsAdminApi.PlayInput> plays, long adminId) {
    SportsRows.Fixture f0 = fixtures.byNo(fixtureNo);
    List<SportsAdminApi.MarketLink> created = tx.execute(st -> {
      SportsRows.Fixture f = mapper.lockFixture(f0.id);
      Instant now = Instant.now(clock);
      List<Want> wants = check(f, plays, now);
      List<SportsAdminApi.MarketLink> out = new ArrayList<>();
      for (Want w : wants) out.add(create(f, w, now, adminId));
      return out;
    });
    List<String> warnings = new ArrayList<>();
    SportsRows.Fixture f = mapper.fixture(f0.id);
    if (f.backupRef == null) warnings.add("mappingNotConfirmed");
    if (!sources.usable(sources.backup())) warnings.add("backupDisabled");
    log.info("比赛 {}（{} vs {}）生成草稿 {}，管理员 {}", f.publicNo, f.homeName, f.awayName, created.stream().map(SportsAdminApi.MarketLink::marketNo).toList(), adminId);
    return new SportsAdminApi.Generated(String.valueOf(f.publicNo), created, warnings);
  }

  /** 一次列出全部错误（C56）：玩法、品类、分界线、重复、时间 */
  private List<Want> check(SportsRows.Fixture f, List<SportsAdminApi.PlayInput> plays, Instant now) {
    ValidationException.Collector errs = new ValidationException.Collector();
    List<SportsAdminApi.PlayInput> in = plays == null ? List.of() : plays;
    if (in.isEmpty()) errs.add("plays", "required");
    if (in.size() > 10) errs.add("plays", "max10");
    List<Want> wants = new ArrayList<>();
    for (int i = 0; i < in.size(); i++) {
      String field = "plays[" + i + "]";
      SportsAdminApi.PlayInput p = in.get(i);
      Play play = p == null ? null : Play.of(p.play());
      if (play == null) {
        errs.add(field + ".play", p == null || p.play() == null || p.play().isBlank() ? "required" : "invalid");
        continue;
      }
      if (!play.sport.equals(f.sport)) {
        errs.add(field + ".play", "wrongSport");
        continue;
      }
      BigDecimal line = null;
      if (play.needsLine) {
        String s = p.line() == null ? "" : p.line().strip();
        if (s.isEmpty()) {
          line = play.defaultLine;
          if (line == null) errs.add(field + ".line", "required");
        } else {
          try {
            line = new BigDecimal(s);
            if (!Play.validLine(line)) errs.add(field + ".line", "notHalf");
          } catch (NumberFormatException e) {
            errs.add(field + ".line", "invalid");
          }
        }
        if (line != null && !Play.validLine(line)) line = null;
      } else if (p.line() != null && !p.line().isBlank()) {
        errs.add(field + ".line", "notAllowed");
      }
      if (play.needsLine && line == null) continue;
      Want w = new Want(play, line == null ? null : line.setScale(1, java.math.RoundingMode.UNNECESSARY));
      if (wants.contains(w)) {
        errs.add(field, "duplicate");
        continue;
      }
      boolean exists = mapper.marketsOf(f.id, play.name(), w.line()).stream().anyMatch(id -> fixtures.live(id).filter(FixtureService::active).isPresent());
      if (exists) errs.add(field, "exists");
      wants.add(w);
    }
    Instant kickoff = FixtureService.instant(f.kickoffAt);
    if (!kickoff.minus(closeLead(f.sport)).isAfter(now.plus(Duration.ofMinutes(1)))) errs.add("kickoffAt", "tooLate");
    errs.throwIfAny();
    return wants;
  }

  private Duration closeLead(String sport) {
    return Duration.ofMinutes(config.integer(ScoreSource.NBA.equals(sport) ? ConfigKey.SPORTS_CLOSE_LEAD_NBA : ConfigKey.SPORTS_CLOSE_LEAD_FOOTBALL, 5));
  }

  private Duration eventLength(String sport) {
    return ScoreSource.NBA.equals(sport) ? Duration.ofMinutes(config.integer(ConfigKey.SPORTS_EVENT_MINUTES_NBA, 180))
        : Duration.ofMinutes(config.integer(ConfigKey.SPORTS_EVENT_MINUTES_FOOTBALL, 120));
  }

  private SportsAdminApi.MarketLink create(SportsRows.Fixture f, Want w, Instant now, long adminId) {
    SportsTemplates.Loaded t = templates.load(w.play());
    Instant kickoff = FixtureService.instant(f.kickoffAt);
    Instant close = kickoff.minus(closeLead(f.sport));
    Instant open = now.truncatedTo(ChronoUnit.SECONDS);
    String line = w.line() == null ? null : w.line().toPlainString();
    ScoreSource primary = sources.byName(f.primarySource);
    Map<String, String> vars = SportsTemplates.vars(f.homeName, f.awayName, f.competitionName, kickoff, close, line, primary.label(),
        sources.backup().label(), props.getFinishWait().toMinutes());
    List<MarketAdminApi.OptionInput> options = new ArrayList<>();
    Map<String, Object> outcomeToSeq = new LinkedHashMap<>();
    int seq = 1;
    for (SportsTemplates.Option o : t.options()) {
      options.add(new MarketAdminApi.OptionInput(SportsTemplates.render(o.label(), vars), SportsTemplates.render(o.winCondition(), vars)));
      outcomeToSeq.put(o.outcome(), seq++);
    }
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("kind", SportsResolver.KIND);
    ref.put("sport", f.sport);
    ref.put("play", w.play().name());
    ref.put("line", line);
    ref.put("fixtureNo", String.valueOf(f.publicNo));
    ref.put("primarySource", f.primarySource);
    ref.put("primaryRef", f.primaryRef);
    ref.put("options", outcomeToSeq);
    SportsRows.Template row = t.row();
    I18nText title = SportsTemplates.render(new I18nText(row.titleEn, row.titleZhCn, row.titleZhTw), vars);
    I18nText rules = SportsTemplates.render(new I18nText(row.rulesEn, row.rulesZhCn, row.rulesZhTw), vars);
    MarketAdminApi.DraftInput in = new MarketAdminApi.DraftInput(f.sport, title, rules, "AUTO", SportsTemplates.render(row.sourcePrimary, vars),
        SportsTemplates.render(row.sourceBackup, vars), ref, open.toString(), close.toString(), kickoff.toString(), kickoff.plus(eventLength(f.sport)).toString(),
        null, null, null, options);
    MarketAdminApi.Detail d = marketAdmin.createDraft(in, adminId);
    long marketId = markets.find(Long.parseLong(d.publicNo())).orElseThrow().marketId();
    SportsRows.FixtureMarket fm = new SportsRows.FixtureMarket();
    fm.marketId = marketId;
    fm.fixtureId = f.id;
    fm.play = w.play().name();
    fm.line = w.line();
    fm.createdBy = adminId;
    fm.createdAt = LocalDateTime.ofInstant(now.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    mapper.insertFixtureMarket(fm);
    return new SportsAdminApi.MarketLink(d.publicNo(), w.play().name(), line, d.status());
  }
}
