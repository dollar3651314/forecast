/**
 * pool 模块的对外接口（forecast S4，技术方案/v1-架构方案.md 第 2.3、3.3、4 节）。其他模块只能用这个包（ModuleBoundaryTest）。
 * <ul>
 *   <li>{@link com.forecast.app.pool.api.PoolMath}、{@link com.forecast.app.pool.api.Odds}：彩池的金额和赔率（纯函数，前后端共用测试向量）。</li>
 *   <li>{@link com.forecast.app.pool.api.PoolApi}：给其他模块的查询（R8 的有效下注本金；S5 起结算用）。</li>
 *   <li>{@link com.forecast.app.pool.api.PoolAdminApi}：后台的下注明细（admin 模块用，权限点 bet:view）。</li>
 * </ul>
 * pool 还实现 market 的 {@code MarketPoolView}（上架建奖池行、截止定格、下注客户名单、客户端接口里的奖池）。
 */
package com.forecast.app.pool.api;
