package com.forecast.app.growth.telegram;

import com.forecast.app.common.health.AlertCheck;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 健康检查 {@code alerts} 里的 {@code telegram.bot}（forecast S16，MF-7）：没配置 {@code FC_TELEGRAM_BOT_TOKEN} 时是 DOWN（"未配置，机器人没有启动"），
 * 调用连续失败、熔断中也是 DOWN。<b>只进 {@code alerts}，不影响 {@code status}</b>（约定 11：外部服务的问题换版本也解决不了，不能让部署回滚）。
 * 开关 {@code forecast.telegram.alert-enabled}（后端测试关闭）。
 */
@Component
class TelegramAlert implements AlertCheck {

  private final TelegramProperties props;
  private final TelegramClient client;
  private final boolean enabled;

  TelegramAlert(TelegramProperties props, TelegramClient client, @Value("${forecast.telegram.alert-enabled:true}") boolean enabled) {
    this.props = props;
    this.client = client;
    this.enabled = enabled;
  }

  @Override
  public List<Item> evaluate() {
    if (!enabled) return List.of();
    String zh = "Telegram 机器人";
    String en = "Telegram bot";
    if (!props.configured()) return List.of(new Item("telegram.bot", zh, en, false, "未配置 FC_TELEGRAM_BOT_TOKEN，机器人没有启动（不影响网站）"));
    if (client.circuitOpen() || client.consecutiveFailures() >= 3) {
      return List.of(new Item("telegram.bot", zh, en, false, "连续 " + client.consecutiveFailures() + " 次调用失败：" + client.lastError()));
    }
    return List.of(new Item("telegram.bot", zh, en, true, "正常"));
  }
}
