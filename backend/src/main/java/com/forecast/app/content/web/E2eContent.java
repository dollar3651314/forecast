package com.forecast.app.content.web;

import com.forecast.app.common.e2e.E2eGuard;
import com.forecast.app.content.ai.GeminiClient;
import com.forecast.app.content.core.ContentProperties;
import com.forecast.app.content.job.ContentTasks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 端到端测试的 Gemini 替身和触发（只在 profile e2e 下存在，forecast S15）。e2e 配置里 Gemini 的地址指向这里（application-e2e.yml），
 * 所以端到端走的是<b>真实的客户端（超时、重试、熔断、额度、NF-16 检查）和起草、检查、发布流程</b>，只是"模型"是这个替身。<b>测试里不连真实的 Gemini。</b>
 * <ul>
 *   <li>{@code POST /api/e2e/gemini/v1beta/models/{model}:generateContent}：按提示里的"数据"清单拼一段文字（只用清单里的数字，所以数字核对能通过）；
 *       模式 {@code wrongNumber} 加一个清单里没有的数字、{@code banned} 加"稳赚"、{@code error500} 返回 500、{@code badJson} 返回不是 JSON 的文字。</li>
 *   <li>{@code POST /api/e2e/gemini/mode} {@code {"mode": "ok", "key": true}}：设模式；key = false 时去掉 key（"没有 key 时降级"），true 时放回测试值。</li>
 *   <li>{@code GET /api/e2e/gemini/requests}：替身收到的全部提示（AC-116：核对里面没有客户的个人数据）。</li>
 *   <li>{@code POST /api/e2e/content/tick}：立即跑一轮定时任务；{@code POST /api/e2e/content/pack}：立即做一次每日草稿包。</li>
 * </ul>
 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eContent {

  static final ObjectMapper JSON = new ObjectMapper();
  /** 测试用的 key（明显不是真的） */
  static final String E2E_KEY = "ci_only_e2e_gemini";

  private final ContentTasks tasks;
  private final ContentProperties props;
  private final GeminiClient gemini;
  private volatile String mode = "ok";
  private final List<String> prompts = new CopyOnWriteArrayList<>();

  E2eContent(ContentTasks tasks, ContentProperties props, GeminiClient gemini) {
    this.tasks = tasks;
    this.props = props;
    this.gemini = gemini;
  }

  record Mode(String mode, Boolean key) {}

  @PostMapping("/api/e2e/gemini/mode")
  Map<String, Object> mode(@RequestBody Mode m) {
    if (m != null && m.mode() != null) mode = m.mode();
    if (m != null && m.key() != null) props.getGemini().setApiKey(m.key() ? E2E_KEY : null);
    gemini.breaker().reset();
    return Map.of("mode", mode, "configured", gemini.configured());
  }

  @GetMapping("/api/e2e/gemini/requests")
  List<String> requests() {
    return List.copyOf(prompts);
  }

  @PostMapping("/api/e2e/gemini/v1beta/models/{call}")
  ResponseEntity<Object> generate(@PathVariable String call, @RequestBody JsonNode body) {
    String prompt = body.path("contents").path(0).path("parts").path(0).path("text").asString("");
    prompts.add(prompt);
    if ("error500".equals(mode)) return ResponseEntity.status(500).body(Map.of("error", Map.of("code", 500, "message", "e2e: 500")));
    String text;
    if ("badJson".equals(mode)) text = "not json";
    else {
      boolean en = prompt.startsWith("You write");
      List<String> lines = new ArrayList<>();
      boolean data = false;
      for (String line : prompt.split("\n")) {
        if (line.startsWith("DATA") || line.startsWith("数据（")) {
          data = true;
          continue;
        }
        if (data && line.startsWith("- ")) {
          // "- 标签：值（来源，时间）" → "标签：值"
          String s = line.substring(2);
          int cut = en ? s.lastIndexOf(" (") : s.lastIndexOf('（');
          lines.add(cut > 0 ? s.substring(0, cut) : s);
        }
        if (lines.size() >= 4) break;
      }
      String b = String.join(en ? ". " : "；", lines) + (en ? "." : "。");
      if ("wrongNumber".equals(mode)) b += en ? " Another 987654 people are watching." : "另有 987654 人在看。";
      if ("banned".equals(mode)) b += en ? " It is risk-free." : "稳赚。";
      ObjectNode out = JSON.createObjectNode();
      out.put("title", en ? "Data snapshot" : "数据速览");
      out.put("body", b);
      text = JSON.writeValueAsString(out);
    }
    ObjectNode resp = JSON.createObjectNode();
    ObjectNode cand = resp.putArray("candidates").addObject();
    cand.putObject("content").putArray("parts").addObject().put("text", text);
    cand.put("finishReason", "STOP");
    ObjectNode usage = resp.putObject("usageMetadata");
    usage.put("promptTokenCount", prompt.length() / 2);
    usage.put("candidatesTokenCount", text.length() / 2);
    return ResponseEntity.ok(resp);
  }

  @PostMapping("/api/e2e/content/tick")
  ContentTasks.Round tick() {
    return tasks.runOnce();
  }

  @PostMapping("/api/e2e/content/pack")
  Map<String, String> pack() {
    return tasks.pack();
  }
}
