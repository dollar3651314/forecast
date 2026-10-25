package com.forecast.app.market.core;

import com.forecast.app.common.id.Snowflake;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.support.MarketTestDoubles;
import com.forecast.app.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * market 模块测试的公共部分（真实 MySQL 8.4，C37）：固定时钟（2026-10-14 10:00 UTC，可以往前拨）、pool / settle 的替身。
 * 测试数据各自新建（雪花编号），互不影响；不清库。
 */
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({FixedClockConfig.class, MarketTestDoubles.class})
abstract class MarketTestSupport {

  static final Instant NOW = FixedClockConfig.NOW;

  @Autowired JdbcTemplate jdbc;
  @Autowired MarketService markets;
  @Autowired MarketMapper mapper;
  @Autowired Snowflake ids;
  @Autowired Clock clock;
  @Autowired MarketTestDoubles.Pool pool;
  @Autowired MarketTestDoubles.Events events;
  @Autowired PlatformTransactionManager tm;

  @AfterEach
  void resetClock() {
    ((MutableClock) clock).reset();
  }

  void advance(Duration d) {
    ((MutableClock) clock).advance(d);
  }

  TransactionTemplate tx() {
    return new TransactionTemplate(tm);
  }

  static LocalDateTime at(Instant i) {
    return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  /** 直接在库里建一个指定状态的市场（两个选项），返回 id。时间：开放 = 现在 − 1 小时，截止 = close，事件开始 = 截止，事件结束 = 截止 + 2 小时 */
  long insert(MarketStatus status, Instant close) {
    return insert(status, NOW.minus(Duration.ofHours(1)), close, close.plus(Duration.ofHours(2)));
  }

  long insert(MarketStatus status, Instant open, Instant close, Instant end) {
    long no = ids.next();
    jdbc.update("INSERT INTO market_question (public_no, category, title_en, title_zh_cn, title_zh_tw, rules_en, rules_zh_cn, rules_zh_tw, resolution_mode, "
        + "source_primary, source_backup, status, open_at, close_at, event_start_at, event_end_at, fee_rate, user_cap, created_at, updated_at) "
        + "VALUES (?, 'FOOTBALL', 'Test', '测试', '測試', 'r', 'r', 'r', 'AUTO', 'a', 'b', ?, ?, ?, ?, ?, 0.05, 10000, ?, ?)",
        no, status.name(), at(open), at(close), at(close), at(end), at(NOW), at(NOW));
    long id = jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, no);
    for (int i = 1; i <= 2; i++) {
      jdbc.update("INSERT INTO market_option (public_no, market_id, seq, label_en, label_zh_cn, label_zh_tw, win_condition, created_at) "
          + "VALUES (?, ?, ?, 'o', 'o', 'o', '{\"en\":\"w\",\"zhCN\":\"w\",\"zhTW\":\"w\"}', ?)", ids.next(), id, i, at(NOW));
    }
    return id;
  }

  long publicNo(long id) {
    return jdbc.queryForObject("SELECT public_no FROM market_question WHERE id = ?", Long.class, id);
  }

  long optionNo(long id, int seq) {
    return jdbc.queryForObject("SELECT public_no FROM market_option WHERE market_id = ? AND seq = ?", Long.class, id, seq);
  }

  String status(long id) {
    return jdbc.queryForObject("SELECT status FROM market_question WHERE id = ?", String.class, id);
  }

  List<String> log(long id) {
    return jdbc.queryForList("SELECT CONCAT(from_status, '>', to_status, ':', actor, ':', COALESCE(reason, '-')) FROM market_status_log WHERE market_id = ? ORDER BY id",
        String.class, id);
  }

  static I18nText t(String s) {
    return new I18nText(s + " en", s + " 简", s + " 繁");
  }

  /** 一个填齐了的草稿输入（足球，开放 = 现在，截止 = 现在 + 1 天） */
  static MarketAdminApi.DraftInput complete() {
    Instant open = NOW;
    Instant close = NOW.plus(Duration.ofDays(1));
    return new MarketAdminApi.DraftInput("FOOTBALL", t("曼城 vs 利物浦"), t("以 90 分钟比分为准"), "AUTO", "premierleague.com", "API-Football", null,
        open.toString(), close.toString(), close.plus(Duration.ofMinutes(5)).toString(), close.plus(Duration.ofHours(2)).toString(), null, null, null,
        List.of(new MarketAdminApi.OptionInput(t("主胜"), t("主队赢")), new MarketAdminApi.OptionInput(t("平"), t("打平")),
            new MarketAdminApi.OptionInput(t("客胜"), t("客队赢"))));
  }
}
