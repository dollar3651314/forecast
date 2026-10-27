package com.forecast.app.growth.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.common.health.AlertCheck;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** forecast S16：健康检查 alerts 的 telegram.bot（没有 token 时"未配置"、只是告警项，不是 HealthCheck，所以不会让 status 变成 DOWN，约定 11）。 */
class TelegramAlertTest {

  @Test
  void 没有token时提示未配置_配了正常_关掉告警时不报() {
    TelegramProperties props = new TelegramProperties();
    TelegramClient client = new TelegramClient(props, new ObjectMapper(), Clock.systemUTC());
    List<AlertCheck.Item> items = new TelegramAlert(props, client, true).evaluate();
    assertThat(items).hasSize(1);
    assertThat(items.get(0).name()).isEqualTo("telegram.bot");
    assertThat(items.get(0).ok()).isFalse();
    assertThat(items.get(0).detail()).contains("未配置 FC_TELEGRAM_BOT_TOKEN");
    assertThat(items.get(0).status()).as("S12b：没配 token 是未开通，不发告警邮件").isEqualTo(AlertCheck.Status.NOT_CONFIGURED);
    props.setToken("ci_only_unit");
    assertThat(new TelegramAlert(props, client, true).evaluate().get(0).ok()).isTrue();
    assertThat(new TelegramAlert(props, client, false).evaluate()).isEmpty();
    // 告警项不是 HealthCheck（HealthCheck 不通过会让 status 变成 DOWN、部署回滚）
    assertThat(com.forecast.app.common.health.HealthCheck.class.isAssignableFrom(TelegramAlert.class)).isFalse();
  }

  @Test
  void 端到端模拟没有token() {
    TelegramProperties props = new TelegramProperties();
    props.setToken("ci_only_unit");
    props.e2eConfigured(false);
    assertThat(props.configured()).isFalse();
    props.e2eConfigured(null);
    assertThat(props.configured()).isTrue();
  }

  @Test
  void 图片说明最多1024个字符_链接一定保留_帖子链接() {
    String text = String.join("\n", java.util.Collections.nCopies(80, "一行很长的说明文字一行很长的说明文字"));
    String c = TelegramPublisher.caption(text, "https://x/markets/1?src=telegram");
    assertThat(c.length()).isLessThanOrEqualTo(1024);
    assertThat(c).endsWith("https://x/markets/1?src=telegram");
    assertThat(TelegramPublisher.postUrl("@qc_cn", null, 9)).isEqualTo("https://t.me/qc_cn/9");
    assertThat(TelegramPublisher.postUrl("-1001", "qc_en", 9)).isEqualTo("https://t.me/qc_en/9");
    assertThat(TelegramPublisher.postUrl("-1001", null, 9)).isNull();
  }
}
