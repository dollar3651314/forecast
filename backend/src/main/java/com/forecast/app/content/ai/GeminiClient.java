package com.forecast.app.content.ai;

import com.forecast.app.common.http.ExternalHttp;
import com.forecast.app.content.check.PiiGuard;
import com.forecast.app.content.core.ContentLog;
import com.forecast.app.content.core.ContentMapper;
import com.forecast.app.content.core.ContentProperties;
import com.forecast.app.content.core.ContentRows;
import com.forecast.app.content.core.Settings;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 调用 Gemini（forecast S15；自动化需求第 6 节、C81、NF-16、EQ-5；架构方案第 6.6 节）。
 * <ol>
 *   <li><b>不能在数据库事务里调用</b>（外部变慢时不能占住连接和锁），在事务里调用直接抛 {@link IllegalStateException}。</li>
 *   <li><b>没有 key</b>（{@code FC_GEMINI_API_KEY} 没填）：不调用，返回 {@link Outcome#UNCONFIGURED}；所有用 Gemini 的功能降级（卡片、数据页、市场草稿照常）。</li>
 *   <li><b>NF-16</b>：提示里有像邮箱、链上地址、key 的字符串（{@link PiiGuard}）就整次不发，返回 {@link Outcome#BLOCKED}，记日志（只记种类）。</li>
 *   <li><b>每天上限</b>（{@code content_ai_day}，条件更新计数，多实例也不会超）：比分解析（{@link Purpose#SCORE}，体育抓取，裁决比写文章重要）能用到上限；
 *       文章起草（{@link Purpose#CONTENT}）只能用到上限的 {@code ai.contentSharePct}%（默认 80%）。每次 HTTP 请求（含重试）算一次。</li>
 *   <li><b>超时</b>：连接 5 秒、读取 30 秒（读完响应体的总时长）；<b>重试</b>：最多再试 2 次、间隔递增（2、4 秒，不超过 8 秒），只重试超时、连不上、断开、
 *       5xx、429、408；<b>熔断</b>：连续 5 次失败停 60 秒；收到 429 这一类功能暂停 10 分钟（{@link Breaker#pause}）。</li>
 *   <li>key 放在请求头 {@code x-goog-api-key}（不在地址里），日志、异常信息、存档里都没有 key。</li>
 * </ol>
 * 告警（每天每种最多一封，{@link AiAlerts}）：未配置、key 不可用（401 / 403）、额度用完、连续失败 10 次。
 */
@Component
public class GeminiClient {

  private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
  private static final Pattern MODEL = Pattern.compile("[a-z0-9][a-z0-9.\\-]{1,63}");
  private static final ObjectMapper JSON = new ObjectMapper();
  /** 连续失败多少次发告警（自动化需求第 6.4 节） */
  static final int ALERT_AFTER_FAILURES = 10;

  /** 用途：比分解析优先 */
  public enum Purpose {
    CONTENT,
    SCORE
  }

  public enum Outcome {
    OK,
    /** 没有配置 key */
    UNCONFIGURED,
    /** 提示里有个人数据或密钥样的字符串，没有发 */
    BLOCKED,
    /** 今天的额度用完了（或文章起草的那一份用完了） */
    QUOTA,
    /** 熔断中 / 被限流暂停中，没有调用 */
    PAUSED,
    /** key 无效、被停用（401 / 403） */
    KEY_INVALID,
    /** 调用失败（超时、5xx、格式不对……，重试过了） */
    FAILED
  }

  /**
   * 一次起草调用的结果。
   *
   * @param text 模型返回的文字（OK 时）
   * @param calls 实际发出的 HTTP 请求数（计入额度）
   */
  public record Result(Outcome outcome, String text, String error, int calls) {

    public boolean ok() {
      return outcome == Outcome.OK;
    }
  }

  private final ContentProperties props;
  private final ContentMapper mapper;
  private final Settings settings;
  private final ContentLog contentLog;
  private final AiAlerts alerts;
  private final Clock clock;
  private final ExternalHttp http;
  private final Breaker breaker;

  @Autowired
  GeminiClient(ContentProperties props, ContentMapper mapper, Settings settings, ContentLog contentLog, AiAlerts alerts, Clock clock) {
    this(props, mapper, settings, contentLog, alerts, clock, new ExternalHttp());
  }

  GeminiClient(ContentProperties props, ContentMapper mapper, Settings settings, ContentLog contentLog, AiAlerts alerts, Clock clock, ExternalHttp http) {
    this.props = props;
    this.mapper = mapper;
    this.settings = settings;
    this.contentLog = contentLog;
    this.alerts = alerts;
    this.clock = clock;
    this.http = http;
    this.breaker = new Breaker(props.getGemini().getFailureThreshold(), props.getGemini().getOpenDuration(), clock);
  }

  /** 有没有配置 key（后台只显示"已配置 / 未配置"） */
  public boolean configured() {
    return props.getGemini().keyConfigured();
  }

  /** 现在是不是暂停中（熔断、被限流） */
  public boolean paused() {
    return breaker.isOpen();
  }

  public Breaker breaker() {
    return breaker;
  }

  /** 当天（UTC）文章起草还能不能调用：有 key、没暂停、额度没到 */
  public boolean contentAvailable() {
    if (!configured() || paused()) return false;
    ContentRows.AiDay d = mapper.aiDay(today());
    return d == null || d.used < cap(Purpose.CONTENT);
  }

  /** 这个用途今天最多能用到多少（当天总数） */
  public int cap(Purpose p) {
    int limit = settings.integer(Settings.AI_DAILY_LIMIT, 300);
    if (p == Purpose.SCORE) return limit;
    int share = settings.integer(Settings.AI_CONTENT_SHARE, 80);
    return limit * share / 100;
  }

  /**
   * 调一次（含重试）。
   *
   * @param what 日志里的说明（例如"PREVIEW zh 123"），不含提示原文
   * @param json true：要求返回 JSON（responseMimeType）
   */
  public Result generate(Purpose purpose, String prompt, boolean json, String what) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("不能在数据库事务里调用 Gemini（C81）");
    ContentProperties.Gemini g = props.getGemini();
    if (!configured()) {
      alerts.raise("unconfigured", "Gemini 未配置", "Gemini not configured", "没有配置 FC_GEMINI_API_KEY，AI 起草的部分停用", "FC_GEMINI_API_KEY is not set; AI drafting is off");
      return new Result(Outcome.UNCONFIGURED, null, "Gemini 未配置（FC_GEMINI_API_KEY）", 0);
    }
    List<String> pii = PiiGuard.scan(prompt);
    if (!pii.isEmpty()) {
      contentLog.write("BLOCKED", null, what, null, "BLOCKED", "提示里有不能发给 Gemini 的内容：" + String.join("、", pii) + "（NF-16），没有发送", 0, null);
      log.warn("Gemini 调用被拦下（{}）：{}", what, pii);
      return new Result(Outcome.BLOCKED, null, "提示里有不能发给 Gemini 的内容：" + String.join("、", pii), 0);
    }
    if (prompt.length() > g.getMaxPromptChars()) return new Result(Outcome.FAILED, null, "提示太长（" + prompt.length() + " 字）", 0);
    if (!MODEL.matcher(g.getModel()).matches()) return new Result(Outcome.FAILED, null, "FC_GEMINI_MODEL 不合法", 0);
    URI uri = URI.create(g.getBaseUrl().replaceAll("/+$", "") + "/v1beta/models/" + g.getModel() + ":generateContent");
    byte[] body = request(prompt, json);
    int calls = 0;
    String lastError = null;
    for (int attempt = 1; attempt <= g.getAttempts(); attempt++) {
      if (attempt > 1) sleep(backoff(g, attempt - 1));
      if (!breaker.allow()) {
        return new Result(Outcome.PAUSED, null, "Gemini 暂停中（连续失败或被限流）" + (lastError == null ? "" : "；上一次：" + lastError), calls);
      }
      LocalDate day = today();
      mapper.ensureAiDay(day);
      if (mapper.reserveAi(day, cap(purpose), purpose == Purpose.SCORE) != 1) {
        breaker.release();
        ContentRows.AiDay d = mapper.aiDay(day);
        alerts.raise("quota", "今日额度已用完", "Daily quota used up", "今天的调用已经达到上限（文章起草最多用到 " + cap(Purpose.CONTENT) + " 次）",
            "Today's calls reached the limit (drafting may use up to " + cap(Purpose.CONTENT) + ")");
        return new Result(Outcome.QUOTA, null, "今日额度已用完（已用 " + (d == null ? 0 : d.used) + " 次）", calls);
      }
      calls++;
      Attempt a = once(uri, body, g);
      if (a.ok) {
        breaker.success();
        mapper.aiSuccess(day, a.tokensIn, a.tokensOut);
        return new Result(Outcome.OK, a.text, null, calls);
      }
      lastError = a.error;
      mapper.aiFailure(day);
      if (a.status == 429) breaker.pause(g.getPauseOn429());
      else breaker.failure();
      failureAlert(day);
      if (a.status == 401 || a.status == 403) {
        alerts.raise("key", "Gemini key 不可用", "Gemini key rejected", "Gemini 返回 " + a.status + "，请检查 key", "Gemini returned " + a.status + "; please check the key");
        return new Result(Outcome.KEY_INVALID, null, "Gemini key 不可用（" + a.status + "）", calls);
      }
      if (!a.retryable) break;
      if (a.status == 429) break; // 被限流：这一类暂停 10 分钟，不再重试
    }
    log.warn("Gemini 调用失败（{}，{} 次）：{}", what, calls, lastError);
    return new Result(Outcome.FAILED, null, lastError, calls);
  }

  private void failureAlert(LocalDate day) {
    ContentRows.AiDay d = mapper.aiDay(day);
    if (d != null && d.consecutiveFailures >= ALERT_AFTER_FAILURES) {
      alerts.raise("failures", "Gemini 连续失败", "Gemini keeps failing", "已经连续失败 " + d.consecutiveFailures + " 次", d.consecutiveFailures + " failures in a row");
    }
  }

  private record Attempt(boolean ok, String text, String error, int status, boolean retryable, long tokensIn, long tokensOut) {}

  private Attempt once(URI uri, byte[] body, ContentProperties.Gemini g) {
    ExternalHttp.Response r;
    try {
      r = http.postJson(uri, g.getConnectTimeout(), g.getReadTimeout(), g.getMaxBodyBytes(), Map.of("x-goog-api-key", g.getApiKey().strip()), body);
    } catch (ExternalHttp.Failure f) {
      boolean retry = f.kind() != ExternalHttp.Kind.TOO_LARGE;
      return new Attempt(false, null, f.kind() + "：" + f.getMessage(), 0, retry, 0, 0);
    }
    int st = r.status();
    if (st < 200 || st > 299) {
      boolean retry = st >= 500 || st == 429 || st == 408;
      return new Attempt(false, null, "HTTP " + st + errorMessage(r.body()), st, retry, 0, 0);
    }
    try {
      JsonNode root = JSON.readTree(new String(r.body(), StandardCharsets.UTF_8));
      if (root.path("promptFeedback").hasNonNull("blockReason")) {
        return new Attempt(false, null, "模型拒绝：" + root.path("promptFeedback").path("blockReason").asString(), st, false, 0, 0);
      }
      StringBuilder sb = new StringBuilder();
      for (JsonNode p : root.path("candidates").path(0).path("content").path("parts")) sb.append(p.path("text").asString(""));
      if (sb.isEmpty()) return new Attempt(false, null, "返回里没有文字（finishReason " + root.path("candidates").path(0).path("finishReason").asString("") + "）", st, true, 0, 0);
      JsonNode u = root.path("usageMetadata");
      long in = u.path("promptTokenCount").asLong(0);
      long out = u.path("candidatesTokenCount").asLong(0);
      return new Attempt(true, sb.toString(), null, st, false, in, out);
    } catch (RuntimeException e) {
      return new Attempt(false, null, "返回不是合法的 JSON", st, true, 0, 0);
    }
  }

  /** 错误返回里的 message（截短；不含请求内容） */
  private static String errorMessage(byte[] body) {
    try {
      String m = JSON.readTree(new String(body, StandardCharsets.UTF_8)).path("error").path("message").asString("");
      return m.isBlank() ? "" : "：" + (m.length() > 200 ? m.substring(0, 200) : m);
    } catch (RuntimeException e) {
      return "";
    }
  }

  static byte[] request(String prompt, boolean json) {
    ObjectNode root = JSON.createObjectNode();
    ArrayNode contents = root.putArray("contents");
    ObjectNode c = contents.addObject();
    c.put("role", "user");
    c.putArray("parts").addObject().put("text", prompt);
    ObjectNode gc = root.putObject("generationConfig");
    gc.put("temperature", 0.4);
    gc.put("maxOutputTokens", 2048);
    if (json) gc.put("responseMimeType", "application/json");
    return JSON.writeValueAsBytes(root);
  }

  static Duration backoff(ContentProperties.Gemini g, int n) {
    Duration d = g.getBackoff().multipliedBy(1L << Math.min(10, n - 1));
    return d.compareTo(g.getMaxBackoff()) > 0 ? g.getMaxBackoff() : d;
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
