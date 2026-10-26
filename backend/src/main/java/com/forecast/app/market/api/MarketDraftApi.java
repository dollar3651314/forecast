package com.forecast.app.market.api;

import java.util.Optional;

/**
 * 自动化程序建市场草稿的接口（forecast S15；需求说明书第 4.1 节"除了加密自动生成模板，任何自动生成的市场都不会自动上架"、NF-15、AC-102）。
 * <b>只有建草稿和查状态</b>：没有提交上架、上架、暂停、取消、改费率、裁决、结算——自动化程序（content 模块）只能拿到这个接口，
 * 拿不到 {@link MarketAdminApi}、{@link MarketApi}（{@code arch/ContentIsolationTest} 检查）。草稿要有 market:publish 的人在后台按清单检查后上架。
 */
public interface MarketDraftApi {

  /**
   * 建一个草稿：和后台"新建草稿"同一套检查（品类、选项 2～10 个、时间先后……，字段错误抛 {@code ValidationException}）；
   * 建草稿的人为空（系统生成），列表里的来源按 {@code sourceRef} 显示（S15：带 {@code econKind} 的是"经济日历"）。
   * 建好后直接提交上架（进"待上架"、提醒有 market:publish 的人）；上架仍要人检查后操作，这个接口没有上架。
   *
   * @return 市场编号
   */
  long createDraft(MarketAdminApi.DraftInput in);

  /** 市场现在的状态（小写，例如 draft、listed）；不存在或草稿已删除为空 */
  Optional<String> status(long publicNo);
}
