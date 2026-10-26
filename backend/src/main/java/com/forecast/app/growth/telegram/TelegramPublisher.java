package com.forecast.app.growth.telegram;

import com.forecast.app.market.api.PublicMarketsApi;
import com.forecast.app.notification.api.StaffAlertApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 往官方频道和开启了播报的群自动发开奖卡（forecast S16，MF-7、MQ-2；需求说明书第 6.3 节、AC-90～AC-93 里和开奖卡有关的部分；
 * SQ-22：公开频道只推开奖卡、和账户无关）。
 * <h2>发什么</h2>
 * 带"列入今日场次"标记的市场进入已结算 / 已作废 / 已取消后的<b>开奖卡</b>（S14 的同一张卡：图片 + 文字 + 带来源参数的市场链接）。
 * 中文频道发简体，英文频道发英文，群按开启播报时选的语言。没有标记的市场一张都不发；公示中的平台裁决市场还没结束，不发（结算后才发）。
 * <h2>发之前的检查（任何一项不通过就不发这一张，发布日志里写原因）</h2>
 * <ol>
 *   <li>紧急停止（后台按钮）：记"已停止"（STOPPED）；恢复后接着发之后的。</li>
 *   <li>渠道开关：频道开关关着 / 群关了播报：不建发布记录；建了以后才关的记 SKIPPED。只发开关打开以后结束的市场（打开时不补发以前的）。</li>
 *   <li>模板审定：这种语言的开奖卡模板不是"已审定"、或者审定以后模板文字改过（指纹不同）：SKIPPED，邮件提醒一次要重新审定。</li>
 *   <li>过期不补发：结束超过 {@code result-max-age}（24 小时）的不发。</li>
 *   <li>数字和系统一致：发之前同一张卡再生成一次，文字和第一次不同（数字在生成后变了）就不发。</li>
 * </ol>
 * <h2>发送</h2>
 * 每条发布记录先"认领"（attempts + 1、下次时间推到认领期之后的条件更新，多实例或重复触发时只有一个去发），在事务外调 Telegram，再记结果。
 * 频率限制：所有发帖之间至少隔 {@code send-interval}、同一个频道 / 群至少隔 {@code chat-interval}；429 按 {@code retry_after} 推迟（这个渠道一起暂停）。
 * 失败最多 3 次（间隔 1、5 分钟），都失败记 FAILED 并邮件提醒（每个渠道每小时最多一封）；<b>某个频道连续 3 次发送失败，自动关掉它的开关并邮件通知</b>；
 * 机器人被移出群（403）时记这个群 LEFT。帖子发出去以后要删，由 Amos 在 Telegram 里手动删（日志里有帖子链接）。
 */
@Service
public class TelegramPublisher {

  private static final Logger log = LoggerFactory.getLogger(TelegramPublisher.class);
  static final String KIND = "RESULT";
  static final String ALERT_TEMPLATE = "TELEGRAM_ALERT";
  /** 连续失败几次自动关渠道 */
  static final int AUTO_OFF = 3;
  /** 认领期：一次发送最长约 3 ×（3 + 15）秒 + 退避 */
  static final Duration CLAIM = Duration.ofMinutes(2);
  private static final Duration[] BACKOFF = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15)};
  private static final Set<String> DESC_KICKED = Set.of("bot was kicked", "bot is not a member", "chat not found", "group chat was upgraded", "bot was blocked");

  private final TelegramProperties props;
  private final TelegramClient client;
  private final TelegramMapper mapper;
  private final PublicMarketsApi markets;
  private final StaffAlertApi staff;
  private final Clock clock;
  private final String clientUrl;
  /** 频率限制（只在本实例内存里：发帖本身有租约，同一时间只有一个实例在发） */
  private final Map<String, Instant> chatNext = new ConcurrentHashMap<>();
  private volatile Instant globalNext = Instant.EPOCH;
  /** 模板文字改了的提醒：每种模板每个新指纹提醒一次 */
  private final Set<String> changedAlerted = ConcurrentHashMap.newKeySet();

  TelegramPublisher(TelegramProperties props, TelegramClient client, TelegramMapper mapper, PublicMarketsApi markets, StaffAlertApi staff, Clock clock,
      @Value("${forecast.client-url:}") String clientUrl) {
    this.props = props;
    this.client = client;
    this.mapper = mapper;
    this.markets = markets;
    this.staff = staff;
    this.clock = clock;
    this.clientUrl = clientUrl == null ? "" : clientUrl.strip().replaceAll("/+$", "");
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  /** 一个发布渠道：频道（CHANNEL_ZH / CHANNEL_EN）或群（G:群编号） */
  record Channel(String key, String chatId, String lang, Instant since) {}

  /** 跑一轮：先把新结束的市场登记成待发，再发到期的。返回发出的条数。没配置 token 时什么都不做。不能在事务里调用 */
  public int publishDue() {
    if (!props.configured()) return 0;
    enqueue();
    return sendDue();
  }

  /** 新结束的"今日场次"市场 × 打开的渠道：建发布记录（唯一键，一个市场一个渠道最多一条）；检查不通过的直接记原因 */
  void enqueue() {
    Instant now = clock.instant();
    Instant oldest = now.minus(props.getResultMaxAge());
    List<Channel> channels = channels();
    if (channels.isEmpty()) return;
    Instant earliest = channels.stream().map(Channel::since).filter(s -> s != null).min(Instant::compareTo).orElse(oldest);
    List<PublicMarketsApi.Ended> ended = markets.endedSlateSince(earliest.isBefore(oldest) ? oldest : earliest, 200);
    if (ended.isEmpty()) return;
    TelegramMapper.State st = mapper.state();
    Map<String, String> templateProblem = new HashMap<>();
    for (PublicMarketsApi.Ended e : ended) {
      if (e.endedAt() == null || e.endedAt().isBefore(oldest)) continue;
      for (Channel c : channels) {
        if (c.since() != null && e.endedAt().isBefore(c.since())) continue;
        String status = "PENDING";
        String note = null;
        if (st.emergencyStop) {
          status = "STOPPED";
          note = "紧急停止中，没有发";
        } else {
          String problem = templateProblem.computeIfAbsent(c.lang(), this::templateProblem);
          if (problem != null) {
            status = "SKIPPED";
            note = problem;
          }
        }
        mapper.insertPost(e.publicNo(), KIND, c.key(), c.chatId(), c.lang(), status, note, now());
      }
    }
  }

  /** 打开的渠道（频道：开关开着、填了编号；群：在群里、开着播报） */
  List<Channel> channels() {
    List<Channel> out = new ArrayList<>();
    for (TelegramMapper.Target t : mapper.targets()) {
      if (t.enabled && t.chatId != null && !t.chatId.isBlank()) out.add(new Channel(t.targetKey, t.chatId, t.lang, instant(t.enabledAt)));
    }
    for (TelegramMapper.Chat c : mapper.broadcastChats()) out.add(new Channel("G:" + c.chatId, String.valueOf(c.chatId), c.lang, instant(c.broadcastAt)));
    return out;
  }

  /** 这种语言的开奖卡模板能不能发；不能时返回原因 */
  String templateProblem(String lang) {
    TelegramMapper.Template t = mapper.template(KIND, lang);
    if (t == null || "DRAFT".equals(t.status)) return "模板是草稿（还没审定），没有发";
    if ("DISABLED".equals(t.status)) return "模板已停用，没有发";
    String fp = markets.cardTemplateFingerprint("result", lang);
    if (!fp.equals(t.fingerprint)) {
      if (changedAlerted.add(lang + "|" + fp)) {
        alert("开奖卡模板文字改过，要重新审定", "The result card template text changed and needs re-approval",
            "语言 " + lang + "：审定以后 content/cards.json 改过，改过的模板没有审定之前不发", "Language " + lang + ": cards.json changed after approval; nothing is posted until it is re-approved");
      }
      return "模板文字审定以后改过，要重新审定，没有发";
    }
    return null;
  }

  int sendDue() {
    int sent = 0;
    for (TelegramMapper.Post p : mapper.duePosts(now(), 50)) {
      if (Boolean.TRUE.equals(sendOne(p))) sent++;
    }
    return sent;
  }

  /** 发一条：认领 → 检查 → 生成卡片 → 调 Telegram → 记结果。返回是否发出 */
  Boolean sendOne(TelegramMapper.Post p) {
    LocalDateTime now = now();
    if (mapper.claim(p.id, p.attempts, now, now.plus(CLAIM)) != 1) return false;
    int attempts = p.attempts + 1;
    // ---- 检查 ----
    TelegramMapper.State st = mapper.state();
    if (st.emergencyStop) return finish(p, attempts, "STOPPED", "紧急停止中，没有发");
    Optional<String> off = channelOff(p);
    if (off.isPresent()) return finish(p, attempts, "SKIPPED", off.get());
    String problem = templateProblem(p.lang);
    if (problem != null) return finish(p, attempts, "SKIPPED", problem);
    if (p.createdAt.plus(props.getResultMaxAge()).isBefore(now)) return finish(p, attempts, "SKIPPED", "超过 24 小时还没发出去，过期不补发");
    Optional<PublicMarketsApi.PostCard> card = markets.resultCard(p.marketNo, p.lang);
    if (card.isEmpty()) return finish(p, attempts, "SKIPPED", "这个市场没有开奖卡（状态变了或者不存在）");
    Optional<PublicMarketsApi.PostCard> again = markets.resultCard(p.marketNo, p.lang);
    if (again.isEmpty() || !again.get().text().equals(card.get().text())) return finish(p, attempts, "SKIPPED", "卡片生成后数字和系统对不上，没有发");
    // ---- 频率限制 ----
    pace(p.chatId);
    String caption = caption(card.get().text(), link(p));
    try {
      JsonNode r = client.sendPhoto(p.chatId, card.get().png(), caption);
      long messageId = r.path("message_id").asLong();
      String url = postUrl(p.chatId, r.path("chat").path("username").asString(null), messageId);
      mapper.markSent(p.id, attempts, messageId, url, now());
      ok(p);
      log.info("Telegram：市场 {} 的开奖卡已发到 {}（{}）", p.marketNo, p.target, url == null ? "消息 " + messageId : url);
      return true;
    } catch (TelegramClient.Failure f) {
      return failed(p, attempts, f);
    } finally {
      // 频率限制按真实时间（和业务时钟无关：测试会拨业务时钟）
      Instant n = Instant.now();
      globalNext = n.plus(props.getSendInterval());
      chatNext.put(p.chatId, n.plus(props.getChatInterval()));
    }
  }

  private Boolean failed(TelegramMapper.Post p, int attempts, TelegramClient.Failure f) {
    LocalDateTime now = now();
    switch (f.kind()) {
      case RATE_LIMITED -> {
        // 限流不算失败：这个渠道（和全部发帖）暂停到 retry_after 之后
        long wait = Math.max(1, f.retryAfter());
        Instant until = Instant.now().plusSeconds(wait);
        chatNext.put(p.chatId, until);
        globalNext = until.isAfter(globalNext) ? until : globalNext;
        mapper.release(p.id, attempts, now.plusSeconds(wait), now);
        return false;
      }
      case NOT_CONFIGURED, CIRCUIT_OPEN -> {
        mapper.release(p.id, attempts, now.plusMinutes(1), now);
        return false;
      }
      case REJECTED -> {
        String desc = f.getMessage() == null ? "" : f.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (p.target.startsWith("G:") && DESC_KICKED.stream().anyMatch(desc::contains)) {
          mapper.chatLeft(Long.parseLong(p.chatId), now);
          return finish(p, attempts, "FAILED", "机器人已不在这个群，已关闭播报：" + cut(f.getMessage(), 300));
        }
        finish(p, attempts, "FAILED", "Telegram 拒绝：" + cut(f.getMessage(), 400));
        channelFailed(p, f.getMessage(), true);
        return false;
      }
      default -> {
        if (attempts >= props.getMaxAttempts()) {
          finish(p, attempts, "FAILED", "重试 " + attempts + " 次都失败：" + cut(f.getMessage(), 380));
          channelFailed(p, f.getMessage(), true);
        } else {
          mapper.finish(p.id, attempts, "PENDING", "第 " + attempts + " 次失败：" + cut(f.getMessage(), 380), now.plus(BACKOFF[Math.min(attempts - 1, BACKOFF.length - 1)]),
              now);
          channelFailed(p, f.getMessage(), false);
        }
        return false;
      }
    }
  }

  /** 一次发送失败：频道连续失败到 3 次自动关开关 + 邮件；最终失败时（每个渠道每小时最多一封）邮件提醒 */
  private void channelFailed(TelegramMapper.Post p, String message, boolean finalFailure) {
    LocalDateTime now = now();
    if (p.target.startsWith("G:")) {
      mapper.chatFailed(Long.parseLong(p.chatId), AUTO_OFF, now);
      return;
    }
    TelegramMapper.Target before = mapper.target(p.target);
    mapper.targetFailed(p.target, AUTO_OFF, "连续 " + AUTO_OFF + " 次发送失败，自动关闭：" + cut(message, 150), now);
    TelegramMapper.Target after = mapper.target(p.target);
    if (before != null && before.enabled && after != null && !after.enabled) {
      alert("频道 " + p.target + " 连续 " + AUTO_OFF + " 次发送失败，已自动关闭", "Channel " + p.target + " failed " + AUTO_OFF + " times in a row and was switched off",
          cut(message, 300) + "；检查机器人是不是还是频道管理员、频道编号对不对，再到后台打开", cut(message, 300) + "; check the bot is still an admin of the channel and the id is right, then switch it on again");
    } else if (finalFailure && mapper.targetAlert(p.target, now.minusHours(1), now) == 1) {
      alert("开奖卡没能发到 " + p.target, "A result card could not be posted to " + p.target, "市场 " + p.marketNo + "：" + cut(message, 300),
          "Market " + p.marketNo + ": " + cut(message, 300));
    }
  }

  private void ok(TelegramMapper.Post p) {
    if (p.target.startsWith("G:")) mapper.chatOk(Long.parseLong(p.chatId));
    else mapper.targetOk(p.target);
  }

  /** 发之前渠道关了 / 换了编号 / 群退出了：这一张不发 */
  private Optional<String> channelOff(TelegramMapper.Post p) {
    if (p.target.startsWith("G:")) {
      TelegramMapper.Chat c = mapper.chat(Long.parseLong(p.chatId));
      return c != null && "ACTIVE".equals(c.status) && c.broadcast ? Optional.empty() : Optional.of("群已关闭播报或机器人已不在群里，没有发");
    }
    TelegramMapper.Target t = mapper.target(p.target);
    if (t == null || !t.enabled) return Optional.of("渠道开关已关，没有发");
    if (!p.chatId.equals(t.chatId)) return Optional.of("频道编号改了，没有发到旧的频道");
    return Optional.empty();
  }

  private Boolean finish(TelegramMapper.Post p, int attempts, String status, String note) {
    mapper.finish(p.id, attempts, status, cut(note, 500), null, now());
    if (!"FAILED".equals(status)) log.info("Telegram：市场 {} 发到 {} 的开奖卡没有发（{}）：{}", p.marketNo, p.target, status, note);
    return false;
  }

  /** 等到可以发：全局间隔、同一个渠道的间隔（最多等 30 秒，限流更久的已经推迟到之后了） */
  private void pace(String chatId) {
    Instant until = globalNext;
    Instant c = chatNext.get(chatId);
    if (c != null && c.isAfter(until)) until = c;
    long ms = Duration.between(Instant.now(), until).toMillis();
    if (ms > 0) sleep(Math.min(ms, 30_000));
  }

  /** 卡片文字 + 链接；Telegram 图片说明最多 1024 个字符（放不下时从后面去掉小字说明的行，链接一定保留） */
  static String caption(String text, String link) {
    List<String> lines = new ArrayList<>(List.of(text.split("\n")));
    String out = String.join("\n", lines) + "\n\n" + link;
    while (out.length() > 1024 && lines.size() > 1) {
      lines.remove(lines.size() - 1);
      out = String.join("\n", lines) + "\n\n" + link;
    }
    return out.length() > 1024 ? out.substring(0, 1024) : out;
  }

  /** 市场链接带来源参数（MF-1）：频道 cmp=channel、ref=zh / en；群 cmp=group、ref=群编号 */
  String link(TelegramMapper.Post p) {
    String q = p.target.startsWith("G:") ? "src=telegram&cmp=group&ref=" + p.chatId
        : "src=telegram&cmp=channel&ref=" + ("CHANNEL_EN".equals(p.target) ? "en" : "zh");
    return clientUrl + "/markets/" + p.marketNo + "?" + q;
  }

  /** 公开频道的帖子链接：t.me/用户名/消息编号（频道没有公开的用户名时没有） */
  static String postUrl(String chatId, String username, long messageId) {
    String u = chatId != null && chatId.startsWith("@") ? chatId.substring(1) : username;
    if (u == null || u.isBlank() || messageId <= 0) return null;
    return "https://t.me/" + u + "/" + messageId;
  }

  void alert(String event, String eventEn, String detail, String detailEn) {
    try {
      Map<String, String> p = new LinkedHashMap<>();
      p.put("event", event);
      p.put("eventEn", eventEn);
      p.put("detail", detail);
      p.put("detailEn", detailEn);
      p.put("at", now().toString().replace('T', ' '));
      staff.notifyStaff(ALERT_TEMPLATE, p);
    } catch (RuntimeException e) {
      log.warn("Telegram 提醒邮件没写成：{}", e.getMessage());
    }
  }

  private static Instant instant(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC);
  }

  static String cut(String s, int n) {
    return s == null ? "" : s.length() <= n ? s : s.substring(0, n - 1) + "…";
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
