package com.forecast.app.common.health;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * 监控告警参数（application.yml 的 forecast.alert）。各模块的 {@link AlertCheck} 读阈值，通知模块的告警任务读收件人和节流。
 *
 * @param enabled 是否定时检查（每 15 秒）并发告警邮件（测试配置关掉，测试里直接调用）；健康检查里的 alerts 不受它影响
 * @param emails 告警收件人（{@code FC_ALERT_EMAILS}，逗号分隔）。<b>代码里没有默认邮箱</b>，默认值写在部署配置里；为空时只记日志
 * @param throttle 同一项告警多长时间内不重复发邮件（反复断开又恢复时不刷屏）
 * @param scanStall 扫块：多长时间没推进（或者落后的区块折合超过这个时间）算"停滞"
 * @param nodeFailures 链节点：连续几次连不上算"节点不通"（扫块每 15 秒一次）
 * @param mailFailures 邮件：连续几封发送失败算"邮件发送失败"
 * @param remindEvery 一直没恢复的告警多久再提醒一次（S12b，默认 24 小时；0 = 不提醒，只在发生和恢复时各发一封）
 */
@ConfigurationProperties("forecast.alert")
public record AlertProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue List<String> emails,
    @DefaultValue("30m") Duration throttle,
    @DefaultValue("10m") Duration scanStall,
    @DefaultValue("3") int nodeFailures,
    @DefaultValue("5") int mailFailures,
    @DefaultValue("24h") Duration remindEvery) {

  /** 去掉空白后的收件人 */
  public List<String> recipients() {
    return emails == null ? List.of() : emails.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  /** 默认值（测试里手动构造检查时用） */
  public static AlertProperties defaults() {
    return new AlertProperties(true, List.of(), Duration.ofMinutes(30), Duration.ofMinutes(10), 3, 5, Duration.ofHours(24));
  }

  /** 换开关和收件人，其他用这一份的值（测试用） */
  public AlertProperties with(boolean enabled, List<String> emails) {
    return new AlertProperties(enabled, emails, throttle, scanStall, nodeFailures, mailFailures, remindEvery);
  }

  @Configuration
  @EnableConfigurationProperties(AlertProperties.class)
  static class Setup {}
}
