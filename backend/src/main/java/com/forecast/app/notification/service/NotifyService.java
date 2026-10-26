package com.forecast.app.notification.service;

import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.task.TaskService;
import com.forecast.app.notification.api.NotifyAdminApi;
import com.forecast.app.notification.api.NotifyApi;
import com.forecast.app.notification.api.NotifyChannel;
import com.forecast.app.common.push.UserEventsApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 通知（技术方案/v1-通知方案.md）：
 * <ul>
 *   <li>站内信始终发送；存模板编号和变量，打开时按客户当前界面语言生成（切换语言后旧消息也跟着变）。</li>
 *   <li>推送和邮件按客户的开关（X-05），关闭的渠道记为已跳过（AC-25）。推送每台设备一条记录、一个 {@code PUSH_SEND} 任务（S15b）。</li>
 *   <li>邮件写成 {@code MAIL_SEND} 任务，和业务同一事务；业务提交后由 {@link NotifyWorker} 发出。</li>
 *   <li>每个渠道一条通知记录（后台"通知记录"页面）。</li>
 * </ul>
 */
@Service
class NotifyService implements NotifyApi, NotifyAdminApi {

  static final String MAIL_TASK = "MAIL_SEND";
  static final int MAIL_ATTEMPTS = 5;

  static final String SKIP_PUSH_OFF = "客户关闭了推送";
  static final String SKIP_EMAIL_OFF = "客户关闭了邮件";
  static final String SKIP_NO_DEVICE = "没有订阅推送的设备";
  static final String SKIP_PUSH_NOT_CONFIGURED = "推送未配置";
  static final String PUSH_TASK = "PUSH_SEND";
  static final int PUSH_ATTEMPTS = 5;

  private static final Logger log = LoggerFactory.getLogger(NotifyService.class);
  private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {};

  /** 站内信，字段和客户端 NotificationItem 一致 */
  record Item(String id, String kind, String title, String body, boolean read, String createdAt) {}

  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  record Prefs(boolean push, boolean email) {}

  private final NotifyMapper mapper;
  private final TaskService tasks;
  private final ObjectMapper json;
  private final UserEventsApi events;
  private final Clock clock;
  private final WebPush webPush;
  private final NotifyProperties.Push pushProps;

  NotifyService(NotifyMapper mapper, TaskService tasks, ObjectMapper json, UserEventsApi events, Clock clock, WebPush webPush, NotifyProperties props) {
    this.mapper = mapper;
    this.tasks = tasks;
    this.json = json;
    this.events = events;
    this.clock = clock;
    this.webPush = webPush;
    this.pushProps = props.push();
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  // ---------- 发送 ----------

  @Override
  @Transactional
  public void toUser(long userId, String template, Map<String, String> params) {
    Rows.TemplateRow t = mapper.template(template);
    if (t == null || !"USER".equals(t.audience)) throw new IllegalArgumentException("不是发给客户的模板：" + template);
    if (!t.enabled) {
      log.info("模板 {} 已停用，不发送 user={}", template, userId);
      return;
    }
    Rows.PrefRow pref = prefRow(userId);
    String title = Render.render(t, params, Render.ZH).title();
    String paramsJson = json.writeValueAsString(params == null ? Map.of() : params);
    Long messageId = null;
    if (t.has("INBOX")) {
      Rows.MessageRow m = new Rows.MessageRow();
      m.userId = userId;
      m.kind = t.kind;
      m.templateCode = t.code;
      m.params = paramsJson;
      m.createdAt = now();
      mapper.insertMessage(m);
      record(userId, null, "TEMPLATE", t.code, "INBOX", title, "SENT", null, null);
      messageId = m.id;
      afterCommit(() -> pushItem(userId, view(m, t, pref.lang)));
    }
    if (t.has("PUSH")) {
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("userId", userId);
      p.put("template", t.code);
      p.put("params", params == null ? Map.of() : params);
      p.put("lang", pref.lang);
      p.put("kind", t.kind);
      p.put("messageId", messageId);
      push(userId, pref, "TEMPLATE", t.code, title, p, null);
    }
    if (t.has("EMAIL")) {
      if (!pref.emailEnabled) {
        record(userId, null, "TEMPLATE", t.code, "EMAIL", title, "SKIPPED", SKIP_EMAIL_OFF, null);
      } else {
        long rid = record(userId, null, "TEMPLATE", t.code, "EMAIL", title, "PENDING", null, null);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("recordId", rid);
        p.put("userId", userId);
        p.put("template", t.code);
        p.put("params", params == null ? Map.of() : params);
        p.put("lang", pref.lang);
        enqueueMail(p);
      }
    }
  }

  /** 批量发送时一条语句最多写多少个客户 */
  static final int BULK = 500;

  /**
   * 批量发送（{@link NotifyApi#toUsers}，forecast S5）：和逐个 {@link #toUser} 写出完全一样的站内信、通知记录（每个渠道一条：发出、跳过及原因）、
   * 推送任务（每台设备一个）、邮件任务，提交后同样推给在线的客户；只是每 500 个客户用几条批量 SQL（偏好、推送订阅一次查出，站内信和记录多行插入）。
   */
  @Override
  @Transactional
  public void toUsers(String template, List<NotifyApi.Notice> notices) {
    if (notices == null || notices.isEmpty()) return;
    Rows.TemplateRow t = mapper.template(template);
    if (t == null || !"USER".equals(t.audience)) throw new IllegalArgumentException("不是发给客户的模板：" + template);
    if (!t.enabled) {
      log.info("模板 {} 已停用，不发送（{} 个客户）", template, notices.size());
      return;
    }
    for (int i = 0; i < notices.size(); i += BULK) sendBulk(t, notices.subList(i, Math.min(notices.size(), i + BULK)));
  }

  private void sendBulk(Rows.TemplateRow t, List<NotifyApi.Notice> chunk) {
    List<Long> users = chunk.stream().map(NotifyApi.Notice::userId).distinct().toList();
    Map<Long, Rows.PrefRow> prefs = new java.util.HashMap<>();
    for (Rows.PrefUserRow p : mapper.prefsOf(users)) prefs.put(p.userId, p);
    LocalDateTime now = now();
    int n = chunk.size();
    String[] titles = new String[n];
    String[] paramsJson = new String[n];
    for (int i = 0; i < n; i++) {
      Map<String, String> params = chunk.get(i).params();
      titles[i] = Render.render(t, params, Render.ZH).title();
      paramsJson[i] = json.writeValueAsString(params == null ? Map.of() : params);
    }
    List<Rows.RecordRow> done = new ArrayList<>();
    Long[] messageIds = new Long[n];
    if (t.has("INBOX")) {
      List<Rows.MessageRow> msgs = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        Rows.MessageRow m = new Rows.MessageRow();
        m.userId = chunk.get(i).userId();
        m.kind = t.kind;
        m.templateCode = t.code;
        m.params = paramsJson[i];
        m.createdAt = now;
        msgs.add(m);
        done.add(recordRow(m.userId, "TEMPLATE", t.code, "INBOX", titles[i], "SENT", null, now));
      }
      mapper.insertMessages(msgs);
      for (int i = 0; i < n; i++) messageIds[i] = msgs.get(i).id;
      afterCommit(() -> {
        for (Rows.MessageRow m : msgs) pushItem(m.userId, view(m, t, prefs.getOrDefault(m.userId, Rows.PrefRow.defaults()).lang));
      });
    }
    // 要回填 id 的记录（推送、邮件的"待发送"）和它们的任务内容
    List<Rows.RecordRow> pending = new ArrayList<>();
    List<Map<String, Object>> pendingPayloads = new ArrayList<>();
    List<String> pendingTypes = new ArrayList<>();
    if (t.has("PUSH")) {
      boolean enabled = webPush.enabled();
      Map<Long, List<Rows.PushSubRow>> subs = new java.util.HashMap<>();
      if (enabled) {
        List<Long> wanted = users.stream().filter(u -> prefs.getOrDefault(u, Rows.PrefRow.defaults()).pushEnabled).toList();
        if (!wanted.isEmpty()) for (Rows.PushSubRow s : mapper.pushSubsOf(wanted)) subs.computeIfAbsent(s.userId, k -> new ArrayList<>()).add(s);
      }
      for (int i = 0; i < n; i++) {
        long uid = chunk.get(i).userId();
        Rows.PrefRow pref = prefs.getOrDefault(uid, Rows.PrefRow.defaults());
        if (!pref.pushEnabled) done.add(recordRow(uid, "TEMPLATE", t.code, "PUSH", titles[i], "SKIPPED", SKIP_PUSH_OFF, now));
        else if (!enabled) done.add(recordRow(uid, "TEMPLATE", t.code, "PUSH", titles[i], "SKIPPED", SKIP_PUSH_NOT_CONFIGURED, now));
        else if (!subs.containsKey(uid)) done.add(recordRow(uid, "TEMPLATE", t.code, "PUSH", titles[i], "SKIPPED", SKIP_NO_DEVICE, now));
        else {
          for (Rows.PushSubRow sub : subs.get(uid)) {
            pending.add(recordRow(uid, "TEMPLATE", t.code, "PUSH", titles[i], "PENDING", null, now));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("userId", uid);
            p.put("template", t.code);
            p.put("params", chunk.get(i).params() == null ? Map.of() : chunk.get(i).params());
            p.put("lang", pref.lang);
            p.put("kind", t.kind);
            p.put("messageId", messageIds[i]);
            p.put("subId", sub.id);
            pendingPayloads.add(p);
            pendingTypes.add(PUSH_TASK);
          }
        }
      }
    }
    if (t.has("EMAIL")) {
      for (int i = 0; i < n; i++) {
        long uid = chunk.get(i).userId();
        Rows.PrefRow pref = prefs.getOrDefault(uid, Rows.PrefRow.defaults());
        if (!pref.emailEnabled) {
          done.add(recordRow(uid, "TEMPLATE", t.code, "EMAIL", titles[i], "SKIPPED", SKIP_EMAIL_OFF, now));
          continue;
        }
        pending.add(recordRow(uid, "TEMPLATE", t.code, "EMAIL", titles[i], "PENDING", null, now));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("userId", uid);
        p.put("template", t.code);
        p.put("params", chunk.get(i).params() == null ? Map.of() : chunk.get(i).params());
        p.put("lang", pref.lang);
        pendingPayloads.add(p);
        pendingTypes.add(MAIL_TASK);
      }
    }
    if (!done.isEmpty()) mapper.insertRecords(done);
    if (!pending.isEmpty()) {
      mapper.insertRecords(pending);
      List<String> push = new ArrayList<>();
      List<String> mail = new ArrayList<>();
      for (int i = 0; i < pending.size(); i++) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("recordId", pending.get(i).id);
        p.putAll(pendingPayloads.get(i));
        (PUSH_TASK.equals(pendingTypes.get(i)) ? push : mail).add(json.writeValueAsString(p));
      }
      if (!push.isEmpty()) tasks.enqueueAll(PUSH_TASK, push, 0, PUSH_ATTEMPTS);
      if (!mail.isEmpty()) tasks.enqueueAll(MAIL_TASK, mail, 0, MAIL_ATTEMPTS);
    }
  }

  private static Rows.RecordRow recordRow(Long userId, String source, String template, String channel, String title, String status, String error,
      LocalDateTime now) {
    Rows.RecordRow r = new Rows.RecordRow();
    r.userId = userId;
    r.source = source;
    r.templateCode = template;
    r.channel = channel;
    r.title = title.length() > 200 ? title.substring(0, 200) : title;
    r.status = status;
    r.error = error;
    r.createdAt = now;
    return r;
  }

  /** 按邮箱发（验证码、员工，{@link AddressMailer}）。lang 为空 = 不知道收件人语言，中英文各一份；不为空只发这种语言（S9，#23） */
  @Transactional
  void sendToAddress(String to, String template, Map<String, String> params, String lang) {
    Rows.TemplateRow t = mapper.template(template);
    if (t == null || "USER".equals(t.audience)) throw new IllegalArgumentException("不能按邮箱发的模板：" + template);
    String title = Render.render(t, params, Render.ZH).title();
    long rid = record(null, to, "TEMPLATE", t.code, "EMAIL", title, "PENDING", null, null);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("recordId", rid);
    p.put("to", to);
    p.put("template", t.code);
    p.put("params", params == null ? Map.of() : params);
    if (lang != null) p.put("lang", lang);
    enqueueMail(p);
  }

  @Override
  @Transactional
  public void manual(long userId, Set<NotifyChannel> channels, String title, String body, long operatorId) {
    List<ValidationException.Field> errors = new ArrayList<>();
    if (channels == null || channels.isEmpty()) errors.add(new ValidationException.Field("channels", "required"));
    if (title == null || title.isBlank()) errors.add(new ValidationException.Field("title", "required"));
    else if (title.length() > 200) errors.add(new ValidationException.Field("title", "tooLong"));
    if (body == null || body.isBlank()) errors.add(new ValidationException.Field("body", "required"));
    else if (body.length() > 2000) errors.add(new ValidationException.Field("body", "tooLong"));
    if (!errors.isEmpty()) throw new ValidationException(errors);
    String tt = title.trim();
    String bb = body.trim();
    Rows.PrefRow pref = prefRow(userId);
    Long messageId = null;
    if (channels.contains(NotifyChannel.INBOX)) {
      Rows.MessageRow m = new Rows.MessageRow();
      m.userId = userId;
      m.kind = "system";
      m.title = tt;
      m.body = bb;
      m.createdAt = now();
      mapper.insertMessage(m);
      record(userId, null, "MANUAL", null, "INBOX", tt, "SENT", null, operatorId);
      messageId = m.id;
      afterCommit(() -> pushItem(userId, view(m, null, pref.lang)));
    }
    if (channels.contains(NotifyChannel.PUSH)) {
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("userId", userId);
      p.put("subject", tt);
      p.put("body", bb);
      p.put("kind", "system");
      p.put("messageId", messageId);
      push(userId, pref, "MANUAL", null, tt, p, operatorId);
    }
    if (channels.contains(NotifyChannel.EMAIL)) {
      if (!pref.emailEnabled) {
        record(userId, null, "MANUAL", null, "EMAIL", tt, "SKIPPED", SKIP_EMAIL_OFF, operatorId);
      } else {
        long rid = record(userId, null, "MANUAL", null, "EMAIL", tt, "PENDING", null, operatorId);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("recordId", rid);
        p.put("userId", userId);
        p.put("subject", tt);
        p.put("body", bb);
        enqueueMail(p);
      }
    }
  }

  /**
   * 推送：客户关了、没有配置、没有设备都记"已跳过"；否则每台设备一条记录、一个任务（某台设备失败重试时不会重复推给别的设备）。
   */
  private void push(long userId, Rows.PrefRow pref, String source, String template, String title, Map<String, Object> base, Long operatorId) {
    if (!pref.pushEnabled) {
      record(userId, null, source, template, "PUSH", title, "SKIPPED", SKIP_PUSH_OFF, operatorId);
      return;
    }
    if (!webPush.enabled()) {
      record(userId, null, source, template, "PUSH", title, "SKIPPED", SKIP_PUSH_NOT_CONFIGURED, operatorId);
      return;
    }
    List<Rows.PushSubRow> subs = mapper.pushSubs(userId);
    if (subs.isEmpty()) {
      record(userId, null, source, template, "PUSH", title, "SKIPPED", SKIP_NO_DEVICE, operatorId);
      return;
    }
    for (Rows.PushSubRow sub : subs) {
      long rid = record(userId, null, source, template, "PUSH", title, "PENDING", null, operatorId);
      Map<String, Object> p = new LinkedHashMap<>(base);
      p.put("recordId", rid);
      p.put("subId", sub.id);
      tasks.enqueue(PUSH_TASK, json.writeValueAsString(p), 0, PUSH_ATTEMPTS);
    }
  }

  private void enqueueMail(Map<String, Object> payload) {
    tasks.enqueue(MAIL_TASK, json.writeValueAsString(payload), 0, MAIL_ATTEMPTS);
  }

  private long record(Long userId, String recipient, String source, String template, String channel, String title, String status, String error,
      Long operatorId) {
    Rows.RecordRow r = new Rows.RecordRow();
    r.userId = userId;
    r.recipient = recipient;
    r.source = source;
    r.templateCode = template;
    r.channel = channel;
    r.title = title.length() > 200 ? title.substring(0, 200) : title;
    r.status = status;
    r.error = error;
    r.operatorId = operatorId;
    r.createdAt = now();
    mapper.insertRecord(r);
    return r.id;
  }

  /** 新站内信实时推给客户端（{"type":"notification","item":{…}}，和客户端 ServerEvent 一致）；事务提交后再推 */
  private void pushItem(long userId, Item item) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("type", "notification");
    e.put("item", item);
    events.send(userId, e);
  }

  private static void afterCommit(Runnable r) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          r.run();
        }
      });
    } else {
      r.run();
    }
  }

  // ---------- 后台（S16b） ----------

  private static NotifyAdminApi.Template template(Rows.TemplateRow t) {
    List<String> vars = t.variables == null || t.variables.isBlank() ? List.of() : java.util.Arrays.stream(t.variables.split(",")).map(String::strip).toList();
    List<NotifyChannel> chs = java.util.Arrays.stream(t.channels.split(",")).map(c -> NotifyChannel.valueOf(c.strip())).toList();
    List<String> perms = t.staffPerms == null || t.staffPerms.isBlank() ? List.of() : java.util.Arrays.stream(t.staffPerms.split(",")).map(String::strip).toList();
    return new NotifyAdminApi.Template(t.code, t.audience, t.kind, t.nameZh, t.nameEn, t.titleZh, t.bodyZh, t.titleEn, t.bodyEn, nz(t.titleTw), nz(t.bodyTw),
        t.reqNo, perms, vars, chs, t.builtIn, t.enabled, t.updatedAt);
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  @Override
  @Transactional(readOnly = true)
  public List<NotifyAdminApi.Template> templates() {
    return mapper.templates().stream().map(NotifyService::template).toList();
  }

  private static final java.util.regex.Pattern VAR = java.util.regex.Pattern.compile("\\{([A-Za-z0-9_]+)}");

  @Override
  @Transactional
  public NotifyAdminApi.Template updateTemplate(String code, String titleZh, String bodyZh, String titleEn, String bodyEn, String titleTw, String bodyTw,
      Boolean enabled) {
    Rows.TemplateRow t = mapper.template(code);
    if (t == null) throw new com.forecast.app.common.error.BizException("NOT_FOUND", "模板不存在");
    java.util.Set<String> allowed = new java.util.HashSet<>(template(t).variables());
    ValidationException.Collector c = new ValidationException.Collector();
    String tz = text(c, "titleZh", titleZh, t.titleZh, 200, allowed);
    String bz = text(c, "bodyZh", bodyZh, t.bodyZh, 1000, allowed);
    String te = text(c, "titleEn", titleEn, t.titleEn, 200, allowed);
    String be = text(c, "bodyEn", bodyEn, t.bodyEn, 1000, allowed);
    // 繁体：可以清空（清空 = 按简体发）；标题和正文要么都有、要么都空
    String tt = twText(c, "titleTw", titleTw, nz(t.titleTw), 200, allowed);
    String bt = twText(c, "bodyTw", bodyTw, nz(t.bodyTw), 1000, allowed);
    if (tt.isEmpty() != bt.isEmpty()) c.add(tt.isEmpty() ? "titleTw" : "bodyTw", "required");
    boolean en = enabled == null ? t.enabled : enabled;
    if (t.builtIn && !en) throw new com.forecast.app.common.error.BizException("BUILT_IN_CANNOT_DISABLE", "内置模板不能停用");
    c.throwIfAny();
    mapper.updateTemplate(code, tz, bz, te, be, tt, bt, en);
    return template(mapper.template(code));
  }

  /** 繁体：null 不改，空串 = 清空（按简体发），其余同 {@link #text} */
  private static String twText(ValidationException.Collector c, String field, String value, String old, int max, java.util.Set<String> allowed) {
    if (value == null) return old;
    if (value.isBlank()) return "";
    return text(c, field, value, old, max, allowed);
  }

  /** 为空不改；不能为空白、不能超长、只能用模板列出的变量 */
  private static String text(ValidationException.Collector c, String field, String value, String old, int max, java.util.Set<String> allowed) {
    if (value == null) return old;
    String v = value.strip();
    if (v.isEmpty()) {
      c.add(field, "required");
      return old;
    }
    if (v.length() > max) {
      c.add(field, "tooLong");
      return old;
    }
    java.util.regex.Matcher m = VAR.matcher(v);
    while (m.find()) {
      if (!allowed.contains(m.group(1))) {
        c.add(field, "unknownVariable");
        return old;
      }
    }
    return v;
  }

  @Override
  @Transactional(readOnly = true)
  public NotifyAdminApi.Page<NotifyAdminApi.Record> records(NotifyChannel channel, String status, java.util.Collection<Long> userIds, int offset, int limit) {
    if (userIds != null && userIds.isEmpty()) return new NotifyAdminApi.Page<>(List.of(), 0);
    String ch = channel == null ? null : channel.name();
    List<NotifyAdminApi.Record> items = mapper.records(ch, status, userIds, offset, limit).stream()
        .map(r -> new NotifyAdminApi.Record(r.id, r.userId, r.recipient, r.source, r.templateCode, NotifyChannel.valueOf(r.channel), r.title, r.status, r.error,
            r.operatorId, r.createdAt))
        .toList();
    return new NotifyAdminApi.Page<>(items, mapper.countRecords(ch, status, userIds));
  }

  // ---------- 客户端读取 ----------

  @Transactional(readOnly = true)
  Page<Item> list(long userId, String lang, int page, int pageSize) {
    int p = Math.max(1, page);
    int size = Math.min(100, Math.max(1, pageSize));
    Map<String, Rows.TemplateRow> cache = new LinkedHashMap<>();
    List<Item> items = mapper.messages(userId, (p - 1) * size, size).stream()
        .map(m -> view(m, m.templateCode == null ? null : cache.computeIfAbsent(m.templateCode, mapper::template), lang)).toList();
    return new Page<>(items, mapper.countMessages(userId), p, size);
  }

  @Transactional(readOnly = true)
  long unread(long userId) {
    return mapper.countUnread(userId);
  }

  @Transactional
  void markRead(long userId, long id) {
    mapper.markRead(userId, id, now());
  }

  @Transactional
  void markAllRead(long userId) {
    mapper.markAllRead(userId, now());
  }

  @Transactional(readOnly = true)
  Prefs prefs(long userId) {
    Rows.PrefRow p = prefRow(userId);
    return new Prefs(p.pushEnabled, p.emailEnabled);
  }

  @Transactional
  Prefs setPrefs(long userId, boolean push, boolean email) {
    mapper.upsertPref(userId, push, email);
    return new Prefs(push, email);
  }

  /** 客户端切换语言时告诉服务端，推送和邮件按这个语言生成 */
  @Transactional
  void setLang(long userId, String lang) {
    if (!Render.ZH.equals(lang) && !Render.EN.equals(lang) && !Render.TW.equals(lang)) throw new ValidationException(List.of(new ValidationException.Field("lang", "invalid")));
    mapper.upsertLang(userId, lang);
  }

  /** Web Push 的公钥（客户端订阅时用）；没有配置时为空 */
  String pushPublicKey() {
    return webPush.publicKey();
  }

  /** 保存订阅（浏览器 PushSubscription.toJSON() 的 endpoint、keys.p256dh、keys.auth）；超过设备上限时删掉最早的 */
  @Transactional
  void subscribe(long userId, String endpoint, String p256dh, String auth) {
    ValidationException.Collector c = new ValidationException.Collector();
    if (endpoint == null || endpoint.isBlank()) c.add("endpoint", "required");
    else if (endpoint.length() > 1000 || !webPush.allowedEndpoint(endpoint)) c.add("endpoint", "invalid");
    if (!validKey(p256dh, 65)) c.add("p256dh", "invalid");
    if (!validKey(auth, 16)) c.add("auth", "invalid");
    c.throwIfAny();
    mapper.upsertPushSub(userId, endpoint, sha256(endpoint), p256dh.strip(), auth.strip(), now());
    mapper.trimPushSubs(userId, pushProps.maxDevices());
  }

  @Transactional
  void unsubscribe(long userId, String endpoint) {
    if (endpoint == null || endpoint.isBlank()) throw new ValidationException(List.of(new ValidationException.Field("endpoint", "required")));
    mapper.deletePushSubByEndpoint(userId, sha256(endpoint));
  }

  private static boolean validKey(String v, int len) {
    if (v == null || v.isBlank() || v.length() > 120) return false;
    try {
      byte[] b = WebPushCrypto.b64(v);
      if (b.length != len) return false;
      if (len == 65) WebPushCrypto.publicKey(b);
      return true;
    } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
      return false;
    }
  }

  static String sha256(String s) {
    try {
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Rows.PrefRow prefRow(long userId) {
    Rows.PrefRow p = mapper.pref(userId);
    return p == null ? Rows.PrefRow.defaults() : p;
  }

  private Item view(Rows.MessageRow m, Rows.TemplateRow t, String lang) {
    String title = m.title;
    String body = m.body;
    if (t != null) {
      Render.Text text = Render.render(t, json.readValue(m.params == null ? "{}" : m.params, PARAMS), lang);
      title = text.title();
      body = text.body();
    }
    return new Item(String.valueOf(m.id), m.kind, title, body, m.readAt != null, m.createdAt.toInstant(ZoneOffset.UTC).toString());
  }
}
