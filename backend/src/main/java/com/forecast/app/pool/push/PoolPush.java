package com.forecast.app.pool.push;

import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.pool.bet.PoolQueries;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 市场奖池的实时推送 {@code WS /api/ws/markets}（forecast S4，技术方案/v1-架构方案.md 第 7 节，需求 AC-17、NF-02）。
 * <ul>
 *   <li>客户端连上后发 {@code {"op":"sub","markets":["<市场编号>",…]}} 订阅（每个连接最多 {@code max-subscriptions} 个，草稿和不存在的忽略），
 *       {@code {"op":"unsub","markets":[…]}} 取消。订阅时立即推一次现在的奖池（断线重连后不会漏掉连上之前的变化）。</li>
 *   <li>推送内容 {@code {"type":"pool","marketNo","status","ts","pool":{…}}}，{@code pool} 和市场详情的 {@code pool} 字段同一个结构（不含本人的持仓）。</li>
 *   <li>下注等事务提交后只把市场标成"有变化"（{@link PoolChanges}）；这里每 {@code tick-ms} 看一次，<b>同一个市场距上次推送不到
 *       {@code min-interval-ms}（1 秒）就等下一轮</b>，所以每个市场每秒最多推一次（合并），从变化到推出最多约 1.2 秒，满足"2 秒内"。</li>
 *   <li>和 {@code /api/ws/user} 一样：要登录（握手时检查）、只接受同源握手、每个连接有发送超时和缓冲上限（慢客户端不拖住别人），
 *       握手按 IP 限流（{@code IpRateLimitFilter}）。多实例时其他实例的变化经 Redis 广播过来（{@link PoolChanges}）。</li>
 * </ul>
 */
@Component
public class PoolPush extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(PoolPush.class);
  static final String USER = "fc.userId";

  /** 一个连接和它订阅的市场 */
  private record Conn(WebSocketSession session, Set<Long> markets) {}

  private final PoolQueries views;
  private final MarketApi markets;
  private final ObjectMapper json;
  private final Clock clock;
  private final long minIntervalNanos;
  private final long tickMillis;
  private final int maxSubscriptions;
  private final Map<String, Conn> conns = new ConcurrentHashMap<>();
  private final Map<Long, Set<Conn>> byMarket = new ConcurrentHashMap<>();
  private final Set<Long> dirty = ConcurrentHashMap.newKeySet();
  private final Map<Long, Long> lastPush = new ConcurrentHashMap<>();
  /** 推出去的消息数（测试看"合并"用） */
  final AtomicLong sent = new AtomicLong();
  private ScheduledExecutorService ticker;

  PoolPush(PoolQueries views, MarketApi markets, ObjectMapper json, Clock clock, @Value("${forecast.pool.push.min-interval-ms:1000}") long minIntervalMs,
      @Value("${forecast.pool.push.tick-ms:200}") long tickMillis, @Value("${forecast.pool.push.max-subscriptions:20}") int maxSubscriptions) {
    this.views = views;
    this.markets = markets;
    this.json = json;
    this.clock = clock;
    this.minIntervalNanos = TimeUnit.MILLISECONDS.toNanos(minIntervalMs);
    this.tickMillis = tickMillis;
    this.maxSubscriptions = maxSubscriptions;
  }

  @jakarta.annotation.PostConstruct
  void start() {
    ticker = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "pool-push");
      t.setDaemon(true);
      return t;
    });
    ticker.scheduleWithFixedDelay(this::tick, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
  }

  @jakarta.annotation.PreDestroy
  void stop() {
    if (ticker != null) ticker.shutdownNow();
  }

  /** 这个市场的奖池有变化（{@link PoolChanges} 调用）；没有人订阅时下一轮直接丢掉 */
  void markDirty(long marketId) {
    dirty.add(marketId);
  }

  // ---------------------------------------------------------------------
  // 连接和订阅
  // ---------------------------------------------------------------------

  @Override
  public void afterConnectionEstablished(WebSocketSession s) {
    conns.put(s.getId(), new Conn(new ConcurrentWebSocketSessionDecorator(s, 2000, 256 * 1024), ConcurrentHashMap.newKeySet()));
  }

  @Override
  public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
    Conn c = conns.remove(s.getId());
    if (c == null) return;
    for (Long m : c.markets()) unsubscribe(c, m);
  }

  @Override
  protected void handleTextMessage(WebSocketSession s, TextMessage message) {
    Conn c = conns.get(s.getId());
    if (c == null) return;
    JsonNode n;
    try {
      n = json.readTree(message.getPayload());
    } catch (RuntimeException e) {
      return; // 格式不对的消息忽略
    }
    String op = n.path("op").asString("");
    JsonNode list = n.path("markets");
    if (!list.isArray()) return;
    for (JsonNode x : list) {
      long no;
      try {
        no = Long.parseLong(x.asString(""));
      } catch (NumberFormatException e) {
        continue;
      }
      if ("unsub".equals(op)) {
        markets.find(no).ifPresent(m -> {
          if (c.markets().remove(m.marketId())) unsubscribe(c, m.marketId());
        });
      } else if ("sub".equals(op)) {
        if (c.markets().size() >= maxSubscriptions) break;
        Optional<MarketApi.MarketRef> m = markets.find(no).filter(r -> r.status() != MarketStatus.DRAFT);
        if (m.isEmpty() || !c.markets().add(m.get().marketId())) continue;
        byMarket.computeIfAbsent(m.get().marketId(), k -> ConcurrentHashMap.newKeySet()).add(c);
        // 订阅时立即推一次现在的奖池
        String text = payload(m.get());
        if (text != null) send(c, text);
      }
    }
  }

  private void unsubscribe(Conn c, long marketId) {
    byMarket.computeIfPresent(marketId, (k, set) -> {
      set.remove(c);
      return set.isEmpty() ? null : set;
    });
  }

  /** 现在订阅了这个市场的连接数（测试用） */
  int subscribers(long marketId) {
    Set<Conn> set = byMarket.get(marketId);
    return set == null ? 0 : set.size();
  }

  // ---------------------------------------------------------------------
  // 合并推送
  // ---------------------------------------------------------------------

  void tick() {
    try {
      long now = System.nanoTime();
      for (Long id : List.copyOf(dirty)) {
        Set<Conn> subs = byMarket.get(id);
        if (subs == null || subs.isEmpty()) {
          dirty.remove(id);
          lastPush.remove(id);
          continue;
        }
        Long last = lastPush.get(id);
        if (last != null && now - last < minIntervalNanos) continue; // 1 秒内推过：留到下一轮，期间的变化合并成一次
        dirty.remove(id);
        lastPush.put(id, now);
        Optional<MarketApi.MarketRef> m = markets.findById(id);
        if (m.isEmpty()) continue;
        String text = payload(m.get());
        if (text == null) continue;
        for (Conn c : new ArrayList<>(subs)) send(c, text);
      }
    } catch (RuntimeException e) {
      log.error("奖池推送失败，下一轮重试", e);
    }
  }

  private String payload(MarketApi.MarketRef m) {
    try {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("type", "pool");
      e.put("marketNo", String.valueOf(m.publicNo()));
      e.put("status", m.status().api());
      e.put("ts", clock.instant().toString());
      e.put("pool", views.marketPool(m));
      return json.writeValueAsString(e);
    } catch (RuntimeException ex) {
      log.warn("读取市场 {} 的奖池失败：{}", m.marketId(), ex.getMessage());
      return null;
    }
  }

  private void send(Conn c, String text) {
    try {
      if (c.session().isOpen()) {
        c.session().sendMessage(new TextMessage(text));
        sent.incrementAndGet();
      }
    } catch (IOException | RuntimeException e) {
      log.debug("推送失败，连接可能已断开");
    }
  }
}
