package com.forecast.app.content.core;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 内容中心的设置（forecast S15，表 {@code content_setting}，迁移 V24 写默认值）。每次读都查库（几行，便宜），多实例也一致。
 * 改设置在 {@code admin/ops/ContentOps} 写审计（content:publish，高危）。
 */
@Component
public class Settings {

  /** 每天 Gemini 调用上限（和体育抓取共用，EQ-5：300） */
  public static final String AI_DAILY_LIMIT = "ai.dailyLimit";
  /** 文章起草最多能用上限的百分之几（剩下的留给比分解析，自动化需求第 6.1 节：80） */
  public static final String AI_CONTENT_SHARE = "ai.contentSharePct";
  /** 每月费用提醒线（美元，按付费档估算） */
  public static final String AI_COST_ALERT = "ai.costAlertUsd";
  /** AI 起草（文章、帖子、SEO 说明、回复）开关 */
  public static final String AI_DRAFTS = "ai.drafts";
  /** 每日草稿包（00:00 UTC） */
  public static final String PACK = "pack.enabled";
  /** 每天新增页面上限（EQ-7：5） */
  public static final String PAGES_DAILY = "pages.dailyNew";
  /** 每周文章上限（EQ-7：2） */
  public static final String ARTICLES_WEEKLY = "articles.weekly";
  /** 经济日历提前几天生成草稿（自动化需求第 3.1 节：7） */
  public static final String ECON_LEAD_DAYS = "econ.leadDays";
  /** 紧急停止：所有发布（草稿箱的发布、帖子的复制）停止 */
  public static final String STOPPED = "publish.stopped";

  /** 每一项的取值范围 [min, max] */
  public static final Map<String, int[]> RANGES = Map.of(AI_DAILY_LIMIT, new int[] {0, 10_000}, AI_CONTENT_SHARE, new int[] {0, 100}, AI_COST_ALERT,
      new int[] {0, 10_000}, AI_DRAFTS, new int[] {0, 1}, PACK, new int[] {0, 1}, PAGES_DAILY, new int[] {0, 50}, ARTICLES_WEEKLY, new int[] {0, 20},
      ECON_LEAD_DAYS, new int[] {1, 30}, STOPPED, new int[] {0, 1});

  private final ContentMapper mapper;
  private final Clock clock;

  Settings(ContentMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  public Map<String, String> all() {
    Map<String, String> m = new LinkedHashMap<>();
    for (ContentRows.Setting s : mapper.settings()) m.put(s.settingKey, s.value);
    return m;
  }

  public List<ContentRows.Setting> rows() {
    return mapper.settings();
  }

  public int integer(String key, int fallback) {
    String v = all().get(key);
    try {
      return v == null ? fallback : Integer.parseInt(v.strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  public boolean flag(String key) {
    return integer(key, 0) == 1;
  }

  /** 改一项（调用方已校验范围）；返回旧值 */
  public String set(String key, String value, long adminId) {
    String old = all().get(key);
    mapper.updateSetting(key, value, adminId, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    return old;
  }
}
