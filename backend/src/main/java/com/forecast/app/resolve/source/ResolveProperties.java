package com.forecast.app.resolve.source;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 自动裁决和数据源的配置（{@code forecast.resolve.*}，application.yml；forecast S6，技术方案第 6.2、6.3 节）。
 * 数值的依据见架构方案第 6.3 节外部调用清单（C81）；地址是公开行情接口，不要 key。
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

  /** 一个数据源：开关和地址 */
  public static class Source {
    private boolean enabled = true;
    private String baseUrl;

    public Source() {}

    public Source(boolean enabled, String baseUrl) {
      this.enabled = enabled;
      this.baseUrl = baseUrl;
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
