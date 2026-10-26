package com.forecast.app.resolve.api;

import com.forecast.app.market.api.I18nText;
import java.util.List;
import java.util.Optional;

/**
 * 后台的体育赛程（forecast S8；需求说明书 v1.3 第 4.2 节"足球、NBA（赛程生成草稿）"、第 4.3 节；技术方案第 6.1 节 X-12）。权限点和审计在 admin 模块
 * （{@code admin/ops/SportsOps}），这里只管业务。编号一律字符串，时间 ISO-8601 UTC。
 * <ol>
 *   <li><b>同步赛程</b>：从主源（足球 football-data、NBA balldontlie）拉未来 7 天的赛程（定时任务也会拉）；</li>
 *   <li><b>两个来源的比赛对应</b>：系统按队名 + 开赛时间在备源（TheSportsDB）里找<b>候选</b>，运营<b>确认</b>（不自动确认；没有确认的，自动裁决只有一个来源，一律转人工）；</li>
 *   <li><b>生成草稿</b>：选比赛和玩法（胜平负 / 大小球 / 胜负 / 大小分），一键生成市场草稿：队名、时间、三种语言的规则文本从模板自动填，
 *       之后照常"提交上架 → 有 market:publish 的人检查后上架"（除了加密自动生成，任何自动生成的市场都不会自动上架）。</li>
 * </ol>
 */
public interface SportsAdminApi {

  /** 玩法（MQ-10） */
  List<String> PLAYS = List.of("FOOTBALL_1X2", "FOOTBALL_OU", "NBA_ML", "NBA_TOTAL");

  record Team(String name, String shortName) {}

  /**
   * 确认的对应（备源的比赛）。
   *
   * @param swapped 备源的主客队和主源相反（比较比分时对调）
   */
  record Mapping(String source, String ref, boolean swapped, String home, String away, String kickoffAt, Long confirmedBy, String confirmedAt) {}

  /**
   * 一个候选（系统找的，只是建议）。
   *
   * @param score 0～1，队名和开赛时间的相似度
   * @param suggested 系统推荐（最高分、≥ 0.75、开赛时间相差不超过 3 小时）；仍然要人确认
   */
  record Candidate(String source, String ref, String home, String away, String kickoffAt, String league, double score, boolean swapped, boolean suggested) {}

  /** 从这场比赛生成的市场 */
  record MarketLink(String marketNo, String play, String line, String status) {}

  /**
   * 一场比赛。
   *
   * @param status 主源的状态（统一后，小写）：scheduled / live / finished / postponed / suspended / cancelled / awarded / unknown
   * @param kickoffChanged 开赛时间和第一次同步到的不一样（E14：以数据源为准）
   * @param mapping 确认的对应，没有为空
   * @param candidates 候选（最近一次找的）
   * @param candidatesError 最近一次找候选失败的原因（备源未开通等），没有为空
   */
  record Fixture(String fixtureNo, String sport, String competition, String competitionName, Team home, Team away, String kickoffAt,
      String originalKickoffAt, boolean kickoffChanged, String status, String rawStatus, String primarySource, String primaryRef, Mapping mapping,
      List<Candidate> candidates, String candidatesAt, String candidatesError, List<MarketLink> markets, String fetchedAt) {}

  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  /**
   * 一次同步的结果（每个品类一行）。
   *
   * @param outcome ok / disabled（来源未开通）/ failed / throttled / circuit_open
   * @param created 新增的比赛
   * @param updated 有变化的比赛（时间、状态、队名）
   * @param kickoffChanged 开赛时间变了的比赛
   * @param candidatesSearched 这次顺便找了候选的比赛数
   */
  record SyncResult(String sport, String source, String outcome, int fetched, int created, int updated, int kickoffChanged, int candidatesSearched,
      String error) {}

  /** 一个要生成的玩法：play 见 {@link #PLAYS}；大小球、大小分要 line（.5 结尾，E8；大小球不填默认 2.5） */
  record PlayInput(String play, String line) {}

  /**
   * 生成草稿的结果。
   *
   * @param warnings mappingNotConfirmed（备源的比赛还没有确认对应：到时只有一个来源，自动裁决会转人工）/ backupDisabled（备源未开通）
   */
  record Generated(String fixtureNo, List<MarketLink> created, List<String> warnings) {}

  record TemplateOption(String outcome, I18nText label, I18nText winCondition) {}

  /** 规则文本模板（每个玩法一个） */
  record Template(String kind, String name, I18nText title, I18nText rules, String sourcePrimary, String sourceBackup, List<TemplateOption> options,
      int version, Long updatedBy, String updatedAt, List<String> variables) {}

  record TemplateInput(String name, I18nText title, I18nText rules, String sourcePrimary, String sourceBackup, List<TemplateOption> options) {}

  /**
   * 赛程列表（按开赛时间）。
   *
   * @param sport FOOTBALL / NBA，空 = 全部
   * @param mapping confirmed / unconfirmed，空 = 全部
   * @param from 开赛时间从（默认现在），to 到（默认 from + 7 天），ISO-8601
   */
  Page<Fixture> fixtures(String sport, String mapping, String from, String to, int page, int pageSize);

  Optional<Fixture> fixture(long fixtureNo);

  /** 立即同步（sport 为空 = 足球和 NBA 都同步）；来源未开通的返回 disabled，不报错 */
  List<SyncResult> sync(String sport);

  /**
   * 在备源里找候选（覆盖上一次的）。ref 不为空时只查这个编号（运营自己在 TheSportsDB 上找到的），查到后加进候选。
   * 备源未开通、取不到时 {@code candidatesError} 写原因。
   */
  Fixture searchCandidates(long fixtureNo, String ref);

  /**
   * 确认对应（运营，{@code market:edit}）：ref 必须是候选里的（先用 {@link #searchCandidates} 查）。开赛以后不能改（409 FIXTURE_STARTED）。
   * 已经开始自动裁决的市场不受影响（进度里记的是当时的对应）。
   */
  Fixture confirmMapping(long fixtureNo, String ref, long adminId);

  /** 取消对应（开赛以后不能改） */
  Fixture clearMapping(long fixtureNo, long adminId);

  /**
   * 按玩法一键生成草稿（{@code market:edit}）。字段错误一次列出全部（{@code ValidationException}，C56）：玩法不对、和品类不符、分界线不是 .5、
   * 同一玩法同一分界线已经有有效的市场（duplicate）、离开赛太近（截止时间已过）。
   */
  Generated generate(long fixtureNo, List<PlayInput> plays, long adminId);

  List<Template> templates();

  /** 改模板（{@code market:autogen}，高危）：只影响之后生成的草稿；校验不认识的变量、选项必须正好是这个玩法的结果 */
  Template updateTemplate(String kind, TemplateInput in, long adminId);
}
