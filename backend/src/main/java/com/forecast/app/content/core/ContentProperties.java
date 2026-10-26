package com.forecast.app.content.core;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 事件和文章自动化程序的配置（{@code forecast.content.*}，forecast S15）。运营能改的（每日上限、开关、紧急停止……）在表 {@code content_setting}，
 * 由后台"内容中心 → 用量和设置"改（content:publish），不在这里。
 * <ul>
 *   <li>{@code enabled}（{@code FC_CONTENT_ENABLED}，默认 true）：整个自动化程序的总开关。关掉时定时任务不跑、手动生成返回 503 {@code CONTENT_DISABLED}；
 *       已发布的公开页面照常显示。<b>下注、结算、充值、提现和它无关</b>（NF-15），关掉、出错都不影响。</li>
 *   <li>{@code jobs-enabled}：定时任务（每分钟：草稿过期、经济日历到期生成、焦点战页、BTC 统计页、每日草稿包）；后端测试关闭，测试直接调用。</li>
 *   <li>{@code async}：Gemini 起草在独立的线程池里做（{@code executor-threads} 个线程、队列 {@code executor-queue}，满了拒绝，不占用接口线程）；
 *       后端测试关掉（在调用线程里做完，便于断言）。</li>
 *   <li>{@code gemini.*}：key 只从环境变量 {@code FC_GEMINI_API_KEY} 来（Amos 自己填，不进仓库、不进日志，后台只显示"已配置 / 未配置"，C21）；
 *       模型 {@code FC_GEMINI_MODEL}（默认 gemini-2.5-flash-lite，架构方案第 6.6 节）；超时、重试、熔断（C81）。</li>
 * </ul>
 */
@ConfigurationProperties("forecast.content")
public class ContentProperties {

  private boolean enabled = true;
  private boolean jobsEnabled = true;
  private boolean async = true;
  private int executorThreads = 2;
  private int executorQueue = 50;
  private final Gemini gemini = new Gemini();

  public static class Gemini {
    private String apiKey;
    private String model = "gemini-2.5-flash-lite";
    private String baseUrl = "https://generativelanguage.googleapis.com";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);
    /** 一次起草最多几次 HTTP 调用（1 次 + 最多 2 次重试，自动化需求第 6.4 节） */
    private int attempts = 3;
    private Duration backoff = Duration.ofSeconds(2);
    private Duration maxBackoff = Duration.ofSeconds(8);
    /** 连续失败几次熔断 */
    private int failureThreshold = 5;
    private Duration openDuration = Duration.ofSeconds(60);
    /** 收到 429（被限流）后这一类功能暂停多久（自动化需求第 6.4 节：10 分钟） */
    private Duration pauseOn429 = Duration.ofMinutes(10);
    private int maxBodyBytes = 524_288;
    /** 发给 Gemini 的提示最多多少字 */
    private int maxPromptChars = 12_000;
    /** 估算费用用的价格（美元 / 百万 token，按付费档 Flash-Lite；免费档实际 0，架构方案第 6.6 节） */
    private BigDecimal priceIn = new BigDecimal("0.10");
    private BigDecimal priceOut = new BigDecimal("0.40");

    /** 有没有配置 key（只给"已配置 / 未配置"用，不返回 key） */
    public boolean keyConfigured() {
      return apiKey != null && !apiKey.isBlank();
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String v) {
      apiKey = v;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String v) {
      model = v == null || v.isBlank() ? "gemini-2.5-flash-lite" : v.strip();
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String v) {
      baseUrl = v;
    }

    public Duration getConnectTimeout() {
      return connectTimeout;
    }

    public void setConnectTimeout(Duration v) {
      connectTimeout = v;
    }

    public Duration getReadTimeout() {
      return readTimeout;
    }

    public void setReadTimeout(Duration v) {
      readTimeout = v;
    }

    public int getAttempts() {
      return attempts;
    }

    public void setAttempts(int v) {
      attempts = Math.max(1, Math.min(3, v));
    }

    public Duration getBackoff() {
      return backoff;
    }

    public void setBackoff(Duration v) {
      backoff = v;
    }

    public Duration getMaxBackoff() {
      return maxBackoff;
    }

    public void setMaxBackoff(Duration v) {
      maxBackoff = v;
    }

    public int getFailureThreshold() {
      return failureThreshold;
    }

    public void setFailureThreshold(int v) {
      failureThreshold = v;
    }

    public Duration getOpenDuration() {
      return openDuration;
    }

    public void setOpenDuration(Duration v) {
      openDuration = v;
    }

    public Duration getPauseOn429() {
      return pauseOn429;
    }

    public void setPauseOn429(Duration v) {
      pauseOn429 = v;
    }

    public int getMaxBodyBytes() {
      return maxBodyBytes;
    }

    public void setMaxBodyBytes(int v) {
      maxBodyBytes = v;
    }

    public int getMaxPromptChars() {
      return maxPromptChars;
    }

    public void setMaxPromptChars(int v) {
      maxPromptChars = v;
    }

    public BigDecimal getPriceIn() {
      return priceIn;
    }

    public void setPriceIn(BigDecimal v) {
      priceIn = v;
    }

    public BigDecimal getPriceOut() {
      return priceOut;
    }

    public void setPriceOut(BigDecimal v) {
      priceOut = v;
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public boolean isJobsEnabled() {
    return jobsEnabled;
  }

  public void setJobsEnabled(boolean v) {
    jobsEnabled = v;
  }

  public boolean isAsync() {
    return async;
  }

  public void setAsync(boolean v) {
    async = v;
  }

  public int getExecutorThreads() {
    return executorThreads;
  }

  public void setExecutorThreads(int v) {
    executorThreads = Math.max(1, v);
  }

  public int getExecutorQueue() {
    return executorQueue;
  }

  public void setExecutorQueue(int v) {
    executorQueue = Math.max(1, v);
  }

  public Gemini getGemini() {
    return gemini;
  }
}
