package com.forecast.app.settle.run;

import com.forecast.app.market.api.MarketListener;
import com.forecast.app.market.api.MarketStatus;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * settle 对 market 的 {@link MarketListener} 的实现（S3 留下的接口，forecast S5）。都在 market 的事务里同步调用（市场行已加排他锁），
 * 抛异常会让 market 的操作一起回滚：退款单建不成，取消、作废也不会生效。
 */
@Component
class SettleMarketListener implements MarketListener {

  private final SettleService settle;
  private final PayoutKick kick;

  SettleMarketListener(SettleService settle, PayoutKick kick) {
    this.settle = settle;
    this.kick = kick;
  }

  /** 进入已取消、已作废（E1、E3、E4、E5 和人工作废）：建退款单（batch-size = 0 时当场退完，否则提交后由派彩任务退） */
  @Override
  public void onRefundRequired(long marketId, MarketStatus to, String reason) {
    settle.createRefundRun(marketId, to, reason);
    kick.afterCommit();
  }

  /** 截止时间提前：新截止时间之后收到的下注当场全额退回（通知 #14） */
  @Override
  public void onCloseAdvanced(long marketId, Instant oldCloseAt, Instant newCloseAt) {
    settle.refundAfterAdvancedClose(marketId, newCloseAt);
  }
}
