package com.forecast.app.growth.waitlist;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 等候页（{@code forecast.growth.waitlist.*}，forecast S16，MF-8）。防刷、频率限制（超出要求的部分，见开发记录 S16 第 3 节）：
 * <ul>
 *   <li>{@code ip-per-hour}：同一个 IP 每小时最多提交几次（默认 10，超过 429；Redis 不可用时放行，约定 16）。</li>
 *   <li>{@code resend-after}：同一邮箱两封确认邮件至少隔多久（默认 10 分钟）；{@code max-confirm-sends}：最多发几封（默认 5）。
 *       没到时间、超过次数时照样回答"请查收邮件"，不再发、也不说明（不暴露这个邮箱是否已经留过）。</li>
 *   <li>{@code confirm-valid}：确认链接多久有效（默认 7 天，从最近一封确认邮件起算）。</li>
 *   <li>{@code launch-enabled}：上线通知的发送任务（每 15 秒，租约 {@code growth.launch}）；后端测试关闭，测试直接调用。</li>
 * </ul>
 */
@ConfigurationProperties("forecast.growth.waitlist")
public class WaitlistProperties {

  private int ipPerHour = 10;
  private Duration resendAfter = Duration.ofMinutes(10);
  private int maxConfirmSends = 5;
  private Duration confirmValid = Duration.ofDays(7);
  private boolean launchEnabled = true;
  private int launchBatch = 200;

  public int getIpPerHour() {
    return ipPerHour;
  }

  public void setIpPerHour(int v) {
    this.ipPerHour = v;
  }

  public Duration getResendAfter() {
    return resendAfter;
  }

  public void setResendAfter(Duration v) {
    this.resendAfter = v;
  }

  public int getMaxConfirmSends() {
    return maxConfirmSends;
  }

  public void setMaxConfirmSends(int v) {
    this.maxConfirmSends = Math.max(1, v);
  }

  public Duration getConfirmValid() {
    return confirmValid;
  }

  public void setConfirmValid(Duration v) {
    this.confirmValid = v;
  }

  public boolean isLaunchEnabled() {
    return launchEnabled;
  }

  public void setLaunchEnabled(boolean v) {
    this.launchEnabled = v;
  }

  public int getLaunchBatch() {
    return launchBatch;
  }

  public void setLaunchBatch(int v) {
    this.launchBatch = Math.max(1, v);
  }
}
