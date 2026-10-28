package com.forecast.app.growth.waitlist;

import com.forecast.app.common.attribution.Attribution;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.ratelimit.RateLimiter;
import com.forecast.app.growth.api.WaitlistAdminApi;
import com.forecast.app.notification.api.MailApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 上线前等候页（forecast S16，MF-8；需求说明书第 5.2 节 P13、AC-95；SQ-22 Amos 2026-10-26 按建议）。
 * <ul>
 *   <li><b>留邮箱</b>：双重确认——提交邮箱只发一封确认邮件（WAITLIST_CONFIRM），点邮件里的链接、在页面上按"确认订阅"才算订阅（邮箱扫描器预取链接不会误确认）。
 *       不管邮箱以前有没有留过，回答都一样（"请查收邮件"），不暴露哪些邮箱留过。</li>
 *   <li><b>防刷、频率限制</b>：同一 IP 每小时次数上限（Redis 计数）；页面上的隐藏字段（正常人看不到、不会填）有值时当成机器提交，照样回答、什么都不做；
 *       同一邮箱确认邮件之间至少隔 10 分钟、最多 5 封。</li>
 *   <li><b>只发一封上线通知</b>（SQ-22，不发开赛提醒）：后台"发送上线通知"（content:publish ★，原因必填、审计）只记一次（{@code growth_launch} 主键 1），
 *       之后由 {@link #sendLaunchBatch()} 一个订阅一个事务地发：CONFIRMED → NOTIFIED 的条件更新成功了才在同一事务里写邮件任务（约定 10），重复执行不会重发。
 *       按下以后等候页关闭（不再收邮箱，确认链接也不再生效），页面变成"数据"页。</li>
 *   <li><b>退订</b>：每封邮件都有退订链接（不用登录，随机串只能退订这一个邮箱），任何状态都能退订；退订后不会再收到。</li>
 * </ul>
 * 订阅的人不一定是客户，和账户无关（不查、不写 account 的任何数据）。来源参数（MF-1）一起记下，只用于统计。
 */
@Service
@EnableConfigurationProperties(WaitlistProperties.class)
public class WaitlistService implements WaitlistAdminApi {

  private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);
  static final String CONFIRM_TEMPLATE = "WAITLIST_CONFIRM";
  static final String LAUNCH_TEMPLATE = "WAITLIST_LAUNCH";
  private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final List<String> STATUSES = List.of("pending", "confirmed", "notified", "unsubscribed");

  /** 留邮箱的请求（已经过格式校验）；website 是隐藏的防刷字段 */
  public record Subscribe(String email, String lang, String website, String src, String cmp, String ref, String start) {}

  /** 确认、退订链接的结果（不返回邮箱） */
  public record LinkResult(String status, String lang) {}

  private final WaitlistMapper mapper;
  private final MailApi mail;
  private final RateLimiter limiter;
  private final WaitlistProperties props;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final String clientUrl;
  /** 确认、退订链接的地址：配了对外预热域名（S18）用它，否则用客户端地址 */
  private final String linkBase;

  WaitlistService(WaitlistMapper mapper, MailApi mail, RateLimiter limiter, WaitlistProperties props, Clock clock, PlatformTransactionManager tm,
      @Value("${forecast.client-url:}") String clientUrl, @Value("${forecast.prelaunch-domain:}") String prelaunchDomain) {
    this.mapper = mapper;
    this.mail = mail;
    this.limiter = limiter;
    this.props = props;
    this.clock = clock;
    this.tx = new TransactionTemplate(tm);
    this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.clientUrl = clientUrl == null ? "" : clientUrl.strip().replaceAll("/+$", "");
    this.linkBase = prelaunchBase(prelaunchDomain).orElse(this.clientUrl);
  }

  /** 对外预热域名（只认合法的域名写法，其他一律当没配，免得拼出奇怪的链接） */
  public static java.util.Optional<String> prelaunchBase(String domain) {
    String d = domain == null ? "" : domain.strip().toLowerCase(Locale.ROOT);
    if (!d.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+")) return java.util.Optional.empty();
    return java.util.Optional.of("https://" + d);
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  /** 已经发过上线通知（正式上线了）：等候页关闭，数据页显示今天的场次 */
  public boolean launched() {
    return mapper.launch() != null;
  }

  // =====================================================================
  // 公开：留邮箱、确认、退订
  // =====================================================================

  public void subscribe(Subscribe in, String ip) {
    if (launched()) throw new BizException("WAITLIST_CLOSED", "已经正式上线，等候页不再收邮箱，直接注册即可");
    // 每小时次数上限按 IP 计（隐藏字段有值的也算一次）；超过 429 RATE_LIMITED，带 Retry-After
    limiter.check("waitlist", ip, props.getIpPerHour(), Duration.ofHours(1));
    if (in.website() != null && !in.website().isBlank()) {
      log.info("等候页：隐藏字段有值，当成机器提交，不处理");
      return;
    }
    String email = in.email().strip().toLowerCase(Locale.ROOT);
    String lang = lang(in.lang());
    Attribution.Source a = Attribution.of(in.src(), in.cmp(), in.ref(), in.start());
    tx.executeWithoutResult(st -> {
      LocalDateTime now = now();
      String confirm = token();
      String unsub = token();
      if (mapper.insertIgnore(email, lang, confirm, unsub, a.src(), a.cmp(), a.ref(), a.start(), sha256(ip == null ? "" : ip), now) == 1) {
        sendConfirm(email, lang, confirm, unsub);
        return;
      }
      WaitlistMapper.Row r = mapper.byEmail(email);
      if (r == null) return;
      boolean waited = !now.isBefore(r.confirmSentAt.plus(props.getResendAfter()));
      switch (r.status) {
        case "PENDING" -> {
          // 再发一封（同一个确认链接）；没到时间或者发够了就不发，回答一样
          if (waited && r.confirmSends < props.getMaxConfirmSends() && mapper.resend(r.id, r.confirmSends, lang, now) == 1) {
            sendConfirm(email, lang, r.confirmToken, r.unsubToken);
          }
        }
        case "UNSUBSCRIBED" -> {
          // 退订过又来留：重新走一次确认（新的确认链接）
          String fresh = token();
          if (waited && mapper.resubscribe(r.id, fresh, lang, a.src(), a.cmp(), a.ref(), a.start(), now) == 1) sendConfirm(email, lang, fresh, r.unsubToken);
        }
        default -> {
          // 已确认 / 已通知：什么都不发
        }
      }
    });
  }

  private void sendConfirm(String email, String lang, String confirmToken, String unsubToken) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("confirm", linkBase + "/data/confirm?t=" + confirmToken);
    p.put("unsubscribe", linkBase + "/data/unsubscribe?t=" + unsubToken);
    mail.send(email, CONFIRM_TEMPLATE, p, lang);
  }

  /** 确认订阅（页面上按"确认订阅"）：随机串不对 404；过期 410 WAITLIST_LINK_EXPIRED；已经上线 409 WAITLIST_CLOSED；重复确认没有效果 */
  public LinkResult confirm(String token) {
    WaitlistMapper.Row r = byToken(token, true);
    if ("PENDING".equals(r.status)) {
      if (launched()) throw new BizException("WAITLIST_CLOSED", "已经正式上线，等候页不再收邮箱，直接注册即可");
      if (now().isAfter(r.confirmSentAt.plus(props.getConfirmValid()))) throw new BizException("WAITLIST_LINK_EXPIRED", "确认链接已过期，请重新留一次邮箱");
      mapper.confirm(r.id, now());
    }
    WaitlistMapper.Row after = mapper.byId(r.id);
    return new LinkResult(view(after.status), after.lang);
  }

  /** 退订（邮件里的链接，不用登录）：随机串不对 404；已经退订的没有效果 */
  public LinkResult unsubscribe(String token) {
    WaitlistMapper.Row r = byToken(token, false);
    if (!"UNSUBSCRIBED".equals(r.status)) mapper.unsubscribe(r.id, now());
    WaitlistMapper.Row after = mapper.byId(r.id);
    return new LinkResult(view(after.status), after.lang);
  }

  private WaitlistMapper.Row byToken(String token, boolean confirm) {
    String t = token == null ? "" : token.strip().toLowerCase(Locale.ROOT);
    WaitlistMapper.Row r = TOKEN.matcher(t).matches() ? (confirm ? mapper.byConfirmToken(t) : mapper.byUnsubToken(t)) : null;
    if (r == null) throw new BizException("NOT_FOUND", "链接无效");
    return r;
  }

  // =====================================================================
  // 上线通知
  // =====================================================================

  @Override
  public Launch startLaunch(long adminId, String reason) {
    tx.executeWithoutResult(st -> {
      int recipients = (int) mapper.countByStatus("CONFIRMED");
      if (mapper.insertLaunch(adminId, reason, recipients, now()) != 1) throw new BizException("LAUNCH_ALREADY_SENT", "上线通知已经发过（只能发一次）");
    });
    log.info("等候页：管理员 {} 按下了发送上线通知", adminId);
    return launch();
  }

  /**
   * 发上线通知（{@link LaunchNoticeJob}；e2e 下 {@code POST /api/e2e/waitlist/launch-tick}）：返回这一轮发了几封。不能在事务里调用（一个订阅一个事务）。
   * 没按过"发送上线通知"、已经发完的什么都不做；发完把上线通知记成 DONE。
   */
  public int sendLaunchBatch() {
    WaitlistMapper.Launch l = mapper.launch();
    if (l == null || "DONE".equals(l.status)) return 0;
    int sent = 0;
    long after = 0;
    while (true) {
      List<Long> ids = mapper.confirmedIds(after, props.getLaunchBatch());
      if (ids.isEmpty()) break;
      for (Long id : ids) {
        after = id;
        try {
          Boolean ok = tx.execute(st -> sendLaunchOne(id));
          if (Boolean.TRUE.equals(ok)) sent++;
        } catch (RuntimeException e) {
          // 一个出错不影响别的；它还是 CONFIRMED，下一轮再发
          log.error("等候页：订阅 {} 的上线通知写入失败，下一轮重试", id, e);
          return sent;
        }
      }
    }
    if (mapper.confirmedIds(0, 1).isEmpty()) mapper.finishLaunch(now());
    return sent;
  }

  private boolean sendLaunchOne(long id) {
    WaitlistMapper.Row r = mapper.byId(id);
    if (r == null || !"CONFIRMED".equals(r.status)) return false;
    if (mapper.markNotified(id, now()) != 1) return false;
    Map<String, String> p = new LinkedHashMap<>();
    p.put("link", clientUrl + "/markets?src=waitlist&cmp=launch");
    p.put("unsubscribe", linkBase + "/data/unsubscribe?t=" + r.unsubToken);
    mail.send(r.email, LAUNCH_TEMPLATE, p, r.lang);
    mapper.addSent(1);
    return true;
  }

  // =====================================================================
  // 后台
  // =====================================================================

  @Override
  public Page list(String status, int page, int pageSize) {
    String st = status == null || status.isBlank() ? null : status.strip().toLowerCase(Locale.ROOT);
    if (st != null && !STATUSES.contains(st)) throw new com.forecast.app.common.error.ValidationException(
        List.of(new com.forecast.app.common.error.ValidationException.Field("status", "invalid")));
    String db = st == null ? null : st.toUpperCase(Locale.ROOT);
    int size = Math.max(1, Math.min(pageSize, 100));
    int p = Math.max(1, page);
    List<Item> items = mapper.list(db, (p - 1) * size, size).stream()
        .map(r -> new Item(r.id, mask(r.email), r.lang, view(r.status), r.src, r.cmp, r.ref, r.startParam, iso(r.createdAt), iso(r.confirmedAt), iso(r.notifiedAt),
            iso(r.unsubscribedAt)))
        .toList();
    Counts c = new Counts(mapper.countByStatus("PENDING"), mapper.countByStatus("CONFIRMED"), mapper.countByStatus("NOTIFIED"),
        mapper.countByStatus("UNSUBSCRIBED"));
    return new Page(items, mapper.count(db), p, size, c, launch());
  }

  @Override
  public Launch launch() {
    WaitlistMapper.Launch l = mapper.launch();
    if (l == null) return new Launch("none", null, null, null, 0, 0, null);
    return new Launch(l.status.toLowerCase(Locale.ROOT), iso(l.createdAt), l.adminId, l.reason, l.recipients, l.sent, iso(l.finishedAt));
  }

  // =====================================================================

  /** 邮箱打码：保留第一个字符和域名（a***@example.com） */
  static String mask(String email) {
    int at = email == null ? -1 : email.indexOf('@');
    if (at <= 0) return "***";
    return email.charAt(0) + "***" + email.substring(at);
  }

  static String lang(String l) {
    if (l == null) return "en";
    String s = l.strip();
    if (s.equalsIgnoreCase("zh-TW") || s.equalsIgnoreCase("zh-HK") || s.equalsIgnoreCase("zh-Hant")) return "zh-TW";
    if (s.toLowerCase(Locale.ROOT).startsWith("zh")) return "zh-CN";
    return "en";
  }

  private static String view(String status) {
    return status.toLowerCase(Locale.ROOT);
  }

  private static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }

  static String token() {
    byte[] b = new byte[16];
    RANDOM.nextBytes(b);
    return HexFormat.of().formatHex(b);
  }

  static String sha256(String v) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
