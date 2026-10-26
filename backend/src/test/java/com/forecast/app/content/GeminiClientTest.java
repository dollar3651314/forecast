package com.forecast.app.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.content.ai.GeminiClient;
import com.forecast.app.content.core.Settings;
import com.forecast.app.pool.bet.PoolViews;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 调 Gemini（forecast S15；自动化需求第 6.4 节、C81、EQ-5、AC-115）：本机 HTTP 替身模拟超时、500、429、401、断开、格式错误；
 * 每次最多重试 2 次、间隔递增；429 暂停这一类功能；额度：文章起草最多用到上限的 80%，比分解析能用满；连续失败 10 次发一封告警（每天一封）；
 * 调用期间下注不受影响。
 */
class GeminiClientTest extends ContentTestSupport {

  @Autowired PlatformTransactionManager tm;

  private GeminiClient.Result call() {
    return gemini.generate(GeminiClient.Purpose.CONTENT, "你替一个预测市场网站写内容。\n\n数据（来源，时间）：\n- 奖池合计（USDT）：100.00（本站奖池，2026-10-14T10:00:00Z）\n",
        true, "test");
  }

  private int used() {
    return jdbc.queryForObject("SELECT COALESCE(SUM(used), 0) FROM content_ai_day", Integer.class);
  }

  @Test
  void 成功_计入用量_key在请求头不在地址里() {
    GeminiClient.Result r = call();
    assertThat(r.ok()).isTrue();
    assertThat(r.text()).contains("数据速览");
    assertThat(r.calls()).isEqualTo(1);
    assertThat(used()).isEqualTo(1);
    assertThat(GEMINI.requests().get(0).getPath()).isEqualTo("/v1beta/models/gemini-2.5-flash-lite:generateContent");
    assertThat(GEMINI.requests().get(0).toString()).doesNotContain("ci_only_test_gemini");
    assertThat(GEMINI.headers().get(0).get("x-goog-api-key")).isEqualTo("ci_only_test_gemini");
    assertThat(jdbc.queryForObject("SELECT tokens_in FROM content_ai_day", Long.class)).isEqualTo(3000);
  }

  @Test
  void 返回500和断开_重试2次_间隔递增_最后失败() {
    for (String m : new String[] {"500", "drop"}) {
      GEMINI.clear();
      gemini.breaker().reset();
      mode = m;
      long t0 = System.nanoTime();
      GeminiClient.Result r = call();
      long ms = (System.nanoTime() - t0) / 1_000_000;
      assertThat(r.outcome()).as(m).isEqualTo(GeminiClient.Outcome.FAILED);
      assertThat(r.calls()).isEqualTo(3);
      assertThat(GEMINI.hits()).isEqualTo(3);
      assertThat(ms).as("退避 20 + 40 毫秒").isGreaterThanOrEqualTo(60);
    }
  }

  @Test
  void 超过读取超时_重试2次_不会一直卡住() {
    mode = "slow"; // 1.5 秒，读取超时 1 秒
    long t0 = System.nanoTime();
    GeminiClient.Result r = call();
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(r.outcome()).isEqualTo(GeminiClient.Outcome.FAILED);
    assertThat(r.error()).contains("READ_TIMEOUT");
    assertThat(r.calls()).isEqualTo(3);
    assertThat(ms).isLessThan(6_000);
  }

  @Test
  void 被限流429_不再重试_这一类暂停() {
    mode = "429";
    GeminiClient.Result r = call();
    assertThat(r.outcome()).isEqualTo(GeminiClient.Outcome.FAILED);
    assertThat(r.calls()).isEqualTo(1);
    assertThat(gemini.paused()).isTrue();
    mode = "ok";
    GeminiClient.Result again = call();
    assertThat(again.outcome()).isEqualTo(GeminiClient.Outcome.PAUSED);
    assertThat(GEMINI.hits()).isEqualTo(1);
    // 10 分钟以后恢复
    advance(Duration.ofMinutes(11));
    assertThat(call().ok()).isTrue();
  }

  @Test
  void key无效401_不重试_发告警() {
    mode = "401";
    int mails = jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE template_code = 'CONTENT_ALERT'", Integer.class);
    GeminiClient.Result r = call();
    assertThat(r.outcome()).isEqualTo(GeminiClient.Outcome.KEY_INVALID);
    assertThat(r.calls()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE template_code = 'CONTENT_ALERT'", Integer.class)).isGreaterThan(mails);
  }

  @Test
  void 没有key_不调用() {
    contentProps.getGemini().setApiKey("");
    GeminiClient.Result r = call();
    assertThat(r.outcome()).isEqualTo(GeminiClient.Outcome.UNCONFIGURED);
    assertThat(GEMINI.hits()).isZero();
  }

  @Test
  void 每天上限_文章起草只能用到80百分比_比分解析能用满_到了当天不再调用() {
    setting(Settings.AI_DAILY_LIMIT, "5");
    // 5 × 80% = 4 次给文章起草
    for (int i = 0; i < 4; i++) assertThat(call().ok()).isTrue();
    GeminiClient.Result q = call();
    assertThat(q.outcome()).isEqualTo(GeminiClient.Outcome.QUOTA);
    assertThat(GEMINI.hits()).isEqualTo(4);
    // 比分解析（体育抓取）优先：还能用到 5 次
    assertThat(gemini.generate(GeminiClient.Purpose.SCORE, "比分", true, "score").ok()).isTrue();
    assertThat(gemini.generate(GeminiClient.Purpose.SCORE, "比分", true, "score").outcome()).isEqualTo(GeminiClient.Outcome.QUOTA);
    assertThat(GEMINI.hits()).isEqualTo(5);
    assertThat(content.status().geminiState()).isEqualTo("quota");
    // 第二天恢复
    advance(Duration.ofDays(1));
    assertThat(call().ok()).isTrue();
  }

  @Test
  void 连续失败10次_发一封告警_同一天不再发() {
    mode = "500";
    int mails = jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE template_code = 'CONTENT_ALERT'", Integer.class);
    for (int i = 0; i < 6; i++) {
      gemini.breaker().reset();
      call();
    }
    assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM content_ai_day", Integer.class)).isEqualTo(18);
    // 一次告警（发给每个有 content:publish 的在职管理员各一封），同一天不再发
    assertThat(jdbc.queryForObject("SELECT alerts FROM content_ai_day", String.class)).isEqualTo("failures,");
    int after = jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE template_code = 'CONTENT_ALERT'", Integer.class);
    assertThat(after).isGreaterThan(mails);
    gemini.breaker().reset();
    call();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE template_code = 'CONTENT_ALERT'", Integer.class)).isEqualTo(after);
  }

  @Test
  void 提示里有个人数据_整次不发() {
    GeminiClient.Result r = gemini.generate(GeminiClient.Purpose.CONTENT, "客户 a@b.co 的下注", true, "pii");
    assertThat(r.outcome()).isEqualTo(GeminiClient.Outcome.BLOCKED);
    assertThat(GEMINI.hits()).isZero();
    assertThat(used()).isZero();
  }

  @Test
  void 不能在数据库事务里调用() {
    TransactionTemplate tx = new TransactionTemplate(tm);
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> call())).isInstanceOf(IllegalStateException.class).hasMessageContaining("事务");
  }

  @Test
  void Gemini慢的时候下注照常_不等它() throws Exception {
    Market m = sportsMarket("隔离 " + System.nanoTime(), Instant.now(clock).plus(Duration.ofDays(1)), false);
    User u = user("100");
    mode = "slow";
    CompletableFuture<GeminiClient.Result> slow = CompletableFuture.supplyAsync(this::call);
    Thread.sleep(200);
    long t0 = System.nanoTime();
    PoolViews.BetResult b = bet(u.id(), m, 0, "10");
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(b).isNotNull();
    assertThat(ms).as("下注不等 Gemini").isLessThan(1_000);
    assertThat(slow.isDone()).isFalse();
    assertThat(slow.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(GeminiClient.Outcome.FAILED);
  }
}
