package com.forecast.app.common.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 任务表，用真实的 MySQL（C37）。每个测试用自己的任务类型，互不影响。 */
@SpringBootTest
@ActiveProfiles("test")
class TaskServiceTest {

  private static final AtomicLong N = new AtomicLong(System.currentTimeMillis());

  @Autowired TaskService tasks;
  @Autowired JdbcTemplate jdbc;

  private static String type() {
    return "T" + N.incrementAndGet();
  }

  @Test
  void 只领取到期的任务并标为处理中() {
    String t = type();
    long due = tasks.enqueue(t, "{\"a\":1}", 0, 3);
    tasks.enqueue(t, "{\"a\":2}", 3600, 3);
    List<Task> got = tasks.claim(t, "w1", 10);
    assertThat(got).extracting(Task::id).containsExactly(due);
    assertThat(got.get(0).status()).isEqualTo(TaskStatus.RUNNING);
    assertThat(got.get(0).attempts()).isEqualTo(1);
    assertThat(got.get(0).payload()).contains("\"a\"");
    assertThat(tasks.claim(t, "w1", 10)).isEmpty();
  }

  @Test
  void 失败后退避重试_次数用完标为失败() {
    String t = type();
    long id = tasks.enqueue(t, "{}", 0, 2);
    tasks.claim(t, "w", 1);
    tasks.fail(id, "网络错误");
    Task after1 = tasks.find(id);
    assertThat(after1.status()).isEqualTo(TaskStatus.PENDING);
    assertThat(after1.lastError()).isEqualTo("网络错误");
    assertThat(tasks.claim(t, "w", 1)).as("退避期内不能再领取").isEmpty();

    jdbc.update("UPDATE common_task SET next_run_at = UTC_TIMESTAMP(3) WHERE id = ?", id);
    assertThat(tasks.claim(t, "w", 1)).hasSize(1);
    tasks.fail(id, "还是失败");
    assertThat(tasks.find(id).status()).isEqualTo(TaskStatus.FAILED);
  }

  @Test
  void 成功后不再领取() {
    String t = type();
    long id = tasks.enqueue(t, "{}", 0, 3);
    tasks.claim(t, "w", 1);
    tasks.succeed(id);
    assertThat(tasks.find(id).status()).isEqualTo(TaskStatus.SUCCEEDED);
    assertThat(tasks.claim(t, "w", 1)).isEmpty();
  }

  @Test
  void 处理中超时的任务会被回收() {
    String t = type();
    long id = tasks.enqueue(t, "{}", 0, 3);
    tasks.claim(t, "w", 1);
    tasks.recoverStuck(300); // 只回收超时的；其他测试留下的超时任务也会被回收，所以不检查总数，只检查这一个
    assertThat(tasks.find(id).status()).as("刚领取的不回收").isEqualTo(TaskStatus.RUNNING);
    jdbc.update("UPDATE common_task SET locked_at = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 10 MINUTE) WHERE id = ?", id);
    assertThat(tasks.recoverStuck(300)).isGreaterThanOrEqualTo(1);
    assertThat(tasks.find(id).status()).isEqualTo(TaskStatus.PENDING);
    assertThat(tasks.claim(t, "w2", 1)).extracting(Task::id).containsExactly(id);
  }

  @Test
  void 多个处理者同时领取不会重复() throws Exception {
    String t = type();
    for (int i = 0; i < 30; i++) tasks.enqueue(t, "{}", 0, 3);
    List<Long> all = Collections.synchronizedList(new ArrayList<>());
    ExecutorService pool = Executors.newFixedThreadPool(4);
    CountDownLatch start = new CountDownLatch(1);
    for (int w = 0; w < 4; w++) {
      String name = "w" + w;
      pool.submit(() -> {
        start.await();
        List<Task> got;
        while (!(got = tasks.claim(t, name, 3)).isEmpty()) got.forEach(x -> all.add(x.id()));
        return null;
      });
    }
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    assertThat(all).hasSize(30).doesNotHaveDuplicates();
  }

  @Test
  void 退避时间按2的次方增长_最长1小时() {
    assertThat(TaskService.backoffSeconds(1)).isEqualTo(2);
    assertThat(TaskService.backoffSeconds(3)).isEqualTo(8);
    assertThat(TaskService.backoffSeconds(30)).isEqualTo(3600);
  }
}
