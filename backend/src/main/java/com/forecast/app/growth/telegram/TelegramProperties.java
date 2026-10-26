package com.forecast.app.growth.telegram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 官方 Telegram 机器人（{@code forecast.telegram.*}，forecast S16，MF-7）。
 * <ul>
 *   <li>{@code token}：机器人的 token，<b>只从环境变量 {@code FC_TELEGRAM_BOT_TOKEN} 来</b>（Amos 自己在 BotFather 建机器人、自己填进服务器 .env，
 *       不经过 AI、不进仓库、不进日志，约定 2）。没填时机器人这部分不启动（不取消息、不发帖），健康检查 {@code alerts} 的 {@code telegram.bot} 提示
 *       "未配置"，<b>不影响 {@code status}</b>（约定 11）。</li>
 *   <li>{@code api-url}：Bot API 的地址（默认 https://api.telegram.org；端到端指向后端自己的替身 {@code /api/e2e/telegram}）。</li>
 *   <li>{@code jobs-enabled}：取消息（长轮询）和发帖的后台线程（独立的线程，不占用别的定时任务）；后端测试关闭，测试直接调用。</li>
 *   <li>{@code poll-timeout}：getUpdates 的长轮询等待（默认 25 秒）。</li>
 *   <li>超时、重试、熔断（C81）：连接 3 秒、读取 15 秒（上传图片）、最多重试 2 次（只重试超时、断开、5xx；429 按 {@code retry_after}，
 *       不超过 {@code max-inline-wait} 的当场等，否则这一张改到那之后再发）；连续 {@code breaker-threshold} 次失败后熔断 {@code breaker-open}。</li>
 *   <li>频率限制：同一个频道 / 群两次发帖至少隔 {@code chat-interval}（默认 3 秒，Telegram 群每分钟 20 条），所有发帖之间至少隔 {@code send-interval}（默认 1 秒）。</li>
 *   <li>{@code result-max-age}：结束多久以内的市场才发开奖卡（默认 24 小时；停机很久以后不补发一大批）。</li>
 * </ul>
 */
@ConfigurationProperties("forecast.telegram")
public class TelegramProperties {

  private String token = "";
  private String apiUrl = "https://api.telegram.org";
  private boolean jobsEnabled = true;
  private Duration pollTimeout = Duration.ofSeconds(25);
  private Duration publishInterval = Duration.ofSeconds(10);
  private Duration connectTimeout = Duration.ofSeconds(3);
  private Duration readTimeout = Duration.ofSeconds(15);
  private int retries = 2;
  private Duration maxInlineWait = Duration.ofSeconds(5);
  private int breakerThreshold = 5;
  private Duration breakerOpen = Duration.ofSeconds(60);
  private Duration chatInterval = Duration.ofSeconds(3);
  private Duration sendInterval = Duration.ofSeconds(1);
  private Duration resultMaxAge = Duration.ofHours(24);
  private int maxAttempts = 3;

  /** 端到端测试模拟"没有配置 token"（只由 profile e2e 下的替身调用，见 {@code E2eTelegram}） */
  private volatile Boolean e2eConfigured;

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token == null ? "" : token.strip();
  }

  /** 配置了 token */
  public boolean configured() {
    if (e2eConfigured != null) return e2eConfigured && !token.isEmpty();
    return !token.isEmpty();
  }

  void e2eConfigured(Boolean v) {
    this.e2eConfigured = v;
  }

  public String getApiUrl() {
    return apiUrl;
  }

  public void setApiUrl(String v) {
    this.apiUrl = v == null ? "" : v.strip().replaceAll("/+$", "");
  }

  public boolean isJobsEnabled() {
    return jobsEnabled;
  }

  public void setJobsEnabled(boolean v) {
    this.jobsEnabled = v;
  }

  public Duration getPollTimeout() {
    return pollTimeout;
  }

  public void setPollTimeout(Duration v) {
    this.pollTimeout = v;
  }

  public Duration getPublishInterval() {
    return publishInterval;
  }

  public void setPublishInterval(Duration v) {
    this.publishInterval = v;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration v) {
    this.connectTimeout = v;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(Duration v) {
    this.readTimeout = v;
  }

  public int getRetries() {
    return retries;
  }

  public void setRetries(int v) {
    this.retries = Math.max(0, Math.min(v, 5));
  }

  public Duration getMaxInlineWait() {
    return maxInlineWait;
  }

  public void setMaxInlineWait(Duration v) {
    this.maxInlineWait = v;
  }

  public int getBreakerThreshold() {
    return breakerThreshold;
  }

  public void setBreakerThreshold(int v) {
    this.breakerThreshold = Math.max(1, v);
  }

  public Duration getBreakerOpen() {
    return breakerOpen;
  }

  public void setBreakerOpen(Duration v) {
    this.breakerOpen = v;
  }

  public Duration getChatInterval() {
    return chatInterval;
  }

  public void setChatInterval(Duration v) {
    this.chatInterval = v;
  }

  public Duration getSendInterval() {
    return sendInterval;
  }

  public void setSendInterval(Duration v) {
    this.sendInterval = v;
  }

  public Duration getResultMaxAge() {
    return resultMaxAge;
  }

  public void setResultMaxAge(Duration v) {
    this.resultMaxAge = v;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int v) {
    this.maxAttempts = Math.max(1, v);
  }
}
