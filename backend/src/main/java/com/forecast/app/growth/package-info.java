/**
 * 推广功能的数据（forecast S13）：来源统计（MF-1，带来源参数的访问 {@code growth_visit}）、增长数据和 CSV 导出（MF-10）。
 * <b>只记来源、不给任何人奖励</b>（MQ-5：不做返佣和分成）；只读其他模块的 api（account 的来源、wallet 的首充、pool 的下注、market 的已结束市场），
 * 只写自己的表。不在下注、结算、充值的事务里做任何事。
 * <p>模块边界（ArchUnit 测试 {@code ModuleBoundaryTest} 强制）：其他模块只能调用本模块 {@code api} 包里的类，不能访问本模块的其他包和数据表。
 */
package com.forecast.app.growth;
