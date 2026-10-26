package com.forecast.app.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.common.error.BizException;
import com.forecast.app.content.api.ContentAdminApi;
import com.forecast.app.content.core.Settings;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 草稿箱（forecast S15；自动化需求第 4 节，AC-106～AC-111、AC-114、AC-116、AC-118）：Gemini 全部是本机替身。
 */
class S15DraftsTest extends ContentTestSupport {

  private Market bettedMatch() throws Exception {
    Market m = sportsMarket("焦点战 " + System.nanoTime(), Instant.now(clock).plus(Duration.ofDays(1)), true);
    User a = user("500");
    User b = user("500");
    bet(a.id(), m, 0, "60");
    bet(b.id(), m, 1, "40");
    return m;
  }

  @Test
  void AC106_赛前分析_中英文两条_带引用的数据和过期时间_待审核_没有任何自动发布() throws Exception {
    Market m = bettedMatch();
    List<ContentAdminApi.Draft> g = group(generate("PREVIEW", m.no(), null, null));
    assertThat(g).hasSize(2).extracting(ContentAdminApi.Draft::lang).containsExactlyInAnyOrder("zh", "en");
    for (ContentAdminApi.Draft d : g) {
      assertThat(d.status()).as(d.lang() + " " + d.failReason()).isEqualTo("PENDING");
      assertThat(d.checkOk()).as(String.valueOf(d.checks())).isTrue();
      assertThat(d.title()).isNotBlank();
      assertThat(d.body()).isNotBlank();
      assertThat(d.refs()).extracting(ContentAdminApi.RefView::key).contains("market.total", "market.bettors", "option.1.pct", "option.2.amount");
      assertThat(d.refs()).filteredOn(r -> r.key().equals("market.total")).first().extracting(ContentAdminApi.RefView::value).isEqualTo("100.00");
      assertThat(d.refs()).allSatisfy(r -> assertThat(r.source()).isNotBlank());
      assertThat(d.expiresAt()).as("赛前分析开赛时过期").isEqualTo(Instant.now(clock).plus(Duration.ofDays(1)).toString());
      assertThat(d.canPublish()).isTrue();
      assertThat(d.copyOnly()).isFalse();
      assertThat(d.publishedAt()).isNull();
    }
    // 英文稿用英文的提示单独起草（MQ-8：不是翻译中文稿）
    assertThat(prompts()).anyMatch(p -> p.startsWith("You write") && p.contains("Write in English")).anyMatch(p -> p.contains("用简体中文写"));
    // 没有任何一条被自动发出
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_log WHERE action = 'PUBLISH' AND ref IN (?, ?)", Integer.class, g.get(0).draftNo(),
        g.get(1).draftNo())).isZero();
    // 每条一次 Gemini 调用；用量记下了
    assertThat(GEMINI.hits()).isEqualTo(2);
    assertThat(content.status().contentUsedToday()).isEqualTo(2);
  }

  @Test
  void AC106_帖子和行情短文_审核以后才能复制_复制带来源参数的链接_回复没有发布() throws Exception {
    Market m = bettedMatch();
    ContentAdminApi.Draft x = lang(group(generate("POST_X", m.no(), null, null)), "zh");
    assertThat(x.copyOnly()).isTrue();
    assertThat(x.canCopy()).as("没审核不能复制").isFalse();
    assertThatThrownBy(() -> content.copied(Long.parseLong(x.draftNo()), 1)).isInstanceOf(BizException.class);
    ContentAdminApi.Draft p = content.publish(Long.parseLong(x.draftNo()), 1);
    assertThat(p.status()).isEqualTo("PUBLISHED");
    assertThat(p.canCopy()).isTrue();
    assertThat(p.link()).isEqualTo("https://app.forecast.test/m/" + m.no() + "?src=x&cmp=post-post-x");
    content.copied(Long.parseLong(x.draftNo()), 1);
    assertThat(logs("COPY", x.draftNo())).isEqualTo(1);
    // 行情短文：BTC 市场 + BTC 每小时统计（数据管道）
    Market btc = btcMarket(Instant.now(clock).plus(Duration.ofHours(3)).truncatedTo(java.time.temporal.ChronoUnit.HOURS));
    ContentAdminApi.Draft brief = lang(group(generate("BRIEF", btc.no(), null, null)), "en");
    assertThat(brief.status()).as(brief.failReason()).isEqualTo("PENDING");
    assertThat(brief.refs()).extracting(ContentAdminApi.RefView::key).contains("btc.up24", "btc.up7dPct", "market.total");
    assertThat(brief.refs()).filteredOn(r -> r.key().startsWith("btc.")).allSatisfy(r -> assertThat(r.source()).contains("Binance BTC/USDT"));
  }

  @Test
  void AC107_数字对不上_标红_不能发布_改正以后能发布() throws Exception {
    Market m = bettedMatch();
    mode = "wrongNumber";
    List<ContentAdminApi.Draft> g = group(generate("PREVIEW", m.no(), null, null));
    // 每条起草两次（第一次不合格重新起草），仍然对不上：待审核但标红
    assertThat(GEMINI.hits()).isEqualTo(4);
    ContentAdminApi.Draft zh = lang(g, "zh");
    assertThat(zh.status()).isEqualTo("PENDING");
    assertThat(zh.checkOk()).isFalse();
    assertThat(zh.canPublish()).isFalse();
    assertThat(zh.checks().numbers()).extracting(ContentAdminApi.NumberHit::text).containsExactly("987654");
    ContentAdminApi.NumberHit hit = zh.checks().numbers().get(0);
    assertThat(zh.body().substring(hit.start(), hit.end())).isEqualTo("987654");
    assertThatThrownBy(() -> content.publish(Long.parseLong(zh.draftNo()), 1)).isInstanceOf(BizException.class)
        .satisfies(e -> assertThat(((BizException) e).code()).isEqualTo("DRAFT_CHECK_FAILED"));
    // 改正后能发布
    ContentAdminApi.Draft fixed = content.edit(Long.parseLong(zh.draftNo()), new ContentAdminApi.EditInput(zh.title(), zh.body().replace("另有 987654 人在看。", ""),
        zh.version()), 1);
    assertThat(fixed.checkOk()).isTrue();
    assertThat(content.publish(Long.parseLong(zh.draftNo()), 1).status()).isEqualTo("PUBLISHED");
    assertThat(jdbc.queryForList("SELECT outcome FROM content_log WHERE action = 'PUBLISH' AND ref = ? ORDER BY id", String.class, zh.draftNo()))
        .containsExactly("BLOCKED", "OK");
  }

  @Test
  void AC108_禁用词_重新起草一次仍有就生成失败_人工加禁用词或倾向不能保存() throws Exception {
    Market m = bettedMatch();
    mode = "banned";
    List<ContentAdminApi.Draft> g = group(generate("POST_TG", m.no(), null, null));
    assertThat(GEMINI.hits()).isEqualTo(4);
    assertThat(lang(g, "zh").status()).isEqualTo("FAILED");
    assertThat(lang(g, "zh").failReason()).contains("稳赚");
    assertThat(lang(g, "en").failReason()).contains("risk-free");
    assertThat(lang(g, "zh").canPublish()).isFalse();
    // 正常的草稿，人工加禁用词：不保存
    mode = "ok";
    ContentAdminApi.Draft ok = lang(group(generate("POST_TG", m.no(), null, null)), "zh");
    for (String bad : List.of("这场稳赚", "建议押大")) {
      assertThatThrownBy(() -> content.edit(Long.parseLong(ok.draftNo()), new ContentAdminApi.EditInput(ok.title(), ok.body() + bad, ok.version()), 1))
          .isInstanceOf(BizException.class).satisfies(e -> assertThat(((BizException) e).code()).isEqualTo("CONTENT_BANNED_WORDS"));
    }
    assertThat(content.group(Long.parseLong(ok.draftNo())).stream().filter(d -> d.draftNo().equals(ok.draftNo())).findFirst().orElseThrow().body())
        .isEqualTo(ok.body());
    // 生成失败的可以重新起草
    mode = "ok";
    ContentAdminApi.Draft again = content.retry(Long.parseLong(lang(g, "zh").draftNo()), 1);
    assertThat(again.status()).isEqualTo("PENDING");
    assertThat(again.checkOk()).isTrue();
  }

  @Test
  void AC111_过期以后不能发布_帖子24小时() throws Exception {
    Market m = bettedMatch();
    ContentAdminApi.Draft x = lang(group(generate("POST_X", m.no(), null, null)), "en");
    assertThat(x.expiresAt()).isEqualTo(Instant.now(clock).plus(Duration.ofHours(24)).toString());
    advance(Duration.ofHours(25));
    assertThatThrownBy(() -> content.publish(Long.parseLong(x.draftNo()), 1)).isInstanceOf(BizException.class)
        .satisfies(e -> assertThat(((BizException) e).code()).isEqualTo("DRAFT_EXPIRED"));
    assertThat(content.group(Long.parseLong(x.draftNo())).stream().filter(d -> d.draftNo().equals(x.draftNo())).findFirst().orElseThrow().status())
        .isEqualTo("EXPIRED");
    // 另一条由定时任务标过期
    tasks.runOnce();
    assertThat(content.group(Long.parseLong(x.draftNo()))).allSatisfy(d -> assertThat(d.status()).isEqualTo("EXPIRED"));
  }

  @Test
  void AC114_没有key_文章类不生成_不调用Gemini_状态显示未配置_数据卡照常() throws Exception {
    Market m = bettedMatch();
    contentProps.getGemini().setApiKey(null);
    assertThat(content.status().geminiConfigured()).isFalse();
    assertThat(content.status().geminiState()).isEqualTo("unconfigured");
    assertThatThrownBy(() -> generate("PREVIEW", m.no(), null, null)).isInstanceOf(BizException.class)
        .satisfies(e -> assertThat(((BizException) e).code()).isEqualTo("AI_UNAVAILABLE")).hasMessageContaining("Gemini 未配置");
    // 数据卡（模板画图，不用 AI）照常
    ContentAdminApi.ToolResult card = content.dataCard("market", m.no(), "zh-CN");
    assertThat(card.image()).startsWith("data:image/png;base64,");
    assertThat(card.text()).contains("100.00 USDT");
    ContentAdminApi.ToolResult btc = content.dataCard("btc", null, "en");
    assertThat(btc.text()).contains("Last 7 days");
    // 对照图：图照常，回复草稿不起草
    ContentAdminApi.ToolResult cmp = content.compare(m.no(), "某大V说这场 90% 会出大球", null, "zh-CN", 1);
    assertThat(cmp.image()).startsWith("data:image/png;base64,");
    assertThat(cmp.replies()).isEmpty();
    assertThat(cmp.notes()).anyMatch(n -> n.contains("Gemini 未配置"));
    assertThat(GEMINI.hits()).isZero();
  }

  @Test
  void AC116_提示里没有客户的个人数据_塞进邮箱的请求被拦下并记日志() throws Exception {
    Market m = bettedMatch();
    generate("PREVIEW", m.no(), null, null);
    generate("ARTICLE", null, null, "彩池玩法怎么算");
    List<String> sent = prompts();
    assertThat(sent).hasSize(4);
    List<String> emails = jdbc.queryForList("SELECT email FROM account_user WHERE id IN (SELECT user_id FROM pool_position WHERE market_id = ?)", String.class,
        m.id());
    for (String p : sent) {
      assertThat(p).doesNotContain("@");
      for (String e : emails) assertThat(p).doesNotContain(e);
      assertThat(com.forecast.app.content.check.PiiGuard.scan(p)).isEmpty();
    }
    // 人为塞一个邮箱：整次不发，草稿生成失败，日志里有拦截记录
    GEMINI.clear();
    List<ContentAdminApi.Draft> g = group(generate("ARTICLE", null, null, "请联系 someone@example.com 了解规则"));
    assertThat(GEMINI.hits()).isZero();
    assertThat(g).allSatisfy(d -> {
      assertThat(d.status()).isEqualTo("FAILED");
      assertThat(d.failReason()).contains("email");
    });
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_log WHERE action = 'BLOCKED' AND detail LIKE '%email%' AND ref = ?", Integer.class,
        "ARTICLE zh " + lang(g, "zh").draftNo())).isEqualTo(1);
  }

  @Test
  void AC118_紧急停止_发布和复制都不能用_恢复以后照常_日志和用量() throws Exception {
    Market m = bettedMatch();
    ContentAdminApi.Draft d = lang(group(generate("PREVIEW", m.no(), null, null)), "zh");
    content.setStopped(true, 1);
    assertThat(content.status().stopped()).isTrue();
    assertThat(content.group(Long.parseLong(d.draftNo())).get(0).canPublish()).isFalse();
    assertThatThrownBy(() -> content.publish(Long.parseLong(d.draftNo()), 1)).isInstanceOf(BizException.class)
        .satisfies(e -> assertThat(((BizException) e).code()).isEqualTo("PUBLISH_STOPPED"));
    content.setStopped(false, 1);
    assertThat(content.publish(Long.parseLong(d.draftNo()), 1).status()).isEqualTo("PUBLISHED");
    assertThat(jdbc.queryForList("SELECT action FROM content_log WHERE action IN ('STOP', 'RESUME') ORDER BY id DESC LIMIT 2", String.class))
        .containsExactly("RESUME", "STOP");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_log WHERE action = 'GENERATE' AND ref = ?", Integer.class, d.draftNo())).isEqualTo(1);
    ContentAdminApi.Status s = content.status();
    assertThat(s.usedToday()).isEqualTo(2);
    assertThat(s.monthCalls()).isGreaterThanOrEqualTo(2);
    assertThat(s.monthCostUsd()).isNotBlank();
  }

  @Test
  void 每周文章上限_到了不生成() throws Exception {
    long done = jdbc.queryForObject("SELECT COUNT(DISTINCT group_no) FROM content_draft WHERE kind = 'ARTICLE' AND status NOT IN ('REJECTED', 'FAILED')"
        + " AND created_at >= '2026-10-12'", Long.class);
    setting(Settings.ARTICLES_WEEKLY, String.valueOf(done + 1));
    generate("ARTICLE", null, null, "第一次充值怎么做");
    assertThatThrownBy(() -> generate("ARTICLE", null, null, "怎么提现")).isInstanceOf(BizException.class)
        .satisfies(e -> assertThat(((BizException) e).code()).isEqualTo("ARTICLE_LIMIT"));
  }

  @Test
  void 每日草稿包_今天的场次起草_汇总邮件发给内容发布权限的人_一天一次() throws Exception {
    Market m = bettedMatch();
    // 让它列入今天（UTC 10-14）的场次
    jdbc.update("UPDATE market_question SET event_start_at = ?, close_at = ? WHERE id = ?", java.sql.Timestamp.valueOf("2026-10-14 20:00:00"),
        java.sql.Timestamp.valueOf("2026-10-14 19:55:00"), m.id());
    jdbc.update("DELETE FROM content_log WHERE action = 'DIGEST'");
    int mails = jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE template_code = 'CONTENT_DIGEST'", Integer.class);
    assertThat(tasks.runOnce().packed()).isTrue();
    assertThat(jdbc.queryForList("SELECT kind FROM content_draft WHERE market_no = ? GROUP BY kind ORDER BY kind", String.class, Long.parseLong(m.no())))
        .contains("POST_TG", "POST_X", "PREVIEW");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE template_code = 'CONTENT_DIGEST'", Integer.class)).isGreaterThan(mails);
    // 同一天第二轮不再做
    assertThat(tasks.runOnce().packed()).isFalse();
  }
}
