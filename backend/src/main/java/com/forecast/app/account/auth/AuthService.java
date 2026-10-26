package com.forecast.app.account.auth;

import com.forecast.app.account.api.SecurityEvent;
import com.forecast.app.account.api.UserStatus;
import com.forecast.app.account.auth.AuthMapper.UserInsert;
import com.forecast.app.account.auth.AuthRequests.Register;
import com.forecast.app.account.auth.Rows.Profile;
import com.forecast.app.account.auth.Rows.UserRow;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.notification.api.NotifyApi;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.wallet.api.WalletApi;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 注册、登录、密码和冻结。会话见 {@link SessionService}，失败计数见 {@link LoginLimiter}。 */
@Service
class AuthService {

  private final AuthMapper mapper;
  private final EmailCodeService codes;
  private final SessionService sessions;
  private final LoginLimiter limiter;
  private final SendCodeLimiter codeLimiter;
  private final LedgerApi ledger;
  private final WalletApi wallet;
  private final NotifyApi notify;
  private final com.forecast.app.account.attribution.AttributionService attribution;
  private final Clock clock;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

  AuthService(AuthMapper mapper, EmailCodeService codes, SessionService sessions, LoginLimiter limiter, SendCodeLimiter codeLimiter, LedgerApi ledger,
      WalletApi wallet, NotifyApi notify, com.forecast.app.account.attribution.AttributionService attribution, Clock clock) {
    this.mapper = mapper;
    this.codes = codes;
    this.sessions = sessions;
    this.limiter = limiter;
    this.codeLimiter = codeLimiter;
    this.ledger = ledger;
    this.wallet = wallet;
    this.notify = notify;
    this.attribution = attribution;
    this.clock = clock;
  }

  /**
   * 记一条账号安全事件（v2 提现自动审批 R6）：和改密码、重置密码、冻结、解冻在同一个事务里写。
   * 时间用注入的时钟（UTC），和提现判定的"现在"同一个来源。
   */
  private void securityEvent(long userId, String type) {
    mapper.insertSecurityEvent(userId, type, LocalDateTime.now(clock.withZone(ZoneOffset.UTC)));
  }

  LocalDateTime registeredAt(long userId) {
    LocalDateTime t = mapper.registeredAt(userId);
    if (t == null) throw new BizException("USER_NOT_FOUND", "用户不存在");
    return t;
  }

  Optional<SecurityEvent> lastSecurityEventSince(long userId, LocalDateTime since) {
    return Optional.ofNullable(mapper.lastSecurityEventSince(userId, since));
  }

  static String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * 发验证码。注册：邮箱已注册时直接提示；重置密码：邮箱不存在时假装已发送，不暴露哪些邮箱注册过。
   * 先按 IP 限次（安全审查 SEC-03），邮箱存不存在都算一次。
   *
   * @return 下次可以重发前要等的秒数
   */
  int sendCode(String rawEmail, EmailCodeService.Purpose purpose, String ip) {
    if (!codeLimiter.allow(ip)) throw new BizException("CODE_TOO_FREQUENT", "发送太频繁，请稍后再试");
    String email = normalizeEmail(rawEmail);
    boolean exists = mapper.findUserByEmail(email) != null;
    if (purpose == EmailCodeService.Purpose.REGISTER && exists) throw new BizException("EMAIL_TAKEN", "邮箱已注册");
    // 找回密码时邮箱不存在：不发，但返回和发了一样的结果，不暴露邮箱是否注册过
    if (purpose == EmailCodeService.Purpose.RESET_PASSWORD && !exists) return codes.cooldownSeconds();
    return codes.send(email, purpose);
  }

  /** 注册：建用户、资料、交易账户，返回用户 ID。同一个事务。 */
  @Transactional
  long register(Register r) {
    String email = normalizeEmail(r.email());
    if (mapper.findUserByEmail(email) != null) throw new BizException("EMAIL_TAKEN", "邮箱已注册");
    // forecast S11（需求 P8、第 3.10 节）：年满 18 周岁（按 UTC 日期，和客户端 validateBirthDate、后台 CustomerAdmin 一致）；先于验证码检查，不消耗验证码
    if (r.birthDate().plusYears(18).isAfter(LocalDate.now(clock.withZone(ZoneOffset.UTC)))) {
      throw new ValidationException(List.of(new ValidationException.Field("birthDate", "AGE_UNDER_18")));
    }
    if (!codes.verifyAndConsume(email, EmailCodeService.Purpose.REGISTER, r.code())) throw new BizException("CODE_INVALID", "验证码错误或已失效");
    UserInsert u = new UserInsert(email, encoder.encode(r.password()));
    u.termsAcceptedAt = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    try {
      mapper.insertUser(u);
    } catch (DuplicateKeyException e) {
      throw new BizException("EMAIL_TAKEN", "邮箱已注册"); // 同一邮箱同时注册，唯一键兜底
    }
    mapper.insertProfile(new Profile(u.id, r.firstName().trim(), r.middleName() == null ? "" : r.middleName().trim(), r.lastName().trim(), r.birthDate(), r.nationality()));
    // forecast S13（MF-1）：浏览器里记的来源（首次来源、注册来源），只用于来源统计
    if (r.attribution() != null) {
      AuthRequests.Touch f = r.attribution().first();
      AuthRequests.Touch l = r.attribution().last();
      attribution.record(u.id, f == null ? null : new com.forecast.app.account.attribution.AttributionService.Touch(f.src(), f.cmp(), f.ref(), f.start(), f.at()),
          l == null ? null : new com.forecast.app.account.attribution.AttributionService.Touch(l.src(), l.cmp(), l.ref(), l.start(), l.at()));
    }
    ledger.openAccount(u.id);
    // 注册时同步分配充值地址（需求 4.4-1）；服务器还没配置合约地址参数（ERC20、BEP20）或 TRON xpub 时那个体系跳过，打开资产页时补分配
    wallet.allocateAddresses(u.id);
    // 欢迎站内信（体验优化 1021 UX-9）：内容和客户端新手引导一致，下一步是认证 → 充值；模板 WELCOME 只发站内信，不推送、不发邮件
    notify.toUser(u.id, "WELCOME", Map.of());
    // forecast S16（SQ-27，Amos 2026-10-26）：注册成功后系统自动登录（AuthController 接着建会话），这也是一次成功登录——验证码已经验证过邮箱。
    // 记下登录时间，开赛提醒（#23，SQ-20"登录成功过的客户"）的订阅资格按它判断，客户不用退出再登录一次
    mapper.touchLogin(u.id);
    return u.id;
  }

  /** 登录：成功返回用户；锁定、密码错误、冻结分别抛不同的错误码。用户不存在和密码错误用同一个错误码。 */
  UserRow login(String rawEmail, String password, String ip) {
    String email = normalizeEmail(rawEmail);
    if (limiter.isLocked(email, ip)) throw new BizException("LOGIN_LOCKED", "失败次数过多，请稍后再试");
    UserRow u = mapper.findUserByEmail(email);
    if (u == null || u.passwordHash() == null || !encoder.matches(password, u.passwordHash())) {
      limiter.recordFailure(email, ip);
      throw new BizException("INVALID_CREDENTIALS", "邮箱或密码错误");
    }
    if (!UserStatus.ACTIVE.name().equals(u.status())) throw new BizException("USER_FROZEN", "账号已冻结");
    limiter.clear(email, ip);
    mapper.touchLogin(u.id());
    return u;
  }

  /** 忘记密码：验证码重置，成功后所有登录失效（AC-03） */
  @Transactional
  void resetPassword(String rawEmail, String code, String password) {
    String email = normalizeEmail(rawEmail);
    UserRow u = mapper.findUserByEmail(email);
    if (u == null || !codes.verifyAndConsume(email, EmailCodeService.Purpose.RESET_PASSWORD, code)) throw new BizException("CODE_INVALID", "验证码错误或已失效");
    mapper.updatePassword(u.id(), encoder.encode(password));
    securityEvent(u.id(), SecurityEvent.PASSWORD_RESET);
    sessions.revokeAll(u.id(), null);
  }

  /** 修改密码：需要原密码；成功后其他设备的登录失效，当前设备保留（AC-03） */
  @Transactional
  void changePassword(long userId, String currentPassword, String newPassword, String currentToken) {
    UserRow u = mapper.findUserById(userId);
    if (u == null || !encoder.matches(currentPassword, u.passwordHash())) throw new BizException("CURRENT_PASSWORD_WRONG", "原密码错误");
    mapper.updatePassword(userId, encoder.encode(newPassword));
    securityEvent(userId, SecurityEvent.PASSWORD_CHANGE);
    sessions.revokeAll(userId, currentToken);
  }

  /** 成功登录过（forecast S11，#23 订阅的条件，SQ-20）；S16 起注册时的自动登录也算（SQ-27） */
  boolean hasLoggedIn(long userId) {
    Boolean v = mapper.hasLoggedIn(userId);
    if (v == null) throw new BizException("USER_NOT_FOUND", "用户不存在");
    return v;
  }

  /** 员工账户（不能下注，forecast S4） */
  boolean isStaff(long userId) {
    Boolean staff = mapper.isStaff(userId);
    if (staff == null) throw new BizException("USER_NOT_FOUND", "用户不存在");
    return staff;
  }

  /** 一批客户里的员工账户（forecast S5，E15） */
  java.util.Set<Long> staffAmong(java.util.Collection<Long> userIds) {
    java.util.Set<Long> out = new java.util.HashSet<>();
    if (userIds == null || userIds.isEmpty()) return out;
    List<Long> all = List.copyOf(new java.util.TreeSet<>(userIds));
    for (int i = 0; i < all.size(); i += 1000) out.addAll(mapper.staffAmong(all.subList(i, Math.min(all.size(), i + 1000))));
    return out;
  }

  UserRow user(long userId) {
    UserRow u = mapper.findUserById(userId);
    if (u == null) throw new BizException("USER_NOT_FOUND", "用户不存在");
    return u;
  }

  @Transactional
  void freeze(long userId) {
    user(userId);
    mapper.updateStatus(userId, UserStatus.FROZEN.name());
    securityEvent(userId, SecurityEvent.FREEZE);
    sessions.revokeAll(userId, null);
  }

  @Transactional
  void unfreeze(long userId) {
    user(userId);
    mapper.updateStatus(userId, UserStatus.ACTIVE.name());
    securityEvent(userId, SecurityEvent.UNFREEZE);
  }

  UserStatus status(long userId) {
    return UserStatus.valueOf(user(userId).status());
  }
}
