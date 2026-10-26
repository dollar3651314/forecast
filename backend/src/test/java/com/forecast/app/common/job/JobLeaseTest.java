package com.forecast.app.common.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 定时任务租约（技术方案第 5.3 节）在真实 MySQL 上：两个"实例"（不同的实例名、同一个库）同时抢同一个任务只有一个执行；
 * 执行中续约，超过 ttl 也不会被抢；实例挂了（不释放）ttl 后别的实例接手；执行出错照样释放。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(com.forecast.app.support.FixedClockConfig.class)
class JobLeaseTest {

  @Autowired JobLeaseMapper mapper;
  @Autowired JdbcTemplate jdbc;

  static String job() {
    return "test.lease." + System.nanoTime();
  }

  @Test
  void 两个实例同时开始_只有一个执行() throws Exception {
    for (int round = 0; round < 10; round++) {
      String job = job();
      JobLease a = new JobLease(mapper, "a");
      JobLease b = new JobLease(mapper, "b");
      CyclicBarrier start = new CyclicBarrier(2);
      CountDownLatch bothTried = new CountDownLatch(2);
      AtomicInteger ran = new AtomicInteger();
      Runnable body = () -> {
        ran.incrementAndGet();
        try {
          // 执行中等另一个实例也试过（不让先跑完的释放以后另一个再拿到，那是"先后各跑一次"，不是"同时"）
          bothTried.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      };
      CompletableFuture<Boolean> fa = CompletableFuture.supplyAsync(() -> run(a, job, start, bothTried, body));
      CompletableFuture<Boolean> fb = CompletableFuture.supplyAsync(() -> run(b, job, start, bothTried, body));
      boolean ra = fa.get(30, TimeUnit.SECONDS);
      boolean rb = fb.get(30, TimeUnit.SECONDS);
      assertThat(ra ^ rb).as("第 " + round + " 轮：恰好一个实例拿到租约").isTrue();
      assertThat(ran.get()).isEqualTo(1);
    }
  }

  /** 先报到"试过了"再执行：拿不到租约的实例 runExclusive 立即返回，报到在它返回之后；拿到的实例在 body 里等两个都报到 */
  private static boolean run(JobLease lease, String job, CyclicBarrier start, CountDownLatch bothTried, Runnable body) {
    try {
      start.await(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    AtomicInteger reported = new AtomicInteger();
    boolean got = lease.runExclusive(job, Duration.ofSeconds(30), () -> {
      reported.incrementAndGet();
      bothTried.countDown();
      body.run();
    });
    if (reported.get() == 0) bothTried.countDown();
    return got;
  }

  @Test
  void 执行时间超过ttl时自动续约_别的实例拿不到() throws Exception {
    String job = job();
    JobLease a = new JobLease(mapper, "a");
    JobLease b = new JobLease(mapper, "b");
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    CompletableFuture<Boolean> fa = CompletableFuture.supplyAsync(() -> a.runExclusive(job, Duration.ofSeconds(1), () -> {
      running.countDown();
      try {
        finish.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }));
    assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(2_500);
    assertThat(b.runExclusive(job, Duration.ofSeconds(1), () -> {})).as("ttl 1 秒，已经跑了 2.5 秒，靠续约仍由 a 持有").isFalse();
    finish.countDown();
    assertThat(fa.get(10, TimeUnit.SECONDS)).isTrue();
    assertThat(b.runExclusive(job, Duration.ofSeconds(1), () -> {})).as("a 跑完释放后 b 能拿到").isTrue();
  }

  @Test
  void 实例挂了没有释放_租约到期后别的实例接手() throws Exception {
    String job = job();
    mapper.ensure(job);
    assertThat(mapper.acquire(job, "dead", 1_000)).as("模拟一个拿了租约就挂掉的实例").isEqualTo(1);
    JobLease b = new JobLease(mapper, "b");
    assertThat(b.runExclusive(job, Duration.ofSeconds(1), () -> {})).isFalse();
    Thread.sleep(1_200);
    assertThat(b.runExclusive(job, Duration.ofSeconds(1), () -> {})).isTrue();
    assertThat(jdbc.queryForObject("SELECT owner FROM common_job_lease WHERE name = ?", String.class, job)).isEqualTo("b");
  }

  @Test
  void 执行出错_异常照样抛出_租约照样释放() {
    String job = job();
    JobLease a = new JobLease(mapper, "a");
    JobLease b = new JobLease(mapper, "b");
    assertThatThrownBy(() -> a.runExclusive(job, Duration.ofSeconds(30), () -> {
      throw new IllegalStateException("boom");
    })).hasMessage("boom");
    assertThat(b.runExclusive(job, Duration.ofSeconds(30), () -> {})).isTrue();
  }

  /**
   * 释放时租约必须已经过期，别的实例和释放在同一毫秒里也能拿到。原来释放把到期时间设成"现在"，同一毫秒内
   * {@code lease_until < 现在} 不成立，偶发拿不到（staging 的 CI 上"执行出错_租约照样释放"因此失败过一次）。
   * 释放语句里 updated_at 就是释放那一刻，所以用 lease_until &lt; updated_at 判断，结果不受快慢影响。
   */
  @Test
  void 释放时租约已经过期_同一毫秒内别的实例也能拿到() {
    String job = job();
    JobLease a = new JobLease(mapper, "a");
    assertThat(a.runExclusive(job, Duration.ofSeconds(30), () -> {})).isTrue();
    assertThat(jdbc.queryForObject("SELECT lease_until < updated_at FROM common_job_lease WHERE name = ?", Boolean.class, job))
        .as("释放后的到期时间早于释放时刻").isTrue();
  }

  @Test
  void 默认实例名带主机名和进程号_每个实例不同() {
    assertThat(JobLease.defaultOwner()).contains(":").isNotEqualTo(JobLease.defaultOwner());
  }
}
