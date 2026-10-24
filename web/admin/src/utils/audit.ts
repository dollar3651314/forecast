/**
 * 审计日志展示：过滤所有密码类字段（修复 fxsystem S5），并把变更前后整理成对比行。
 * 服务端写入时已过滤；前端再过滤一次，避免任何情况下把密码显示出来。
 */
import type { AuditValue } from '../api/types';

const SENSITIVE = /pass(word)?|pwd|secret|hash|token/i;

/** 字段名像密码 / 哈希 / 密钥，且值不是布尔开关（mustChangePassword 这类开关可以显示） */
export function isSensitiveField(key: string, value: AuditValue): boolean {
  return SENSITIVE.test(key) && typeof value !== 'boolean';
}

export function sanitizeAuditPayload(obj: Record<string, AuditValue> | null | undefined): Record<string, AuditValue> | null {
  if (!obj) return null;
  return Object.fromEntries(Object.entries(obj).filter(([k, v]) => !isSensitiveField(k, v)));
}

export interface DiffRow {
  field: string;
  before: string;
  after: string;
  changed: boolean;
}

function show(v: AuditValue | undefined): string {
  if (v === undefined) return '—';
  if (v === null) return 'null';
  if (Array.isArray(v)) return v.join(', ');
  return String(v);
}

export function auditDiff(before: Record<string, AuditValue> | null, after: Record<string, AuditValue> | null): DiffRow[] {
  const b = sanitizeAuditPayload(before) ?? {};
  const a = sanitizeAuditPayload(after) ?? {};
  const keys = [...new Set([...Object.keys(b), ...Object.keys(a)])];
  return keys.map((k) => ({ field: k, before: show(b[k]), after: show(a[k]), changed: show(b[k]) !== show(a[k]) }));
}
