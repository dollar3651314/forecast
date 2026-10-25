package com.forecast.app.ledger.api;

import java.util.Collection;
import java.util.Map;

/**
 * 客户资金流水里"和哪个市场有关"的显示（AC-39：下注、派彩、抽成、退款、补偿每条能点到对应市场；forecast S4）。
 * 账本只记市场的内部 id（{@code ledger_entry.ref_market_id}），ledger 不依赖 market（依赖方向见技术方案第 2.3 节），
 * 所以<b>由 market 模块实现</b>（{@code market/core/MarketService}），ledger 的流水接口通过它换成对外编号和题目。没有实现时流水里不带市场。
 */
public interface MarketLabels {

  /** 市场的对外编号（字符串）和三种语言的题目 */
  record MarketLabel(String publicNo, String titleEn, String titleZhCn, String titleZhTw) {}

  /** 内部 id → 显示信息；不存在的不在结果里 */
  Map<Long, MarketLabel> labels(Collection<Long> marketIds);
}
