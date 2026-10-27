package com.forecast.app.market.autogen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAutogenAdminApi;
import com.forecast.app.resolve.CryptoTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 加密市场自动生成（forecast S6；需求说明书第 4.2 节、第 3.7 节、AC-28；MQ-1 节目化）。测试时钟 2026-10-14 10:00 UTC。
 * 新库里的默认模板：BTC 1 小时、按场次、每天 13:00 UTC 一场、关闭（启用要 market:publish 检查一次）。
 */
class CryptoAutogenTest extends CryptoTestSupport {

  static final long DEFAULT_NO = 1000000000000000002L;

  @Autowired MarketAutogenAdminApi api;

  @BeforeEach
  @AfterEach
  void resetAutogens() {
    // 测试数据隔离（C80）：之前的测试由默认模板生成的市场不再挂在模板上（否则同一窗口的唯一键让这里生成不出来），测试建的模板删掉
    jdbc.update("UPDATE market_question SET autogen_id = NULL WHERE autogen_id IS NOT NULL");
    jdbc.update("DELETE FROM market_autogen WHERE public_no <> ?", DEFAULT_NO);
    jdbc.update("UPDATE market_autogen SET enabled = 0, mode = 'SCHEDULE', slots = '[\"13:00\"]', rolling_count = 3, generate_ahead_min = 1440, user_cap = NULL, "
        + "checked_by = NULL, checked_at = NULL, checked_template_version = NULL WHERE public_no = ?", DEFAULT_NO);
  }

  /** 这个模板生成的、还没截止的已上架市场（可下注或待开放） */
  int listedAhead(long autogenId) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM market_question WHERE autogen_id = ? AND status = 'LISTED' AND close_at > ?", Integer.class, autogenId,
        java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(Instant.now(clock), java.time.ZoneOffset.UTC)));
  }

  static String ts(Object v) {
    return v instanceof java.sql.Timestamp t ? t.toLocalDateTime().toString() : String.valueOf(v);
  }

  long idOf(String autogenNo) {
    return jdbc.queryForObject("SELECT id FROM market_autogen WHERE public_no = ?", Long.class, Long.parseLong(autogenNo));
  }

  Map<String, Object> generatedAt(long autogenId, String windowStart) {
    return jdbc.queryForMap("SELECT * FROM market_question WHERE autogen_id = ? AND event_start_at = ?", autogenId,
        java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(Instant.parse(windowStart), java.time.ZoneOffset.UTC)));
  }

  /** 这个模板生成的全部市场：窗口开始时间 → 开放、截止、今日场次标记 */
  java.util.List<String> generatedRows(long autogenId) {
    return jdbc.queryForList("SELECT CONCAT(DATE_FORMAT(event_start_at, '%m-%d %H:%i'), ' open ', DATE_FORMAT(open_at, '%m-%d %H:%i:%s'), ' close ', "
        + "DATE_FORMAT(close_at, '%m-%d %H:%i:%s'), ' slate ', today_slate) FROM market_question WHERE autogen_id = ? ORDER BY event_start_at", String.class, autogenId);
  }

  /**
   * AC-80（S13 核对 MF-3 的场次表）：BTC 1 小时、场次表"每天 13:00 UTC"：时钟每 30 分钟走一步、走 3 天，每天只生成一个 13:00～14:00 的市场，
   * 10:00 开放、12:59:30 截止；任何时刻接下来 24 小时内场次表里的时刻都已生成；按场次表生成的自动列入今日场次。
   * 加一个 01:00（要先关闭、改完重新检查启用）以后每天 2 个，已生成的不变；场次可以限定星期几（S13 补上）。
   */
  @Test
  void AC80_场次表_走三天每天一场_接下来24小时都已生成_加场次后每天两场_星期几_今日场次标记() {
    MarketAutogenAdminApi.Autogen a = api.enable(DEFAULT_NO, true, 7);
    long id = idOf(a.autogenNo());
    for (int step = 0; step < 3 * 48; step++) {
      autogen.generateDue();
      Instant now = Instant.now(clock);
      // 接下来 24 小时内的 13:00 都已生成
      for (Instant t = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS).plus(Duration.ofHours(13)); t.isBefore(now.plus(Duration.ofHours(24)));
          t = t.plus(Duration.ofDays(1))) {
        if (t.minusSeconds(30).isAfter(now)) {
          assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_question WHERE autogen_id = ? AND event_start_at = ?", Integer.class, id,
              java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(t, java.time.ZoneOffset.UTC)))).as(now + " 时 " + t).isEqualTo(1);
        }
      }
      advance(Duration.ofMinutes(30));
    }
    java.util.List<String> rows = generatedRows(id);
    // 10-14 10:00 起走了 3 天（到 10-17 10:00），提前 1440 分钟生成：14、15、16、17 日的 13:00 都有，每天只有一个、只在 13:00
    assertThat(rows).containsExactly("10-14 13:00 open 10-14 10:00:00 close 10-14 12:59:30 slate 1", "10-15 13:00 open 10-15 10:00:00 close 10-15 12:59:30 slate 1",
        "10-16 13:00 open 10-16 10:00:00 close 10-16 12:59:30 slate 1", "10-17 13:00 open 10-17 10:00:00 close 10-17 12:59:30 slate 1");
    // 加一个 01:00 UTC：启用中不能改，先关闭、改完重新检查启用
    api.disable(DEFAULT_NO, 1);
    MarketAutogenAdminApi.AutogenInput in = new MarketAutogenAdminApi.AutogenInput("BTCUSDT", "1H", "schedule", java.util.List.of("13:00", "01:00@1,2,3,4,5,6,7"),
        null, 1440, null, null);
    MarketAutogenAdminApi.Autogen u = api.update(DEFAULT_NO, in, 1);
    assertThat(u.slots()).as("七天全选写成每天；按时间排").containsExactly("01:00", "13:00");
    api.enable(DEFAULT_NO, true, 7);
    autogen.generateDue();
    for (int step = 0; step < 48; step++) {
      advance(Duration.ofMinutes(30));
      autogen.generateDue();
    }
    java.util.List<String> after = generatedRows(id);
    assertThat(after.subList(0, 4)).as("已生成的不变").isEqualTo(rows);
    // 10-18 01:00、10-18 13:00、10-19 01:00 新生成（01:00 的场次 22:00 前一天开放）
    assertThat(after).contains("10-18 01:00 open 10-17 22:00:00 close 10-18 00:59:30 slate 1", "10-18 13:00 open 10-18 10:00:00 close 10-18 12:59:30 slate 1",
        "10-19 01:00 open 10-18 22:00:00 close 10-19 00:59:30 slate 1");
    // 星期几：13:00 只在周六（10-24 是周六），01:00 每天
    api.disable(DEFAULT_NO, 1);
    api.update(DEFAULT_NO, new MarketAutogenAdminApi.AutogenInput("BTCUSDT", "1H", "schedule", java.util.List.of("13:00@6"), null, 1440, null, null), 1);
    assertThat(api.detail(DEFAULT_NO).orElseThrow().upcoming()).extracting(MarketAutogenAdminApi.Window::windowStart).allMatch(w -> w.endsWith("T13:00:00Z"))
        .allMatch(w -> java.time.LocalDate.parse(w.substring(0, 10)).getDayOfWeek() == java.time.DayOfWeek.SATURDAY);
    assertThatThrownBy(() -> api.update(DEFAULT_NO, new MarketAutogenAdminApi.AutogenInput("BTCUSDT", "1H", "schedule", java.util.List.of("13:00@8", "13:00@6"),
        null, 1440, null, null), 1)).isInstanceOfSatisfying(ValidationException.class,
            e -> assertThat(e.fields()).extracting(f -> f.field() + ":" + f.code()).containsExactlyInAnyOrder("slots[0]:invalidDays"));
  }

  @Test
  void 场次宽松写法_1点写成1冒号00也能保存_逐行报错_S12c() {
    // 测试环境反馈（S12c）：BTCUSDT 1H 场次加一行 "1:00"，页面显示北京 09:00，保存报"场次格式不正确"
    MarketAutogenAdminApi.AutogenInput loose = new MarketAutogenAdminApi.AutogenInput("BTCUSDT", "1H", "schedule",
        List.of("13:00", "1:00", " 2 ", "3：0@ 7 , 6 "), null, 1440, null, null);
    MarketAutogenAdminApi.Autogen a = api.update(DEFAULT_NO, loose, 1);
    assertThat(a.slots()).as("保存成标准写法、按时间排").containsExactly("01:00", "02:00", "03:00@6,7", "13:00");
    assertThat(jdbc.queryForObject("SELECT slots FROM market_autogen WHERE public_no = ?", String.class, DEFAULT_NO))
        .as("库里存的也是标准写法（JSON 列，MySQL 输出带空格）").isEqualTo("[\"01:00\", \"02:00\", \"03:00@6,7\", \"13:00\"]");
    assertThat(a.upcoming()).extracting(MarketAutogenAdminApi.Window::windowStart).isNotEmpty()
        .allMatch(w -> w.matches(".*T(01|02|03|13):00:00Z"));
    // 逐行报错：第几行（下标）、错在哪（错误码），一次列全
    MarketAutogenAdminApi.AutogenInput bad = new MarketAutogenAdminApi.AutogenInput("BTCUSDT", "1H", "schedule",
        List.of("13:00", "1:30", "abc", "24:00", "01", "", "13:00@9"), null, 1440, null, null);
    assertThatThrownBy(() -> api.update(DEFAULT_NO, bad, 1)).isInstanceOfSatisfying(ValidationException.class,
        e -> assertThat(e.fields()).extracting(f -> f.field() + ":" + f.code()).containsExactly("slots[1]:hourOnly", "slots[2]:timeFormat",
            "slots[3]:outOfRange", "slots[5]:required", "slots[6]:invalidDays"));
    MarketAutogenAdminApi.AutogenInput dup = new MarketAutogenAdminApi.AutogenInput("BTCUSDT", "1H", "schedule", List.of("1:00", "01:00@6"), null, 1440, null,
        null);
    assertThatThrownBy(() -> api.update(DEFAULT_NO, dup, 1)).isInstanceOfSatisfying(ValidationException.class,
        e -> assertThat(e.fields()).extracting(f -> f.field() + ":" + f.code()).containsExactly("slots[1]:duplicate"));
  }

  @Test
  void 默认模板_BTC1小时_每天13点UTC一场_关闭_检查时能看到渲染出来的样子() {
    MarketAutogenAdminApi.Autogen a = api.detail(DEFAULT_NO).orElseThrow();
    assertThat(a.symbol()).isEqualTo("BTCUSDT");
    assertThat(a.period()).isEqualTo("1H");
    assertThat(a.category()).isEqualTo("CRYPTO_1H");
    assertThat(a.mode()).isEqualTo("schedule");
    assertThat(a.slots()).containsExactly("13:00");
    assertThat(a.enabled()).isFalse();
    assertThat(a.missing()).isEmpty();
    assertThat(a.upcoming()).first().satisfies(w -> {
      assertThat(w.windowStart()).isEqualTo("2026-10-14T13:00:00Z");
      assertThat(w.windowEnd()).isEqualTo("2026-10-14T14:00:00Z");
      assertThat(w.openAt()).isEqualTo("2026-10-14T10:00:00Z");
      assertThat(w.closeAt()).isEqualTo("2026-10-14T12:59:30Z");
      assertThat(w.generated()).isFalse();
    });
    assertThat(a.upcoming()).extracting(MarketAutogenAdminApi.Window::windowStart).containsExactly("2026-10-14T13:00:00Z", "2026-10-15T13:00:00Z",
        "2026-10-16T13:00:00Z", "2026-10-17T13:00:00Z");
    assertThat(a.sample().title()).isEqualTo(new I18nText("BTC Up or Down: 2026-10-14 13:00–14:00 UTC", "BTC 涨还是跌：2026-10-14 13:00–14:00 UTC",
        "BTC 漲還是跌：2026-10-14 13:00–14:00 UTC"));
    assertThat(a.sample().rules().zhCN()).contains("币安现货 BTC/USDT").contains("OKX 现货 BTC-USDT").contains("窗口开始前 30 秒").contains("结束价 ≥ 开始价判\"涨\"")
        .doesNotContain("{");
    assertThat(a.sample().sourcePrimary()).isEqualTo("Binance spot BTC/USDT 1m klines (data-api.binance.vision)");
    assertThat(a.sample().options()).extracting(o -> o.outcome() + " " + o.label().zhCN()).containsExactly("UP 涨", "DOWN 跌");
    // 关闭时不生成
    assertThat(autogen.generateDue()).isZero();
    assertThat(listedAhead(idOf(a.autogenNo()))).isZero();
  }

  @Test
  void 启用要勾选检查_启用后按场次生成并直接上架() {
    assertThatThrownBy(() -> api.enable(DEFAULT_NO, false, 1)).isInstanceOfSatisfying(ValidationException.class,
        e -> assertThat(e.fields()).extracting(ValidationException.Field::field).containsExactly("checked"));
    MarketAutogenAdminApi.Autogen a = api.enable(DEFAULT_NO, true, 7);
    assertThat(a.enabled()).isTrue();
    assertThat(a.checkedBy()).isEqualTo(7L);
    assertThat(a.checkedTemplateVersion()).isNotNull();
    long id = idOf(a.autogenNo());
    // F-I2-1（AC-80）：默认提前 1440 分钟生成 → 10:00 时今天 13:00 和明天 13:00（开放 10-15 10:00、生成时间 10-14 10:00）两场都生成
    assertThat(api.detail(DEFAULT_NO).orElseThrow().generateAheadMinutes()).isEqualTo(1440);
    assertThat(autogen.generateDue()).isEqualTo(2);
    Map<String, Object> m = generatedAt(id, "2026-10-14T13:00:00Z");
    assertThat(m.get("status")).isEqualTo("LISTED");
    assertThat(m.get("category")).isEqualTo("CRYPTO_1H");
    assertThat(m.get("resolution_mode")).isEqualTo("AUTO");
    assertThat(ts(m.get("open_at"))).startsWith("2026-10-14T10:00");
    assertThat(ts(m.get("close_at"))).startsWith("2026-10-14T12:59:30");
    assertThat(ts(m.get("event_start_at"))).startsWith("2026-10-14T13:00");
    assertThat(ts(m.get("event_end_at"))).startsWith("2026-10-14T14:00");
    assertThat(m.get("fee_rate").toString()).isEqualTo("0.0500");
    assertThat(((java.math.BigDecimal) m.get("user_cap"))).isEqualByComparingTo("2000");
    assertThat(m.get("listed_by")).isNull();
    assertThat(m.get("created_by")).isNull();
    assertThat(m.get("title_zh_cn")).isEqualTo("BTC 涨还是跌：2026-10-14 13:00–14:00 UTC");
    assertThat(m.get("title_zh_tw")).isEqualTo("BTC 漲還是跌：2026-10-14 13:00–14:00 UTC");
    assertThat(m.get("source_ref").toString()).contains("\"kind\": \"CRYPTO_UPDOWN\"").contains("\"symbol\": \"BTCUSDT\"").contains("\"UP\": 1").contains("\"DOWN\": 2");
    long marketId = ((Number) m.get("id")).longValue();
    assertThat(jdbc.queryForList("SELECT CONCAT(from_status, '>', to_status, ' ', actor) FROM market_status_log WHERE market_id = ?", String.class, marketId))
        .containsExactly("DRAFT>LISTED AUTOGEN");
    assertThat(jdbc.queryForList("SELECT label_zh_cn FROM market_option WHERE market_id = ? ORDER BY seq", String.class, marketId)).containsExactly("涨", "跌");
    // 后台详情：已生成的窗口带市场编号
    MarketAutogenAdminApi.Autogen d = api.detail(DEFAULT_NO).orElseThrow();
    assertThat(d.upcoming().get(0).generated()).isTrue();
    assertThat(d.upcoming().get(0).marketNo()).isEqualTo(String.valueOf(m.get("public_no")));
    assertThat(generatedAt(id, "2026-10-15T13:00:00Z").get("status")).isEqualTo("LISTED");
    // 再跑不重复；后天 13:00 的场次在明天 10:00（开放前 1440 分钟）生成
    assertThat(autogen.generateDue()).isZero();
    advance(Duration.ofHours(23).plusMinutes(59));
    assertThat(autogen.generateDue()).isZero();
    advance(Duration.ofMinutes(1));
    assertThat(autogen.generateDue()).isEqualTo(1);
    assertThat(generatedAt(id, "2026-10-16T13:00:00Z").get("status")).isEqualTo("LISTED");
  }

  @Test
  void 生成的市场可以下注() throws Exception {
    api.enable(DEFAULT_NO, true, 1);
    autogen.generateDue();
    Map<String, Object> m = generatedAt(idOf(String.valueOf(DEFAULT_NO)), "2026-10-14T13:00:00Z");
    long id = ((Number) m.get("id")).longValue();
    List<String> opts = jdbc.queryForList("SELECT CAST(public_no AS CHAR) FROM market_option WHERE market_id = ? ORDER BY seq", String.class, id);
    Market market = new Market(id, String.valueOf(m.get("public_no")), opts);
    User u = user("100");
    bet(u.id(), market, 0, "10");
    assertThat(betLocked(u.id())).isEqualByComparingTo("10");
  }

  @Test
  void 多个实例同时生成_同一窗口只有一个市场() throws Exception {
    api.enable(DEFAULT_NO, true, 1);
    ExecutorService ex = Executors.newFixedThreadPool(4);
    List<Future<Integer>> fs = new ArrayList<>();
    for (int i = 0; i < 4; i++) fs.add(ex.submit((Callable<Integer>) () -> autogen.generateDue()));
    int total = 0;
    for (Future<Integer> f : fs) total += f.get();
    ex.shutdown();
    // 默认提前 1440 分钟生成（F-I2-1）：今天和明天 13:00 两个窗口，每个窗口只有一个市场
    assertThat(total).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_question WHERE autogen_id = ?", Integer.class, idOf(String.valueOf(DEFAULT_NO)))).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT event_start_at) FROM market_question WHERE autogen_id = ?", Integer.class, idOf(String.valueOf(DEFAULT_NO))))
        .isEqualTo(2);
  }

  @Test
  void AC28_滚动_15分钟任何时刻未来至少4个窗口可下注或待开放_关闭后不再生成_已生成的照常() {
    MarketAutogenAdminApi.Autogen a = api.create(new MarketAutogenAdminApi.AutogenInput("ETHUSDT", "15M", "rolling", List.of(), null, null, null, null), 1);
    assertThat(a.rollingCount()).isEqualTo(4);
    assertThat(a.mode()).isEqualTo("rolling");
    assertThat(a.enabled()).isFalse();
    api.enable(Long.parseLong(a.autogenNo()), true, 1);
    long id = idOf(a.autogenNo());
    // 2 小时，每分钟一轮生成 + 截止任务（和生产一样，生成任务每分钟一次）
    for (int i = 0; i < 120; i++) {
      autogen.generateDue();
      marketService.closeDue();
      assertThat(listedAhead(id)).as("第 %d 分钟", i).isGreaterThanOrEqualTo(4);
      advance(Duration.ofMinutes(1));
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT title_en) FROM market_question WHERE autogen_id = ?", Integer.class, id)).isGreaterThan(8);
    assertThat(jdbc.queryForObject("SELECT MIN(category) FROM market_question WHERE autogen_id = ?", String.class, id)).isEqualTo("CRYPTO_15M");
    // 15 分钟市场：开放提前 60 分钟
    assertThat(jdbc.queryForObject("SELECT MIN(TIMESTAMPDIFF(MINUTE, open_at, event_start_at)) FROM market_question WHERE autogen_id = ?", Integer.class, id))
        .isEqualTo(60);
    // 关闭：不再生成新的；已生成的照常（到点截止）
    api.disable(Long.parseLong(a.autogenNo()), 1);
    int before = jdbc.queryForObject("SELECT COUNT(*) FROM market_question WHERE autogen_id = ?", Integer.class, id);
    int listed = listedAhead(id);
    assertThat(autogen.generateDue()).isZero();
    assertThat(listedAhead(id)).isEqualTo(listed);
    advance(Duration.ofMinutes(30));
    assertThat(autogen.generateDue()).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_question WHERE autogen_id = ?", Integer.class, id)).isEqualTo(before);
  }

  @Test
  void 修改只能在关闭时_改了要重新检查_可以切换成滚动() {
    api.enable(DEFAULT_NO, true, 1);
    MarketAutogenAdminApi.AutogenInput rolling = new MarketAutogenAdminApi.AutogenInput("BTCUSDT", "1H", "rolling", List.of("13:00"), 3, 60, null, "500");
    assertThatThrownBy(() -> api.update(DEFAULT_NO, rolling, 1)).isInstanceOfSatisfying(BizException.class,
        e -> assertThat(e.code()).isEqualTo("AUTOGEN_ENABLED"));
    api.disable(DEFAULT_NO, 1);
    MarketAutogenAdminApi.Autogen a = api.update(DEFAULT_NO, rolling, 2);
    assertThat(a.mode()).isEqualTo("rolling");
    assertThat(a.userCap()).isEqualTo("500");
    assertThat(a.checkedBy()).as("改了以后检查记录清空").isNull();
    assertThat(a.upcoming()).extracting(MarketAutogenAdminApi.Window::windowStart).containsExactly("2026-10-14T11:00:00Z", "2026-10-14T12:00:00Z",
        "2026-10-14T13:00:00Z");
    api.enable(DEFAULT_NO, true, 1);
    assertThat(autogen.generateDue()).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT user_cap FROM market_question WHERE autogen_id = ? ORDER BY id LIMIT 1", java.math.BigDecimal.class,
        idOf(String.valueOf(DEFAULT_NO)))).isEqualByComparingTo("500");
  }

  @Test
  void 输入校验一次列出全部_同一币种周期只能一个() {
    MarketAutogenAdminApi.AutogenInput bad = new MarketAutogenAdminApi.AutogenInput("DOGEUSDT", "5M", "weekly", List.of("13:07", "25:00"), 0, -1, "123", "0");
    assertThatThrownBy(() -> api.create(bad, 1)).isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fields()).extracting(f -> f.field() + ":" + f.code())
        .contains("symbol:invalid", "period:invalid", "mode:invalid", "slots[1]:outOfRange", "rollingCount:invalid", "generateAheadMinutes:invalid",
            "templateNo:invalid", "userCap:invalid"));
    MarketAutogenAdminApi.AutogenInput misaligned = new MarketAutogenAdminApi.AutogenInput("ETHUSDT", "15M", "schedule", List.of("13:07", "13:15", "13:15"), null,
        null, null, "999999");
    assertThatThrownBy(() -> api.create(misaligned, 1)).isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fields())
        .extracting(f -> f.field() + ":" + f.code()).containsExactlyInAnyOrder("slots[0]:quarterOnly", "slots[2]:duplicate", "userCap:aboveDefault"));
    MarketAutogenAdminApi.AutogenInput noSlots = new MarketAutogenAdminApi.AutogenInput("ETHUSDT", "1H", "schedule", List.of(), null, null, null, null);
    assertThatThrownBy(() -> api.create(noSlots, 1)).isInstanceOfSatisfying(ValidationException.class,
        e -> assertThat(e.fields()).extracting(ValidationException.Field::field).containsExactly("slots"));
    MarketAutogenAdminApi.AutogenInput dup = new MarketAutogenAdminApi.AutogenInput("BTCUSDT", "1H", "rolling", List.of(), null, null, null, null);
    assertThatThrownBy(() -> api.create(dup, 1)).isInstanceOfSatisfying(BizException.class, e -> assertThat(e.code()).isEqualTo("AUTOGEN_EXISTS"));
  }

  @Test
  void 规则文本模板_启用中不能改_改了要重新检查才生成() {
    MarketAutogenAdminApi.Template t = api.templates().get(0);
    assertThat(t.kind()).isEqualTo("CRYPTO_UPDOWN");
    assertThat(t.variables()).contains("asset", "date", "startTime", "endTime", "closeLeadSeconds");
    assertThat(t.usedBy()).contains(String.valueOf(DEFAULT_NO));
    MarketAutogenAdminApi.TemplateInput in = new MarketAutogenAdminApi.TemplateInput(t.name(), new I18nText("{asset} up/down {date} {startTime}", t.title().zhCN(),
        t.title().zhTW()), t.rules(), t.sourcePrimary(), t.sourceBackup(), t.options());
    api.enable(DEFAULT_NO, true, 1);
    assertThatThrownBy(() -> api.updateTemplate(Long.parseLong(t.templateNo()), in, 1)).isInstanceOfSatisfying(BizException.class,
        e -> assertThat(e.code()).isEqualTo("TEMPLATE_IN_USE"));
    api.disable(DEFAULT_NO, 1);
    String oldTitle = jdbc.queryForObject("SELECT title_en FROM market_template WHERE public_no = ?", String.class, Long.parseLong(t.templateNo()));
    try {
      MarketAutogenAdminApi.Template after = api.updateTemplate(Long.parseLong(t.templateNo()), in, 1);
      assertThat(after.version()).isEqualTo(t.version() + 1);
      assertThat(api.detail(DEFAULT_NO).orElseThrow().sample().title().en()).isEqualTo("BTC up/down 2026-10-14 13:00");
      // 启用时检查的是旧版本：数据库里强行改成"启用"也不生成（启用要重新检查）
      jdbc.update("UPDATE market_autogen SET enabled = 1 WHERE public_no = ?", DEFAULT_NO);
      assertThat(autogen.generateDue()).isZero();
      jdbc.update("UPDATE market_autogen SET enabled = 0 WHERE public_no = ?", DEFAULT_NO);
      MarketAutogenAdminApi.Autogen a = api.enable(DEFAULT_NO, true, 1);
      assertThat(a.checkedTemplateVersion()).isEqualTo(after.version());
      assertThat(autogen.generateDue()).isEqualTo(2);
      // 模板校验：不认识的变量、选项不是涨 / 跌、必填
      MarketAutogenAdminApi.TemplateInput broken = new MarketAutogenAdminApi.TemplateInput("", new I18nText("{coin}", "", "x"), t.rules(), t.sourcePrimary(),
          t.sourceBackup(), List.of(t.options().get(0)));
      api.disable(DEFAULT_NO, 1);
      assertThatThrownBy(() -> api.updateTemplate(Long.parseLong(t.templateNo()), broken, 1)).isInstanceOfSatisfying(ValidationException.class,
          e -> assertThat(e.fields()).extracting(f -> f.field() + ":" + f.code()).contains("name:required", "title.en:unknownVariable", "title.zhCN:required",
              "options:upDown"));
    } finally {
      jdbc.update("UPDATE market_template SET title_en = ? WHERE public_no = ?", oldTitle, Long.parseLong(t.templateNo()));
    }
  }

  @Test
  void 停机恢复_还能下注的窗口补上_已截止的不补() {
    MarketAutogenAdminApi.Autogen a = api.create(new MarketAutogenAdminApi.AutogenInput("ETHUSDT", "1H", "schedule", List.of("09:00", "11:00"), null, 180, null, null), 1);
    api.enable(Long.parseLong(a.autogenNo()), true, 1);
    // 10:00：09:00 的窗口已截止（不补）；11:00 的开放 08:00、截止 10:59:30，补上
    // 明天 09:00 的开放 06:00、生成时间 03:00，还没到
    assertThat(autogen.generateDue()).isEqualTo(1);
    long id = idOf(a.autogenNo());
    assertThat(jdbc.queryForList("SELECT CAST(event_start_at AS CHAR) FROM market_question WHERE autogen_id = ?", String.class, id))
        .containsExactly("2026-10-14 11:00:00.000");
  }
}
