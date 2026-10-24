/**
 * ledger 模块对外公开的接口和数据类型。其他模块只能依赖这个包（ArchUnit {@code ModuleBoundaryTest}）。
 * <p>forecast S1：从 复制源的 {@code trading/api}、{@code trading/ledger} 搬成独立模块，删掉了差价合约相关的部分（挂单冻结、
 * 交易流水类型、持仓）。可用余额 / 下注锁定 / 提现冻结三项、平台账户、新的流水类型在 S2 做（技术方案/v1-架构方案.md 第 1.2、3.2 节）。
 */
package com.forecast.app.ledger.api;
