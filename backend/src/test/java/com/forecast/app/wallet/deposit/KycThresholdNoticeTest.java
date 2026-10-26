package com.forecast.app.wallet.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.pool.PoolTestSupport;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 通知 #7（forecast S9，需求第 7 节、第 3.10 节）：累计充值（扣冲正）第一次超过 KYC 阈值（默认 1,000）、还没认证时发一条（站内信 + 推送 + 邮件），
 * 之后的充值不再发；已认证的不发。口径和下注的 KYC_REQUIRED 拒单一致。
 */
class KycThresholdNoticeTest extends PoolTestSupport {

  @Autowired KycThresholdNotice notice;

  int count(long uid) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE user_id = ? AND template_code = 'KYC_REQUIRED'", Integer.class, uid);
  }

  @Test
  void 第一次超过阈值发一条_之后不再发_已认证不发() throws Exception {
    User u = user("0");
    deposit(u.id(), "1000");
    assertThat(notice.afterCredit(u.id(), new BigDecimal("1000"))).as("正好等于阈值不算超过").isFalse();
    deposit(u.id(), "0.01");
    assertThat(notice.afterCredit(u.id(), new BigDecimal("0.01"))).isTrue();
    deposit(u.id(), "500");
    assertThat(notice.afterCredit(u.id(), new BigDecimal("500"))).isFalse();
    assertThat(count(u.id())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT params FROM notify_message WHERE user_id = ? AND template_code = 'KYC_REQUIRED'", String.class, u.id()))
        .contains("\"threshold\":\"1000.00\"").contains("\"total\":\"1000.01\"");
    assertThat(jdbc.queryForList("SELECT channel FROM notify_record WHERE user_id = ? AND template_code = 'KYC_REQUIRED' ORDER BY id", String.class, u.id()))
        .contains("INBOX", "EMAIL");
    // 一次充值就跨过阈值
    User big = user("0");
    deposit(big.id(), "3000");
    assertThat(notice.afterCredit(big.id(), new BigDecimal("3000"))).isTrue();
    // 已认证的不发
    User kyc = user("0", true);
    deposit(kyc.id(), "3000");
    assertThat(notice.afterCredit(kyc.id(), new BigDecimal("3000"))).isFalse();
    assertThat(count(kyc.id())).isZero();
  }
}
