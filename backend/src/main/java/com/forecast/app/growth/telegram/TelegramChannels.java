package com.forecast.app.growth.telegram;

import org.springframework.stereotype.Component;

/** 数据页上的"官方 Telegram"链接（forecast S16）：只给配置了 token、打开了开关、用 @用户名 填的公开频道，以及取到了用户名的机器人。 */
@Component
public class TelegramChannels {

  public record Links(String channelZh, String channelEn, String bot) {}

  private final TelegramProperties props;
  private final TelegramMapper mapper;
  private final TelegramBot bot;

  TelegramChannels(TelegramProperties props, TelegramMapper mapper, TelegramBot bot) {
    this.props = props;
    this.mapper = mapper;
    this.bot = bot;
  }

  public Links links() {
    if (!props.configured()) return new Links(null, null, null);
    String zh = null;
    String en = null;
    for (TelegramMapper.Target t : mapper.targets()) {
      if (!t.enabled || t.chatId == null || !t.chatId.startsWith("@")) continue;
      String url = "https://t.me/" + t.chatId.substring(1);
      if ("CHANNEL_ZH".equals(t.targetKey)) zh = url;
      else if ("CHANNEL_EN".equals(t.targetKey)) en = url;
    }
    String b = bot.username().map(u -> "https://t.me/" + u + "?start=s-web__c-data").orElse(null);
    return new Links(zh, en, b);
  }
}
