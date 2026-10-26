package com.forecast.app.account.auth;

import com.forecast.app.account.api.SecurityEvent;
import com.forecast.app.account.api.UserStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 给 account 模块其他包（例如 {@code AccountFacade}）用的登录相关操作，auth 包的内部类不对外。 */
@Component
public class AuthAccess {

  private final AuthService auth;

  AuthAccess(AuthService auth) {
    this.auth = auth;
  }

  public void freeze(long userId) {
    auth.freeze(userId);
  }

  public void unfreeze(long userId) {
    auth.unfreeze(userId);
  }

  public UserStatus status(long userId) {
    return auth.status(userId);
  }

  public boolean isStaff(long userId) {
    return auth.isStaff(userId);
  }

  public boolean hasLoggedIn(long userId) {
    return auth.hasLoggedIn(userId);
  }

  public java.util.Set<Long> staffAmong(java.util.Collection<Long> userIds) {
    return auth.staffAmong(userIds);
  }

  public String email(long userId) {
    return auth.user(userId).email();
  }

  public LocalDateTime registeredAt(long userId) {
    return auth.registeredAt(userId);
  }

  public Optional<SecurityEvent> lastSecurityEventSince(long userId, LocalDateTime since) {
    return auth.lastSecurityEventSince(userId, since);
  }
}
