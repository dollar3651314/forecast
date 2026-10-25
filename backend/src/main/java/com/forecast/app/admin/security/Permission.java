package com.forecast.app.admin.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * 权限点：和后台 {@code web/admin/src/config/permissions.ts} 一一对应（测试核对，不一致 CI 失败）。highRisk 标记高危权限。
 * <p>forecast S1：删掉了差价合约的 order、position、pending、risk、symbol、session、fx、params 和 customer:adjust_balance；
 * forecast S3：按需求说明书第 2.2 节一次加齐市场、裁决、争议、结算、下注记录、费率和参数、报表、彩池对账、风控告警的 21 个权限点（共 53 个）；
 * 接口随各步加（S3 用到 market:*、fee:edit、betting_params:edit）。超级管理员自动拥有全部，其他角色由超管在后台配置（RQ-17：一个有权限的人就能完成）。
 */
public enum Permission {
  DASHBOARD_VIEW("dashboard:view", "dashboard", false),

  CUSTOMER_VIEW("customer:view", "customer", false),
  /** v2（E1）：编辑客户资料（姓名、出生日期、国籍） */
  CUSTOMER_EDIT("customer:edit", "customer", true),
  CUSTOMER_FREEZE("customer:freeze", "customer", true),

  KYC_VIEW("kyc:view", "kyc", false),
  KYC_REVIEW("kyc:review", "kyc", false),

  DEPOSIT_VIEW("deposit:view", "funds", false),
  WITHDRAWAL_VIEW("withdrawal:view", "funds", false),
  WITHDRAWAL_REVIEW("withdrawal:review", "funds", false),
  WITHDRAWAL_EMERGENCY_CANCEL("withdrawal:emergency_cancel", "funds", true),
  /** v2 提现自动审批：改总开关和 13 条规则（查看用 withdrawal:view）。不放进系统配置页，否则有 config:edit 的人就能绕过去放宽审核 */
  WITHDRAWAL_AUTO_RULE_EDIT("withdrawal:auto_rule_edit", "funds", true),
  RECON_VIEW("recon:view", "funds", false),
  RECON_RESOLVE("recon:resolve", "funds", false),
  /** v2 资金归集（技术方案/v2-半自动归集.md SW-12）：查看归集记录、预览、设置 */
  SWEEP_VIEW("sweep:view", "funds", false),
  /** v2 资金归集：执行归集、重试（高危，原因必填）；TRON 下一阶段的生成清单 / 导入签名 / 广播 / 作废也用它 */
  SWEEP_OPERATE("sweep:operate", "funds", true),
  /** v2 资金归集：改门槛、手续费占比上限、上线初期金额上限、发起账户余额告警下限（高危，原因必填）。以太坊、BSC 的冷钱包写在合约里，后台改不了 */
  SWEEP_CONFIG("sweep:config", "funds", true),
  TASK_VIEW("task:view", "funds", false),
  TASK_RETRY("task:retry", "funds", false),

  NOTIFY_TEMPLATE_VIEW("notify_template:view", "notify", false),
  NOTIFY_TEMPLATE_EDIT("notify_template:edit", "notify", false),
  NOTIFY_SEND("notify:send", "notify", true),
  NOTIFY_RECORD_VIEW("notify_record:view", "notify", false),

  ADMIN_VIEW("admin:view", "system", false),
  ADMIN_EDIT("admin:edit", "system", true),
  ROLE_VIEW("role:view", "system", false),
  ROLE_EDIT("role:edit", "system", true),
  MENU_VIEW("menu:view", "system", false),
  MENU_EDIT("menu:edit", "system", false),
  PERMISSION_VIEW("permission:view", "system", false),
  AUDIT_VIEW("audit:view", "system", false),
  CONFIG_VIEW("config:view", "system", false),
  CONFIG_EDIT("config:edit", "system", true),

  // ---------- forecast S3：需求说明书第 2.2 节 ----------
  /** 查看市场、奖池、下注明细 */
  MARKET_VIEW("market:view", "market", false),
  /** 新建、编辑、删除草稿；提交上架（只加标记，不改状态） */
  MARKET_EDIT("market:edit", "market", false),
  /** 上架（检查清单）/ 退回草稿；启用加密自动生成模板 */
  MARKET_PUBLISH("market:publish", "market", false),
  /** 暂停 / 恢复下注；提前截止（需求没写提前截止的权限点，S3 暂用这个，开发记录 S3 待确认） */
  MARKET_PAUSE("market:pause", "market", true),
  /** 截止前取消市场（全额退款） */
  MARKET_CANCEL("market:cancel", "market", true),
  /** 上架后发布补充说明 */
  MARKET_CLARIFY("market:clarify", "market", true),
  /** 加密市场自动生成模板（S6） */
  MARKET_AUTOGEN("market:autogen", "market", true),
  /** 查看客户下注记录（S4 起） */
  BET_VIEW("bet:view", "market", false),
  /** 提议结果（S7） */
  RESOLVE_PROPOSE("resolve:propose", "resolve", false),
  /** 确认结果 / 退回提议（S7） */
  RESOLVE_CONFIRM("resolve:confirm", "resolve", true),
  /** 截止后作废市场（S7） */
  RESOLVE_VOID("resolve:void", "resolve", true),
  /** 查看争议（S7） */
  DISPUTE_VIEW("dispute:view", "resolve", false),
  /** 处理争议（S7） */
  DISPUTE_HANDLE("dispute:handle", "resolve", true),
  /** 发起并确认补偿（S7） */
  SETTLE_COMPENSATE("settle:compensate", "settle", true),
  /** 彩池对账（S5） */
  POOL_RECON_VIEW("pool_recon:view", "settle", false),
  POOL_RECON_RESOLVE("pool_recon:resolve", "settle", false),
  /** 平台收入和运营报表（S9） */
  REPORT_VIEW("report:view", "settle", false),
  /** 改品类默认费率、改单个市场的费率（需求第 3.8 节） */
  FEE_EDIT("fee:edit", "params", true),
  /** 改下注限额、截止提前量、争议窗口、KYC 阈值、单笔提现最小金额等参数（S2 的遗留：单笔最小提现从 config:edit 改到这里） */
  BETTING_PARAMS_EDIT("betting_params:edit", "params", true),
  /** 风控告警（S10） */
  RISK_ALERT_VIEW("risk_alert:view", "risk", false),
  RISK_ALERT_HANDLE("risk_alert:handle", "risk", false),
  /** 来源统计、增长数据 CSV 导出（S13，MF-1、MF-10；需求第 6.2 节） */
  GROWTH_VIEW("growth:view", "growth", false);

  public final String code;
  public final String module;
  public final boolean highRisk;

  Permission(String code, String module, boolean highRisk) {
    this.code = code;
    this.module = module;
    this.highRisk = highRisk;
  }

  public static Optional<Permission> of(String code) {
    return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst();
  }
}
