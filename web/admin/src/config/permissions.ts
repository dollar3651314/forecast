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
] as const satisfies readonly PermissionDef[];

export type PermissionCode = (typeof PERMISSIONS)[number]['code'];

export const ALL_PERMISSION_CODES: string[] = PERMISSIONS.map((p) => p.code);

// forecast S1 删除了 trading、risk、market（复制源的品种行情）、params 四个模块；market、resolve、dispute、settle 等随 S3 以后各步加
export const PERMISSION_MODULES = ['dashboard', 'customer', 'kyc', 'funds', 'notify', 'system'] as const;

/** 超级管理员角色编码：只有超级管理员才能把这个角色分配给别人（修复 fxsystem S4） */
export const SUPER_ADMIN_ROLE = 'super_admin';
