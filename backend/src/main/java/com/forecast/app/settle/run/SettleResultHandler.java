package com.forecast.app.settle.run;

import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.resolve.api.ResultHandler;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * settle 对 resolve 的 {@link ResultHandler} 的实现（forecast S6；S7 起公示期满、争议处理完也从这里进）：结果定了 → 按 S5 的结算三段结算（{@link SettleService#settle}）。
 * 同一市场重复调用只结算一次（三道防重复）；batch-size &gt; 0 时返回时已算好、进入派彩中，由派彩任务派完。
 */
@Component
class SettleResultHandler implements ResultHandler {

  private final SettleService settle;

  SettleResultHandler(SettleService settle) {
    this.settle = settle;
  }

  @Override
  public void settle(long marketId, long winningOptionId, Instant actualStartAt, String resolvedBy, MarketStatus expect) {
    settle.settle(marketId, winningOptionId, actualStartAt, resolvedBy, expect);
  }
}
