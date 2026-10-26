package com.forecast.app.growth.telegram;

import com.forecast.app.common.attribution.Attribution;
import com.forecast.app.growth.board.BoardProperties;
import com.forecast.app.growth.board.BtcBoard;
import com.forecast.app.growth.board.GrowthTexts;
import com.forecast.app.growth.visit.VisitMapper;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.PublicMarketsApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 官方 Telegram 机器人：收消息、回命令、登记群（forecast S16，MF-7；需求说明书第 6.3 节、AC-89；SQ-22：和账户无关）。
 * <ul>
 *   <li><b>取消息</b>：getUpdates 长轮询（不用 webhook：不需要公网回调地址，测试环境在 Cloudflare 后面也能用）；只取 message、my_chat_member 两种；
 *       处理完一条记一条的 offset（{@code growth_tg_state}），重启后接着取。多实例时由租约 {@code growth.telegram.poll} 保证只有一个在取（同一个 token 两个人同时取会冲突）。</li>
 *   <li><b>命令</b>：{@code /start [参数]}、{@code /help}、{@code /btc}（BTC 现价、过去 N 小时每小时涨跌的比例、当前这场 BTC 市场的概率和链接）、
 *       {@code /today}（今天的场次和两边的人数）；群里的 {@code /broadcast_on [zh|en]}、{@code /broadcast_off}（只有群管理员能用，getChatMember 核对）。
 *       <b>没有下注、没有账户绑定</b>（SQ-22、MF-16 不做）；不主动私信任何人，只回应命令（Telegram 机器人条款、市场策略 G-3）。</li>
 *   <li><b>来源统计</b>（MF-1，只记来源、不发奖励）：{@code /start 参数}（深链 t.me/机器人?start=…）记一次访问（src=telegram、cmp=bot、start=原文），
 *       回复里的链接带同一个 start；群里的回复和播报的链接带 src=telegram、cmp=group、ref=群编号（"群里来的用户记为该群来源"）；
 *       用 startgroup 深链加进群时的参数记在群上。</li>
 *   <li><b>群</b>：机器人被拉进群 / 在群里收到消息时登记（ACTIVE），被移出时记 LEFT、关闭播报。</li>
 * </ul>
 * 回复的语言按发命令的人的 Telegram 语言（{@code language_code}）；群播报的语言由开启的人指定（默认按他的语言）。
 */
@Service
public class TelegramBot {

  private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);
  private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);
  private static final Set<String> ADMIN = Set.of("creator", "administrator");

  private final TelegramProperties props;
  private final TelegramClient client;
  private final TelegramMapper mapper;
  private final VisitMapper visits;
  private final BtcBoard board;
  private final BoardProperties boardProps;
  private final PublicMarketsApi markets;
  private final GrowthTexts texts;
  private final Clock clock;
  private final String clientUrl;
  private volatile String username;

  TelegramBot(TelegramProperties props, TelegramClient client, TelegramMapper mapper, VisitMapper visits, BtcBoard board, BoardProperties boardProps,
      PublicMarketsApi markets, GrowthTexts texts, Clock clock, @Value("${forecast.client-url:}") String clientUrl) {
    this.props = props;
    this.client = client;
    this.mapper = mapper;
    this.visits = visits;
    this.board = board;
    this.boardProps = boardProps;
    this.markets = markets;
    this.texts = texts;
    this.clock = clock;
    this.clientUrl = clientUrl == null ? "" : clientUrl.strip().replaceAll("/+$", "");
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  /** 机器人的用户名（getMe 取到以后才有；数据页的"Telegram 机器人"链接用它） */
  public Optional<String> username() {
    return Optional.ofNullable(username);
  }

  /** 取一次机器人自己的信息（用户名）；失败返回空，下一轮再试 */
  Optional<String> ensureMe() {
    if (username != null) return Optional.of(username);
    try {
      JsonNode me = client.call("getMe", Map.of());
      String u = me.path("username").asString(null);
      if (u != null && !u.isBlank()) username = u;
    } catch (TelegramClient.Failure e) {
      log.info("Telegram getMe 失败：{}", e.getMessage());
    }
    return Optional.ofNullable(username);
  }

  /**
   * 取一批消息并处理（{@link com.forecast.app.growth.board.GrowthJobs} 的长轮询；e2e 下 {@code POST /api/e2e/telegram/tick}）。返回处理了几条。
   * 没配置 token 时什么都不做。不能在事务里调用（要调 Telegram）。
   */
  public int pollOnce(Duration wait) {
    if (!props.configured()) return 0;
    ensureMe();
    TelegramMapper.State st = mapper.state();
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("offset", st.updateOffset);
    p.put("timeout", Math.max(0, wait.toSeconds()));
    p.put("allowed_updates", List.of("message", "my_chat_member"));
    JsonNode updates;
    try {
      updates = client.call("getUpdates", p, wait.plus(Duration.ofSeconds(10)));
    } catch (TelegramClient.Failure e) {
      if (e.kind() != TelegramClient.Kind.CIRCUIT_OPEN) log.info("Telegram getUpdates 失败：{}", e.getMessage());
      return 0;
    }
    int n = 0;
    for (JsonNode u : updates) {
      long id = u.path("update_id").asLong();
      try {
        handle(u);
      } catch (RuntimeException e) {
        // 一条处理出错不能卡住后面的（不然 offset 不动，每次都取到这一条）
        log.warn("Telegram 消息 {} 处理失败，跳过", id, e);
      }
      mapper.saveOffset(id + 1, now());
      n++;
    }
    return n;
  }

  void handle(JsonNode u) {
    if (u.has("my_chat_member")) {
      memberChange(u.path("my_chat_member"));
      return;
    }
    JsonNode m = u.path("message");
    if (m.isMissingNode()) return;
    JsonNode chat = m.path("chat");
    String type = chat.path("type").asString("");
    long chatId = chat.path("id").asLong();
    boolean group = "group".equals(type) || "supergroup".equals(type);
    if (group) {
      if (m.has("left_chat_member") && isMe(m.path("left_chat_member"))) {
        mapper.chatLeft(chatId, now());
        return;
      }
      mapper.upsertChat(chatId, type, cut(chat.path("title").asString(""), 255), null, now());
    }
    String text = m.path("text").asString("");
    if (!text.startsWith("/")) return;
    String[] parts = text.strip().split("\\s+", 2);
    String cmd = parts[0].substring(1);
    int at = cmd.indexOf('@');
    if (at >= 0) {
      String target = cmd.substring(at + 1);
      // 群里给别的机器人的命令不理
      if (username != null && !target.equalsIgnoreCase(username)) return;
      cmd = cmd.substring(0, at);
    }
    cmd = cmd.toLowerCase(Locale.ROOT);
    String arg = parts.length > 1 ? parts[1].strip() : "";
    JsonNode from = m.path("from");
    String lang = group ? chatLang(chatId, from) : GrowthTexts.lang(from.path("language_code").asString(null));
    String linkQuery = group ? "src=telegram&cmp=group&ref=" + chatId : "src=telegram&cmp=bot";
    switch (cmd) {
      case "start" -> start(chatId, group, type, chat.path("title").asString(""), from, arg, lang);
      case "help" -> reply(chatId, group ? texts.t(lang, "telegram.startGroup") : texts.t(lang, "telegram.startPrivate", Map.of("link", link("/markets", linkQuery))));
      case "btc" -> reply(chatId, btcText(lang, linkQuery));
      case "today" -> reply(chatId, todayText(lang, linkQuery));
      case "broadcast_on", "broadcast_off" -> broadcast(m, chatId, group, cmd.endsWith("on"), arg, from);
      default -> {
        // 私聊里不认识的命令回说明；群里不认识的不理（不打扰）
        if (!group) reply(chatId, texts.t(lang, "telegram.startPrivate", Map.of("link", link("/markets", linkQuery))));
      }
    }
  }

  private void memberChange(JsonNode mc) {
    JsonNode chat = mc.path("chat");
    String type = chat.path("type").asString("");
    if (!"group".equals(type) && !"supergroup".equals(type)) return; // 频道的管理员变化不用登记（频道由 Amos 在后台填）
    long chatId = chat.path("id").asLong();
    String status = mc.path("new_chat_member").path("status").asString("");
    if (Set.of("member", "administrator", "creator", "restricted").contains(status)) {
      mapper.upsertChat(chatId, type, cut(chat.path("title").asString(""), 255), null, now());
    } else if (Set.of("left", "kicked").contains(status)) {
      mapper.chatLeft(chatId, now());
    }
  }

  private void start(long chatId, boolean group, String type, String title, JsonNode from, String arg, String lang) {
    String payload = arg.isEmpty() ? null : arg;
    String start = payload == null ? null : Attribution.of(null, null, null, payload).start();
    LocalDateTime now = now();
    if (group) {
      // startgroup 深链加进群：参数记在群上（只记第一次的），同时记一次来源访问
      mapper.upsertChat(chatId, type, cut(title, 255), start, now);
      if (start != null) visits.insertIgnore(now.toLocalDate(), sha256("tg-group:" + chatId), "telegram", "group", String.valueOf(chatId), start, now);
      reply(chatId, texts.t(lang, "telegram.startGroup"));
      return;
    }
    // 私聊的深链参数（MF-1）：按 Telegram 用户编号的哈希去重，不存编号原文
    if (start != null) visits.insertIgnore(now.toLocalDate(), sha256("tg:" + from.path("id").asLong()), "telegram", "bot", "", start, now);
    String q = "src=telegram&cmp=bot" + (start == null ? "" : "&start=" + start);
    reply(chatId, texts.t(lang, "telegram.startPrivate", Map.of("link", link("/markets", q))));
  }

  /** 群播报的开关：只有群管理员（或用群身份发言的匿名管理员）能改 */
  private void broadcast(JsonNode m, long chatId, boolean group, boolean on, String arg, JsonNode from) {
    String userLang = GrowthTexts.lang(from.path("language_code").asString(null));
    if (!group) {
      reply(chatId, texts.t(userLang, "telegram.groupOnly"));
      return;
    }
    boolean admin = m.path("sender_chat").path("id").asLong(0) == chatId;
    if (!admin) {
      try {
        JsonNode member = client.call("getChatMember", Map.of("chat_id", chatId, "user_id", from.path("id").asLong()));
        admin = ADMIN.contains(member.path("status").asString(""));
      } catch (TelegramClient.Failure e) {
        reply(chatId, texts.t(userLang, "telegram.adminUnknown"));
        return;
      }
    }
    if (!admin) {
      reply(chatId, texts.t(userLang, "telegram.notAdmin"));
      return;
    }
    String lang = arg.toLowerCase(Locale.ROOT).startsWith("en") ? "en" : arg.toLowerCase(Locale.ROOT).startsWith("zh") ? "zh-CN"
        : "en".equals(userLang) ? "en" : "zh-CN";
    mapper.setBroadcast(chatId, on, on ? lang : null, now());
    reply(chatId, on ? texts.t(lang, "telegram.broadcastOn", Map.of("lang", texts.t(lang, "telegram.langName"))) : texts.t(userLang, "telegram.broadcastOff"));
  }

  private String chatLang(long chatId, JsonNode from) {
    TelegramMapper.Chat c = mapper.chat(chatId);
    if (c != null && c.broadcast) return c.lang;
    return GrowthTexts.lang(from.path("language_code").asString(null));
  }

  // ---------------------------------------------------------------------
  // 回复内容（只有系统数据：币安行情、我们自己的奖池）
  // ---------------------------------------------------------------------

  /** /btc：BTC 现价、过去 N 小时的涨跌比例（数据页同一份）、当前这场 BTC 市场的各选项概率和人数、链接 */
  String btcText(String lang, String linkQuery) {
    List<String> lines = new ArrayList<>();
    BtcBoard.Snapshot s = board.snapshot();
    if (s == null) {
      lines.add(texts.t(lang, "telegram.btcNoData"));
    } else {
      lines.add(texts.t(lang, "telegram.btcPrice", Map.of("price", s.price(), "time", HM.format(s.fetchedAt()))));
      lines.add(texts.t(lang, "telegram.btcHours", Map.of("hours", String.valueOf(s.hours()), "up", String.valueOf(s.up()), "down", String.valueOf(s.down()),
          "upPct", s.upPct())));
    }
    lines.add("");
    Optional<PublicMarketsApi.MarketStat> m = markets.currentAutogen(boardProps.getSymbol());
    if (m.isEmpty()) {
      lines.add(texts.t(lang, "telegram.noMarket"));
      lines.add(texts.t(lang, "telegram.view", Map.of("url", link("/markets", linkQuery))));
      return String.join("\n", lines);
    }
    PublicMarketsApi.MarketStat ms = m.get();
    lines.add(texts.t(lang, "telegram.market", Map.of("title", pick(ms.title(), lang))));
    for (PublicMarketsApi.OptionStat o : ms.options()) {
      lines.add(texts.t(lang, "telegram.option", Map.of("label", pick(o.label(), lang), "pct", o.pct(), "bettors", String.valueOf(o.bettors()))));
    }
    if (ms.upcoming()) {
      lines.add(texts.t(lang, "telegram.upcoming", Map.of("open", HM.format(ms.openAt()), "close", HM.format(ms.closeAt()))));
    } else {
      lines.add(texts.t(lang, "telegram.pool", Map.of("pool", ms.poolTotal(), "bettors", String.valueOf(ms.bettors()), "close", HM.format(ms.closeAt()))));
    }
    lines.add(texts.t(lang, "telegram.note"));
    lines.add(texts.t(lang, "telegram.view", Map.of("url", link("/markets/" + ms.publicNo(), linkQuery))));
    return String.join("\n", lines);
  }

  /** /today：今天（UTC）的场次和两边的人数、概率 */
  String todayText(String lang, String linkQuery) {
    PublicMarketsApi.Slate slate = markets.todaySlate();
    List<String> lines = new ArrayList<>();
    if (slate.items().isEmpty()) {
      lines.add(texts.t(lang, "telegram.todayNone"));
      if (slate.next() != null) {
        lines.add(texts.t(lang, "telegram.todayNext", Map.of("time", HM.format(start(slate.next())), "title", pick(slate.next().title(), lang))));
      }
    } else {
      lines.add(texts.t(lang, "telegram.todayTitle"));
      for (PublicMarketsApi.MarketStat ms : slate.items()) {
        lines.add("");
        lines.add(texts.t(lang, "telegram.todayItem", Map.of("time", HM.format(start(ms)), "title", pick(ms.title(), lang))));
        for (PublicMarketsApi.OptionStat o : ms.options()) {
          lines.add(texts.t(lang, "telegram.option", Map.of("label", pick(o.label(), lang), "pct", o.pct(), "bettors", String.valueOf(o.bettors()))));
        }
        lines.add(link("/markets/" + ms.publicNo(), linkQuery));
      }
    }
    lines.add("");
    lines.add(texts.t(lang, "telegram.view", Map.of("url", link("/markets", linkQuery))));
    return String.join("\n", lines);
  }

  private static Instant start(PublicMarketsApi.MarketStat m) {
    return m.eventStartAt() != null ? m.eventStartAt() : m.closeAt();
  }

  private void reply(long chatId, String text) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("chat_id", chatId);
    p.put("text", cut(text, 4000));
    p.put("link_preview_options", Map.of("is_disabled", true));
    try {
      client.call("sendMessage", p);
    } catch (TelegramClient.Failure e) {
      log.info("Telegram 回复失败（chat {}）：{}", chatId, e.getMessage());
    }
  }

  String link(String path, String query) {
    return clientUrl + path + (query == null || query.isEmpty() ? "" : "?" + query);
  }

  private boolean isMe(JsonNode user) {
    return user.path("is_bot").asBoolean(false) && username != null && username.equalsIgnoreCase(user.path("username").asString(""));
  }

  static String pick(I18nText t, String lang) {
    if (t == null) return "";
    String v = switch (lang) {
      case "zh-CN" -> t.zhCN();
      case "zh-TW" -> t.zhTW() == null || t.zhTW().isBlank() ? t.zhCN() : t.zhTW();
      default -> t.en();
    };
    return v == null || v.isBlank() ? (t.en() == null ? "" : t.en()) : v;
  }

  static String cut(String s, int n) {
    return s == null ? "" : s.length() <= n ? s : s.substring(0, n - 1) + "…";
  }

  static String sha256(String v) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
