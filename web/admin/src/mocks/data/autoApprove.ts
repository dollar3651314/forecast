/**
 * v2 提现自动审批的模拟数据（技术方案/v2-提现自动审批.md）：规则配置、修改记录、每笔出金的规则判定（快照）。
 *
 * 故事线（原型的"现在"是 2026-10-14 16:20）：
 * - 第 1 版 2026-10-13 06:00 Amos 打开总开关试运行（单笔上限 1,000、当天 5 笔）；
 * - 第 2 版 2026-10-13 18:40 改回规则表的默认值（单笔 500、当天 3 笔）；
 * - 第 3 版 2026-10-14 08:00 关闭总开关（报价源切换演练，期间全部人工）；
 * - 第 4 版 2026-10-14 09:20 重新打开，并暂时关闭 R8（演示"未启用"）。
 * 每笔出金的判定记录带着判定时那一版的阈值，所以同一条规则在不同记录里的阈值可以不同（例如 R1 第 1 版是 1,000）。
 * 冷静期中的出金只有提交时的预判；冷静期结束时的复判才决定自动通过还是转人工。
 * 字段和真实接口一致（后端 admin/ops/AutoApproveOps.java、wallet/withdraw/AutoApproveEngine.java 的数值键）。
 * R4、R5、R7、R9 的数值按模拟数据里这个客户的出金、入金、注册时长、KYC 算（和出金详情一致）；R2、R3、R8、R10、R12 的数值是演示用的。
 * R13 盈利客户（v1.2）：每个客户有一组累计的已实现盈亏、手续费、隔夜费（默认 310 − 236.80 − 85.45 = −12.25，不盈利），R9 的"盈亏"用同一个已实现盈亏；
 * 累计净盈利超过当时那一版的门槛就自动算 R13 未通过（和 R4、R5 一样不用在计划里手写）。为了演示简单，R13 在模拟的 4 个版本里都有（真实系统是 2026-10-18 迁移 V27 加的）。
 */
import type { AutoApproveChange, AutoApproveSettings, AutoReviewDetail, AutoReviewOutcome, AutoRuleCode, AutoRuleResult, Withdrawal } from '../../api/types';
import { AUTO_RULES } from '../../config/autoApprove';
import { autoSeedCustomerIds, customers, deposits, registeredDaysOf, withdrawals } from './db';
import { fmtTime } from './seed';

type Params = Record<AutoRuleCode, Record<string, string>>;
interface Version {
  version: number;
  at: string;
  by: string;
  master: boolean;
  disabled: AutoRuleCode[];
  params: Params;
}

const defaults = (): Params => Object.fromEntries(AUTO_RULES.map((r) => [r.code, Object.fromEntries(r.params.map((p) => [p.key, p.defaultValue]))])) as Params;
const v1p = defaults();
v1p.R1.maxAmount = '1000';
v1p.R11.maxCount = '5';

const VERSIONS: Version[] = [
  { version: 1, at: '2026-10-13 06:00:00', by: 'Amos', master: true, disabled: [], params: v1p },
  { version: 2, at: '2026-10-13 18:40:00', by: 'Amos', master: true, disabled: [], params: defaults() },
  { version: 3, at: '2026-10-14 08:00:00', by: 'Amos', master: false, disabled: [], params: defaults() },
  { version: 4, at: '2026-10-14 09:20:00', by: 'Amos', master: true, disabled: ['R8'], params: defaults() },
];

/** 当前配置（页面保存会修改这里） */
const latest = VERSIONS[VERSIONS.length - 1];
export const settings: AutoApproveSettings = {
  masterEnabled: latest.master,
  rules: AUTO_RULES.map((r) => ({ code: r.code, enabled: !latest.disabled.includes(r.code), params: { ...latest.params[r.code] } })),
  version: latest.version,
  updatedAt: latest.at,
  updatedBy: latest.by,
};

/** 修改记录（最新的在前） */
export const changes: AutoApproveChange[] = [
  {
    id: 'AAC-4',
    version: 4,
    time: '2026-10-14 09:20:00',
    adminName: 'Amos',
    reason: '热钱包补足余额，恢复自动审批；R8 下注量口径待确认，先关闭',
    changes: [
      { field: 'master', before: 'false', after: 'true' },
      { field: 'R8.enabled', before: 'true', after: 'false' },
    ],
  },
  { id: 'AAC-3', version: 3, time: '2026-10-14 08:00:00', adminName: 'Amos', reason: '报价源切换演练，期间全部人工审核', changes: [{ field: 'master', before: 'true', after: 'false' }] },
  {
    id: 'AAC-2',
    version: 2,
    time: '2026-10-13 18:40:00',
    adminName: 'Amos',
    reason: '试运行一天，改回规则表的默认值',
    changes: [
      { field: 'R1.maxAmount', before: '1000', after: '500' },
      { field: 'R11.maxCount', before: '5', after: '3' },
    ],
  },
  {
    id: 'AAC-1',
    version: 1,
    time: '2026-10-13 06:00:00',
    adminName: 'Amos',
    reason: '打开自动审批试运行',
    changes: [
      { field: 'master', before: 'false', after: 'true' },
      { field: 'R1.maxAmount', before: '500', after: '1000' },
      { field: 'R11.maxCount', before: '3', after: '5' },
    ],
  },
];

// ---------------- 每笔出金的判定 ----------------
const n2 = (v: number) => v.toFixed(2);
const toMs = (t: string) => Date.parse(t.replace(' ', 'T') + 'Z');
const plusMinutes = (t: string, m: number) => fmtTime(toMs(t) + m * 60_000);

function versionAt(time: string): Version | null {
  const ms = toMs(time);
  return [...VERSIONS].reverse().find((x) => toMs(x.at) <= ms) ?? null;
}

/** 生成数值时用到的这笔出金和客户的情况 */
interface Ctx {
  w: Withdrawal;
  v: Version;
  amt: number;
  /** 客户累计入金（和出金详情的"累计入金"一致） */
  deposit: number;
  regDays: number;
  kyc: string;
  /** 这笔之前这个客户已完成的出金笔数、往同一地址的笔数、金额合计（和出金详情的"最近出金"一致） */
  done: number;
  sameAddr: number;
  doneSum: number;
  /** 累计提现：不是驳回、撤销、失败已退回的出金合计，含本笔 */
  withdrawn: number;
  /** R13：累计已实现盈亏、付的手续费、付的隔夜费（R9 的"盈亏"也用 realized） */
  profit: Profit;
}
interface Profit {
  realized: number;
  fee: number;
  swap: number;
}
/** 默认：不盈利（310 − 236.80 − 85.45 = −12.25） */
const NO_PROFIT: Profit = { realized: 310, fee: 236.8, swap: 85.45 };
const netOf = (p: Profit) => Math.round((p.realized - p.fee - p.swap) * 100) / 100;
type Fail = Record<string, string> | ((c: Ctx) => Record<string, string>);
type Fails = Partial<Record<AutoRuleCode, Fail>>;

/** 通过时的数值（和出金详情里的注册时长、KYC、累计入金一致） */
function passValues(c: Ctx): Record<AutoRuleCode, Record<string, string>> {
  const p = c.v.params;
  const dep = c.deposit;
  const principal = Math.max(0, dep - c.doneSum);
  return {
    R1: { amount: c.w.amount, limit: p.R1.maxAmount },
    R2: { used: '0.00', amount: c.w.amount, total: c.w.amount, limit: p.R2.maxUser24h },
    R3: { used: '6420.00', amount: c.w.amount, total: n2(6420 + c.amt), limit: p.R3.maxPlatform24h },
    R4: { count: String(c.done) },
    R5: { count: String(c.sameAddr) },
    R6: { hours: p.R6.hours },
    R7: { days: String(c.regDays), limit: p.R7.days, kyc: c.kyc },
    R8: { volume: n2(dep * 2.4), deposit: n2(dep), ratio: '2.4', limit: p.R8.multiple },
    R9: { withdrawn: n2(c.withdrawn), deposit: n2(dep), pnl: n2(c.profit.realized), cap: n2(dep + c.profit.realized) },
    R10: { profit: '820.00', principal: n2(principal), pct: principal ? ((820 / principal) * 100).toFixed(1) : '—', limitAbs: p.R10.maxProfit7d, limitPct: p.R10.maxProfitPct },
    R11: { count: '1', limit: p.R11.maxCount },
    R12: { balance: '38500.00', need: n2(c.amt - Number(c.w.fee)) },
    R13: { net: n2(netOf(c.profit)), realized: n2(c.profit.realized), fee: n2(c.profit.fee), swap: n2(c.profit.swap), limit: p.R13.maxNetProfit },
  };
}

/** 客户本身的情况决定的未通过（第一笔、新地址、注册不满、KYC 没过、提现超过入金 + 盈亏、盈利客户），不用在下面的计划里手写 */
function implied(c: Ctx): Fails {
  const f: Fails = {};
  if (c.done === 0) f.R4 = {};
  if (c.sameAddr === 0) f.R5 = {};
  if (c.regDays < Number(c.v.params.R7.days) || c.kyc !== 'approved') f.R7 = {};
  if (c.deposit === 0) f.R9 = { withdrawn: n2(c.withdrawn), deposit: '0.00', pnl: '0.00', cap: '0.00' };
  else if (c.withdrawn > c.deposit + c.profit.realized) f.R9 = {};
  if (netOf(c.profit) > Number(c.v.params.R13.maxNetProfit)) f.R13 = {};
  return f;
}

function buildRules(c: Ctx, fails: Fails): AutoRuleResult[] {
  const pass = passValues(c);
  const all: Fails = { ...implied(c), ...fails };
  return AUTO_RULES.map(({ code }) => {
    if (c.v.disabled.includes(code)) return { code, status: 'disabled' as const, values: {} };
    const f = all[code];
    if (!f) return { code, status: 'passed' as const, values: pass[code] };
    const vals = typeof f === 'function' ? f(c) : f;
    return { code, status: 'failed' as const, values: { ...pass[code], ...vals } };
  });
}

// 常用的未通过写法
const R2_OVER: Fail = {};
const R5_NEW: Fail = {};
const R6_PWD: Fail = { event: 'password_change', ago: '1.5' };
const R8_LOW: Fail = (c) => ({ volume: n2(c.deposit * 0.35), ratio: '0.35' });
const r10Profit = (c: Pick<Ctx, 'v' | 'deposit'>) => Math.max(Number(c.v.params.R10.maxProfit7d), (c.deposit * Number(c.v.params.R10.maxProfitPct)) / 100) + 1380;
const R10_HIGH: Fail = (c) => {
  const profit = r10Profit(c);
  return { profit: n2(profit), pct: c.deposit ? ((profit / c.deposit) * 100).toFixed(1) : '—' };
};

interface Plan {
  /** 客户：customers 里的下标，或 autoSeedCustomerIds 里的序号（x） */
  ci?: number;
  x?: number;
  /** 复判没通过的规则；冷静期中的出金这里是预判的结果。不写时按金额推出 R1 / R2 */
  fails?: Fails;
  /** 提交时预判没通过的规则（不写 = 和复判一样） */
  preFails?: Fails;
  /** 功能上线前的出金：没有判定记录 */
  none?: boolean;
  /** R13 用的累计盈亏（不写 = 不盈利的默认值） */
  profit?: (c: Pick<Ctx, 'v' | 'deposit'>) => Profit;
}

const PLANS: Plan[] = [
  // 待审核：触发多条（列表第一条，详情默认显示这笔）
  { ci: 0, fails: { R1: {}, R2: R2_OVER, R5: R5_NEW } },
  // 待审核：只触发 1 条（R1 单笔 800 > 500）
  { ci: 1, fails: { R1: {} } },
  { ci: 3, fails: { R1: {}, R2: R2_OVER } },
  // 冷静期中改了密码：预判只有 R1，复判多了 R6
  { ci: 4, fails: { R1: {}, R6: R6_PWD }, preFails: { R1: {} } },
  { ci: 6, fails: { R1: {} } },
  { ci: 7, fails: { R1: {}, R2: R2_OVER, R11: { count: '4' } } },
  { ci: 8, fails: { R1: {}, R7: { days: '3' } } },
  { ci: 9, fails: { R1: {} } },
  { ci: 10, fails: { R1: {}, R12: (c) => ({ issues: 'hotWallet', balance: '820.00', need: n2(c.amt - Number(c.w.fee)) }) } },
  // 盈利客户：近 7 天盈利超过上限（R10），累计净盈利也 > 0（R13 自动算出来）
  { ci: 11, fails: { R1: {}, R10: R10_HIGH }, profit: (c) => ({ realized: r10Profit(c) + 640, fee: 486.2, swap: 133.8 }) },
  { ci: 12, fails: { R1: {}, R8: R8_LOW } },
  // 只触发 1 条且不是 R1：提交时预判可以自动通过，冷静期里改了密码，复判转人工
  { ci: 13, fails: { R6: R6_PWD }, preFails: {} },
  // 延迟中（人工审核通过后）
  { ci: 2, fails: { R1: {}, R2: R2_OVER, R3: { used: '16800.00', total: '28800.00' } } },
  { ci: 5 },
  { ci: 14 },
  // 冷静期中：只有预判（R9 是否触发按这个客户的入金和出金算）
  { ci: 15, fails: { R1: {} } },
  { ci: 16 },
  { ci: 17 },
  { ci: 18 },
  { ci: 19, fails: { R1: {}, R11: { count: '4' } } },
  // 已完成 / 上链中
  ...[20, 22, 23, 24, 25, 26, 27, 28, 29, 30].map((ci) => ({ ci })),
  // 已驳回：转人工后被驳回
  { ci: 31, fails: { R7: { days: '2' } } },
  // 已紧急取消：功能上线前提交的，没有判定记录
  { ci: 32, none: true },
  { ci: 33 },
  // 补的 6 笔：自动通过（已完成、上链中）、冷静期预判可以通过、总开关关闭时本可以自动通过、正好等于上限 500、
  // 第 1 版时自动通过后上链失败（判定时单笔上限是 1,000，快照里也是 1,000）
  { x: 0, fails: {} },
  { x: 1, fails: {} },
  { x: 2, fails: {} },
  { x: 3, fails: {} },
  { x: 4, fails: {} },
  { x: 5, fails: {} },
  // R13（v1.2）：只触发 R13 的盈利客户——金额 300、老地址、规则都满足，但累计净盈利 1,860 − 212.40 − 47.60 = 1,600 > 门槛 0，转人工
  { x: 6, fails: {}, profit: () => ({ realized: 1860, fee: 212.4, swap: 47.6 }) },
];

function outcomeOf(master: boolean, rules: AutoRuleResult[]): AutoReviewOutcome {
  if (!master) return 'master_off';
  if (rules.some((r) => r.status === 'unknown')) return 'error';
  return rules.some((r) => r.status === 'failed') ? 'manual' : 'auto_approved';
}

const failedOf = (rules: AutoRuleResult[]) => rules.filter((r) => r.status === 'failed' || r.status === 'unknown').map((r) => r.code);

const OUT = ['rejected', 'cancelled', 'failed'];
const sum = (xs: Withdrawal[]) => xs.reduce((s, x) => s + Number(x.amount), 0);

const depositOf = (customerId: number) =>
  customerId === 100482
    ? 20000
    : deposits.filter((d) => d.customerId === customerId && d.status === 'credited').reduce((sum, d) => sum + Number(d.amount), 0);

/** 出金编号 → 判定记录 */
export const reviews = new Map<string, AutoReviewDetail>();

for (const plan of PLANS) {
  const custId = plan.x !== undefined ? autoSeedCustomerIds[plan.x] : customers[plan.ci ?? -1]?.id;
  const cust = customers.find((c) => c.id === custId);
  // 这个客户的第一笔就是要演示的那笔（补的历史出金排在最后）
  const w = cust ? withdrawals.find((x) => x.customerId === cust.id) : undefined;
  if (!cust || !w || plan.none) continue;
  const amt = Number(w.amount);
  const fails: Fails = plan.fails ?? (amt > 2000 ? { R1: {}, R2: R2_OVER } : amt > 500 ? { R1: {} } : {});
  const mine = withdrawals.filter((x) => x.customerId === cust.id && x.id !== w.id);
  const done = mine.filter((x) => x.status === 'completed' && x.submittedAt < w.submittedAt);
  const sameAddr = done.filter((x) => x.chain === w.chain && x.address.toLowerCase() === w.address.toLowerCase());
  const withdrawn = sum(mine.filter((x) => !OUT.includes(x.status) && x.submittedAt <= w.submittedAt)) + amt;
  const ctxAt = (v: Version): Ctx => ({
    w,
    v,
    amt,
    profit: plan.profit ? plan.profit({ v, deposit: depositOf(cust.id) }) : NO_PROFIT,
    deposit: depositOf(cust.id),
    regDays: registeredDaysOf(cust),
    kyc: cust.kycStatus,
    done: done.length,
    sameAddr: sameAddr.length,
    doneSum: sum(done),
    withdrawn,
  });
  const preAt = w.submittedAt;
  const preV = versionAt(preAt);
  if (!preV) continue;
  const preRules = buildRules(ctxAt(preV), plan.preFails ?? fails);
  const pre = { phase: 'precheck' as const, outcome: outcomeOf(preV.master, preRules), failed: failedOf(preRules), decidedAt: preAt };
  if (w.status === 'cooling') {
    reviews.set(w.id, { ...pre, masterEnabled: preV.master, rules: preRules, configVersion: preV.version, configChangedAt: preV.at, configChangedBy: preV.by, precheck: null });
    continue;
  }
  const finalAt = plusMinutes(w.submittedAt, 120);
  const v = versionAt(finalAt)!;
  const rules = buildRules(ctxAt(v), fails);
  reviews.set(w.id, {
    phase: 'final',
    outcome: outcomeOf(v.master, rules),
    failed: failedOf(rules),
    decidedAt: finalAt,
    masterEnabled: v.master,
    rules,
    configVersion: v.version,
    configChangedAt: v.at,
    configChangedBy: v.by,
    precheck: pre,
  });
}
