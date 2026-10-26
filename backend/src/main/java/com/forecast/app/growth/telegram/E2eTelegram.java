package com.forecast.app.growth.telegram;

import com.forecast.app.common.e2e.E2eGuard;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 端到端测试的 Telegram 替身（只在 profile e2e 下存在，forecast S16；约定 13）。e2e 配置里 Bot API 的地址指向这里（application-e2e.yml），
 * 所以端到端走的是<b>真实的客户端（超时、重试、限流、熔断）、取消息、命令、发帖和检查</b>，只是 Telegram 是假的、不连外网。
 * <ul>
 *   <li>{@code /api/e2e/telegram/bot{token}/{method}}：getMe、getUpdates（返回排队的消息）、sendMessage、sendPhoto（记下来）、getChatMember（按设的管理员回答）</li>
 *   <li>{@code POST /api/e2e/telegram/updates} {"chatId","chatType","title","fromId","languageCode","text"} 或 {"chatId","chatType","title","myChatMember":"member|left"}：排一条消息</li>
 *   <li>{@code GET /api/e2e/telegram/sent?chatId=}：机器人发出去的消息（方法、chat、文字 / 图片说明、图片字节数）</li>
 *   <li>{@code POST /api/e2e/telegram/admins} {"chatId","userId"}：这个用户是群管理员</li>
 *   <li>{@code POST /api/e2e/telegram/fail} {"method","status","times","retryAfter"}：接下来 times 次这个方法返回错误（429 带 retry_after）</li>
 *   <li>{@code POST /api/e2e/telegram/configured} {"configured": false}：模拟"没有配置 token"（机器人不启动、健康检查 alerts 提示）；true 恢复</li>
 *   <li>{@code POST /api/e2e/telegram/tick}：立即取一轮消息（不等长轮询）并发一轮帖子；{@code POST /api/e2e/telegram/reset}：清空</li>
 * </ul>
 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eTelegram {

  record Update(Long chatId, String chatType, String title, Long fromId, String languageCode, String text, String myChatMember, Boolean senderIsChat) {}

  record Sent(String method, String chatId, String text, int photoBytes) {}

  record Fail(String method, Integer status, Integer times, Integer retryAfter) {}

  private final TelegramProperties props;
  private final TelegramBot bot;
  private final TelegramPublisher publisher;
  private final ObjectMapper json;
  private final List<Map<String, Object>> queue = new CopyOnWriteArrayList<>();
  private final List<Sent> sent = new CopyOnWriteArrayList<>();
  private final List<String> admins = new CopyOnWriteArrayList<>();
  private final Map<String, Fail> fails = new java.util.concurrent.ConcurrentHashMap<>();
  private final AtomicLong updateId = new AtomicLong(1000);
  private final AtomicLong messageId = new AtomicLong(1);

  E2eTelegram(TelegramProperties props, TelegramBot bot, TelegramPublisher publisher, ObjectMapper json) {
    this.props = props;
    this.bot = bot;
    this.publisher = publisher;
    this.json = json;
  }

  // ---------------------------------------------------------------------
  // 假的 Bot API
  // ---------------------------------------------------------------------

  @PostMapping(value = "/api/e2e/telegram/bot{token}/{method}", consumes = "application/json")
  ResponseEntity<Map<String, Object>> api(@PathVariable String token, @PathVariable String method, @RequestBody(required = false) JsonNode body) {
    ResponseEntity<Map<String, Object>> f = failure(method);
    if (f != null) return f;
    JsonNode b = body == null ? json.createObjectNode() : body;
    return switch (method) {
      case "getMe" -> ok(Map.of("id", 777, "is_bot", true, "username", "fc_e2e_bot", "first_name", "Quick Come"));
      case "getUpdates" -> {
        long offset = b.path("offset").asLong(0);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> u : queue) if (((Number) u.get("update_id")).longValue() >= offset) out.add(u);
        queue.removeIf(u -> ((Number) u.get("update_id")).longValue() < offset);
        yield ok(out);
      }
      case "sendMessage" -> {
        String chat = b.path("chat_id").asString(b.path("chat_id").toString());
        sent.add(new Sent("sendMessage", chat, b.path("text").asString(""), 0));
        yield ok(Map.of("message_id", messageId.getAndIncrement(), "chat", Map.of("id", chat)));
      }
      case "getChatMember" -> {
        String key = b.path("chat_id").asString(b.path("chat_id").toString()) + ":" + b.path("user_id").asString(b.path("user_id").toString());
        yield ok(Map.of("status", admins.contains(key) ? "administrator" : "member"));
      }
      default -> ResponseEntity.status(404).body(Map.of("ok", false, "error_code", 404, "description", "Not Found: method " + method));
    };
  }

  @PostMapping(value = "/api/e2e/telegram/bot{token}/sendPhoto", consumes = "multipart/form-data")
  ResponseEntity<Map<String, Object>> photo(@PathVariable String token, @RequestParam("chat_id") String chatId, @RequestParam("caption") String caption,
      @RequestParam("photo") MultipartFile photo) {
    ResponseEntity<Map<String, Object>> f = failure("sendPhoto");
    if (f != null) return f;
    sent.add(new Sent("sendPhoto", chatId, caption, (int) photo.getSize()));
    Map<String, Object> chat = new LinkedHashMap<>();
    chat.put("id", chatId);
    if (chatId.startsWith("@")) chat.put("username", chatId.substring(1));
    return ok(Map.of("message_id", messageId.getAndIncrement(), "chat", chat));
  }

  private ResponseEntity<Map<String, Object>> failure(String method) {
    Fail f = fails.get(method);
    if (f == null || f.times() == null || f.times() <= 0) return null;
    fails.put(method, new Fail(f.method(), f.status(), f.times() - 1, f.retryAfter()));
    int status = f.status() == null ? 500 : f.status();
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("ok", false);
    b.put("error_code", status);
    b.put("description", status == 429 ? "Too Many Requests: retry after " + f.retryAfter() : status == 403 ? "Forbidden: bot was kicked" : "e2e: " + status);
    if (status == 429) b.put("parameters", Map.of("retry_after", f.retryAfter() == null ? 1 : f.retryAfter()));
    return ResponseEntity.status(status).body(b);
  }

  private static ResponseEntity<Map<String, Object>> ok(Object result) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("ok", true);
    m.put("result", result);
    return ResponseEntity.ok(m);
  }

  // ---------------------------------------------------------------------
  // 测试用的控制接口
  // ---------------------------------------------------------------------

  @PostMapping("/api/e2e/telegram/updates")
  Map<String, Object> push(@RequestBody Update u) {
    long id = updateId.getAndIncrement();
    Map<String, Object> chat = new LinkedHashMap<>();
    chat.put("id", u.chatId());
    chat.put("type", u.chatType() == null ? "private" : u.chatType());
    if (u.title() != null) chat.put("title", u.title());
    Map<String, Object> upd = new LinkedHashMap<>();
    upd.put("update_id", id);
    if (u.myChatMember() != null) {
      upd.put("my_chat_member", Map.of("chat", chat, "new_chat_member", Map.of("status", u.myChatMember(), "user", Map.of("id", 777, "is_bot", true))));
    } else {
      Map<String, Object> from = new LinkedHashMap<>();
      from.put("id", u.fromId() == null ? 42 : u.fromId());
      from.put("is_bot", false);
      if (u.languageCode() != null) from.put("language_code", u.languageCode());
      Map<String, Object> msg = new LinkedHashMap<>();
      msg.put("message_id", id);
      msg.put("chat", chat);
      msg.put("from", from);
      msg.put("text", u.text() == null ? "" : u.text());
      if (Boolean.TRUE.equals(u.senderIsChat())) msg.put("sender_chat", Map.of("id", u.chatId()));
      upd.put("message", msg);
    }
    queue.add(upd);
    return Map.of("updateId", id);
  }

  @GetMapping("/api/e2e/telegram/sent")
  List<Sent> sent(@RequestParam(required = false) String chatId) {
    return sent.stream().filter(s -> chatId == null || chatId.equals(s.chatId())).toList();
  }

  @PostMapping("/api/e2e/telegram/admins")
  Map<String, Object> admin(@RequestBody Map<String, Object> b) {
    admins.add(b.get("chatId") + ":" + b.get("userId"));
    return Map.of("ok", true);
  }

  @PostMapping("/api/e2e/telegram/fail")
  Map<String, Object> fail(@RequestBody Fail f) {
    fails.put(f.method(), f);
    return Map.of("ok", true);
  }

  @PostMapping("/api/e2e/telegram/configured")
  Map<String, Object> configured(@RequestBody Map<String, Boolean> b) {
    Boolean v = b.get("configured");
    props.e2eConfigured(Boolean.TRUE.equals(v) ? null : Boolean.FALSE);
    return Map.of("configured", props.configured());
  }

  @PostMapping("/api/e2e/telegram/tick")
  Map<String, Object> tick() {
    int polled = bot.pollOnce(Duration.ZERO);
    int posted = publisher.publishDue();
    return Map.of("polled", polled, "posted", posted, "configured", props.configured());
  }

  @PostMapping("/api/e2e/telegram/reset")
  Map<String, Object> reset() {
    queue.clear();
    sent.clear();
    admins.clear();
    fails.clear();
    props.e2eConfigured(null);
    return Map.of("ok", true);
  }
}
