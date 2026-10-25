/**
 * ledger 模块对外公开的接口和数据类型。其他模块只能依赖这个包（ArchUnit {@code ModuleBoundaryTest}）。
 * <p>forecast S1：从复制源的 {@code trading/api}、{@code trading/ledger} 搬成独立模块，删掉了差价合约相关的部分（挂单冻结、交易流水类型、持仓）。
 * S2：账户三项改为可用余额 / 下注锁定 / 提现冻结（{@link com.forecast.app.ledger.api.Bucket}），新增彩池的流水类型
 * （{@link com.forecast.app.ledger.api.LedgerEntryType}）、平台收入和平台补偿支出账户（{@link com.forecast.app.ledger.api.PlatformLedgerApi}）、
 * 账本和余额的核对（{@link com.forecast.app.ledger.api.LedgerQueryApi#mismatches()}）（技术方案/v1-架构方案.md 第 1.2、3.2 节）。
 */
package com.forecast.app.ledger.api;
