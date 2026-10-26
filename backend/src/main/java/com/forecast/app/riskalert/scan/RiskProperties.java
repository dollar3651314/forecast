package com.forecast.app.riskalert.scan;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 风控扫描的配置（{@code forecast.riskalert.*}，forecast S10）。
 * <ul>
 *   <li>{@code scan-enabled}：每 30 秒扫一轮（租约 {@code riskalert.scan}）；后端测试关闭，测试直接调用 {@link RiskScanner#scanAll()}。</li>
 *   <li>{@code scan-lag}：一行写入多久以后才处理（默认 10 秒）：按 id 往后扫时，让进行中的事务先提交，不会跳过 id 小、提交晚的行。后端测试 0。</li>
 *   <li>{@code batch}：每个来源每轮最多处理多少行（默认 500）。</li>
 * </ul>
 */
@ConfigurationProperties("forecast.riskalert")
public class RiskProperties {

  private boolean scanEnabled = true;
  private Duration scanLag = Duration.ofSeconds(10);
  private int batch = 500;

  public boolean isScanEnabled() {
    return scanEnabled;
  }

  public void setScanEnabled(boolean scanEnabled) {
    this.scanEnabled = scanEnabled;
  }

  public Duration getScanLag() {
    return scanLag;
  }

  public void setScanLag(Duration scanLag) {
    this.scanLag = scanLag == null || scanLag.isNegative() ? Duration.ZERO : scanLag;
  }

  public int getBatch() {
    return batch;
  }

  public void setBatch(int batch) {
    this.batch = Math.max(1, batch);
  }
}
