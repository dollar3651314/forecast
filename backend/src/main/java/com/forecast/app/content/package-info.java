/**
 * 事件和文章自动化程序（forecast S15；需求文档/v1.3-事件和文章自动化.md、需求说明书 NF-15、NF-16；架构方案第 18.3 节）。
 * <p>和主应用同一个进程（测试服务器只剩约 170 MB 余量，单独的容器放不下，见开发记录 S15 第 4 节），但按"独立运行"的要求隔离：
 * 自己的表（content_*）、自己的线程池（{@code core/Workers}）、自己的定时任务和租约（{@code job/ContentJob}）、自己的总开关（FC_CONTENT_ENABLED）；
 * 只能经 {@code market.api.MarketDraftApi} 建市场草稿（不能上架、改状态），读公开快照（{@code MarketPublicApi}）和公开行情（{@code resolve.api.MarketDataApi}），
 * 不依赖账本、彩池、结算、钱包、客户（{@code arch/ContentIsolationTest} 检查）。外部调用（Gemini、行情）一律在事务外、有超时、重试、熔断。
 */
package com.forecast.app.content;
