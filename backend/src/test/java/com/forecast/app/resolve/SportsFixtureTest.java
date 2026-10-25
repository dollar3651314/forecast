package com.forecast.app.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.resolve.api.SportsAdminApi;
import com.forecast.app.support.ScoreBook;
import com.forecast.app.support.StubHttp.Reply;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 体育赛程（forecast S8；需求说明书 v1.3 第 4.2 节"从体育数据源拉取未来 7 天的赛程，选比赛和玩法一键生成草稿，自动填队名、时间、规则模板"；
 * 技术方案第 6.1 节 X-12"两个来源的比赛编号由运营确认对应"；MQ-10 大小球、大小分）。替身数据源，真实的网关、库和 market 模块。
 */
class SportsFixtureTest extends SportsTestSupport {

  long no(Fx f) {
    return Long.parseLong(f.fixtureNo());
  }

  @Test
  void 同步未来7天赛程_新增和更新_开赛时间变了且有市场时提醒运营一次() throws Exception {
    Instant k1 = in(Duration.ofDays(2));
    Fx f = football(k1);
    // 7 天以外的不同步
    String far = ref();
    BOOK.add("FOOTBALL_DATA", far, "FOOTBALL", "Chelsea FC", "Liverpool FC", in(Duration.ofDays(9)));
    List<SportsAdminApi.SyncResult> r = sportsAdmin.sync("FOOTBALL");
    assertThat(r).hasSize(1);
    assertThat(r.get(0).outcome()).isEqualTo("ok");
    assertThat(r.get(0).source()).isEqualTo("FOOTBALL_DATA");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resolve_fixture WHERE primary_ref = ?", Integer.class, far)).isZero();
    SportsAdminApi.Fixture d = sportsAdmin.fixture(no(f)).orElseThrow();
    assertThat(d.sport()).isEqualTo("FOOTBALL");
    assertThat(d.competition()).isEqualTo("PL");
    assertThat(d.home().name()).isEqualTo("Arsenal FC");
    assertThat(d.home().shortName()).isEqualTo("Arsenal");
    assertThat(d.kickoffAt()).isEqualTo(k1.toString());
    assertThat(d.status()).isEqualTo("scheduled");
    assertThat(d.kickoffChanged()).isFalse();
    assertThat(d.mapping()).isNull();
    // 没有市场时开赛时间变了不提醒
    f.primary().kickoff = k1.plus(Duration.ofMinutes(30));
    SportsAdminApi.SyncResult moved = sportsAdmin.sync("FOOTBALL").get(0);
    assertThat(moved.kickoffChanged()).isGreaterThanOrEqualTo(1);
    verify(staff, times(0)).notifyStaff(eq("SPORTS_KICKOFF_CHANGED"), argThat(p -> p.get("markets") != null && p.get("fixture").contains("Arsenal")
        && p.get("newKickoff").startsWith(SportsTemplatesFmt.hm(k1.plus(Duration.ofMinutes(30))))));
    // 生成并上架一个市场，再改开赛时间：提醒运营（同一个新时间只提醒一次）
    confirm(f);
    Market m = listed(f, "FOOTBALL_1X2", null);
    Instant k2 = k1.minus(Duration.ofHours(2));
    f.primary().kickoff = k2;
    sportsAdmin.sync("FOOTBALL");
    sportsAdmin.sync("FOOTBALL");
    verify(staff, times(1)).notifyStaff(eq("SPORTS_KICKOFF_CHANGED"), argThat(p -> p.get("markets").contains(m.no()) && p.get("oldKickoff").contains("UTC")
        && p.get("newKickoff").startsWith(SportsTemplatesFmt.hm(k2))));
    d = sportsAdmin.fixture(no(f)).orElseThrow();
    assertThat(d.kickoffAt()).isEqualTo(k2.toString());
    assertThat(d.originalKickoffAt()).isEqualTo(k1.toString());
    assertThat(d.kickoffChanged()).isTrue();
    assertThat(d.markets()).extracting(SportsAdminApi.MarketLink::marketNo).containsExactly(m.no());
    // 状态跟着变
    f.primary().status("POSTPONED");
    sportsAdmin.sync("FOOTBALL");
    assertThat(sportsAdmin.fixture(no(f)).orElseThrow().status()).isEqualTo("postponed");
  }

  @Test
  void NBA从balldontlie同步() {
    Fx f = nba(in(Duration.ofDays(1)));
    SportsAdminApi.Fixture d = sportsAdmin.fixture(no(f)).orElseThrow();
    assertThat(d.sport()).isEqualTo("NBA");
    assertThat(d.primarySource()).isEqualTo("BALLDONTLIE");
    assertThat(d.home().name()).isEqualTo("Los Angeles Lakers");
    assertThat(STUB.headers().stream().filter(h -> h.containsKey("authorization")).findFirst().orElseThrow()).containsEntry("authorization", "test-key");
  }

  @Test
  void 来源未开通_同步返回disabled_不调用() {
    resolveProps.getSources().get("football-data").setKey(null);
    STUB.clear();
    SportsAdminApi.SyncResult r = sportsAdmin.sync("FOOTBALL").get(0);
    assertThat(r.outcome()).isEqualTo("disabled");
    assertThat(r.error()).contains("未开通").contains("FC_SRC_FOOTBALL_DATA_TOKEN");
    assertThat(STUB.hits()).isZero();
    // 两个品类一起同步：互不影响
    assertThat(sportsAdmin.sync(null)).extracting(SportsAdminApi.SyncResult::sport).containsExactly("FOOTBALL", "NBA");
  }

  @Test
  void 同步失败_返回原因_不报错() {
    BOOK.fault("FOOTBALL_DATA", Reply.status(500, "oops"));
    SportsAdminApi.SyncResult r = sportsAdmin.sync("FOOTBALL").get(0);
    assertThat(r.outcome()).isEqualTo("failed");
    assertThat(r.error()).isEqualTo("HTTP 500");
    assertThatThrownBy(() -> sportsAdmin.sync("TENNIS")).isInstanceOf(ValidationException.class);
  }

  @Test
  void 候选_同步时自动找_按联赛和日期_推荐同一场_运营确认() {
    Instant k = in(Duration.ofDays(1));
    Fx f = fixture("FOOTBALL", "Manchester United FC", "AFC Bournemouth", "Manchester United", "Bournemouth", k);
    // 同一天英超的另一场（干扰项）
    BOOK.add("THESPORTSDB", ref(), "FOOTBALL", "Sunderland", "Leeds United", k.plus(Duration.ofHours(2)));
    SportsAdminApi.Fixture d = sportsAdmin.searchCandidates(no(f), null);
    assertThat(d.candidates()).isNotEmpty();
    SportsAdminApi.Candidate top = d.candidates().get(0);
    assertThat(top.ref()).isEqualTo(f.backupRef());
    assertThat(top.suggested()).isTrue();
    assertThat(top.score()).isEqualTo(1.0);
    assertThat(d.candidates().stream().filter(SportsAdminApi.Candidate::suggested)).hasSize(1);
    assertThat(d.mapping()).as("只是建议，不自动确认").isNull();
    // 不是候选的编号不能确认
    assertThatThrownBy(() -> sportsAdmin.confirmMapping(no(f), "424242", 1)).isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(((ValidationException) e).fields()).extracting(ValidationException.Field::code).containsExactly("notCandidate"));
    SportsAdminApi.Fixture c = sportsAdmin.confirmMapping(no(f), f.backupRef(), 7);
    assertThat(c.mapping().ref()).isEqualTo(f.backupRef());
    assertThat(c.mapping().source()).isEqualTo("THESPORTSDB");
    assertThat(c.mapping().confirmedBy()).isEqualTo(7L);
    assertThat(c.mapping().swapped()).isFalse();
    assertThat(sportsAdmin.fixtures("FOOTBALL", "confirmed", null, null, 1, 100).items()).extracting(SportsAdminApi.Fixture::fixtureNo).contains(f.fixtureNo());
    assertThat(sportsAdmin.fixtures("FOOTBALL", "unconfirmed", null, null, 1, 100).items()).extracting(SportsAdminApi.Fixture::fixtureNo)
        .doesNotContain(f.fixtureNo());
    // 取消对应
    assertThat(sportsAdmin.clearMapping(no(f), 7).mapping()).isNull();
    assertThatThrownBy(() -> sportsAdmin.clearMapping(no(f), 7)).isInstanceOf(BizException.class).hasFieldOrPropertyWithValue("code", "NOT_MAPPED");
  }

  @Test
  void 候选_不在对照表的赛事按队名找_主客对调标出_手工加编号() {
    Instant k = in(Duration.ofDays(1));
    String pr = ref();
    String br = ref();
    ScoreBook.Game p = BOOK.add("FOOTBALL_DATA", pr, "FOOTBALL", "Paris Saint-Germain FC", "Olympique de Marseille", k);
    p.competition = "XYZ";
    // 备源把主客队写反了（例如中立场地）
    BOOK.add("THESPORTSDB", br, "FOOTBALL", "Marseille", "Paris SG", k);
    sportsAdmin.sync("FOOTBALL");
    String fixtureNo = fixtureNo("FOOTBALL_DATA", pr);
    STUB.clear();
    SportsAdminApi.Fixture d = sportsAdmin.searchCandidates(Long.parseLong(fixtureNo), null);
    assertThat(STUB.requests()).as("没有联赛编号，只按队名找").allMatch(u -> u.getPath().endsWith("searchevents.php"));
    // 按"主队 vs 客队"找不到写反了的 → 没有候选
    assertThat(d.candidates()).isEmpty();
    assertThat(d.candidatesError()).contains("没有找到");
    // 运营在 TheSportsDB 上找到编号，手工加
    d = sportsAdmin.searchCandidates(Long.parseLong(fixtureNo), br);
    assertThat(d.candidates()).hasSize(1);
    assertThat(d.candidates().get(0).swapped()).isTrue();
    assertThat(sportsAdmin.confirmMapping(Long.parseLong(fixtureNo), br, 1).mapping().swapped()).isTrue();
  }

  @Test
  void 备源未开通_找候选记下原因() {
    resolveProps.getSources().get("thesportsdb").setKey("");
    Fx f = football(in(Duration.ofDays(1)));
    SportsAdminApi.Fixture d = sportsAdmin.searchCandidates(no(f), null);
    assertThat(d.candidates()).isEmpty();
    assertThat(d.candidatesError()).contains("TheSportsDB").contains("未开通").contains("FC_SRC_THESPORTSDB_KEY");
    // 生成草稿照常，但提示到时只有一个来源
    SportsAdminApi.Generated g = sportsAdmin.generate(no(f), List.of(new SportsAdminApi.PlayInput("FOOTBALL_1X2", null)), 1);
    assertThat(g.warnings()).containsExactlyInAnyOrder("mappingNotConfirmed", "backupDisabled");
  }

  @Test
  void 开赛以后不能改对应() {
    Fx f = football(in(Duration.ofHours(1)));
    sportsAdmin.searchCandidates(no(f), null);
    advance(Duration.ofHours(1));
    assertThatThrownBy(() -> sportsAdmin.confirmMapping(no(f), f.backupRef(), 1)).isInstanceOf(BizException.class)
        .hasFieldOrPropertyWithValue("code", "FIXTURE_STARTED");
  }

  @Test
  void 一键生成草稿_胜平负和大小球_自动填队名时间三语规则_提交上架后由有权限的人上架() throws Exception {
    Instant k = in(Duration.ofDays(2));
    Fx f = football(k);
    confirm(f);
    SportsAdminApi.Generated g = sportsAdmin.generate(no(f), List.of(new SportsAdminApi.PlayInput("FOOTBALL_1X2", null),
        new SportsAdminApi.PlayInput("FOOTBALL_OU", null)), 9);
    assertThat(g.warnings()).isEmpty();
    assertThat(g.created()).extracting(SportsAdminApi.MarketLink::play).containsExactly("FOOTBALL_1X2", "FOOTBALL_OU");
    assertThat(g.created().get(1).line()).as("大小球默认 2.5（MQ-10）").isEqualTo("2.5");
    assertThat(g.created()).extracting(SportsAdminApi.MarketLink::status).containsOnly("draft");
    MarketAdminApi.Detail x = admin.detail(Long.parseLong(g.created().get(0).marketNo())).orElseThrow();
    assertThat(x.category()).isEqualTo("FOOTBALL");
    assertThat(x.status()).as("除了加密，自动生成的不会自动上架").isEqualTo("draft");
    assertThat(x.createdBy()).isEqualTo(9L);
    assertThat(x.resolutionMode()).isEqualTo("AUTO");
    assertThat(x.title().zhCN()).isEqualTo("Arsenal FC vs Everton FC（90 分钟胜平负）");
    assertThat(x.title().en()).isEqualTo("Arsenal FC vs Everton FC: 90-minute result");
    assertThat(x.title().zhTW()).isEqualTo("Arsenal FC vs Everton FC（90 分鐘勝平負）");
    for (I18nText t : List.of(x.title(), x.rules())) {
      for (String s : List.of(t.en(), t.zhCN(), t.zhTW())) assertThat(s).doesNotContain("{").doesNotContain("}");
    }
    assertThat(x.rules().zhCN()).contains("Premier League").contains(SportsTemplatesFmt.date(k)).contains("football-data.org").contains("TheSportsDB")
        .contains("完赛 30 分钟后").contains("截止下注：" + SportsTemplatesFmt.dateHm(k.minus(Duration.ofMinutes(5))) + " UTC");
    assertThat(x.sourcePrimary()).isEqualTo("football-data.org");
    assertThat(x.sourceBackup()).isEqualTo("TheSportsDB");
    assertThat(x.closeAt()).as("开赛前 5 分钟截止").isEqualTo(k.minus(Duration.ofMinutes(5)).toString());
    assertThat(x.eventStartAt()).isEqualTo(k.toString());
    assertThat(x.eventEndAt()).as("足球 + 120 分钟").isEqualTo(k.plus(Duration.ofMinutes(120)).toString());
    assertThat(Instant.parse(x.openAt())).isBeforeOrEqualTo(Instant.now(clock));
    assertThat(x.feeRate()).isEqualTo("5");
    assertThat(x.userCap()).isEqualTo("10000");
    assertThat(x.options()).extracting(o -> o.label().zhCN()).containsExactly("主胜 Arsenal FC", "平局", "客胜 Everton FC");
    assertThat(x.missing()).as("必填项齐全，可以提交上架").isEmpty();
    assertThat(x.sourceRef()).containsEntry("kind", "SPORTS").containsEntry("play", "FOOTBALL_1X2").containsEntry("fixtureNo", f.fixtureNo())
        .containsEntry("primarySource", "FOOTBALL_DATA").containsEntry("primaryRef", f.primaryRef()).containsEntry("options", Map.of("HOME", 1, "DRAW", 2, "AWAY", 3));
    MarketAdminApi.Detail ou = admin.detail(Long.parseLong(g.created().get(1).marketNo())).orElseThrow();
    assertThat(ou.title().zhCN()).isEqualTo("Arsenal FC vs Everton FC：总进球大于还是小于 2.5");
    assertThat(ou.options()).extracting(o -> o.label().zhCN()).containsExactly("大于 2.5 球", "小于 2.5 球");
    assertThat(ou.sourceRef()).containsEntry("line", "2.5").containsEntry("options", Map.of("OVER", 1, "UNDER", 2));
    // 运营提交上架 → 有 market:publish 的人勾选检查后上架
    admin.submit(Long.parseLong(x.publicNo()));
    assertThat(admin.publish(Long.parseLong(x.publicNo()), true, 1).status()).isEqualTo("listed");
    // 赛程详情里看得到生成的市场
    assertThat(sportsAdmin.fixture(no(f)).orElseThrow().markets()).extracting(SportsAdminApi.MarketLink::status).containsExactly("listed", "draft");
  }

  @Test
  void NBA胜负和大小分_分界线要填_事件结束是开赛加3小时() {
    Instant k = in(Duration.ofDays(1));
    Fx f = nba(k);
    SportsAdminApi.Generated g = sportsAdmin.generate(no(f), List.of(new SportsAdminApi.PlayInput("NBA_ML", null), new SportsAdminApi.PlayInput("NBA_TOTAL",
        "221.5")), 1);
    assertThat(g.warnings()).containsExactly("mappingNotConfirmed");
    MarketAdminApi.Detail ml = admin.detail(Long.parseLong(g.created().get(0).marketNo())).orElseThrow();
    assertThat(ml.category()).isEqualTo("NBA");
    assertThat(ml.title().zhCN()).isEqualTo("Los Angeles Lakers vs Boston Celtics：谁赢（含加时）");
    assertThat(ml.sourcePrimary()).isEqualTo("balldontlie.io");
    assertThat(ml.eventEndAt()).isEqualTo(k.plus(Duration.ofMinutes(180)).toString());
    assertThat(ml.options()).hasSize(2);
    MarketAdminApi.Detail tot = admin.detail(Long.parseLong(g.created().get(1).marketNo())).orElseThrow();
    assertThat(tot.options()).extracting(o -> o.label().en()).containsExactly("Over 221.5", "Under 221.5");
    assertThatThrownBy(() -> sportsAdmin.generate(no(f), List.of(new SportsAdminApi.PlayInput("NBA_TOTAL", null)), 1)).isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(fields(e)).containsExactly("plays[0].line=required"));
  }

  @Test
  void 生成草稿_一次列出全部错误_重复_分界线不是点5_品类不对_太晚() {
    Fx f = football(in(Duration.ofDays(1)));
    sportsAdmin.generate(no(f), List.of(new SportsAdminApi.PlayInput("FOOTBALL_OU", "2.5")), 1);
    assertThatThrownBy(() -> sportsAdmin.generate(no(f), List.of(new SportsAdminApi.PlayInput("FOOTBALL_OU", "3"), new SportsAdminApi.PlayInput("NBA_ML", null),
        new SportsAdminApi.PlayInput("TENNIS", null), new SportsAdminApi.PlayInput("FOOTBALL_1X2", "1.5"), new SportsAdminApi.PlayInput("FOOTBALL_OU", "2.5"),
        new SportsAdminApi.PlayInput("FOOTBALL_OU", "abc"), new SportsAdminApi.PlayInput("FOOTBALL_OU", "3.5"), new SportsAdminApi.PlayInput("FOOTBALL_OU", "3.5")),
        1)).isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(fields(e)).containsExactly("plays[0].line=notHalf", "plays[1].play=wrongSport", "plays[2].play=invalid",
            "plays[3].line=notAllowed", "plays[4]=exists", "plays[5].line=invalid", "plays[7]=duplicate"));
    assertThatThrownBy(() -> sportsAdmin.generate(no(f), List.of(), 1)).isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(fields(e)).containsExactly("plays=required"));
    // 一个都没生成（同一个事务）
    assertThat(sportsAdmin.fixture(no(f)).orElseThrow().markets()).hasSize(1);
    // 删掉草稿以后可以重新生成
    String draft = sportsAdmin.fixture(no(f)).orElseThrow().markets().get(0).marketNo();
    admin.deleteDraft(Long.parseLong(draft));
    assertThat(sportsAdmin.generate(no(f), List.of(new SportsAdminApi.PlayInput("FOOTBALL_OU", "2.5")), 1).created()).hasSize(1);
    // 离开赛不到 5 分钟 + 1 分钟：太晚了
    Fx soon = football(in(Duration.ofMinutes(6)));
    assertThatThrownBy(() -> sportsAdmin.generate(no(soon), List.of(new SportsAdminApi.PlayInput("FOOTBALL_1X2", null)), 1)).isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(fields(e)).containsExactly("kickoffAt=tooLate"));
    assertThatThrownBy(() -> sportsAdmin.generate(1L, List.of(), 1)).isInstanceOf(BizException.class).hasFieldOrPropertyWithValue("code", "NOT_FOUND");
  }

  @Test
  void 规则文本模板_四个玩法_改了只影响之后的草稿_校验一次列出全部() {
    List<SportsAdminApi.Template> list = sportsAdmin.templates();
    assertThat(list).extracting(SportsAdminApi.Template::kind).containsExactly("FOOTBALL_1X2", "FOOTBALL_OU", "NBA_ML", "NBA_TOTAL");
    SportsAdminApi.Template ml = list.get(2);
    assertThat(ml.variables()).contains("home", "away", "line", "closeTime");
    assertThat(ml.options()).extracting(SportsAdminApi.TemplateOption::outcome).containsExactly("HOME", "AWAY");
    // 错误一次列出：缺语言、不认识的变量、选项不对
    I18nText bad = new I18nText("{home} vs {away} {score}", "", "x");
    assertThatThrownBy(() -> sportsAdmin.updateTemplate("NBA_ML", new SportsAdminApi.TemplateInput("", bad, ml.rules(), ml.sourcePrimary(), "",
        List.of(ml.options().get(0))), 1)).isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(fields(e)).containsExactly("name=required", "title.zhCN=required", "sourceBackup=required", "options=mustBe:HOME,AWAY",
            "variables=unknown:score"));
    Fx f = nba(in(Duration.ofDays(1)));
    String before = sportsAdmin.generate(no(f), List.of(new SportsAdminApi.PlayInput("NBA_ML", null)), 1).created().get(0).marketNo();
    try {
      SportsAdminApi.Template t = sportsAdmin.updateTemplate("NBA_ML", new SportsAdminApi.TemplateInput(ml.name(), new I18nText("{home} v {away}", "{home} 对 {away}",
          "{home} 對 {away}"), ml.rules(), ml.sourcePrimary(), ml.sourceBackup(), ml.options()), 3);
      assertThat(t.version()).isEqualTo(ml.version() + 1);
      assertThat(t.updatedBy()).isEqualTo(3L);
      Fx g = nba(in(Duration.ofDays(1)));
      String after = sportsAdmin.generate(no(g), List.of(new SportsAdminApi.PlayInput("NBA_ML", null)), 1).created().get(0).marketNo();
      assertThat(admin.detail(Long.parseLong(after)).orElseThrow().title().zhCN()).isEqualTo("Los Angeles Lakers 对 Boston Celtics");
      assertThat(admin.detail(Long.parseLong(before)).orElseThrow().title().zhCN()).as("已生成的草稿不变").contains("谁赢");
    } finally {
      SportsAdminApi.Template now = sportsAdmin.templates().get(2);
      sportsAdmin.updateTemplate("NBA_ML", new SportsAdminApi.TemplateInput(ml.name(), ml.title(), ml.rules(), ml.sourcePrimary(), ml.sourceBackup(), ml.options()),
          1);
      assertThat(now.kind()).isEqualTo("NBA_ML");
    }
    assertThatThrownBy(() -> sportsAdmin.updateTemplate("TENNIS", null, 1)).isInstanceOf(BizException.class).hasFieldOrPropertyWithValue("code", "NOT_FOUND");
  }

  static List<String> fields(Throwable e) {
    return ((ValidationException) e).fields().stream().map(x -> x.field() + "=" + x.code()).toList();
  }

  /** 和模板一样的时间格式（UTC） */
  static final class SportsTemplatesFmt {
    static String hm(Instant i) {
      return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneOffset.UTC).format(i);
    }

    static String date(Instant i) {
      return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneOffset.UTC).format(i);
    }

    static String dateHm(Instant i) {
      return hm(i);
    }
  }
}
