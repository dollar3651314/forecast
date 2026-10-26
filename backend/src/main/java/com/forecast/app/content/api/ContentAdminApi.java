package com.forecast.app.content.api;

import java.util.List;
import java.util.Map;

/**
 * 后台"内容中心"（forecast S15；需求说明书第 6.2 节、自动化需求第 7 节）。权限点和审计在 admin 模块（{@code admin/ops/ContentOps}）：
 * 看 content:view；改草稿、手动生成、录入经济日历、改模板 content:edit；发布、改设置和词表、紧急停止 content:publish（高危）。
 * <p>编号一律字符串，时间 ISO-8601 UTC。<b>AI 写的所有东西都要审核后才发布</b>（EQ-1：第一期没有自动发布）；X / Telegram 帖子只出草稿，
 * 审核通过后复制、由 Amos 手动发（自动发要等 SQ-22 和账号）；回复草稿只能复制，没有发布。
 */
public interface ContentAdminApi {

  // =====================================================================
  // 草稿箱
  // =====================================================================

  record RefView(String key, String label, String labelEn, String value, String source, String at) {}

  record NumberHit(String field, String text, int start, int end) {}

  /** 检查结果：对不上的数字、禁用词、倾向词、Polymarket 说明、长度、缺字段；summary 是一句话的说明 */
  record Checks(List<NumberHit> numbers, List<String> banned, List<String> tendency, boolean polymarketNoteMissing, int maxLength, int actualLength,
      List<String> missing, String summary) {}

  /**
   * 一条草稿（一种语言）。
   *
   * @param kind PREVIEW 赛前分析 / BRIEF 行情短文 / SEO_INTRO 页面说明 / ARTICLE 文章 / POST_X / POST_TG 帖子 / REPLY 回复草稿
   * @param status GENERATING / PENDING 待审核 / PUBLISHED 已发布（帖子：已审核）/ REJECTED / EXPIRED / FAILED
   * @param canPublish 现在能不能发布（待审核、检查通过、没过期、没有紧急停止；回复草稿永远不能）
   * @param copyOnly 这一类只能复制、由人手动发（帖子、行情短文、回复）
   * @param canCopy 现在能不能复制（帖子、行情短文：审核通过以后；回复：检查通过就能复制；紧急停止时都不能）
   * @param link 复制时附带的链接（帖子：市场链接，带来源参数 src=x / tg）
   * @param siteUrl 发布在网站上的地址（文章、页面说明、赛前分析）
   */
  record Draft(String draftNo, String groupNo, String kind, String lang, String title, String body, List<RefView> refs, Checks checks, boolean checkOk,
      String status, String failReason, String rejectReason, String marketNo, String pageSlug, String topic, String expiresAt, boolean expired, int aiCalls,
      String createdAt, Long createdBy, String publishedAt, Long publishedBy, int version, boolean canPublish, boolean copyOnly, boolean canCopy, String link,
      String siteUrl) {}

  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  /** 草稿列表：status、kind、lang（zh / en）筛选；待审核的排在最前 */
  Page<Draft> drafts(String status, String kind, String lang, int page, int pageSize);

  /** 一条草稿和同一次生成的另一种语言（中文在前） */
  List<Draft> group(long draftNo);

  /**
   * 手动生成（content:edit）：kind + 目标（marketNo / pageSlug / topic / viewpoint）。中文、英文各一条，起草在后台线程里做（状态 GENERATING，
   * 做完变成待审核或生成失败）。Gemini 未配置、AI 起草关着、额度用完时返回 409 {@code AI_UNAVAILABLE}；数据取不到 409 {@code DATA_UNAVAILABLE}。
   */
  record GenerateInput(String kind, String marketNo, String pageSlug, String topic, String viewpoint) {}

  List<Draft> generate(GenerateInput in, long adminId);

  /** 改草稿（content:edit）：只有待审核、生成失败的能改；有禁用词、倾向词的不保存（400 {@code CONTENT_BANNED_WORDS}）；数字重新核对 */
  record EditInput(String title, String body, Integer version) {}

  Draft edit(long draftNo, EditInput in, long adminId);

  /** 发布（content:publish）：检查不通过 409 {@code DRAFT_CHECK_FAILED}、过期 409 {@code DRAFT_EXPIRED}、紧急停止 409 {@code PUBLISH_STOPPED} */
  Draft publish(long draftNo, long adminId);

  /** 驳回（content:edit），原因写进日志（用来改写作规则） */
  Draft reject(long draftNo, String reason, long adminId);

  /** 重新起草（content:edit）：生成失败、驳回的 */
  Draft retry(long draftNo, long adminId);

  /** 记一次"复制"（帖子、行情短文审核通过以后；回复草稿检查通过以后）；不能复制时 409 */
  Draft copied(long draftNo, long adminId);

  // =====================================================================
  // 用量和设置
  // =====================================================================

  /**
   * 内容中心的状态。
   *
   * @param geminiState ok / unconfigured（没有 key）/ paused（熔断或被限流）/ quota（今日额度已用完）/ disabled（AI 起草关着）
   * @param monthCostUsd 本月按付费档价格估算的费用（免费档实际是 0）
   * @param settings content_setting 的全部项
   */
  record Status(boolean contentEnabled, boolean geminiConfigured, String geminiState, String model, int usedToday, int limit, int contentCap,
      int contentUsedToday, int scoreUsedToday, int failuresToday, long monthCalls, String monthCostUsd, String costAlertUsd, boolean costAlert,
      boolean stopped, int pagesToday, int pagesDaily, long articlesThisWeek, int articlesWeekly, boolean polymarketEnabled, int queue, String pausedUntil,
      Map<String, String> settings) {}

  Status status();

  /** 改设置（content:publish）：只改传了的项，范围不对的一次列出全部 */
  Status updateSettings(Map<String, String> values, long adminId);

  /** 紧急停止 / 恢复（content:publish）：停止后草稿箱的发布、帖子的复制都不可用 */
  Status setStopped(boolean stopped, long adminId);

  record Word(String id, String kind, String word, boolean builtIn) {}

  List<Word> words(String kind);

  Word addWord(String kind, String word, long adminId);

  /** 删除（内置的不能删：409 {@code WORD_BUILT_IN}） */
  Word deleteWord(long id);

  // =====================================================================
  // 事件候选（经济日历、赛程、焦点战建议、已过滤）
  // =====================================================================

  /**
   * 经济日历的一条。
   *
   * @param kind FOMC / CPI
   * @param period YYYY-MM
   * @param status SCHEDULED / DRAFTED / FILTERED / FAILED / CANCELLED / EXPIRED
   * @param marketStatus 生成的市场现在的状态（草稿、已上架……；草稿删了为空）
   * @param draftDueAt 什么时候自动生成草稿（发布前 N 天）
   */
  record Econ(String id, String kind, String period, String releaseAt, String prevValue, String sourceUrl, String status, String marketNo,
      String marketStatus, String note, String draftDueAt, String closeAt, int version) {}

  record EconInput(String kind, String period, String releaseAt, String prevValue, String sourceUrl, Integer version) {}

  List<Econ> econ();

  Econ addEcon(EconInput in, long adminId);

  Econ updateEcon(long id, EconInput in, long adminId);

  Econ cancelEcon(long id, long adminId);

  /** 立即按模板生成市场草稿（不等发布前 7 天） */
  Econ draftEcon(long id, long adminId);

  /** 焦点战建议（规则排的，只是建议，不替人决定开哪场，MQ-1） */
  record Suggestion(String fixtureNo, String sport, String competition, String competitionName, String home, String away, String kickoffAt,
      boolean mappingConfirmed, int markets, String suggestedPlay, List<String> reasons) {}

  record Fixture(String fixtureNo, String sport, String competitionName, String home, String away, String kickoffAt, boolean mappingConfirmed, int markets,
      boolean filtered, String filteredBy) {}

  record Filtered(String source, String ref, String title, String reason, String matched, String createdAt) {}

  record Candidates(List<Suggestion> focus, List<Fixture> fixtures, List<Econ> econ, List<Filtered> filtered, String sportsError) {}

  Candidates candidates();

  // =====================================================================
  // 模板（经济日历的题型模板）
  // =====================================================================

  record Text(String en, String zhCN, String zhTW) {}

  record TemplateOption(Text label, Text winCondition) {}

  record Template(String code, Text title, Text rules, List<TemplateOption> options, String sourcePrimary, String sourceBackup, int version, Long updatedBy,
      String updatedAt, List<String> variables) {}

  record TemplateInput(Text title, Text rules, List<TemplateOption> options, String sourcePrimary, String sourceBackup, Integer version) {}

  List<Template> templates();

  /** 改模板（content:edit）：三种语言都要有、选项数和顺序不变、只能用认识的变量、没有禁用词；带版本号 */
  Template updateTemplate(String code, TemplateInput in, long adminId);

  // =====================================================================
  // 网站页面
  // =====================================================================

  record PageRow(String slug, String kind, String marketNo, String titleZhCN, String titleEn, List<String> introLangs, List<String> bodyLangs, String dataAt,
      String createdDay, String status, String path) {}

  record Pages(List<PageRow> items, int createdToday, int dailyLimit) {}

  Pages pages();

  /** BTC 统计页：没有就建、立即刷新数据 */
  PageRow refreshBtc(long adminId);

  /** 显示 / 隐藏（content:publish） */
  PageRow setPageVisible(String slug, boolean visible, long adminId);

  // =====================================================================
  // 工具（MF-9：数据卡、大 V 对照图、回复草稿）
  // =====================================================================

  /**
   * 工具的结果：图片（PNG 的 data URL，只能下载，没有发布）、同一份内容的文字、用到的数据。
   *
   * @param replies 对照图同时生成的回复草稿（中文、英文；只能复制）
   * @param notes 说明（例如 Polymarket 没有打开）
   */
  record ToolResult(String image, String text, List<RefView> refs, List<Draft> replies, List<String> notes) {}

  /** 数据卡：kind = market（一个市场的奖池）/ btc（BTC 每小时涨跌统计） */
  ToolResult dataCard(String kind, String marketNo, String lang);

  /** 大 V 对照图：观点摘要 + 一个市场（+ 可选的 Polymarket 同类题 slug，默认关闭）；有 Gemini 时同时起草回复（只能复制） */
  ToolResult compare(String marketNo, String viewpoint, String polymarketSlug, String lang, long adminId);

  // =====================================================================
  // 日志
  // =====================================================================

  record LogRow(String id, String action, String kind, String ref, String lang, String outcome, String detail, int aiCalls, Long adminId, String createdAt) {}

  Page<LogRow> logs(String from, String to, String action, int page, int pageSize);

  /** CSV（UTF-8 带 BOM） */
  String logsCsv(String from, String to, String action);
}
