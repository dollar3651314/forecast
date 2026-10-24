/**
 * 钱包：充值地址、三链扫块、提现、对账（S12–S14）。
 * <p>模块边界（00-架构方案.md 3.2，ArchUnit 测试 {@code ModuleBoundaryTest} 强制）：其他模块只能调用本模块 {@code api} 包里的类，
 * 不能访问本模块的其他包和数据表。
 */
package com.forecast.app.wallet;
