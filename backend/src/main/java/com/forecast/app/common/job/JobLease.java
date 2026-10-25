package com.forecast.app.common.job;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定时任务租约（技术方案/v1-架构方案.md 第 5.3 节；forecast S3）：多实例部署时，同一个任务同一时间只有一个实例在跑。
 * <ol>
 *   <li>开始前 {@code UPDATE common_job_lease SET owner = 我, lease_until = 现在 + ttl WHERE name = 任务 AND (lease_until &lt; 现在 OR owner = 我)}，
 *       更新到 1 行才执行；时间用数据库时间。</li>
 *   <li>执行中每 ttl/3 续约一次；执行完释放（别的实例下一轮就能拿到）。</li>
 *   <li>实例异常退出：最多 ttl 后别的实例接手。</li>
 *   <li>租约只是"尽量不重复"：GC 停顿等让租约意外过期时两个实例可能同时跑，所以任务本身还必须是幂等的
 *       （状态跳转是带旧状态的条件更新，见 market.core.MarketService）。</li>
 * </ol>
 * 第一期单实例部署（AQ-11），代码按多实例写；不用 ShedLock（对 Spring Boot 4 的支持未核实，架构方案第 5.3 节）。
 * 不能在事务里调用（租约要立即提交，别的实例才看得到）。
 */
@Service
public class JobLease {

  private static final Logger log = LoggerFactory.getLogger(JobLease.class);
  private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "fc-job-lease");
    t.setDaemon(true);
    return t;
  });

  private final JobLeaseMapper mapper;
  private final String owner;

  @Autowired
  JobLease(JobLeaseMapper mapper) {
    this(mapper, defaultOwner());
  }

  /** 测试用：指定实例名，模拟两个实例 */
  JobLease(JobLeaseMapper mapper, String owner) {
    this.mapper = mapper;
    this.owner = owner;
  }

  /** 这个实例的名字：主机名 + 进程号 + 随机串（同一台机器上起两个进程也不同） */
  static String defaultOwner() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      host = "unknown";
    }
    String id = host + ":" + ManagementFactory.getRuntimeMXBean().getPid() + ":" + UUID.randomUUID().toString().substring(0, 8);
    return id.length() <= 128 ? id : id.substring(id.length() - 128);
  }

  public String owner() {
    return owner;
  }

  /**
   * 拿到租约就执行 {@code body}，执行完释放；没拿到（别的实例正在跑）直接返回 false，不执行。
   * body 抛出的异常照样抛出（租约照样释放）。
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public boolean runExclusive(String job, Duration ttl, Runnable body) {
    long ttlMillis = Math.max(1_000, ttl.toMillis());
    mapper.ensure(job);
    if (mapper.acquire(job, owner, ttlMillis) != 1) {
      log.debug("任务 {} 正由别的实例执行，跳过", job);
      return false;
    }
    long every = Math.max(300, ttlMillis / 3);
    ScheduledFuture<?> beat = HEARTBEAT.scheduleAtFixedRate(() -> {
      try {
        if (mapper.renew(job, owner, ttlMillis) != 1) log.warn("任务 {} 的租约已被别的实例拿走（可能停顿过久），本次执行继续，靠条件更新保证不重复", job);
      } catch (RuntimeException e) {
        log.warn("任务 {} 续约失败：{}", job, e.getMessage());
      }
    }, every, every, TimeUnit.MILLISECONDS);
    try {
      body.run();
      return true;
    } finally {
      beat.cancel(false);
      try {
        mapper.release(job, owner);
      } catch (RuntimeException e) {
        log.warn("任务 {} 释放租约失败（到期后自动失效）：{}", job, e.getMessage());
      }
    }
  }
}
