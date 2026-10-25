package com.forecast.app.resolve.source;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 自动裁决和数据源的配置（{@code forecast.resolve.*}，application.yml；forecast S6，技术方案第 6.2、6.3 节）。
 * 数值的依据见架构方案第 6.3 节外部调用清单（C81）。加密的地址是公开行情接口，不要 key；forecast S8 的体育数据源（football-data、balldontlie、TheSportsDB）
 * 要 key，key 只从环境变量来（{@link Source#getKey()}）。
 */
@Component
@ConfigurationProperties("forecast.resolve")
public class ResolveProperties {

  /** 加密自动裁决任务的开关（后端测试关闭，测试直接调用） */
  private boolean cryptoEnabled = true;
  /** 窗口结束后多久第一次取（需求第 4.3 节：1 分钟） */
  private Duration firstDelay = Duration.ofMinutes(1);
  /** 不一致或拿不到时多久重试（每分钟） */
  private Duration retryInterval = Duration.ofMinutes(1);
  /** 第一次取数以后多久仍不行就转人工（30 分钟） */
  private Duration giveUpAfter = Duration.ofMinutes(30);
  private Gateway gateway = new Gateway();
  private Map<String, Source> sources = new LinkedHashMap<>();

  /** 网关：超时、有上限的退避重试、熔断（第 6.3 节） */
  public static class Gateway {
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);
    /** 一轮取数最多几次（含第一次） */
    private int attempts = 3;
    /** 第一次重试前等多久，之后每次翻倍 */
    private Duration backoff = Duration.ofSeconds(1);
    private Duration maxBackoff = Duration.ofSeconds(4);
    /** 连续几次失败打开熔断 */
    private int failureThreshold = 5;
    /** 熔断打开多久，之后放 1 次探测 */
    private Duration openDuration = Duration.ofSeconds(60);
    /** 响应体上限（超过的断开，不存原文） */
    private int maxBodyBytes = 1_048_576;

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
      attempts = v;
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

    public int getMaxBodyBytes() {
      return maxBodyBytes;
    }

    public void setMaxBodyBytes(int v) {
      maxBodyBytes = v;
    }
  }

  /**
   * 一个数据源：开关和地址；forecast S8 起还有 key、单个来源的超时 / 重试覆盖、限频（为空的项用 {@link Gateway} 的默认值）。
   * <p>key 只从环境变量读（例如 {@code FC_SRC_FOOTBALL_DATA_TOKEN}），由 Amos 填进服务器；{@code keyRequired = true} 而 key 为空时这个来源"未开通"：
   * 网关不调用（{@code DISABLED}），健康检查的 alerts 里显示"未开通"。存档、日志里的请求地址不带 key。
   */
  public static class Source {
    private boolean enabled = true;
    private String baseUrl;
    /** 接口 key（请求头或路径里，由适配器决定怎么带）；只从环境变量来 */
    private String key;
    /** 没有 key 就不能用（football-data、balldontlie、TheSportsDB） */
    private boolean keyRequired;
    /** 没配 key 时健康检查里提示的环境变量名（只写名字） */
    private String keyEnv;
    private Duration connectTimeout;
    private Duration readTimeout;
    private Integer attempts;
    private Duration backoff;
    private Duration maxBackoff;
    /** 限频：每个窗口最多调用几次（0 = 不限）。按各家公布的限额留余量（架构方案第 6.3 节），超过的排队等 */
    private int rateLimit;
    /** 限频的窗口（默认 1 分钟） */
    private Duration rateWindow = Duration.ofMinutes(1);
    /** 排队最多等多久，超过就这一轮不调用（THROTTLED），下一轮再来 */
    private Duration queueWait = Duration.ofSeconds(30);

    public Source() {}

    public Source(boolean enabled, String baseUrl) {
      this.enabled = enabled;
      this.baseUrl = baseUrl;
    }

    /** 打开、有地址、要 key 的有 key */
    public boolean usable() {
      return enabled && baseUrl != null && !baseUrl.isBlank() && (!keyRequired || (key != null && !key.isBlank()));
    }

    /** 为什么不能用（给健康检查和同步结果看）；能用时为 null */
    public String unusableReason() {
      if (!enabled) return "没有打开";
      if (baseUrl == null || baseUrl.isBlank()) return "没有配置地址";
      if (keyRequired && (key == null || key.isBlank())) return "未开通：没有配置 " + (keyEnv == null ? "key" : keyEnv);
      return null;
    }

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean v) {
      enabled = v;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String v) {
      baseUrl = v;
    }

    public String getKey() {
      return key;
    }

    public void setKey(String v) {
      key = v;
    }

    public boolean isKeyRequired() {
      return keyRequired;
    }

    public void setKeyRequired(boolean v) {
      keyRequired = v;
    }

    public String getKeyEnv() {
      return keyEnv;
    }

    public void setKeyEnv(String v) {
      keyEnv = v;
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

    public Integer getAttempts() {
      return attempts;
    }

    public void setAttempts(Integer v) {
      attempts = v;
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

    public int getRateLimit() {
      return rateLimit;
    }

    public void setRateLimit(int v) {
      rateLimit = v;
    }

    public Duration getRateWindow() {
      return rateWindow;
    }

    public void setRateWindow(Duration v) {
      rateWindow = v;
    }

    public Duration getQueueWait() {
      return queueWait;
    }

    public void setQueueWait(Duration v) {
      queueWait = v;
    }
  }

  /** 某个数据源的配置；没配置的算关闭 */
  public Source source(String key) {
    Source s = sources.get(key);
    return s == null ? new Source(false, null) : s;
  }

  public boolean isCryptoEnabled() {
    return cryptoEnabled;
  }

  public void setCryptoEnabled(boolean v) {
    cryptoEnabled = v;
  }

  public Duration getFirstDelay() {
    return firstDelay;
  }

  public void setFirstDelay(Duration v) {
    firstDelay = v;
  }

  public Duration getRetryInterval() {
    return retryInterval;
  }

  public void setRetryInterval(Duration v) {
    retryInterval = v;
  }

  public Duration getGiveUpAfter() {
    return giveUpAfter;
  }

  public void setGiveUpAfter(Duration v) {
    giveUpAfter = v;
  }

  public Gateway getGateway() {
    return gateway;
  }

  public void setGateway(Gateway v) {
    gateway = v;
  }

  public Map<String, Source> getSources() {
    return sources;
  }

  public void setSources(Map<String, Source> v) {
    sources = v;
  }
}
