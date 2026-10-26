package com.forecast.app.content.core;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 自动化程序的配置和隔离（forecast S15，NF-15）：Gemini 起草在自己的小线程池里做（{@link Workers}），线程数、队列都有上限，满了直接拒绝、
 * 不排到接口线程或别的模块的线程里；外部调用不在数据库事务里。
 */
@Configuration
@EnableConfigurationProperties(ContentProperties.class)
class ContentConfig {

  /** 起草用的线程池（守护线程，名字 content-N） */
  @org.springframework.context.annotation.Bean(destroyMethod = "shutdownNow")
  Workers contentWorkers(ContentProperties props) {
    AtomicInteger n = new AtomicInteger();
    ThreadPoolExecutor ex = new ThreadPoolExecutor(props.getExecutorThreads(), props.getExecutorThreads(), 60, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(props.getExecutorQueue()), r -> {
          Thread t = new Thread(r, "content-" + n.incrementAndGet());
          t.setDaemon(true);
          return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    ex.allowCoreThreadTimeOut(true);
    return new Workers(ex, props.isAsync());
  }
}
