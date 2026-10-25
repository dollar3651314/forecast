/**
 * resolve 模块的对外接口（forecast S6 起，技术方案/v1-架构方案.md 第 2.3、6 节）。其他模块只能用这个包（ModuleBoundaryTest）。
 * <ul>
 *   <li>{@link com.forecast.app.resolve.api.ResultHandler}：结果定了以后结算，<b>由 settle 实现</b>（依赖方向 resolve ← settle，和 market 的 MarketListener 同样的做法）。</li>
 *   <li>{@link com.forecast.app.resolve.api.ResolveAdminApi}：后台看自动裁决的进度、裁决记录、数据源原始返回（admin 模块用）。</li>
 * </ul>
 */
package com.forecast.app.resolve.api;
