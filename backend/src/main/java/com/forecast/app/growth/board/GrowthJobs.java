package com.forecast.app.growth.board;

import com.forecast.app.common.job.JobLease;
import com.forecast.app.growth.telegram.TelegramBot;
import com.forecast.app.growth.telegram.TelegramProperties;
import com.forecast.app.growth.telegram.TelegramPublisher;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 推广功能的后台线程（forecast S16）：数据页的币安行情刷新、Telegram 机器人的长轮询和发帖。用自己的线程池（3 个线程），
 * <b>不占用 Spring 共用的定时任务线程</b>（长轮询一次等 25 秒，放在共用线程上会拖慢下注截止等任务）。
 * <ul>
 *   <li>行情：每 {@code forecast.growth.board.refresh-interval}（60 秒）一次；只是内存缓存，每个实例各取各的，不用租约。</li>
 *   <li>长轮询：租约 {@code growth.telegram.poll}（同一个 token 只能有一个人在取，多实例时只有拿到租约的取）；没配置 token 时什么都不做。</li>
 *   <li>发帖：每 {@code forecast.telegram.publish-interval}（10 秒）一轮，租约 {@code growth.telegram.publish}；发送本身是带认领的条件更新（约定 25）。</li>
 * </ul>
 * 开关：{@code forecast.growth.board.refresh-enabled}、{@code forecast.telegram.jobs-enabled}（后端测试和端到端关闭，直接调用 / e2e 接口触发）。
 */
@Component
class GrowthJobs implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(GrowthJobs.class);
  static final String POLL_JOB = "growth.telegram.poll";
  static final String PUBLISH_JOB = "growth.telegram.publish";

  private final BtcBoard board;
  private final BoardProperties boardProps;
  private final TelegramProperties tg;
  private final TelegramBot bot;
  private final TelegramPublisher publisher;
  private final JobLease lease;
  private ScheduledExecutorService pool;
  private volatile boolean running;

  GrowthJobs(BtcBoard board, BoardProperties boardProps, TelegramProperties tg, TelegramBot bot, TelegramPublisher publisher, JobLease lease) {
    this.board = board;
    this.boardProps = boardProps;
    this.tg = tg;
    this.bot = bot;
    this.publisher = publisher;
    this.lease = lease;
  }

  @Override
  public synchronized void start() {
    if (running) return;
    running = true;
    if (!boardProps.isRefreshEnabled() && !tg.isJobsEnabled()) return;
    pool = Executors.newScheduledThreadPool(3, r -> {
      Thread t = new Thread(r, "fc-growth");
      t.setDaemon(true);
      return t;
    });
    if (boardProps.isRefreshEnabled()) {
      pool.scheduleWithFixedDelay(() -> safe("数据页行情刷新", board::refresh), 2_000, boardProps.getRefreshInterval().toMillis(), TimeUnit.MILLISECONDS);
    }
    if (tg.isJobsEnabled()) {
      if (!tg.configured()) log.warn("没有配置 FC_TELEGRAM_BOT_TOKEN：Telegram 机器人不启动（健康检查 alerts 的 telegram.bot 提示）");
      Duration wait = tg.getPollTimeout();
      pool.scheduleWithFixedDelay(() -> {
        if (!tg.configured()) return;
        safe("Telegram 取消息", () -> lease.runExclusive(POLL_JOB, wait.plus(Duration.ofSeconds(40)), () -> bot.pollOnce(wait)));
      }, 5_000, 1_000, TimeUnit.MILLISECONDS);
      pool.scheduleWithFixedDelay(() -> {
        if (!tg.configured()) return;
        safe("Telegram 发帖", () -> lease.runExclusive(PUBLISH_JOB, Duration.ofMinutes(5), () -> publisher.publishDue()));
      }, 15_000, tg.getPublishInterval().toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  private static void safe(String what, Runnable r) {
    try {
      r.run();
    } catch (RuntimeException e) {
      log.error("{}失败", what, e);
    }
  }

  @Override
  public synchronized void stop() {
    running = false;
    if (pool != null) pool.shutdownNow();
    pool = null;
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
