package com.forecast.app.pool.push;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * "某个市场的奖池有变化"（forecast S4，技术方案/v1-架构方案.md 第 5.3、7 节）：下注、截止、状态跳转、改费率的事务<b>提交以后</b>调用
 * {@link #afterCommit}，本机的 {@link PoolPush} 把这个市场标成"有变化"，合并后每秒最多推一次；同时经 Redis 发布 / 订阅广播给其他实例
 * （频道 {@value #CHANNEL}，消息"实例名:市场 id"），每个实例给连在自己身上的客户端推。
 * <ul>
 *   <li>发布在单独的线程里做（同一个市场的多次变化先合并），Redis 慢或不可用时不拖住下注请求；发布失败后 30 秒内不再试（和限流的做法一样），
 *       单实例部署时推送照常（本机直接标记）。</li>
 *   <li>业务回滚时不标记（只在提交后调用）。</li>
 * </ul>
 */
@Component
public class PoolChanges {

  private static final Logger log = LoggerFactory.getLogger(PoolChanges.class);
  public static final String CHANNEL = "fc:pool:changed";
  private static final long REDIS_DOWN_SKIP_NANOS = TimeUnit.SECONDS.toNanos(30);

  /** 本实例的名字：自己发布的消息自己收到时不重复标记 */
  final String instance = UUID.randomUUID().toString();
  private final PoolPush push;
  private final StringRedisTemplate redis;
  private final boolean redisEnabled;
  private final Set<Long> toPublish = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean publishing = new AtomicBoolean();
  private final ExecutorService publisher = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "pool-publish");
    t.setDaemon(true);
    return t;
  });
  private volatile long redisDownAt = Long.MIN_VALUE;

  PoolChanges(PoolPush push, StringRedisTemplate redis, @Value("${forecast.pool.push.redis-enabled:true}") boolean redisEnabled) {
    this.push = push;
    this.redis = redis;
    this.redisEnabled = redisEnabled;
  }

  /** 在业务事务里调用：提交以后再标记（没有事务时立即标记） */
  public void afterCommit(long marketId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          changed(marketId);
        }
      });
    } else {
      changed(marketId);
    }
  }

  /** 立即标记（本机）并排队广播（其他实例） */
  public void changed(long marketId) {
    push.markDirty(marketId);
    if (!redisEnabled) return;
    toPublish.add(marketId);
    if (publishing.compareAndSet(false, true)) publisher.execute(this::publishPending);
  }

  /** 其他实例发布的消息（{@link PoolPushConfig} 的 Redis 订阅调用） */
  void onMessage(String body) {
    int i = body == null ? -1 : body.lastIndexOf(':');
    if (i <= 0 || body.substring(0, i).equals(instance)) return;
    try {
      push.markDirty(Long.parseLong(body.substring(i + 1)));
    } catch (NumberFormatException e) {
      log.debug("忽略格式不对的奖池变化消息");
    }
  }

  private void publishPending() {
    try {
      while (!toPublish.isEmpty()) {
        Long[] ids = toPublish.toArray(Long[]::new);
        for (Long id : ids) {
          toPublish.remove(id);
          if (redisDownAt != Long.MIN_VALUE && System.nanoTime() - redisDownAt < REDIS_DOWN_SKIP_NANOS) continue;
          try {
            redis.convertAndSend(CHANNEL, instance + ":" + id);
            redisDownAt = Long.MIN_VALUE;
          } catch (RuntimeException e) {
            redisDownAt = System.nanoTime();
            log.warn("奖池变化广播失败（Redis 不可用），30 秒内只推本机：{}", e.getMessage());
          }
        }
      }
    } finally {
      publishing.set(false);
      // 放手以后又进来的，再跑一轮
      if (!toPublish.isEmpty() && publishing.compareAndSet(false, true)) publisher.execute(this::publishPending);
    }
  }

  @jakarta.annotation.PreDestroy
  void stop() {
    publisher.shutdownNow();
  }
}
