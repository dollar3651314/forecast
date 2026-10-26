/**
 * 提现自动审批的规则定义（复制源的技术方案 v2-提现自动审批 第 2 节；forecast S10 按需求说明书第 8.2 节改 R8～R10、R13 的口径，加 R14、R15）：
 * 规则（现在 15 条，条数以 AUTO_RULES 为准）的编号、数值参数、单位、默认值和取值范围。
 * 和后端 backend/.../wallet/api/AutoApprove.java 一致（后端保存时再校验一遍）。规则名称、说明、判定结果的文字在翻译文件的 autoRule 下（中英文）。
 *
 * Auto-approval rule definitions; mirrors the backend's AutoApprove.java.
 */
import type { AutoRuleCode } from '../api/types';

/** 改总开关和规则的权限点（高危）；查看用 withdrawal:view */
export const AUTO_RULE_EDIT_PERM = 'withdrawal:auto_rule_edit';

export type RuleParamUnit = 'usdt' | 'hours' | 'days' | 'times' | 'multiple' | 'percent';

export interface RuleParamDef {
  key: string;
  unit: RuleParamUnit;
  /** 默认值（Amos 2026-10-17 确认的规则表；R13 是 Amos 2026-10-18 定的） */
  defaultValue: string;
  min: number;
  max: number;
  /** 最多几位小数；0 表示整数 */
  decimals: number;
}

export interface AutoRuleDef {
  code: AutoRuleCode;
  params: RuleParamDef[];
}

const usdt = (key: string, defaultValue: string, max = 1_000_000): RuleParamDef => ({ key, unit: 'usdt', defaultValue, min: 0.01, max, decimals: 2 });

export const AUTO_RULES: AutoRuleDef[] = [
  { code: 'R1', params: [usdt('maxAmount', '500')] },
  { code: 'R2', params: [usdt('maxUser24h', '2000')] },
  { code: 'R3', params: [usdt('maxPlatform24h', '20000', 100_000_000)] },
  { code: 'R4', params: [] },
  { code: 'R5', params: [] },
  { code: 'R6', params: [{ key: 'hours', unit: 'hours', defaultValue: '48', min: 1, max: 720, decimals: 0 }] },
  { code: 'R7', params: [{ key: 'days', unit: 'days', defaultValue: '7', min: 0, max: 365, decimals: 0 }] },
  { code: 'R8', params: [{ key: 'multiple', unit: 'multiple', defaultValue: '1', min: 0, max: 100, decimals: 2 }] },
  { code: 'R9', params: [] },
  { code: 'R10', params: [usdt('maxProfit7d', '5000'), { key: 'maxProfitPct', unit: 'percent', defaultValue: '50', min: 1, max: 1000, decimals: 0 }] },
  { code: 'R11', params: [{ key: 'maxCount', unit: 'times', defaultValue: '3', min: 1, max: 50, decimals: 0 }] },
  { code: 'R12', params: [] },
  // R13 盈利客户：累计净盈利（forecast S10：派彩 + 补偿 − 已结算本金 − 抽成）> 门槛转人工；门槛默认 0（RQ-11），可以填 0
  { code: 'R13', params: [{ key: 'maxNetProfit', unit: 'usdt', defaultValue: '0', min: 0, max: 1_000_000, decimals: 2 }] },
  // forecast S10（需求第 8.2 节）：R14 关联账户对押（近 N 天、盈利门槛 100），R15 可疑押中（近 N 天有没处理的告警）
  { code: 'R14', params: [{ key: 'days', unit: 'days', defaultValue: '30', min: 1, max: 365, decimals: 0 }, usdt('minProfit', '100')] },
  { code: 'R15', params: [{ key: 'days', unit: 'days', defaultValue: '7', min: 1, max: 365, decimals: 0 }] },
];

export const AUTO_RULE_CODES: AutoRuleCode[] = AUTO_RULES.map((r) => r.code);

/** 数值参数的校验：返回翻译键和参数，合法时返回 null */
export function ruleParamProblem(def: RuleParamDef, raw: string | undefined): { key: string; params?: Record<string, string | number> } | null {
  const v = (raw ?? '').trim();
  if (!v) return { key: 'autoApprove.errRequired' };
  const re = def.decimals === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${def.decimals}})?$`);
  if (!re.test(v)) return def.decimals === 0 ? { key: 'autoApprove.errInteger' } : { key: 'autoApprove.errDecimals', params: { n: def.decimals } };
  const n = Number(v);
  if (n < def.min || n > def.max) return { key: 'autoApprove.errRange', params: { min: def.min, max: def.max } };
  return null;
}

/** 规则之间的关系：单笔 ≤ 客户 24 小时 ≤ 全平台 24 小时（否则后面的规则没有意义），返回出问题的参数位置 */
export function crossRuleProblems(params: Partial<Record<AutoRuleCode, Record<string, string>>>): { code: AutoRuleCode; key: string; msg: string }[] {
  const r1 = Number(params.R1?.maxAmount);
  const r2 = Number(params.R2?.maxUser24h);
  const r3 = Number(params.R3?.maxPlatform24h);
  const out: { code: AutoRuleCode; key: string; msg: string }[] = [];
  if (Number.isFinite(r1) && Number.isFinite(r2) && r1 > r2) out.push({ code: 'R2', key: 'maxUser24h', msg: 'autoApprove.errR2LtR1' });
  if (Number.isFinite(r2) && Number.isFinite(r3) && r2 > r3) out.push({ code: 'R3', key: 'maxPlatform24h', msg: 'autoApprove.errR3LtR2' });
  return out;
}
