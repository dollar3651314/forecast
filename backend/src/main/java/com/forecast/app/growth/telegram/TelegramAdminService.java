package com.forecast.app.growth.telegram;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.growth.api.TelegramAdminApi;
import com.forecast.app.market.api.PublicMarketsApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** {@link TelegramAdminApi} 的实现（forecast S16，MF-7）。只改 growth 自己的表；审计和权限在 admin 模块。 */
@Service
class TelegramAdminService implements TelegramAdminApi {

  /** 频道编号：@用户名（5～32 位）或数字编号（频道是 -100 开头） */
  static final Pattern CHAT_ID = Pattern.compile("@[A-Za-z][A-Za-z0-9_]{4,31}|-?\\d{5,20}");
  static final List<String> KINDS = List.of("RESULT");
  static final List<String> LANGS = List.of("zh-CN", "en");

  private final TelegramProperties props;
  private final TelegramClient client;
  private final TelegramMapper mapper;
  private final TelegramBot bot;
  private final TelegramPublisher publisher;
  private final PublicMarketsApi markets;
  private final Clock clock;

  TelegramAdminService(TelegramProperties props, TelegramClient client, TelegramMapper mapper, TelegramBot bot, TelegramPublisher publisher,
      PublicMarketsApi markets, Clock clock) {
    this.props = props;
    this.client = client;
    this.mapper = mapper;
    this.bot = bot;
    this.publisher = publisher;
    this.markets = markets;
    this.clock = clock;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  @Override
  public Overview overview() {
    return new Overview(status(), mapper.targets().stream().map(TelegramAdminService::target).toList(),
        mapper.chats(200).stream().map(TelegramAdminService::group).toList(), mapper.templates().stream().map(this::template).toList());
  }

  Status status() {
    TelegramMapper.State st = mapper.state();
    boolean healthy = props.configured() && !client.circuitOpen() && client.consecutiveFailures() < 3;
    return new Status(props.configured(), bot.username().orElse(null), healthy, client.lastError(), client.lastOkAt() == null ? null : client.lastOkAt().toString(),
        st.emergencyStop, st.stopReason, iso(st.stopAt), st.stopBy);
  }

  @Override
  public Posts posts(String status, int page, int pageSize) {
    String st = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
    if (st != null && !List.of("PENDING", "SENT", "FAILED", "SKIPPED", "STOPPED").contains(st)) throw invalid("status");
    int size = Math.max(1, Math.min(pageSize, 100));
    int p = Math.max(1, page);
    List<Post> items = mapper.posts(st, (p - 1) * size, size).stream()
        .map(x -> new Post(x.id, String.valueOf(x.marketNo), x.kind.toLowerCase(Locale.ROOT), x.target, x.lang, x.status.toLowerCase(Locale.ROOT), x.attempts, x.postUrl,
            x.note, iso(x.createdAt), iso(x.sentAt)))
        .toList();
    return new Posts(items, mapper.countPosts(st), p, size);
  }

  @Override
  public Target updateTarget(String key, String chatId, boolean enabled, long adminId) {
    if (mapper.target(key) == null) throw new BizException("NOT_FOUND", "没有这个频道");
    String id = chatId == null || chatId.isBlank() ? null : chatId.strip();
    if (id != null && !CHAT_ID.matcher(id).matches()) throw invalid("chatId");
    if (enabled && id == null) throw new ValidationException(List.of(new ValidationException.Field("chatId", "required")));
    mapper.updateTarget(key, id, enabled, adminId, now());
    return target(mapper.target(key));
  }

  @Override
  public Group stopGroupBroadcast(String chatId, long adminId) {
    long id;
    try {
      id = Long.parseLong(chatId);
    } catch (NumberFormatException e) {
      throw new BizException("NOT_FOUND", "没有这个群");
    }
    if (mapper.chat(id) == null) throw new BizException("NOT_FOUND", "没有这个群");
    mapper.setBroadcast(id, false, null, now());
    return group(mapper.chat(id));
  }

  @Override
  public Optional<Preview> preview(String kind, String lang) {
    check(kind, lang);
    Optional<Long> no = markets.latestEnded();
    if (no.isEmpty()) return Optional.empty();
    return markets.resultCard(no.get(), lang)
        .map(c -> new Preview(kind, lang, String.valueOf(no.get()), c.text(), markets.cardTemplateFingerprint("result", lang)));
  }

  @Override
  public Optional<byte[]> previewPng(String kind, String lang) {
    check(kind, lang);
    return markets.latestEnded().flatMap(no -> markets.resultCard(no, lang)).map(PublicMarketsApi.PostCard::png);
  }

  @Override
  public Template approve(String kind, String lang, String fingerprint, long adminId) {
    check(kind, lang);
    String current = markets.cardTemplateFingerprint("result", lang);
    if (fingerprint == null || !fingerprint.equals(current)) {
      throw new BizException("TELEGRAM_TEMPLATE_CHANGED", "预览以后模板文字改过，请重新打开预览再审定");
    }
    mapper.setTemplate(kind, lang, "APPROVED", current, adminId, now());
    return template(mapper.template(kind, lang));
  }

  @Override
  public Template disable(String kind, String lang, long adminId) {
    check(kind, lang);
    TelegramMapper.Template t = mapper.template(kind, lang);
    mapper.setTemplate(kind, lang, "DISABLED", t.fingerprint, adminId, now());
    return template(mapper.template(kind, lang));
  }

  @Override
  public Status setStop(boolean stop, String reason, long adminId) {
    if (mapper.setStop(stop, reason, adminId, now()) == 1) {
      // 需求第 6.3 节：按下紧急停止写审计（admin 模块）并邮件通知超管；恢复同样通知
      publisher.alert(stop ? "已紧急停止所有自动发布" : "已恢复自动发布", stop ? "All automatic posting was stopped" : "Automatic posting was resumed",
          "操作人 " + adminId + "，原因：" + reason, "By admin " + adminId + ", reason: " + reason);
    }
    return status();
  }

  private void check(String kind, String lang) {
    if (!KINDS.contains(kind)) throw new BizException("NOT_FOUND", "没有这种模板");
    if (!LANGS.contains(lang)) throw new BizException("NOT_FOUND", "没有这种语言的模板");
  }

  private Template template(TelegramMapper.Template t) {
    boolean changed = "APPROVED".equals(t.status) && !markets.cardTemplateFingerprint("result", t.lang).equals(t.fingerprint);
    return new Template(t.kind, t.lang, t.status.toLowerCase(Locale.ROOT), changed, iso(t.approvedAt), t.approvedBy);
  }

  private static Target target(TelegramMapper.Target t) {
    return new Target(t.targetKey, t.lang, t.chatId, t.enabled, iso(t.enabledAt), t.failStreak, t.disabledReason, iso(t.updatedAt));
  }

  private static Group group(TelegramMapper.Chat c) {
    return new Group(String.valueOf(c.chatId), c.title, c.chatType, c.status.toLowerCase(Locale.ROOT), c.broadcast, c.lang, c.startParam, iso(c.addedAt),
        iso(c.broadcastAt));
  }

  private static ValidationException invalid(String field) {
    return new ValidationException(List.of(new ValidationException.Field(field, "invalid")));
  }

  private static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }
}
