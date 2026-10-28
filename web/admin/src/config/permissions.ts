/**
 * 权限点字典（真源）。每个后台页面和操作都对应一个权限点；highRisk 标记高危权限（修复 fxsystem S6：高危权限全部登记）。
 * Permission dictionary. Every page/action maps to a code; high-risk ones are flagged.
 */
import type { PermissionDef } from '../api/types';

export const PERMISSIONS = [
  { code: 'dashboard:view', module: 'dashboard', highRisk: false },

  { code: 'customer:view', module: 'customer', highRisk: false },
  { code: 'customer:edit', module: 'customer', highRisk: true },
  { code: 'customer:freeze', module: 'customer', highRisk: true },
  // forecast S17A：测试入金（只在测试环境有接口和按钮；生产 404、不显示）
  { code: 'balance:test_credit', module: 'customer', highRisk: true },
  // forecast S17B：人工调账（加款、扣款；生产可用。查看"人工调账记录"用 recon:view）
  { code: 'balance:adjust', module: 'customer', highRisk: true },

  { code: 'kyc:view', module: 'kyc', highRisk: false },
  { code: 'kyc:review', module: 'kyc', highRisk: false },

  { code: 'deposit:view', module: 'funds', highRisk: false },
  { code: 'withdrawal:view', module: 'funds', highRisk: false },
  { code: 'withdrawal:review', module: 'funds', highRisk: false },
  { code: 'withdrawal:emergency_cancel', module: 'funds', highRisk: true },
  { code: 'withdrawal:auto_rule_edit', module: 'funds', highRisk: true },
  { code: 'recon:view', module: 'funds', highRisk: false },
  { code: 'recon:resolve', module: 'funds', highRisk: false },
  { code: 'sweep:view', module: 'funds', highRisk: false },
  { code: 'sweep:operate', module: 'funds', highRisk: true },
  { code: 'sweep:config', module: 'funds', highRisk: true },
  { code: 'task:view', module: 'funds', highRisk: false },
  { code: 'task:retry', module: 'funds', highRisk: false },

  { code: 'notify_template:view', module: 'notify', highRisk: false },
  { code: 'notify_template:edit', module: 'notify', highRisk: false },
  { code: 'notify:send', module: 'notify', highRisk: true },
  { code: 'notify_record:view', module: 'notify', highRisk: false },

  { code: 'admin:view', module: 'system', highRisk: false },
  { code: 'admin:edit', module: 'system', highRisk: true },
  { code: 'role:view', module: 'system', highRisk: false },
  { code: 'role:edit', module: 'system', highRisk: true },
  { code: 'menu:view', module: 'system', highRisk: false },
  { code: 'menu:edit', module: 'system', highRisk: false },
  { code: 'permission:view', module: 'system', highRisk: false },
  { code: 'audit:view', module: 'system', highRisk: false },
  { code: 'config:view', module: 'system', highRisk: false },
  { code: 'config:edit', module: 'system', highRisk: true },

  // forecast S3：需求说明书第 2.2 节新增的 21 个（接口随各步加；页面在原型确认后做）
  { code: 'market:view', module: 'market', highRisk: false },
  { code: 'market:edit', module: 'market', highRisk: false },
  { code: 'market:publish', module: 'market', highRisk: false },
  { code: 'market:pause', module: 'market', highRisk: true },
  { code: 'market:cancel', module: 'market', highRisk: true },
  { code: 'market:clarify', module: 'market', highRisk: true },
  { code: 'market:autogen', module: 'market', highRisk: true },
  { code: 'bet:view', module: 'market', highRisk: false },
  { code: 'resolve:propose', module: 'resolve', highRisk: false },
  { code: 'resolve:confirm', module: 'resolve', highRisk: true },
  { code: 'resolve:void', module: 'resolve', highRisk: true },
  { code: 'dispute:view', module: 'resolve', highRisk: false },
  { code: 'dispute:handle', module: 'resolve', highRisk: true },
  { code: 'settle:compensate', module: 'settle', highRisk: true },
  { code: 'pool_recon:view', module: 'settle', highRisk: false },
  { code: 'pool_recon:resolve', module: 'settle', highRisk: false },
  { code: 'report:view', module: 'settle', highRisk: false },
  { code: 'fee:edit', module: 'params', highRisk: true },
  { code: 'betting_params:edit', module: 'params', highRisk: true },
  // forecast S12b：告警邮件的总开关和不发邮件的告警项（Amos 2026-10-27："告警邮件要能控制是否发送"）
  { code: 'alert_params:edit', module: 'params', highRisk: true },
  { code: 'risk_alert:view', module: 'risk', highRisk: false },
  { code: 'risk_alert:handle', module: 'risk', highRisk: false },
  // forecast S13：来源统计（MF-1、MF-10；导出写审计）
  { code: 'growth:view', module: 'growth', highRisk: false },
  // forecast S15：内容中心（事件和文章自动化程序；发布是高危，C73）。S16 也加了 content:view、content:publish（Telegram 机器人页、"发送上线通知"），
  // 同名同义，整合 4 去重成一份
  { code: 'content:view', module: 'content', highRisk: false },
  { code: 'content:edit', module: 'content', highRisk: false },
  { code: 'content:publish', module: 'content', highRisk: true },
] as const satisfies readonly PermissionDef[];

export type PermissionCode = (typeof PERMISSIONS)[number]['code'];

export const ALL_PERMISSION_CODES: string[] = PERMISSIONS.map((p) => p.code);

// forecast S1 删除了复制源的 trading、risk、market（品种行情）、params 四个模块；S3 按需求第 2.2 节加了市场、裁决、结算、参数、风控告警
export const PERMISSION_MODULES = ['dashboard', 'customer', 'kyc', 'funds', 'notify', 'system', 'market', 'resolve', 'settle', 'params', 'risk', 'growth', 'content'] as const;

/** 超级管理员角色编码：只有超级管理员才能把这个角色分配给别人（修复 fxsystem S4） */
export const SUPER_ADMIN_ROLE = 'super_admin';
