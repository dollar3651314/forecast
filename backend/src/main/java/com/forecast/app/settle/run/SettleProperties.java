package com.forecast.app.settle.run;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 结算的配置（{@code forecast.settle.*}，forecast S5）。
 * <ul>
 *   <li>{@code batch-size}（环境变量 {@code FC_SETTLE_BATCH_SIZE}）：派彩时每批的持仓数（退款时每批的下注数）。<b>500（默认）= 架构方案第 4.3 节的
 *       方案 B</b>：先在一个事务里算好、通过守恒校验、落结算单，再每批一个事务分批派彩，可以续跑（AQ-3，Amos 2026-10-25 定：B）。
 *       <b>0 = 计算和派彩在同一个事务里一次完成</b>（保留为可配置项）。两种模式的结算单、明细、三道防重复、守恒校验完全相同，只有事务边界不同。</li>
 *   <li>{@code worker-enabled}：派彩任务（每 2 秒推进派彩中的结算单）；后端测试关闭，测试直接调用。</li>
 *   <li>{@code recon-enabled}：每日彩池对账（UTC 00:30）；后端测试关闭，测试直接调用。</li>
 * </ul>
 * batchSize 可以在运行时改（测试用；生产只从配置读），每次结算、每批派彩开始时读一次。
 */
@ConfigurationProperties("forecast.settle")
public class SettleProperties {

  private int batchSize = 500;
  private boolean workerEnabled = true;
  private boolean reconEnabled = true;

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    if (batchSize < 0) throw new IllegalArgumentException("forecast.settle.batch-size 不能为负：" + batchSize);
    this.batchSize = batchSize;
  }

  public boolean isWorkerEnabled() {
    return workerEnabled;
  }

  public void setWorkerEnabled(boolean workerEnabled) {
    this.workerEnabled = workerEnabled;
  }

  public boolean isReconEnabled() {
    return reconEnabled;
  }

  public void setReconEnabled(boolean reconEnabled) {
    this.reconEnabled = reconEnabled;
  }
}
