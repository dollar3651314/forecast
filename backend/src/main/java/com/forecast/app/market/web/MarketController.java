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
 * 客户端的市场列表和详情（forecast S3，技术方案/v1-架构方案.md 第 2.4 节）。现在要求登录（{@code SessionFilter}）；
 * 不登录也能浏览待 MF 结论（第 18 节）。页面在原型确认后做（S11），这里先定接口和最小契约（{@code MarketClientContractTest}）。
 * 字段见 {@link ClientViews}；奖池、概率、预计赔率、本人的持仓在 {@code pool} 字段（S4，pool 模块提供）。
 */
@RestController
class MarketController {

  private final MarketService markets;

  MarketController(MarketService markets) {
    this.markets = markets;
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

  /** 市场详情：草稿和不存在的都返回 404；奖池里带本人的持仓 */
  @GetMapping("/api/markets/{publicNo}")
  ClientViews.Detail detail(@PathVariable String publicNo) {
    long no;
    try {
      no = Long.parseLong(publicNo);
    } catch (NumberFormatException e) {
      throw new BizException("NOT_FOUND", "市场不存在");
    }
    return markets.clientDetail(no, CurrentUser.id()).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
  }
}
