package com.forecast.app.market.remind;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.account.api.UserStatus;
import com.forecast.app.common.error.BizException;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.notification.api.NotifyApi;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #23 开赛提醒（forecast S11；需求说明书第 7 节 #23、v1.3.2 第 18.2 节 SQ-20；开发记录/S11-客户端.md 第 3 节）。
 * <ul>
 *   <li><b>谁能订阅</b>：注册后用邮箱和密码成功登录过至少一次的客户（{@link AccountApi#hasLoggedIn}，SQ-20："开放给登录成功过的客户"）；
 *       发到账户邮箱，不能填别的邮箱。</li>
 *   <li><b>订阅什么</b>：一个还没开放下注的市场（已上架或已暂停、开放时间在将来），在市场详情点"开放时提醒我"；"通知偏好"里列出订阅、可以退订。</li>
 *   <li><b>什么时候发</b>：市场到开放时间时，{@link ReminderJob}（每 30 秒，租约 {@code market.reminder}）发一封邮件（模板 MATCH_REMINDER：
 *       题目按客户的语言、开放和截止时间、市场链接、退订链接）；只发一次。到点时市场已经不能下注（取消、作废、已截止）或账户被冻结的不发（MISSED）。</li>
 *   <li><b>邮件开关</b>：走 {@link NotifyApi#toUser}，客户在"通知偏好"关了邮件就不发（通知记录写"跳过"），和需求第 7 节"发 = 客户没关闭该渠道时发送"一致。</li>
 *   <li><b>退订</b>：客户端"通知偏好"或市场详情；邮件里的退订链接不用登录（随机串 {@code token}，只能退订这一条）。</li>
 * </ul>
 * 每一步都是带旧状态的条件更新，发送和"ACTIVE → SENT"在同一个事务（通知和业务同一事务，约定 10），所以重复执行不会重复发。
 */
@Service
public class ReminderService {

  private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
  static final String TEMPLATE = "MATCH_REMINDER";
  /** 每个客户同时订阅中的上限（防止滥用；超出要求加的，开发记录 S11 第 9 节） */
  static final int MAX_ACTIVE = 100;
  /** "通知偏好"里除了订阅中的，再列出最近多少条已发 / 已退订 / 错过的 */
  static final int HISTORY = 20;
  private static final Set<MarketStatus> SUBSCRIBABLE = EnumSet.of(MarketStatus.LISTED, MarketStatus.PAUSED);
  private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");
  private static final DateTimeFormatter UTC_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");
  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * 市场详情里本人的提醒状态（{@code reminder} 字段）。
   *
   * @param available 现在能订阅（市场已上架或已暂停、还没到开放时间）
   * @param eligible 本人能订阅（用邮箱和密码登录过，SQ-20）
   * @param status none 没订阅 / active 订阅中 / sent 已发 / cancelled 已退订 / missed 没有发（开放前市场已结束）
   */
  public record State(boolean available, boolean eligible, String status) {}

  /** "通知偏好"里的一条订阅 */
  public record Item(String marketNo, I18nText title, String marketStatus, String openAt, String closeAt, String status, String sentAt) {}

  /** 本人的订阅列表 */
  public record Listing(boolean eligible, List<Item> items) {}

  /** 邮件退订链接的结果（不用登录；只给题目和状态，不给邮箱等信息） */
  public record Unsubscribed(String marketNo, I18nText title, String status) {}

  private final ReminderMapper mapper;
  private final AccountApi accounts;
  private final NotifyApi notify;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final String clientUrl;

  ReminderService(ReminderMapper mapper, AccountApi accounts, NotifyApi notify, Clock clock, PlatformTransactionManager tm,
      @Value("${forecast.client-url:}") String clientUrl) {
    this.mapper = mapper;
    this.accounts = accounts;
    this.notify = notify;
    this.clock = clock;
    this.tx = new TransactionTemplate(tm);
    this.clientUrl = clientUrl == null ? "" : clientUrl.strip().replaceAll("/+$", "");
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
  }

  private boolean subscribable(String status, LocalDateTime openAt, LocalDateTime deletedAt) {
    MarketStatus s = MarketStatus.valueOf(status);
    return deletedAt == null && SUBSCRIBABLE.contains(s) && openAt.isAfter(now());
  }

  // =====================================================================
  // 客户端
  // =====================================================================

  /** 市场详情里的状态；userId 为空（没登录）或者既不能订阅、也没有订阅记录时返回 null（详情里不显示） */
  @Transactional(readOnly = true)
  public State state(Long userId, long marketId, MarketStatus status, LocalDateTime openAt) {
    if (userId == null) return null;
    boolean available = SUBSCRIBABLE.contains(status) && openAt.isAfter(now());
    ReminderMapper.Row r = mapper.find(userId, marketId);
    if (!available && r == null) return null;
    return new State(available, accounts.hasLoggedIn(userId), r == null ? "none" : r.status.toLowerCase(java.util.Locale.ROOT));
  }

  /** 订阅（重复订阅没有效果；退订过的重新订阅） */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public State subscribe(long userId, long publicNo) {
    ReminderMapper.MarketRow m = market(publicNo);
    if (!subscribable(m.status, m.openAt, m.deletedAt)) throw new BizException("REMINDER_NOT_AVAILABLE", "这个市场已经开放下注或已结束，不能再订阅开赛提醒");
    if (!accounts.hasLoggedIn(userId)) throw new BizException("REMINDER_LOGIN_REQUIRED", "开赛提醒只开放给用邮箱和密码登录过的账户");
    ReminderMapper.Row r = mapper.find(userId, m.id);
    if (r != null && "ACTIVE".equals(r.status)) return new State(true, true, "active");
    if (mapper.countActive(userId) >= MAX_ACTIVE) throw new BizException("REMINDER_TOO_MANY", "订阅中的开赛提醒太多，请先退订一些");
    LocalDateTime now = now();
    if (r == null) {
      mapper.insertIgnore(userId, m.id, newToken(), now);
    } else if ("CANCELLED".equals(r.status) || "MISSED".equals(r.status)) {
      // 重新订阅：退订过的（或者市场延后开放之前错过的）改回订阅中
      mapper.cas(r.id, r.status, "ACTIVE", null, now);
    }
    ReminderMapper.Row after = mapper.find(userId, m.id);
    return new State(true, true, after == null ? "none" : after.status.toLowerCase(java.util.Locale.ROOT));
  }

  /** 在客户端退订（没有订阅、已经发过的没有效果） */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public State unsubscribe(long userId, long publicNo) {
    ReminderMapper.MarketRow m = market(publicNo);
    ReminderMapper.Row r = mapper.find(userId, m.id);
    if (r != null && "ACTIVE".equals(r.status)) mapper.cas(r.id, "ACTIVE", "CANCELLED", "USER", now());
    ReminderMapper.Row after = mapper.find(userId, m.id);
    return new State(subscribable(m.status, m.openAt, m.deletedAt), accounts.hasLoggedIn(userId),
        after == null ? "none" : after.status.toLowerCase(java.util.Locale.ROOT));
  }

  /** 邮件里的退订链接（不用登录）：随机串不对 → 404 */
  @Transactional
  public Unsubscribed unsubscribeByToken(String token) {
    String t = token == null ? "" : token.strip().toLowerCase(java.util.Locale.ROOT);
    ReminderMapper.Row r = TOKEN.matcher(t).matches() ? mapper.byToken(t) : null;
    if (r == null) throw new BizException("NOT_FOUND", "退订链接无效");
    if ("ACTIVE".equals(r.status)) mapper.cas(r.id, "ACTIVE", "CANCELLED", "LINK", now());
    ReminderMapper.Row after = mapper.byId(r.id);
    return new Unsubscribed(String.valueOf(after.publicNo), title(after), after.status.toLowerCase(java.util.Locale.ROOT));
  }

  /** "通知偏好"里的订阅列表 */
  @Transactional(readOnly = true)
  public Listing list(long userId) {
    List<Item> items = mapper.listOf(userId, HISTORY).stream()
        .map(r -> new Item(String.valueOf(r.publicNo), title(r), MarketStatus.valueOf(r.marketStatus).api(), iso(r.openAt), iso(r.closeAt),
            r.status.toLowerCase(java.util.Locale.ROOT), iso(r.sentAt)))
        .toList();
    return new Listing(accounts.hasLoggedIn(userId), items);
  }

  private ReminderMapper.MarketRow market(long publicNo) {
    ReminderMapper.MarketRow m = mapper.market(publicNo);
    if (m == null || m.deletedAt != null || MarketStatus.DRAFT.name().equals(m.status)) throw new BizException("NOT_FOUND", "市场不存在");
    return m;
  }

  // =====================================================================
  // 定时发送（ReminderJob；e2e 下 POST /api/e2e/reminders/tick）
  // =====================================================================

  /**
   * 处理到点的订阅，返回发出的邮件数。每条订阅一个事务：条件更新 ACTIVE → SENT（或 MISSED），更新到 1 行才在同一个事务里交给通知模块。
   * 不能在事务里调用（每条单独提交）。
   */
  public int sendDue() {
    int sent = 0;
    for (int round = 0; round < 20; round++) {
      List<Long> ids = mapper.due(now(), 200);
      for (Long id : ids) {
        try {
          Boolean ok = tx.execute(st -> sendOne(id));
          if (Boolean.TRUE.equals(ok)) sent++;
        } catch (RuntimeException e) {
          // 一条出错不影响别的；它还是 ACTIVE，下一轮再试
          log.error("开赛提醒 {} 发送失败，下一轮重试", id, e);
        }
      }
      if (ids.size() < 200) break;
    }
    return sent;
  }

  private boolean sendOne(long id) {
    ReminderMapper.Row r = mapper.byId(id);
    if (r == null || !"ACTIVE".equals(r.status)) return false;
    LocalDateTime now = now();
    if (r.openAt.isAfter(now)) return false;
    MarketStatus s = MarketStatus.valueOf(r.marketStatus);
    boolean frozen;
    try {
      frozen = accounts.status(r.userId) != UserStatus.ACTIVE;
    } catch (BizException e) {
      frozen = true;
    }
    if (!SUBSCRIBABLE.contains(s) || !now.isBefore(r.closeAt) || frozen) {
      mapper.cas(r.id, "ACTIVE", "MISSED", null, now);
      return false;
    }
    if (mapper.cas(r.id, "ACTIVE", "SENT", null, now) != 1) return false;
    I18nText t = title(r);
    Map<String, String> params = new LinkedHashMap<>();
    params.put("match", t.zhCN());
    params.put("matchEn", t.en());
    params.put("matchTw", t.zhTW());
    params.put("openAt", UTC_TEXT.format(r.openAt));
    params.put("closeAt", UTC_TEXT.format(r.closeAt));
    params.put("link", clientUrl + "/markets/" + r.publicNo);
    params.put("unsubscribe", clientUrl + "/unsubscribe?t=" + r.token);
    notify.toUser(r.userId, TEMPLATE, params);
    return true;
  }

  // =====================================================================

  static String newToken() {
    byte[] b = new byte[16];
    RANDOM.nextBytes(b);
    return HexFormat.of().formatHex(b);
  }

  private static I18nText title(ReminderMapper.Row r) {
    return new I18nText(r.titleEn, r.titleZhCn, r.titleZhTw);
  }

  private static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }
}
