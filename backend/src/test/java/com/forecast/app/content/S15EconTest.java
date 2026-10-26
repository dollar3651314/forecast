package com.forecast.app.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.content.api.ContentAdminApi;
import com.forecast.app.market.api.MarketAdminApi;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 经济日历（forecast S15；自动化需求第 3 节、EQ-6，AC-100、AC-102、AC-103、AC-105）：录入 FOMC、CPI → 发布前 7 天按题型模板生成<b>市场草稿</b>
 * （三语、选项覆盖所有可能、一手来源、截止 = 发布前 1 小时、平台裁决）→ 只是草稿，要人检查上架；命中敏感词的不生成、记"已过滤"。
 */
class S15EconTest extends ContentTestSupport {

  /** 测试之间错开时间（唯一键 kind + 发布时间） */
  private static Instant release(int days) {
    return com.forecast.app.support.FixedClockConfig.NOW.plus(Duration.ofDays(days)).plus(Duration.ofMinutes((System.nanoTime() / 1000) % 59)).truncatedTo(
        java.time.temporal.ChronoUnit.MINUTES);
  }

  private MarketAdminApi.Detail market(String no) {
    return admin.detail(Long.parseLong(no)).orElseThrow();
  }

  @Test
  void AC100_FOMC和CPI_发布前7天生成草稿_三语选项来源截止齐全_只是草稿() {
    Instant fomcAt = release(10);
    Instant cpiAt = release(9);
    ContentAdminApi.Econ fomc = content.addEcon(new ContentAdminApi.EconInput("FOMC", "2026-12", fomcAt.toString(), "4.50", null, null), 1);
    ContentAdminApi.Econ cpi = content.addEcon(new ContentAdminApi.EconInput("CPI", "2026-11", cpiAt.toString(), "3.0", "https://www.bls.gov/schedule/news_release/cpi.htm",
        null), 1);
    assertThat(fomc.status()).isEqualTo("SCHEDULED");
    assertThat(fomc.draftDueAt()).isEqualTo(fomcAt.minus(Duration.ofDays(7)).toString());
    assertThat(fomc.closeAt()).isEqualTo(fomcAt.minus(Duration.ofHours(1)).toString());
    tasks.runOnce();
    assertThat(econ(fomc.id()).status()).as("还没到发布前 7 天").isEqualTo("SCHEDULED");
    advance(Duration.ofDays(3).plusHours(1));
    tasks.runOnce();
    ContentAdminApi.Econ f = econ(fomc.id());
    ContentAdminApi.Econ c = econ(cpi.id());
    assertThat(f.status()).isEqualTo("DRAFTED");
    assertThat(c.status()).isEqualTo("DRAFTED");
    assertThat(f.marketStatus()).isEqualTo("draft");

    MarketAdminApi.Detail fm = market(f.marketNo());
    assertThat(fm.status()).as("只是草稿，不自动上架（AC-102）").isEqualTo("draft");
    assertThat(fm.submitted()).as("直接进\"待上架\"，等有 market:publish 的人检查").isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE template_code = 'MARKET_SUBMITTED' AND title LIKE ?", Integer.class,
        "%" + f.marketNo() + "%")).as("提醒有上架权限的人").isPositive();
    assertThat(fm.category()).isEqualTo("MACRO");
    assertThat(fm.resolutionMode()).isEqualTo("PLATFORM");
    assertThat(fm.origin()).isEqualTo("econ");
    assertThat(fm.createdBy()).as("系统生成").isNull();
    assertThat(fm.title().zhCN()).isEqualTo("美联储 2026 年 12 月 议息：联邦基金利率目标区间会降、维持还是加？");
    assertThat(fm.title().en()).startsWith("Fed December 2026 meeting");
    assertThat(fm.title().zhTW()).contains("聯準會");
    assertThat(fm.options()).extracting(o -> o.label().zhCN()).containsExactly("降息", "维持", "加息");
    assertThat(fm.rules().zhCN()).contains("（4.50%）").contains("federalreserve.gov").contains("发布前 1 小时");
    assertThat(fm.sourcePrimary()).isEqualTo("https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm");
    assertThat(fm.closeAt()).isEqualTo(fomcAt.minus(Duration.ofHours(1)).toString());
    assertThat(fm.eventStartAt()).isEqualTo(fomcAt.toString());
    assertThat(fm.disputeWindowMinutes()).isNotNull();
    assertThat(fm.missing()).as("必填项齐全，可以检查上架").isEmpty();
    assertThat(fm.sourceRef()).containsEntry("econKind", "FOMC");

    MarketAdminApi.Detail cm = market(c.marketNo());
    assertThat(cm.title().zhCN()).isEqualTo("美国 2026 年 11 月 CPI 同比：高于、等于还是低于 3.0%？");
    assertThat(cm.options()).extracting(o -> o.label().en()).containsExactly("Above 3.0%", "Equal to 3.0%", "Below 3.0%");
    assertThat(cm.rules().en()).contains("CPI-U").contains("first published");
    assertThat(cm.missing()).isEmpty();
    // 草稿列表能按来源（经济日历）看
    assertThat(admin.list("draft", "MACRO", null, null, null, 1, 100).items()).filteredOn(s -> s.publicNo().equals(c.marketNo()))
        .extracting(MarketAdminApi.Summary::origin).containsExactly("econ");
    // 人检查后上架（有 market:publish 的人；程序没有这个能力）
    assertThat(admin.publish(Long.parseLong(c.marketNo()), true, 1).status()).isEqualTo("listed");
    assertThat(econ(cpi.id()).marketStatus()).isEqualTo("listed");
    // 再跑一次不会重复生成
    tasks.runOnce();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_question WHERE JSON_EXTRACT(source_ref, '$.econEventId') = ?", Integer.class,
        Long.parseLong(fomc.id()))).isEqualTo(1);
  }

  private ContentAdminApi.Econ econ(String id) {
    return content.econ().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
  }

  @Test
  void 录入检查_CPI要上月值_发布时间太近_同一时间重复() {
    Instant at = release(12);
    assertThatThrownBy(() -> content.addEcon(new ContentAdminApi.EconInput("CPI", "2026-11", at.toString(), null, null, null), 1))
        .isInstanceOf(ValidationException.class).satisfies(e -> assertThat(((ValidationException) e).fields()).extracting(ValidationException.Field::code)
            .contains("cpiOneDecimal"));
    assertThatThrownBy(() -> content.addEcon(new ContentAdminApi.EconInput("FOMC", "2026-11", Instant.now(clock).plus(Duration.ofMinutes(30)).toString(), null,
        null, null), 1)).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> content.addEcon(new ContentAdminApi.EconInput("NFP", "2026-11", at.toString(), null, null, null), 1))
        .isInstanceOf(ValidationException.class);
    content.addEcon(new ContentAdminApi.EconInput("FOMC", "2026-11", at.toString(), null, null, null), 1);
    assertThatThrownBy(() -> content.addEcon(new ContentAdminApi.EconInput("FOMC", "2026-11", at.toString(), null, null, null), 1))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void AC103_命中敏感词的不生成草稿_记已过滤日志() {
    jdbc.update("INSERT IGNORE INTO content_word (kind, word, built_in, created_at) VALUES ('SENSITIVE', '议息', 0, UTC_TIMESTAMP(3))");
    try {
      ContentAdminApi.Econ e = content.addEcon(new ContentAdminApi.EconInput("FOMC", "2027-01", release(11).toString(), null, null, null), 1);
      ContentAdminApi.Econ after = content.draftEcon(Long.parseLong(e.id()), 1);
      assertThat(after.status()).isEqualTo("FILTERED");
      assertThat(after.marketNo()).isNull();
      assertThat(after.note()).contains("议息");
      List<Map<String, Object>> rows = jdbc.queryForList("SELECT reason, matched FROM content_filter_log WHERE source = 'ECON' AND ref = ?", e.id());
      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).get("reason")).isEqualTo("KEYWORD");
      assertThat(content.candidates().filtered()).anyMatch(f -> f.ref().equals(e.id()) && f.matched().contains("议息"));
    } finally {
      jdbc.update("DELETE FROM content_word WHERE kind = 'SENSITIVE' AND word = '议息'");
    }
  }

  @Test
  void 来不及生成的_标过期() {
    ContentAdminApi.Econ e = content.addEcon(new ContentAdminApi.EconInput("FOMC", "2026-10", release(1).toString(), null, null, null), 1);
    advance(Duration.ofDays(1));
    tasks.runOnce();
    assertThat(econ(e.id()).status()).isEqualTo("EXPIRED");
    assertThat(econ(e.id()).marketNo()).isNull();
  }

  @Test
  void 没有key_经济日历草稿照常生成() {
    contentProps.getGemini().setApiKey(null);
    ContentAdminApi.Econ e = content.addEcon(new ContentAdminApi.EconInput("CPI", "2027-02", release(13).toString(), "2.9", null, null), 1);
    assertThat(content.draftEcon(Long.parseLong(e.id()), 1).status()).isEqualTo("DRAFTED");
    assertThat(GEMINI.hits()).isZero();
  }

  @Test
  void 模板_禁用词和不认识的变量不能保存_改了只影响之后生成的() {
    ContentAdminApi.Template t = content.templates().stream().filter(x -> x.code().equals("CPI")).findFirst().orElseThrow();
    ContentAdminApi.TemplateInput bad = new ContentAdminApi.TemplateInput(new ContentAdminApi.Text(t.title().en(), t.title().zhCN() + "稳赚", t.title().zhTW()),
        t.rules(), t.options(), t.sourcePrimary(), t.sourceBackup(), t.version());
    assertThatThrownBy(() -> content.updateTemplate("CPI", bad, 1)).isInstanceOf(BizException.class)
        .satisfies(e -> assertThat(((BizException) e).code()).isEqualTo("CONTENT_BANNED_WORDS"));
    ContentAdminApi.TemplateInput unknown = new ContentAdminApi.TemplateInput(new ContentAdminApi.Text(t.title().en() + " {foo}", t.title().zhCN(),
        t.title().zhTW()), t.rules(), t.options(), t.sourcePrimary(), t.sourceBackup(), t.version());
    assertThatThrownBy(() -> content.updateTemplate("CPI", unknown, 1)).isInstanceOf(ValidationException.class);
    ContentAdminApi.TemplateInput ok = new ContentAdminApi.TemplateInput(new ContentAdminApi.Text(t.title().en(), "（新）" + t.title().zhCN(), t.title().zhTW()),
        t.rules(), t.options(), t.sourcePrimary(), t.sourceBackup(), t.version());
    ContentAdminApi.Template saved = content.updateTemplate("CPI", ok, 1);
    try {
      assertThat(saved.version()).isEqualTo(t.version() + 1);
      ContentAdminApi.Econ e = content.addEcon(new ContentAdminApi.EconInput("CPI", "2027-03", release(14).toString(), "2.8", null, null), 1);
      ContentAdminApi.Econ d = content.draftEcon(Long.parseLong(e.id()), 1);
      assertThat(market(d.marketNo()).title().zhCN()).startsWith("（新）美国 2027 年 3 月 CPI");
      // 版本号不对（别人刚改过）
      assertThatThrownBy(() -> content.updateTemplate("CPI", ok, 1)).isInstanceOf(BizException.class);
    } finally {
      content.updateTemplate("CPI", new ContentAdminApi.TemplateInput(t.title(), t.rules(), t.options(), t.sourcePrimary(), t.sourceBackup(), saved.version()), 1);
    }
  }
}
