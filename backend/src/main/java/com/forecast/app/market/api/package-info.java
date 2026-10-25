/**
 * market 模块的对外接口（forecast S3，技术方案/v1-架构方案.md 第 2.3、3.3、5 节）。其他模块只能用这个包（ModuleBoundaryTest）。
 * <ul>
 *   <li>{@link com.forecast.app.market.api.MarketApi}：下注前的截止屏障（S4 pool 用）、状态跳转（S5～S7 用）。</li>
 *   <li>{@link com.forecast.app.market.api.MarketAdminApi}：后台的市场管理（admin 模块用，审计在 admin 模块）。</li>
 *   <li>{@link com.forecast.app.market.api.MarketPoolView}、{@link com.forecast.app.market.api.MarketListener}：反过来由 pool、settle 实现，
 *       market 不依赖 pool、settle（依赖方向见第 2.3 节）。</li>
 * </ul>
 */
package com.forecast.app.market.api;
