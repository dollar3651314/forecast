/**
 * 风控告警（forecast S10，需求说明书第 8 节；技术方案/v1-架构方案.md 第 1.2、2.3、3.3 节）：关联账户（同设备、同 IP、同充值来源地址、同提现地址）、
 * 告警（尾盘大额、关联账户对押、新账户押中冷门、充了不玩就提）、提现自动审批 R14 / R15 的查询。
 * 数据从各模块的 api 按 id 往后扫描（{@code scan.RiskScanJob}），不改任何业务数据。
 */
package com.forecast.app.riskalert;
