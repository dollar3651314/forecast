package com.forecast.app.pool.web;

import com.forecast.app.common.ratelimit.UserWriteLimited;
import com.forecast.app.common.web.CurrentUser;
import com.forecast.app.pool.bet.BetService;
import com.forecast.app.pool.bet.PoolQueries;
import com.forecast.app.pool.bet.PoolViews;
import java.time.Clock;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户端的下注接口（forecast S4，技术方案/v1-架构方案.md 第 2.4 节；页面在原型确认后做，S11）。都要登录。
 * 字段见 {@link PoolViews}（契约测试 {@code PoolClientContractTest}）；市场详情里的奖池和本人持仓在市场接口的 {@code pool} 字段。
 */
@RestController
class BetController {

  /**
   * 下注请求。
   *
   * @param optionNo 选项编号（字符串）
   * @param amount 金额（字符串，最多 2 位小数，不舍入）
   * @param clientReqId 客户端每次下注新生成的请求号（UUID）：网络重试、双击时重复提交只下一次注，返回第一次的结果
   */
  record BetBody(String optionNo, String amount, String clientReqId) {}

  private final BetService bets;
  private final PoolQueries queries;
  private final Clock clock;

  BetController(BetService bets, PoolQueries queries, Clock clock) {
    this.bets = bets;
    this.queries = queries;
    this.clock = clock;
  }

  /**
   * 下注。截止判定只看服务器收到请求的时间（这里取，AC-15）；资金写操作按客户限流（{@code @UserWriteLimited}，项目地图约定 16）。
   * 拒绝时返回错误码（客户端翻译成中英文，需求第 3.3 节的 11 种原因见 {@link BetService}）。
   */
  @UserWriteLimited
  @PostMapping("/api/markets/{publicNo}/bets")
  PoolViews.BetResult place(@PathVariable String publicNo, @RequestBody(required = false) BetBody body) {
    Instant receivedAt = clock.instant();
    BetBody b = body == null ? new BetBody(null, null, null) : body;
    return bets.place(new BetService.Place(CurrentUser.id(), publicNo, b.optionNo(), b.amount(), b.clientReqId(), receivedAt));
  }

  /** 我的下注：tab = active 进行中 / pending 待结果 / settled 已结算 / refunded 已退回，不传或 all 为全部；带四个分组的汇总 */
  @GetMapping("/api/me/bets")
  PoolViews.MyBets mine(@RequestParam(required = false) String tab, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return queries.myBets(CurrentUser.id(), tab, page, pageSize);
  }
}
