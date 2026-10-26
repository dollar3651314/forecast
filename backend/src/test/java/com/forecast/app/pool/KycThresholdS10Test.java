package com.forecast.app.pool;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.common.config.ConfigKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * KYC 阈值（forecast S10 补齐，需求说明书第 3.10 节、AC-62）：阈值是参数（后台参数设置页改，{@code kyc.depositThreshold}），下注时按当时的值判断；
 * 正好等于阈值还能下注，超过 0.01 不能；L1 通过以后可以下注；已有下注不受影响。默认 1,000 的边界在 {@link BetFlowTest}，#7 通知在 KycThresholdNoticeTest。
 */
class KycThresholdS10Test extends PoolTestSupport {

  @AfterEach
  void restore() {
    jdbc.update("UPDATE common_config SET value = '1000' WHERE config_key = ?", ConfigKey.KYC_DEPOSIT_THRESHOLD.key);
    config.refresh();
  }

  @Test
  void AC62_改阈值以后按新值判断_等于阈值能下注_L1通过以后能下注() throws Exception {
    Market m = market(2);
    User u = user("600");
    assertThat(tryBet(u.id(), m, 0, "10")).as("默认阈值 1,000").isEqualTo("OK");
    jdbc.update("UPDATE common_config SET value = '500' WHERE config_key = ?", ConfigKey.KYC_DEPOSIT_THRESHOLD.key);
    config.refresh();
    assertThat(tryBet(u.id(), m, 0, "10")).as("阈值改成 500：累计充值 600 超过").isEqualTo("KYC_REQUIRED");
    assertThat(betLocked(u.id())).as("已有的下注不受影响").isEqualByComparingTo("10");
    User eq = user("500");
    assertThat(tryBet(eq.id(), m, 1, "10")).as("正好等于阈值").isEqualTo("OK");
    deposit(eq.id(), "0.01");
    assertThat(tryBet(eq.id(), m, 1, "10")).isEqualTo("KYC_REQUIRED");
    jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', ?, 'f', 'b', 's', 'APPROVED')",
        u.id(), "P" + u.id());
    assertThat(tryBet(u.id(), m, 0, "10")).as("L1 通过以后").isEqualTo("OK");
  }
}
