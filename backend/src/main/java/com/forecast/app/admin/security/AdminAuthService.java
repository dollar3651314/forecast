package com.forecast.app.admin.security;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.error.BizException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台登录（后台方案 G2）：用户名 + 密码；连续失败达到次数（系统配置 {@code login.lockThreshold}，默认 5）锁定一段时间（{@code login.lockMinutes}，默认 30 分钟）；
 * 会话 8 小时有效、30 分钟不操作失效；停用、重置密码后已有的登录立即失效。
 */
@Service
public class AdminAuthService {

  /** 登录结果：令牌原值只在这里出现一次，写进 Cookie */
  record LoginResult(String token, long adminId, String username, String displayName, boolean mustChangePassword) {}

  private final SecurityMapper mapper;
  private final SystemConfig config;
  private final AdminProperties props;
  private final Audit audit;
  private final Clock clock;

  AdminAuthService(SecurityMapper mapper, SystemConfig config, AdminProperties props, Audit audit, Clock clock) {
    this.mapper = mapper;
    this.config = config;
    this.props = props;
    this.audit = audit;
    this.clock = clock;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  @Transactional(noRollbackFor = BizException.class)
  LoginResult login(String username, String password, String ip) {
    String name = username == null ? "" : username.strip();
    SecurityMapper.AdminRow a = name.isEmpty() ? null : mapper.byUsername(name);
    LocalDateTime now = now();
    if (a == null) {
      Passwords.matches(password == null ? "" : password, Passwords.DUMMY_HASH);
      audit.record("admin.login", name, false, "FAILURE", "INVALID_CREDENTIALS", null, null);
      throw new BizException("INVALID_CREDENTIALS", "用户名或密码错误");
    }
    if (a.lockedUntil != null && a.lockedUntil.isAfter(now)) {
      audit.record("admin.login", name, false, "FAILURE", "LOGIN_LOCKED", null, null);
      throw new BizException("LOGIN_LOCKED", "失败次数过多，请稍后再试");
    }
    if (!Passwords.matches(password, a.passwordHash)) {
      int failed = a.failedCount + 1;
      int limit = config.integer(ConfigKey.LOGIN_LOCK_THRESHOLD, 5);
      boolean lock = failed >= limit;
      mapper.loginFailed(a.id, lock ? 0 : failed, lock ? now.plus(config.minutes(ConfigKey.LOGIN_LOCK_MINUTES, Duration.ofMinutes(30))) : null);
      audit.record("admin.login", name, false, "FAILURE", lock ? "LOGIN_LOCKED" : "INVALID_CREDENTIALS", null, null);
      if (lock) throw new BizException("LOGIN_LOCKED", "失败次数过多，请稍后再试");
      throw new BizException("INVALID_CREDENTIALS", "用户名或密码错误");
    }
    if (!"ACTIVE".equals(a.status)) {
      audit.record("admin.login", name, false, "FAILURE", "ACCOUNT_DISABLED", null, null);
      throw new BizException("ACCOUNT_DISABLED", "账号已停用");
    }
    mapper.loginOk(a.id, now);
    String token = Tokens.newToken();
    mapper.insertSession(a.id, Tokens.sha256(token), ip, now, now.plus(props.sessionTtl()));
    audit.record("admin.login", name, false, "SUCCESS", null, null, null);
    return new LoginResult(token, a.id, a.username, a.displayName, a.mustChangePassword);
  }

  /** 按令牌找到有效的登录；过期、超时不操作、已退出、管理员已停用都返回空 */
  @Transactional
  Optional<AdminContext> authenticate(String token, String ip) {
    if (token == null || token.isBlank()) return Optional.empty();
    SecurityMapper.SessionRow s = mapper.session(Tokens.sha256(token));
    LocalDateTime now = now();
    if (s == null || s.revokedAt != null || !s.expiresAt.isAfter(now) || !s.lastSeenAt.plus(props.idleTimeout()).isAfter(now)) return Optional.empty();
    SecurityMapper.AdminRow a = mapper.byId(s.adminId);
    if (a == null || !"ACTIVE".equals(a.status)) return Optional.empty();
    // 每分钟最多更新一次最后活动时间
    if (s.lastSeenAt.plusMinutes(1).isBefore(now)) mapper.touchSession(s.id, now);
    boolean superAdmin = mapper.isSuper(a.id);
    Set<Permission> perms = EnumSet.noneOf(Permission.class);
    for (String code : mapper.permissions(a.id)) Permission.of(code).ifPresent(perms::add);
    return Optional.of(new AdminContext(a.id, a.username, ip, superAdmin, Set.copyOf(perms), s.id));
  }

  boolean mustChangePassword(long adminId) {
    SecurityMapper.AdminRow a = mapper.byId(adminId);
    return a != null && a.mustChangePassword;
  }

  @Transactional
  void logout(AdminContext ctx) {
    mapper.revokeSession(ctx.sessionId(), now());
    audit.record("admin.logout", ctx.username(), false, "SUCCESS", null, null, null);
  }

  /** 改自己的密码：要原密码；新密码要够强、不能和原密码一样；其他设备上的登录退出 */
  @Transactional
  void changePassword(AdminContext ctx, String oldPassword, String newPassword) {
    audit.run("admin.change_password", ctx.username(), false, null, () -> {
      SecurityMapper.AdminRow a = mapper.byId(ctx.adminId());
      if (a == null || !Passwords.matches(oldPassword, a.passwordHash)) throw new BizException("CURRENT_PASSWORD_WRONG", "原密码错误");
      Passwords.check("newPassword", newPassword);
      if (newPassword.equals(oldPassword)) throw new BizException("SAME_PASSWORD", "新密码不能和原密码一样");
      mapper.updatePassword(a.id, Passwords.hash(newPassword), false);
      mapper.revokeAll(a.id, now());
      return Audit.Change.of(null, Map.of("mustChangePassword", a.mustChangePassword, "password", "-"), Map.of("mustChangePassword", false, "password", "-"));
    });
  }

  /** 改完密码后当前设备重新发一个令牌（其他设备已经退出） */
  @Transactional
  String reissue(long adminId, String ip) {
    String token = Tokens.newToken();
    LocalDateTime now = now();
    mapper.insertSession(adminId, Tokens.sha256(token), ip, now, now.plus(props.sessionTtl()));
    return token;
  }
}
