package com.forecast.app.account.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.notification.api.MailApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 压测 LT-1（测试报告/v1-压测报告-AC13.md）：多个新邮箱同时发注册验证码不能互相死锁。
 * 原来发验证码先 SELECT … FOR UPDATE（新邮箱查不到记录时锁住索引间隙）再插入，两个新邮箱同时发就死锁、返回 500。用真实的 MySQL（实际执行）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrentSendCodeTest {

  private static final AtomicLong N = new AtomicLong(System.currentTimeMillis());

  @Autowired MockMvc mvc;
  @MockitoBean MailApi mail;

  @Test
  void 多个新邮箱同时发验证码_全部成功_没有死锁() throws Exception {
    int threads = 12;
    for (int round = 0; round < 5; round++) {
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Integer>> fs = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        long n = N.incrementAndGet();
        String email = "cc" + n + "@example.com";
        String ip = "10.77." + (n >> 8 & 255) + "." + (n & 255);
        fs.add(pool.submit(() -> {
          start.await();
          return mvc.perform(post("/api/auth/email-code").with(r -> {
            r.setRemoteAddr(ip);
            return r;
          }).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}")).andReturn().getResponse().getStatus();
        }));
      }
      start.countDown();
      for (Future<Integer> f : fs) assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(200);
      pool.shutdown();
    }
  }
}
