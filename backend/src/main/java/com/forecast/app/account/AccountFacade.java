package com.forecast.app.account;

import com.forecast.app.account.api.AccountApi;
import com.forecast.app.account.api.KycStatus;
import com.forecast.app.account.api.SecurityEvent;
import com.forecast.app.account.api.UserStatus;
import com.forecast.app.account.auth.AuthAccess;
import com.forecast.app.account.kyc.KycAccess;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** {@link AccountApi} 的实现：把登录和 KYC 两部分的对外操作合在一起。 */
@Service
class AccountFacade implements AccountApi {

  private final AuthAccess auth;
  private final KycAccess kyc;

  AccountFacade(AuthAccess auth, KycAccess kyc) {
    this.auth = auth;
    this.kyc = kyc;
  }

  @Override
  public void freeze(long userId) {
    auth.freeze(userId);
  }

  @Override
  public void unfreeze(long userId) {
    auth.unfreeze(userId);
  }

  @Override
  public UserStatus status(long userId) {
    return auth.status(userId);
  }

  @Override
  public String email(long userId) {
    return auth.email(userId);
  }

  @Override
  public LocalDateTime registeredAt(long userId) {
    return auth.registeredAt(userId);
  }

  @Override
  public Optional<SecurityEvent> lastSecurityEventSince(long userId, LocalDateTime since) {
    return auth.lastSecurityEventSince(userId, since);
  }

  @Override
  public KycStatus kycStatus(long userId) {
    return kyc.status(userId);
  }

  @Override
  public void approveKyc(long applicationId, long reviewerId) {
    kyc.approve(applicationId, reviewerId);
  }

  @Override
  public void rejectKyc(long applicationId, long reviewerId, String reason) {
    kyc.reject(applicationId, reviewerId, reason);
  }
}
