package com.forecast.app.resolve.sports;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 体育赛程和自动裁决的配置（{@code forecast.sports.*}，application.yml；forecast S8，需求说明书 v1.3 第 4.2、4.3 节，技术方案第 5.2、6.2 节）。
 * 数据源的地址、key、超时、限频在 {@code forecast.resolve.sources.*}（和加密共用一个网关）。
 */
@Component
@ConfigurationProperties("forecast.sports")
public class SportsProperties {

  /** 定时同步赛程的开关（后端测试关闭，测试直接调用） */
  private boolean syncEnabled = true;
  /** 体育自动裁决任务的开关（后端测试关闭，测试直接调用） */
  private boolean resolverEnabled = true;
  /** 多久同步一次赛程（架构方案第 14.1 节按每天 7 次估算；默认 4 小时） */
  private Duration syncInterval = Duration.ofHours(4);
  /** 拉未来几天的赛程（需求第 4.2 节：未来 7 天） */
  private int horizonDays = 7;
  /** 每次同步顺便给几场还没确认对应的比赛找候选（备源限频每分钟 25 次，一场 1～3 次） */
  private int candidateBatch = 10;
  /** 自动裁决：多久看一次（架构方案第 5.2 节 SportsResolver：每 5 分钟） */
  private Duration pollInterval = Duration.ofMinutes(5);
  /** 主源报告完赛以后等多久再比较两个来源（需求第 4.3 节：30 分钟，给数据修正时间） */
  private Duration finishWait = Duration.ofMinutes(30);
  /** 等完以后，备源还拿不到或还没完赛，再等多久就转人工 */
  private Duration giveUpAfter = Duration.ofMinutes(30);
  /** 进入待裁决（事件结束）以后，主源多久还没报告完赛就转人工 */
  private Duration unfinishedLimit = Duration.ofHours(6);
  /** E14：两个来源的开赛时间相差多少以内算一致 */
  private Duration kickoffTolerance = Duration.ofMinutes(15);
  /**
   * 认领期（S14）：取数前认领这一次尝试，认领期内别的触发不再取数。一轮最长：主源、备源先后各一次，每次限频排队最多 30 秒 + 取数约 27 秒，
   * 合计约 2 分钟，所以 5 分钟（= 每 5 分钟看一次）；进程中途退出时 5 分钟后接着取
   */
  private Duration claimTtl = Duration.ofMinutes(5);
  /** 后台交互式调用（立即同步、找候选）限频排队最多等多久 */
  private Duration interactiveQueueWait = Duration.ofSeconds(10);

  public boolean isSyncEnabled() {
    return syncEnabled;
  }

  public void setSyncEnabled(boolean v) {
    syncEnabled = v;
  }

  public boolean isResolverEnabled() {
    return resolverEnabled;
  }

  public void setResolverEnabled(boolean v) {
    resolverEnabled = v;
  }

  public Duration getSyncInterval() {
    return syncInterval;
  }

  public void setSyncInterval(Duration v) {
    syncInterval = v;
  }

  public int getHorizonDays() {
    return horizonDays;
  }

  public void setHorizonDays(int v) {
    horizonDays = v;
  }

  public int getCandidateBatch() {
    return candidateBatch;
  }

  public void setCandidateBatch(int v) {
    candidateBatch = v;
  }

  public Duration getPollInterval() {
    return pollInterval;
  }

  public void setPollInterval(Duration v) {
    pollInterval = v;
  }

  public Duration getFinishWait() {
    return finishWait;
  }

  public void setFinishWait(Duration v) {
    finishWait = v;
  }

  public Duration getGiveUpAfter() {
    return giveUpAfter;
  }

  public void setGiveUpAfter(Duration v) {
    giveUpAfter = v;
  }

  public Duration getUnfinishedLimit() {
    return unfinishedLimit;
  }

  public void setUnfinishedLimit(Duration v) {
    unfinishedLimit = v;
  }

  public Duration getClaimTtl() {
    return claimTtl;
  }

  public void setClaimTtl(Duration v) {
    claimTtl = v;
  }

  public Duration getKickoffTolerance() {
    return kickoffTolerance;
  }

  public void setKickoffTolerance(Duration v) {
    kickoffTolerance = v;
  }

  public Duration getInteractiveQueueWait() {
    return interactiveQueueWait;
  }

  public void setInteractiveQueueWait(Duration v) {
    interactiveQueueWait = v;
  }
}
