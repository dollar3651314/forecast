package com.forecast.app.market.web;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.web.CurrentUser;
import com.forecast.app.market.core.ClientViews;
import com.forecast.app.market.core.MarketService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户端的市场列表和详情（forecast S3，技术方案/v1-架构方案.md 第 2.4 节）。S13（MF-4）：{@code forecast.public-browse.enabled}（默认开）时
 * 列表、今日场次、详情不登录也能看（{@code SessionFilter}）；登录了详情里带本人的持仓，没登录只有公开数据。关掉时和以前一样要登录。
 * 契约见 {@code MarketClientContractTest}。
 * 字段见 {@link ClientViews}；奖池、概率、预计赔率、本人的持仓在 {@code pool} 字段（S4，pool 模块提供）。
 */
@RestController
class MarketController {

  private final MarketService markets;
  private final com.forecast.app.market.core.MarketPublicService publicMarkets;

  MarketController(MarketService markets, com.forecast.app.market.core.MarketPublicService publicMarkets) {
    this.markets = markets;
    this.publicMarkets = publicMarkets;
  }

  /**
   * 市场列表：category（FOOTBALL 等）、status（不传为全部；active / closed / ended 或某个状态）、sort（closeAt / newest / pool，S11 加奖池）、
   * q（S11：题目包含或市场编号等于）、分页（每页最多 50）
   */
  @GetMapping("/api/markets")
  ClientViews.Page<ClientViews.Card> list(@RequestParam(required = false) String category, @RequestParam(required = false) String status,
      @RequestParam(required = false) String sort, @RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return markets.clientList(status, category, sort, q, page, pageSize);
  }

  /**
   * 今日场次（S13，MF-3，需求第 4.2 节、P11、AC-82）：from、to 是客户所在时区的"今天"换成的 UTC 时间（客户端算好传来，[from, to)）；
   * 不传按 UTC 的今天。items 按场次时间排，没有时 next 是下一场。
   */
  @GetMapping("/api/markets/today")
  com.forecast.app.market.core.MarketPublicService.Today today(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    return publicMarkets.today(from, to);
  }

  /** 市场详情：草稿和不存在的都返回 404；登录了奖池里带本人的持仓（S13 起不登录也能看） */
  @GetMapping("/api/markets/{publicNo}")
  ClientViews.Detail detail(@PathVariable String publicNo) {
    long no;
    try {
      no = Long.parseLong(publicNo);
    } catch (NumberFormatException e) {
      throw new BizException("NOT_FOUND", "市场不存在");
    }
    return markets.clientDetail(no, CurrentUser.optional().orElse(null)).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
  }
}
