package com.forecast.app.account.kyc;

import com.forecast.app.account.api.KycStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 给 {@code AccountFacade} 用的 KYC 操作。 */
@Component
public class KycAccess {

  private final KycService kyc;

  KycAccess(KycService kyc) {
    this.kyc = kyc;
  }

  public KycStatus status(long userId) {
    return kyc.status(userId);
  }

  @Transactional
  public void approve(long applicationId, long reviewerId) {
    kyc.approve(applicationId, reviewerId);
  }

  @Transactional
  public void reject(long applicationId, long reviewerId, String reason) {
    kyc.reject(applicationId, reviewerId, reason);
  }
}
