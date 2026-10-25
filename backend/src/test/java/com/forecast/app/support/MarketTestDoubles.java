package com.forecast.app.support;

import com.forecast.app.market.api.MarketListener;
import com.forecast.app.market.api.MarketPoolView;
import com.forecast.app.market.api.MarketStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 市场测试用的替身（forecast S3）：S4 的 pool、S5 的 settle 还没有，用它们代替。
 * <ul>
 *   <li>{@link Pool}：截止时每个市场有几个有效选项（没设置的按 2 个，市场正常进入"截止下注"）、有下注的客户名单。</li>
 *   <li>{@link Events}：记下"要退款"和"截止时间提前"的事件（S5 在这里建退款单）。</li>
 * </ul>
 * 市场测试都 {@code @Import(FixedClockConfig.class, MarketTestDoubles.class)}，共用同一个 Spring 上下文。
 */
@TestConfiguration
public class MarketTestDoubles {

  public static class Pool implements MarketPoolView {
    public final Map<Long, Integer> activeOptions = new ConcurrentHashMap<>();
    public final Map<Long, List<Long>> bettors = new ConcurrentHashMap<>();
    public final List<Long> frozen = new CopyOnWriteArrayList<>();

    @Override
    public int freezeAndCountActiveOptions(long marketId) {
      frozen.add(marketId);
      return activeOptions.getOrDefault(marketId, 2);
    }

    @Override
    public List<Long> bettors(long marketId) {
      return bettors.getOrDefault(marketId, List.of());
    }
  }

  public record Refund(long marketId, MarketStatus to, String reason) {}

  public record Advanced(long marketId, Instant oldCloseAt, Instant newCloseAt) {}

  public static class Events implements MarketListener {
    public final List<Refund> refunds = new CopyOnWriteArrayList<>();
    public final List<Advanced> advanced = new CopyOnWriteArrayList<>();

    @Override
    public void onRefundRequired(long marketId, MarketStatus to, String reason) {
      refunds.add(new Refund(marketId, to, reason));
    }

    @Override
    public void onCloseAdvanced(long marketId, Instant oldCloseAt, Instant newCloseAt) {
      advanced.add(new Advanced(marketId, oldCloseAt, newCloseAt));
    }

    public List<Refund> refundsOf(long marketId) {
      return refunds.stream().filter(r -> r.marketId() == marketId).toList();
    }
  }

  @Bean
  Pool testPool() {
    return new Pool();
  }

  @Bean
  Events testMarketEvents() {
    return new Events();
  }
}
