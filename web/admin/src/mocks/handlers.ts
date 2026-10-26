/**
 * 模拟接口实现。函数名和参数与 src/api 一一对应；操作会修改内存数据并写审计日志。
 * 服务端校验（权限、超管分配、余额调整上限等）在这里也模拟一遍，确保前端不是唯一防线。
 */
import {
  ApiError,
  type ParamItem,
  type ParamSection,
  type AdminInput,
  type AdminSession,
  type AdminUser,
  type AuditLog,
  type AuditQuery,
  type AuditValue,
  type CurrentAdmin,
  type CustomerDetail,
  type CustomerProfileInput,
  type CustomerQuery,
  type CustomerSummary,
  type DashboardStats,
  type DepositQuery,
  type Deposit,
  type KycApplication,
  type KycQuery,
  type LedgerEntry,
  type ManualNotifyInput,
  type MenuBadges,
  type MenuNode,
  type NotifyRecordQuery,
  type NotifyTemplate,
  type Page,
  type PageQuery,
  type ReconQuery,
  type Role,
  type RoleInput,
  type TaskQuery,
  type TemporaryPassword,
  type WithdrawalDetail,
  type AutoApproveChange,
  type AutoApproveSettings,
  type AutoApproveSettingsInput,
  type AutoReviewSummary,
  type WithdrawalQuery,
  type WithdrawalSummary,
  type Withdrawal,
  type SweepBatch,
  type SweepBatchDetail,
  type SweepBatchQuery,
  type SweepChain,
  type SweepImportResult,
  type SweepItem,
  type SweepOverview,
  type SweepPreview,
  type SweepSettings,
  type SweepSettingsInput,
} from '../api/types';
import { GAS_ASSET, SWEEP_CHAINS, SWEEP_MODE, SWEEP_PERM, isSweepNumber, isTronAddress } from '../config/sweep';
import * as sw from './data/sweep';
import { AUTO_RULE_EDIT_PERM, AUTO_RULES, crossRuleProblems, ruleParamProblem } from '../config/autoApprove';
import { MENU, type MenuItemDef } from '../config/menu';
import { PERMISSIONS, SUPER_ADMIN_ROLE } from '../config/permissions';
import { sanitizeAuditPayload } from '../utils/audit';
import { compareAmounts, fromScaled, sumAmounts, toScaled } from '../utils/money';
import { birthDateProblems, nameProblems, nationalityProblems } from '../utils/validators';
import * as db from './data/db';
import { NOW_MS, createRng, minutesAgo, nowString } from './data/seed';
import * as auto from './data/autoApprove';
import { emptyPage, matches, mutate, paginate, respond } from './runtime';

// ============ 工具 ============
function currentAdmin(): AdminUser {
  return db.admins.find((a) => a.id === db.session.adminId) ?? db.admins[0];
}

function permissionsOf(admin: AdminUser): Set<string> {
  const set = new Set<string>();
  admin.roleIds.forEach((rid) => db.roles.find((r) => r.id === rid)?.permissions.forEach((p) => set.add(p)));
  return set;
}

function isSuper(admin: AdminUser): boolean {
  return admin.roleIds.some((rid) => db.roles.find((r) => r.id === rid)?.code === SUPER_ADMIN_ROLE);
}

/** 模拟服务端的权限检查（没有权限返回 403） */
function requirePerm(code: string): void {
  if (!permissionsOf(currentAdmin()).has(code)) throw new ApiError('errors.forbidden');
}

function requireReason(reason: string | undefined): string {
  const r = (reason ?? '').trim();
  if (!r) throw new ApiError('errors.reasonRequired');
  return r;
}

/** 审计里不记录任何密码字段（修复 fxsystem S5）：服务端写入前就过滤 */
const scrub = sanitizeAuditPayload;

let auditSeq = 240001;
function audit(action: string, target: string, highRisk: boolean, reason: string | null, before: AuditLog['before'], after: AuditLog['after']): void {
  db.auditLogs.unshift({
    id: `AU-${auditSeq++}`,
    time: nowString(),
    adminName: currentAdmin().displayName,
    ip: '203.0.113.24',
    action,
    target,
    highRisk,
    result: 'success',
    reason,
    before: scrub(before),
    after: scrub(after),
  });
}

const sortDesc = <T,>(arr: T[], key: (x: T) => string) => [...arr].sort((a, b) => (key(a) < key(b) ? 1 : key(a) > key(b) ? -1 : 0));

// ============ 认证 / 原型身份 ============
export function login(username: string, password: string): Promise<AdminSession> {
  return respond(
    () => {
      if (!username.trim() || !password) throw new ApiError('errors.loginRequired');
      const admin = db.admins.find((a) => a.username === username.trim());
      if (admin && admin.status === 'disabled') throw new ApiError('errors.accountDisabled');
      // 原型：任意密码可登录；未知账号按超级管理员 Amos 登录
      const target = admin ?? db.admins[0];
      db.session.adminId = target.id;
      return {
        token: `proto-${target.id}-${Date.now()}`,
        adminId: target.id,
        username: target.username,
        displayName: target.displayName,
        mustChangePassword: target.mustChangePassword,
      };
    },
    { bypass: true },
  );
}

export function changePassword(oldPassword: string, newPassword: string): Promise<void> {
  return respond(
    () => {
      if (!oldPassword || !newPassword) throw new ApiError('errors.loginRequired');
      if (oldPassword === newPassword) throw new ApiError('errors.samePassword');
      currentAdmin().mustChangePassword = false;
      audit('admin.change_password', currentAdmin().username, false, null, { mustChangePassword: true }, { mustChangePassword: false });
    },
    { bypass: true },
  );
}

export type { CurrentAdmin };

export function getCurrentAdmin(adminId: number): Promise<CurrentAdmin> {
  return respond(
    () => {
      db.session.adminId = adminId;
      const admin = currentAdmin();
      return {
        admin,
        roles: db.roles.filter((r) => admin.roleIds.includes(r.id)),
        permissions: [...permissionsOf(admin)],
        isSuperAdmin: isSuper(admin),
      };
    },
    { bypass: true },
  );
}

export type ProtoIdentity = 'super' | 'ops' | 'risk';
export function switchIdentity(identity: ProtoIdentity): Promise<AdminSession> {
  return respond(
    () => {
      const admin = db.admins.find((a) => a.id === db.identityAdmin[identity])!;
      db.session.adminId = admin.id;
      return { token: `proto-${admin.id}-${Date.now()}`, adminId: admin.id, username: admin.username, displayName: admin.displayName, mustChangePassword: false };
    },
    { bypass: true },
  );
}

// ============ 仪表盘 ============
export function getDashboard(): Promise<DashboardStats> {
  const zero: DashboardStats = {
    todayRegistrations: 0, onlineCustomers: 0, todayDeposit: '0.00', todayDepositCount: 0, todayWithdraw: '0.00', todayWithdrawCount: 0,
    pendingKyc: 0, pendingWithdrawals: 0, failedTasks: 0, openReconciliations: 0, updatedAt: nowString(),
  };
  return respond(
    () => {
      const today = '2026-10-14';
      const todayDeposits = db.deposits.filter((d) => d.status === 'credited' && d.creditedAt?.startsWith(today));
      const todayWd = db.withdrawals.filter((w) => w.status === 'completed' && w.submittedAt.startsWith(today));
      return {
        todayRegistrations: db.customers.filter((c) => c.registeredAt.startsWith(today)).length,
        onlineCustomers: 37,
        todayDeposit: sumAmounts(todayDeposits.map((d) => d.amount)),
        todayDepositCount: todayDeposits.length,
        todayWithdraw: sumAmounts(todayWd.map((w) => w.amount)),
        todayWithdrawCount: todayWd.length,
        pendingKyc: db.kycApplications.filter((k) => k.status === 'pending').length,
        pendingWithdrawals: db.withdrawals.filter((w) => w.status === 'pending').length,
        failedTasks: db.tasks.filter((t) => t.status === 'failed').length,
        openReconciliations: db.reconItems.filter((r) => r.status === 'open').length,
        updatedAt: nowString(),
      };
    },
    { empty: () => zero },
  );
}

export function getMenuBadges(): Promise<MenuBadges> {
  return respond(
    () => ({
      kyc: db.kycApplications.filter((k) => k.status === 'pending').length,
      withdrawals: db.withdrawals.filter((w) => w.status === 'pending').length,
      review: 2,
      riskAlerts: 9,
    }),
    { empty: () => ({ kyc: 0, withdrawals: 0, review: 0, riskAlerts: 0 }) },
  );
}

// ============ 客户 ============
export function listCustomers(q: CustomerQuery): Promise<Page<CustomerSummary>> {
  return respond(
    () => {
      const rows = sortDesc(db.customers, (c) => c.registeredAt).filter(
        (c) => matches(q.keyword, c.name, c.email, c.id) && (!q.status || c.status === q.status) && (!q.kycStatus || c.kycStatus === q.kycStatus),
      );
      return paginate(rows, q.page, q.pageSize);
    },
    { empty: () => emptyPage(q.page, q.pageSize) },
  );
}

function ledgerFor(c: CustomerSummary, empty: boolean): LedgerEntry[] {
  if (empty) return [];
  const rng = createRng(c.id);
  // forecast S1 只有充值、提现、退回（S2 起加下注、派彩、退款、补偿）
  const types: LedgerEntry['type'][] = ['withdraw', 'deposit', 'withdraw', 'refund', 'deposit', 'withdraw', 'deposit', 'refund', 'withdraw', 'deposit', 'withdraw', 'deposit'];
  let bal = toScaled(c.balance) ?? 0n;
  // 单笔金额不超过余额的 1/15（余额为 0 时按 500 计），倒推 11 笔后余额仍为正
  const cap = Number((bal > 50000n ? bal : 50000n) / 15n) / 100;
  return types.map((type, i) => {
    const raw = type === 'deposit' ? rng.amount(500, 12000) : rng.amount(0, cap);
    const negative = type === 'withdraw';
    const amount = (negative ? '-' : '') + raw;
    const entry: LedgerEntry = {
      id: `LG-${c.id}-${12 - i}`,
      type,
      amount,
      balanceAfter: fromScaled(bal),
      ref: type === 'deposit' ? 'DP-20261001-000412' : 'WD-20261010-000101',
      time: minutesAgo(i * 300 + 15),
    };
    bal -= toScaled(amount) ?? 0n;
    if (bal < 0n) bal = 0n;
    return entry;
  });
}

export function getCustomer(id: number): Promise<CustomerDetail> {
  return mutateRead(() => {
    const c = db.customerById(id);
    if (!c) throw new ApiError('errors.notFound');
    const kyc = db.kycApplications.find((k) => k.customerId === id);
    const frozenWd = sumAmounts(db.withdrawals.filter((w) => w.customerId === id && ['cooling', 'pending', 'delayed', 'broadcasting'].includes(w.status)).map((w) => w.amount));
    const avail = fromScaled((toScaled(c.balance) ?? 0n) - (toScaled(frozenWd) ?? 0n));
    const [first, ...rest] = c.name.split(' ');
    const edited = db.customerProfiles.get(id);
    return {
      ...c,
      lastLoginIp: db.lastLoginIpOf(c),
      lastLoginIpAt: c.lastLoginAt,
      firstName: edited ? edited.firstName : rest.length ? first : c.name.slice(1) || c.name,
      middleName: edited ? edited.middleName : '',
      lastName: edited ? edited.lastName : rest.length ? rest.join(' ') : c.name.slice(0, 1),
      birthDate: edited ? edited.birthDate : kyc?.birthDate ?? '1990-05-12',
      frozenReason: c.status === 'frozen' ? '客户来电称手机丢失，申请冻结' : null,
      kyc: {
        status: c.kycStatus,
        docType: kyc?.docType ?? null,
        docNumber: kyc?.docNumber ?? null,
        reviewedAt: kyc?.reviewedAt ?? null,
        rejectReason: kyc?.rejectReason ?? null,
      },
      // 原型的客户还没有下注（S4 起），下注中为 0
      account: { balance: c.balance, available: compareAmounts(avail, '0') < 0 ? '0.00' : avail, betLocked: '0.00', frozenWithdraw: frozenWd },
      ledger: ledgerFor(c, false),
    };
  });
}

/** 详情类接口：空数据场景下返回的明细列表为空 */
function mutateRead<T extends object>(produce: () => T): Promise<T> {
  return respond(produce, {
    empty: () => {
      const v = produce() as T & { positions?: unknown[]; ledger?: unknown[]; recentDeposits?: unknown[]; recentWithdrawals?: unknown[] };
      const acc = (v as { account?: CustomerDetail['account'] }).account;
      if (acc) Object.assign(acc, { usedMargin: '0.00', floatingPnl: '0.00', frozenWithdraw: '0.00', available: acc.balance });
      if (v.positions) v.positions = [];
      if (v.ledger) v.ledger = [];
      if ((v as { closedPositions?: unknown[] }).closedPositions) (v as { closedPositions?: unknown[] }).closedPositions = [];
      if (v.recentDeposits) v.recentDeposits = [];
      if (v.recentWithdrawals) v.recentWithdrawals = [];
      return v;
    },
  });
}

export function setCustomerFrozen(id: number, frozen: boolean, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('customer:freeze');
    const r = requireReason(reason);
    const c = db.customerById(id);
    if (!c) throw new ApiError('errors.notFound');
    const before = c.status;
    c.status = frozen ? 'frozen' : 'active';
    audit(frozen ? 'customer.freeze' : 'customer.unfreeze', String(id), true, r, { status: before }, { status: c.status });
  });
}

/** 字段错误：和真实接口一样一次返回全部（apiFieldCode 的翻译键） */
function fieldError(fields: { field: string; code: string }[]): ApiError {
  return new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields });
}

/** 翻译键 validation.xxx → 后端的字段错误码（和后端 CustomerAdmin 一致） */
const FIELD_CODE: Record<string, string> = {
  'validation.required': 'required',
  'validation.nameTooLong': 'nameTooLong',
  'validation.nameChars': 'nameChars',
  'validation.dateIncomplete': 'dateFormat',
  'validation.yearFormat': 'dateFormat',
  'validation.dateInvalid': 'dateInvalid',
  'validation.ageUnder18': 'ageUnder18',
  'validation.nationalityFormat': 'nationalityInvalid',
};

/** 编辑客户资料（v2 E1，高危）：校验和客户端注册一致；KYC 已通过也允许改，审计里标出 */
export function updateCustomerProfile(id: number, input: CustomerProfileInput, reason: string): Promise<CustomerDetail> {
  return mutate(() => {
    requirePerm('customer:edit');
    const r = requireReason(reason);
    const c = db.customerById(id);
    if (!c) throw new ApiError('errors.notFound');
    const problems: { field: string; code: string }[] = [];
    const push = (field: string, keys: string[]) => keys.forEach((k) => problems.push({ field, code: FIELD_CODE[k] ?? k }));
    push('firstName', nameProblems(input.firstName, true));
    push('middleName', nameProblems(input.middleName, false));
    push('lastName', nameProblems(input.lastName, true));
    push('birthDate', birthDateProblems(input.birthDate, new Date(NOW_MS)));
    push('nationality', nationalityProblems(input.nationality));
    if (problems.length) throw fieldError(problems);
    const kyc = db.kycApplications.find((k) => k.customerId === id);
    const [first, ...rest] = c.name.split(' ');
    const old = db.customerProfiles.get(id) ?? {
      firstName: rest.length ? first : c.name.slice(1) || c.name,
      middleName: '',
      lastName: rest.length ? rest.join(' ') : c.name.slice(0, 1),
      birthDate: kyc?.birthDate ?? '1990-05-12',
    };
    const next = { firstName: input.firstName.trim(), middleName: input.middleName.trim(), lastName: input.lastName.trim(), birthDate: input.birthDate.trim() };
    const before: Record<string, AuditValue> = {};
    const after: Record<string, AuditValue> = {};
    (Object.keys(next) as (keyof typeof next)[]).forEach((k) => {
      if (old[k] !== next[k]) {
        before[k] = old[k];
        after[k] = next[k];
      }
    });
    if (c.nationality !== input.nationality.trim()) {
      before.nationality = c.nationality;
      after.nationality = input.nationality.trim();
    }
    if (c.kycStatus === 'approved') {
      after.kycApproved = true;
      if (['firstName', 'middleName', 'lastName', 'birthDate'].some((k) => k in after)) after.kycMismatch = 'KYC 已通过，修改后和证件不一致';
    }
    db.customerProfiles.set(id, next);
    c.nationality = input.nationality.trim();
    c.name = [next.lastName, next.firstName].every((x) => /^[\u4e00-\u9fff]+$/.test(x))
      ? `${next.lastName}${next.firstName}`
      : [next.firstName, next.middleName, next.lastName].filter(Boolean).join(' ');
    audit('customer.edit', String(id), true, r, before, after);
  }).then(() => getCustomer(id));
}

// ============ KYC ============
export function listKyc(q: KycQuery): Promise<Page<KycApplication>> {
  return respond(
    () => paginate(db.kycApplications.filter((k) => (!q.status || k.status === q.status) && matches(q.keyword, k.name, k.email, k.customerId, k.id)), q.page, q.pageSize),
    { empty: () => emptyPage(q.page, q.pageSize) },
  );
}

export function getKyc(id: string): Promise<KycApplication> {
  return respond(() => {
    const k = db.kycApplications.find((x) => x.id === id);
    if (!k) throw new ApiError('errors.notFound');
    return k;
  });
}

export function reviewKyc(id: string, approve: boolean, reason?: string): Promise<void> {
  return mutate(() => {
    requirePerm('kyc:review');
    const k = db.kycApplications.find((x) => x.id === id);
    if (!k) throw new ApiError('errors.notFound');
    if (k.status !== 'pending') throw new ApiError('errors.stateChanged');
    const r = approve ? null : requireReason(reason);
    k.status = approve ? 'approved' : 'rejected';
    k.reviewer = currentAdmin().displayName;
    k.reviewedAt = nowString();
    k.rejectReason = r;
    const c = db.customerById(k.customerId);
    if (c) c.kycStatus = k.status;
    audit(approve ? 'kyc.approve' : 'kyc.reject', id, false, r, { status: 'pending' }, { status: k.status });
  });
}

// ============ 资金 ============
export function listDeposits(q: DepositQuery): Promise<Page<Deposit>> {
  return respond(
    () => paginate(db.deposits.filter((d) => (!q.chain || d.chain === q.chain) && (!q.status || d.status === q.status) && matches(q.keyword, d.customerName, d.email, d.txHash, d.customerId, d.id)), q.page, q.pageSize),
    { empty: () => emptyPage(q.page, q.pageSize) },
  );
}

const OPEN_WD = ['cooling', 'pending', 'delayed'];

/** v2 自动审批：出金加上规则判定摘要；自动通过的没有审核人（技术方案/v2-提现自动审批.md 第 3 节） */
function withAutoReview(w: Withdrawal): Withdrawal {
  const r = auto.reviews.get(w.id);
  if (!r) return { ...w, autoReview: null };
  const summary: AutoReviewSummary = { phase: r.phase, outcome: r.outcome, failed: r.failed, decidedAt: r.decidedAt };
  return { ...w, autoReview: summary, reviewer: r.outcome === 'auto_approved' ? null : w.reviewer };
}

function matchesAutoFilter(w: Withdrawal, f: WithdrawalQuery['autoReview']): boolean {
  if (!f) return true;
  const r = w.autoReview;
  if (f === 'none') return !r;
  if (!r) return false;
  if (f === 'auto_approved' || f === 'manual' || f === 'master_off') return r.outcome === f || (f === 'manual' && r.outcome === 'error');
  return r.failed.includes(f);
}

export function listWithdrawals(q: WithdrawalQuery): Promise<Page<Withdrawal>> {
  return respond(
    () => {
      const rows = db.withdrawals.map(withAutoReview).filter((w) => {
        if (!matchesAutoFilter(w, q.autoReview)) return false;
        if (q.status === 'open' ? !OPEN_WD.includes(w.status) : q.status && w.status !== q.status) return false;
        if (q.chain && w.chain !== q.chain) return false;
        if (q.minAmount && compareAmounts(w.amount, q.minAmount) < 0) return false;
        if (q.maxAmount && compareAmounts(w.amount, q.maxAmount) > 0) return false;
        return matches(q.keyword, w.customerName, w.email, w.customerId, w.id);
      });
      // 待审核排在前面，其次按提交时间倒序
      const rank = (w: Withdrawal) => (w.status === 'pending' ? 0 : w.status === 'delayed' ? 1 : w.status === 'cooling' ? 2 : 3);
      rows.sort((a, b) => rank(a) - rank(b) || (a.submittedAt < b.submittedAt ? 1 : -1));
      return paginate(rows, q.page, q.pageSize);
    },
    { empty: () => emptyPage(q.page, q.pageSize) },
  );
}

export function getWithdrawalSummary(): Promise<WithdrawalSummary> {
  const group = (pred: (w: Withdrawal) => boolean) => {
    const rows = db.withdrawals.filter(pred);
    return { count: rows.length, amount: sumAmounts(rows.map((w) => w.amount)) };
  };
  const zero = { count: 0, amount: '0.00' };
  return respond(
    () => ({
      pending: group((w) => w.status === 'pending'),
      delayed: group((w) => w.status === 'delayed'),
      cooling: group((w) => w.status === 'cooling'),
      completedToday: group((w) => w.status === 'completed' && w.submittedAt.startsWith('2026-10-14')),
    }),
    { empty: () => ({ pending: zero, delayed: zero, cooling: zero, completedToday: zero }) },
  );
}

export function getWithdrawal(id: string): Promise<WithdrawalDetail> {
  return mutateRead(() => {
    const w = db.withdrawals.find((x) => x.id === id);
    if (!w) throw new ApiError('errors.notFound');
    const c = db.customerById(w.customerId)!;
    const deps = db.deposits.filter((d) => d.customerId === c.id && d.status === 'credited').slice(0, 3);
    const prevWd = db.withdrawals.filter((x) => x.customerId === c.id && x.id !== id && x.status === 'completed').slice(0, 3);
    const regDays = Math.max(1, Math.round((Date.UTC(2026, 9, 14) - Date.parse(c.registeredAt.replace(' ', 'T') + 'Z')) / 86_400_000));
    return {
      ...withAutoReview(w),
      autoReviewDetail: auto.reviews.get(w.id) ?? null,
      kycStatus: c.kycStatus,
      registeredDays: w.customerId === 100482 ? 30 : regDays,
      totalDeposit: w.customerId === 100482 ? '20000.00' : sumAmounts(db.deposits.filter((d) => d.customerId === c.id && d.status === 'credited').map((d) => d.amount)),
      balance: c.balance,
      recentDeposits:
        w.customerId === 100482
          ? [
              { time: '2026-10-13 20:14:00', chain: 'TRC20', amount: '12000.00' },
              { time: '2026-09-28 11:02:00', chain: 'TRC20', amount: '8000.00' },
            ]
          : deps.map((d) => ({ time: d.creditedAt ?? d.detectedAt, chain: d.chain, amount: d.amount })),
      recentWithdrawals: prevWd.map((x) => ({ time: x.submittedAt, chain: x.chain, amount: x.amount })),
    };
  });
}

export function approveWithdrawal(id: string): Promise<Withdrawal> {
  return mutate(() => {
    requirePerm('withdrawal:review');
    const w = db.withdrawals.find((x) => x.id === id);
    if (!w) throw new ApiError('errors.notFound');
    if (w.status !== 'pending') throw new ApiError('errors.stateChanged');
    const large = compareAmounts(w.amount, db.systemConfigs.find((c) => c.key === 'withdraw.largeThreshold')!.value) >= 0;
    w.status = large ? 'delayed' : 'broadcasting';
    w.delayRemainingMinutes = large ? 360 : null;
    w.reviewer = currentAdmin().displayName;
    audit('withdrawal.approve', id, false, null, { status: 'pending' }, { status: w.status });
    return w;
  });
}

export function rejectWithdrawal(id: string, reason: string): Promise<Withdrawal> {
  return mutate(() => {
    requirePerm('withdrawal:review');
    const r = requireReason(reason);
    const w = db.withdrawals.find((x) => x.id === id);
    if (!w) throw new ApiError('errors.notFound');
    if (w.status !== 'pending') throw new ApiError('errors.stateChanged');
    w.status = 'rejected';
    w.reason = r;
    w.reviewer = currentAdmin().displayName;
    audit('withdrawal.reject', id, false, r, { status: 'pending' }, { status: 'rejected' });
    return w;
  });
}

export function emergencyCancelWithdrawal(id: string, reason: string): Promise<Withdrawal> {
  return mutate(() => {
    requirePerm('withdrawal:emergency_cancel');
    const r = requireReason(reason);
    const w = db.withdrawals.find((x) => x.id === id);
    if (!w) throw new ApiError('errors.notFound');
    if (w.status !== 'delayed') throw new ApiError('errors.stateChanged');
    w.status = 'cancelled';
    w.reason = r;
    w.delayRemainingMinutes = null;
    audit('withdrawal.emergency_cancel', id, true, r, { status: 'delayed' }, { status: 'cancelled' });
    return w;
  });
}

// ============ v2 提现自动审批（技术方案/v2-提现自动审批.md；和后端 admin/ops/AutoApproveOps 同一口径） ============
/** GET /api/admin/withdraw-auto-approve */
export function getAutoApproveSettings(): Promise<AutoApproveSettings> {
  return respond(() => auto.settings);
}

/** GET /api/admin/withdraw-auto-approve/changes：最近 20 次修改 */
export function listAutoApproveChanges(): Promise<AutoApproveChange[]> {
  return respond(() => auto.changes.slice(0, 20), { empty: () => [] });
}

/**
 * PUT /api/admin/withdraw-auto-approve（高危，权限 withdrawal:auto_rule_edit）：一次保存总开关和全部规则，必须填原因，写审计。
 * expectedVersion 不是当前版本时和后端一样返回 409 CONFIG_CHANGED；没有变化返回 NO_CHANGE。
 */
export function updateAutoApproveSettings(input: AutoApproveSettingsInput): Promise<AutoApproveSettings> {
  return mutate(() => {
    requirePerm(AUTO_RULE_EDIT_PERM);
    const reason = requireReason(input.reason);
    const cur = auto.settings;
    if (input.expectedVersion !== cur.version) throw new ApiError('errors.configChanged', undefined, { status: 409, code: 'CONFIG_CHANGED' });
    const diff: AutoApproveChange['changes'] = [];
    if (input.masterEnabled !== cur.masterEnabled) diff.push({ field: 'master', before: String(cur.masterEnabled), after: String(input.masterEnabled) });
    const nextParams: Record<string, Record<string, string>> = {};
    for (const def of AUTO_RULES) {
      const before = cur.rules.find((r) => r.code === def.code)!;
      const after = input.rules.find((r) => r.code === def.code);
      if (!after) throw new ApiError('errors.autoApproveInvalid');
      if (after.enabled !== before.enabled) diff.push({ field: `${def.code}.enabled`, before: String(before.enabled), after: String(after.enabled) });
      nextParams[def.code] = {};
      for (const p of def.params) {
        const v = (after.params[p.key] ?? '').trim();
        if (ruleParamProblem(p, v)) throw new ApiError('errors.autoApproveInvalid');
        const norm = String(Number(v));
        nextParams[def.code][p.key] = norm;
        if (Number(norm) !== Number(before.params[p.key])) diff.push({ field: `${def.code}.${p.key}`, before: before.params[p.key], after: norm });
      }
    }
    if (crossRuleProblems(nextParams).length) throw new ApiError('errors.autoApproveInvalid');
    if (!diff.length) throw new ApiError('errors.autoApproveNoChange', undefined, { status: 400, code: 'NO_CHANGE' });
    const time = nowString();
    const admin = currentAdmin().displayName;
    cur.masterEnabled = input.masterEnabled;
    cur.rules = AUTO_RULES.map((d) => ({ code: d.code, enabled: input.rules.find((r) => r.code === d.code)!.enabled, params: nextParams[d.code] }));
    cur.version += 1;
    cur.updatedAt = time;
    cur.updatedBy = admin;
    auto.changes.unshift({ id: `AAC-${cur.version}`, version: cur.version, time, adminName: admin, reason, changes: diff });
    audit(
      'withdrawal.auto_rule_update',
      `v${cur.version}`,
      true,
      reason,
      Object.fromEntries(diff.map((d) => [d.field, d.before])),
      Object.fromEntries(diff.map((d) => [d.field, d.after])),
    );
    return cur;
  });
}

export function listRecon(q: ReconQuery) {
  return respond(
    () => paginate(sortDesc(db.reconItems, (r) => r.detectedAt).filter((r) => !q.status || r.status === q.status), q.page, q.pageSize),
    { empty: () => emptyPage<(typeof db.reconItems)[number]>(q.page, q.pageSize) },
  );
}

/** 热钱包余额：下限读系统配置（和后端一样，0 = 不告警） */
export function listHotWallets() {
  return respond(
    () =>
      db.hotWallets.map((h) => {
        const threshold = db.systemConfigs.find((c) => c.key === db.hotWalletMinKey(h.chain, h.gas))?.value ?? '0';
        const status = compareAmounts(threshold, '0') <= 0 ? 'off' : h.balance === null ? 'unknown' : compareAmounts(h.balance, threshold) < 0 ? 'low' : 'ok';
        return { ...h, threshold, status } as const;
      }),
    { empty: () => [] },
  );
}

export function resolveRecon(id: string, note: string): Promise<void> {
  return mutate(() => {
    requirePerm('recon:resolve');
    const n = requireReason(note);
    const r = db.reconItems.find((x) => x.id === id);
    if (!r) throw new ApiError('errors.notFound');
    if (r.status !== 'open') throw new ApiError('errors.issueNotOpen');
    r.status = 'resolved';
    r.resolvedAt = nowString();
    r.note = n;
    r.handler = currentAdmin().displayName;
    audit('recon.resolve', id, false, n, { status: 'open' }, { status: 'resolved' });
  });
}

export function listTasks(q: TaskQuery) {
  return respond(
    () => paginate(db.tasks.filter((t) => (!q.type || t.type === q.type) && (!q.status || t.status === q.status)), q.page, q.pageSize),
    { empty: () => emptyPage<(typeof db.tasks)[number]>(q.page, q.pageSize) },
  );
}

export function retryTask(id: string): Promise<void> {
  return mutate(() => {
    requirePerm('task:retry');
    const t = db.tasks.find((x) => x.id === id);
    if (!t) throw new ApiError('errors.notFound');
    if (t.status !== 'failed') throw new ApiError('errors.stateChanged');
    t.status = 'pending';
    t.nextRetryAt = nowString();
    t.updatedAt = nowString();
    audit('task.retry', id, false, null, { status: 'failed', attempts: t.attempts }, { status: 'pending', attempts: t.attempts });
  });
}

// ============ 通知 ============
export function listTemplates() {
  return respond(() => db.templates, { empty: () => [] as NotifyTemplate[] });
}

export function updateTemplate(id: string, patch: Partial<Pick<NotifyTemplate, 'titleZh' | 'bodyZh' | 'titleEn' | 'bodyEn' | 'titleTw' | 'bodyTw' | 'enabled'>>): Promise<void> {
  return mutate(() => {
    requirePerm('notify_template:edit');
    const t = db.templates.find((x) => x.id === id);
    if (!t) throw new ApiError('errors.notFound');
    // 内置模板不能禁用（禁用会导致出金结算等流程失败）
    if (t.builtIn && patch.enabled === false) throw new ApiError('errors.builtInCannotDisable');
    const before = { titleZh: t.titleZh, bodyZh: t.bodyZh, titleEn: t.titleEn, bodyEn: t.bodyEn, enabled: t.enabled };
    Object.assign(t, patch, { updatedAt: nowString() });
    audit('notify.template_update', t.code, false, null, before, { titleZh: t.titleZh, bodyZh: t.bodyZh, titleEn: t.titleEn, bodyEn: t.bodyEn, enabled: t.enabled });
  });
}

export function searchCustomers(keyword: string): Promise<CustomerSummary[]> {
  return respond(() => db.customers.filter((c) => matches(keyword, c.name, c.email, c.id)).slice(0, 20), { bypass: true });
}

export function sendManualNotification(input: ManualNotifyInput): Promise<void> {
  return mutate(() => {
    requirePerm('notify:send');
    const r = requireReason(input.reason);
    const c = db.customerById(input.customerId);
    if (!c) throw new ApiError('errors.notFound');
    input.channels.forEach((ch) =>
      db.notifyRecords.unshift({
        id: `NT-${553200 + db.notifyRecords.length}`,
        customerId: c.id,
        customerName: c.name,
        source: 'manual',
        templateCode: null,
        channel: ch,
        title: input.title,
        status: 'sent',
        error: null,
        operator: currentAdmin().displayName,
        time: nowString(),
      }),
    );
    audit('notify.manual_send', String(c.id), true, r, null, { channels: input.channels, title: input.title });
  });
}

export function listNotifyRecords(q: NotifyRecordQuery) {
  return respond(
    () => paginate(db.notifyRecords.filter((n) => (!q.channel || n.channel === q.channel) && (!q.status || n.status === q.status) && matches(q.keyword, n.customerName, n.customerId, n.title)), q.page, q.pageSize),
    { empty: () => emptyPage<(typeof db.notifyRecords)[number]>(q.page, q.pageSize) },
  );
}

// ============ 系统：管理员 ============
export function listAdmins(q: PageQuery & { keyword?: string }) {
  return respond(() => paginate(db.admins.filter((a) => matches(q.keyword, a.username, a.displayName, a.email)), q.page, q.pageSize), {
    empty: () => emptyPage<AdminUser>(q.page, q.pageSize),
  });
}

function guardSuperAssignment(roleIds: string[], existing?: AdminUser): void {
  const superRole = db.roles.find((r) => r.code === SUPER_ADMIN_ROLE)!;
  const addsSuper = roleIds.includes(superRole.id) && !(existing?.roleIds.includes(superRole.id) ?? false);
  // 修复 fxsystem S4：只有超级管理员能分配超级管理员角色（包括给自己）
  if (addsSuper && !isSuper(currentAdmin())) throw new ApiError('errors.cannotAssignSuper');
}

/** 原型演示用的临时密码（真实接口由后端生成，只返回一次） */
function demoTempPassword(): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';
  let out = '';
  for (let i = 0; i < 14; i++) out += chars[Math.floor(Math.random() * chars.length)];
  return out + '7k';
}

export function createAdmin(input: AdminInput): Promise<TemporaryPassword> {
  return mutate(() => {
    requirePerm('admin:edit');
    if (db.admins.some((a) => a.username === input.username)) throw new ApiError('errors.usernameTaken');
    guardSuperAssignment(input.roleIds);
    const a: AdminUser = { id: Math.max(...db.admins.map((x) => x.id)) + 1, ...input, status: 'active', mustChangePassword: true, lastLoginAt: null, createdAt: nowString() };
    db.admins.push(a);
    audit('admin.create', input.username, true, null, null, { username: input.username, roleIds: input.roleIds, password: '(临时密码)' });
    return { id: a.id, temporaryPassword: demoTempPassword() };
  });
}

export function updateAdmin(id: number, input: AdminInput): Promise<void> {
  return mutate(() => {
    requirePerm('admin:edit');
    const a = db.admins.find((x) => x.id === id);
    if (!a) throw new ApiError('errors.notFound');
    guardSuperAssignment(input.roleIds, a);
    const before = { displayName: a.displayName, email: a.email, roleIds: a.roleIds };
    Object.assign(a, { displayName: input.displayName, email: input.email, roleIds: input.roleIds });
    audit('admin.update', a.username, true, null, before, { displayName: a.displayName, email: a.email, roleIds: a.roleIds });
  });
}

export function setAdminStatus(id: number, active: boolean, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('admin:edit');
    const r = requireReason(reason);
    const a = db.admins.find((x) => x.id === id);
    if (!a) throw new ApiError('errors.notFound');
    if (a.id === currentAdmin().id) throw new ApiError('errors.cannotDisableSelf');
    if (isSuper(a) && !isSuper(currentAdmin())) throw new ApiError('errors.forbidden');
    a.status = active ? 'active' : 'disabled';
    audit(active ? 'admin.enable' : 'admin.disable', a.username, true, r, { status: active ? 'disabled' : 'active' }, { status: a.status });
  });
}

export function resetAdminPassword(id: number, reason: string): Promise<TemporaryPassword> {
  return mutate(() => {
    requirePerm('admin:edit');
    const r = requireReason(reason);
    const a = db.admins.find((x) => x.id === id);
    if (!a) throw new ApiError('errors.notFound');
    if (isSuper(a) && !isSuper(currentAdmin())) throw new ApiError('errors.forbidden');
    a.mustChangePassword = true;
    // 临时密码只在结果里返回一次（后台方案第 7 节），审计里不出现密码
    audit('admin.reset_password', a.username, true, r, { mustChangePassword: false, password: '***' }, { mustChangePassword: true, password: '***' });
    return { id: a.id, temporaryPassword: demoTempPassword() };
  });
}

// ============ 系统：角色 / 菜单 / 权限 ============
export function listRoles() {
  return respond(
    () => db.roles.map((r) => ({ ...r, memberCount: db.admins.filter((a) => a.roleIds.includes(r.id)).length })),
    { empty: () => [] as Role[] },
  );
}

export function saveRole(id: string | null, input: RoleInput): Promise<void> {
  return mutate(() => {
    requirePerm('role:edit');
    if (id) {
      const r = db.roles.find((x) => x.id === id);
      if (!r) throw new ApiError('errors.notFound');
      if (r.builtIn) throw new ApiError('errors.builtInRole');
      const before = { nameZh: r.nameZh, permissions: r.permissions };
      Object.assign(r, input);
      audit('role.update', r.nameZh, true, null, before, { nameZh: r.nameZh, permissions: r.permissions });
    } else {
      if (input.code === SUPER_ADMIN_ROLE || db.roles.some((r) => r.code === input.code)) throw new ApiError('errors.roleCodeTaken');
      db.roles.push({ id: `R-${db.roles.length + 10}`, ...input, builtIn: false, memberCount: 0 });
      audit('role.create', input.nameZh, true, null, null, { code: input.code, permissions: input.permissions });
    }
  });
}

export function deleteRole(id: string): Promise<void> {
  return mutate(() => {
    requirePerm('role:edit');
    const r = db.roles.find((x) => x.id === id);
    if (!r) throw new ApiError('errors.notFound');
    if (r.builtIn) throw new ApiError('errors.builtInRole');
    if (db.admins.some((a) => a.roleIds.includes(id))) throw new ApiError('errors.roleInUse');
    db.roles.splice(db.roles.indexOf(r), 1);
    audit('role.delete', r.nameZh, true, null, { code: r.code }, null);
  });
}

function toMenuNodes(items: MenuItemDef[]): MenuNode[] {
  return items.map((m, i) => ({
    key: m.key,
    path: m.path ?? null,
    labelKey: m.labelKey,
    permission: m.permission ?? null,
    order: (i + 1) * 10,
    visible: true,
    children: m.children ? toMenuNodes(m.children) : undefined,
  }));
}
const menuTree: MenuNode[] = toMenuNodes(MENU);

export function listMenus() {
  return respond(() => menuTree, { empty: () => [] as MenuNode[] });
}

export function updateMenu(key: string, patch: Pick<MenuNode, 'order'> & Partial<Pick<MenuNode, 'visible'>>): Promise<void> {
  return mutate(() => {
    requirePerm('menu:edit');
    const walk = (nodes: MenuNode[]): MenuNode | undefined => nodes.reduce<MenuNode | undefined>((f, n) => f ?? (n.key === key ? n : walk(n.children ?? [])), undefined);
    const n = walk(menuTree);
    if (!n) throw new ApiError('errors.notFound');
    const before = { order: n.order, visible: n.visible };
    n.order = patch.order;
    if (patch.visible !== undefined) n.visible = patch.visible;
    audit('menu.update', key, false, null, before, { order: n.order, visible: n.visible });
  });
}

export function listPermissions() {
  return respond(() => [...PERMISSIONS], { empty: () => [] });
}

// ============ 系统：审计、配置 ============
export function listAuditLogs(q: AuditQuery) {
  return respond(
    () =>
      paginate(
        db.auditLogs
          .filter((l) => (!q.action || l.action === q.action) && (!q.admin || l.adminName === q.admin) && (!q.highRiskOnly || l.highRisk) && matches(q.keyword, l.target, l.adminName, l.reason))
          .map((l) => ({ ...l, before: scrub(l.before), after: scrub(l.after) })),
        q.page,
        q.pageSize,
      ),
    { empty: () => emptyPage<AuditLog>(q.page, q.pageSize) },
  );
}

export function listAuditActions(): Promise<string[]> {
  return respond(() => [...new Set(db.auditLogs.map((l) => l.action))].sort(), { bypass: true });
}

export function listSystemConfigs() {
  return respond(() => db.systemConfigs, { empty: () => [] as typeof db.systemConfigs });
}

const WITHDRAW_CHAINS = ['ERC20', 'TRC20', 'BSC'];
const cfgValue = (k: string) => db.systemConfigs.find((x) => x.key === k)?.value;
const paramValue = (k: string) => db.params.find((x) => x.key === k)?.value;

/**
 * 单笔最小提现必须大于每条链的提现手续费（RQ-16、AC-61，和后端 SystemService 一致）。最小金额 S3 起在参数设置（db.params），手续费在系统配置。
 * 一次列出全部不满足的链。
 */
function checkMinAboveFees(key: string, value: string): void {
  const feeChain = WITHDRAW_CHAINS.find((ch) => key === `withdraw.fee.${ch}`);
  if (key !== 'withdraw.minAmount' && !feeChain) return;
  const min = key === 'withdraw.minAmount' ? value : paramValue('withdraw.minAmount');
  const bad = WITHDRAW_CHAINS.filter((ch) => !feeChain || ch === feeChain)
    .filter((ch) => {
      const fee = ch === feeChain ? value : cfgValue(`withdraw.fee.${ch}`);
      return min !== undefined && fee !== undefined && compareAmounts(min, fee) <= 0;
    })
    .map((ch) => ({ field: ch, code: feeChain ? 'feeNotBelowMin' : 'minNotAboveFee' }));
  if (bad.length) throw fieldError(bad);
}

export function updateSystemConfig(key: string, value: string, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('config:edit');
    const r = requireReason(reason);
    const c = db.systemConfigs.find((x) => x.key === key);
    if (!c) throw new ApiError('errors.notFound');
    checkMinAboveFees(key, value);
    const before = c.value;
    c.value = value;
    c.updatedAt = nowString();
    audit('config.update', key, true, r, { value: before }, { value });
  });
}

/** 参数设置（forecast S3）：和后端一样按组要权限点（fee:edit / betting_params:edit） */
const PARAM_PERM: Record<ParamSection, string> = { fee: 'fee:edit', betting: 'betting_params:edit' };

export function listParams(section: ParamSection): Promise<ParamItem[]> {
  return respond(
    () => {
      requirePerm(PARAM_PERM[section]);
      return db.params.filter((p) => p.section === section);
    },
    { empty: () => [] as ParamItem[] },
  );
}

export function updateParam(section: ParamSection, key: string, value: string, reason: string, expected?: string): Promise<void> {
  return mutate(() => {
    requirePerm(PARAM_PERM[section]);
    const r = requireReason(reason);
    const p = db.params.find((x) => x.key === key && x.section === section);
    if (!p) throw new ApiError('errors.notFound');
    if (expected !== undefined && compareAmounts(expected, p.value) !== 0) {
      throw new ApiError('errors.configChanged', undefined, { status: 409, code: 'CONFIG_CHANGED' });
    }
    checkMinAboveFees(key, value);
    const before = p.value;
    p.value = value;
    p.updatedAt = nowString();
    audit('params.update', key, true, r, { value: before }, { value });
  });
}

// ============ v2 资金归集（原型，技术方案/v2-半自动归集.md 路线 (b)） ============
// 权限借用已有的（config/sweep.ts 的 SWEEP_PERM）：查看 recon:view，执行 / 生成 / 导入 / 广播 / 作废 recon:resolve，改设置 config:edit

function sweepDetail(no: string): SweepBatchDetail {
  const d = sw.details.get(no);
  if (!d) throw new ApiError('errors.notFound');
  return d;
}

function syncBatch(d: SweepBatchDetail): void {
  const b = sw.batches.find((x) => x.no === d.no)!;
  Object.assign(b, {
    status: d.status,
    sweptUsdt: d.sweptUsdt,
    fee: d.fee,
    finishedAt: d.finishedAt,
    txHash: d.txHash,
  });
}

export function getSweepOverview(): Promise<SweepOverview[]> {
  return respond(
    () =>
      SWEEP_CHAINS.map((chain) => {
        const c = sw.candidatesOf(chain).filter((x) => !x.skip);
        const last = sw.batches.find((b) => b.chain === chain && (b.status === 'confirmed' || b.status === 'partial'));
        const active = sw.batches.find((b) => b.chain === chain && sw.ACTIVE.includes(b.status));
        return {
          chain,
          mode: SWEEP_MODE[chain],
          pendingUsdt: sumAmounts(c.map((x) => x.usdt)),
          eligibleCount: c.length,
          belowCount: sw.belowCount(chain),
          destination: chain === 'TRC20' ? sw.settings.tronTarget.address : sw.settings.evm.parent,
          operatorBalance: chain === 'TRC20' ? null : sw.settings.evm.operatorBalances[chain],
          gasAsset: GAS_ASSET[chain],
          lastSweepAt: last?.finishedAt ?? null,
          activeBatch: active?.no ?? null,
        };
      }),
    { empty: () => [] },
  );
}

export function previewSweep(chain: SweepChain): Promise<SweepPreview> {
  return respond(() => {
    const candidates = sw.candidatesOf(chain);
    const ok = candidates.filter((c) => !c.skip);
    const usdt = sumAmounts(ok.map((c) => c.usdt));
    const fee = ok.reduce((s, c) => s + Number(c.estFee), 0);
    const feeUsd = ok.reduce((s, c) => s + Number(c.estFeeUsd), 0);
    const te = sw.settings.tronEnergy;
    const tronRent =
      chain === 'TRC20'
        ? { mode: te.mode, provider: te.provider, priceSun: sw.TRON_RENT.priceSun, energyPerItem: sw.TRON_RENT.energyPerItem, durationMin: te.durationMin, burnSun: sw.TRON_RENT.burnSun }
        : null;
    return {
      chain,
      mode: SWEEP_MODE[chain],
      destination: chain === 'TRC20' ? sw.settings.tronTarget.address : sw.settings.evm.parent,
      threshold: sw.settings.thresholds[chain],
      maxFeeRatioPct: sw.settings.maxFeeRatioPct,
      gasPrice: tronRent?.mode === 'rent' ? `${tronRent.priceSun} sun / 能量（${tronRent.provider}，${tronRent.durationMin} 分钟）` : sw.GAS_PRICE_TEXT[chain],
      tronRent,
      gasAsset: GAS_ASSET[chain],
      candidates,
      totals: {
        count: ok.length,
        usdt,
        fee: sw.fmtFee(chain, fee),
        feeUsd: feeUsd.toFixed(2),
        feePct: Number(usdt) > 0 ? ((feeUsd / Number(usdt)) * 100).toFixed(2) : '0',
        topup: ok.reduce((s, c) => s + Number(c.topup), 0).toFixed(2),
        rent: ok.reduce((s, c) => s + Number(c.rentTrx), 0).toFixed(2),
        skipped: candidates.length - ok.length,
      },
    };
  });
}

/** 以太坊、BSC：执行一批（服务器签名批量交易）；TRON：生成待签名清单（没激活的地址热钱包转 1 TRX 激活；租能量在广播前） */
export function runSweep(chain: SweepChain, reason: string): Promise<{ no: string }> {
  return mutate(() => {
    requirePerm(SWEEP_PERM.operate);
    const r = requireReason(reason);
    if (sw.batches.some((b) => b.chain === chain && sw.ACTIVE.includes(b.status))) throw new ApiError('errors.sweepBatchActive');
    if (chain === 'TRC20' && !sw.settings.tronTarget.address) throw new ApiError('errors.sweepNoTarget');
    const ok = sw.candidatesOf(chain).filter((c) => !c.skip);
    if (!ok.length) throw new ApiError('errors.sweepNothing');
    const no = sw.nextBatchNo();
    const now = nowString();
    const offline = SWEEP_MODE[chain] === 'offline';
    const items: SweepItem[] = ok.map((c, i) => ({
      itemNo: i + 1,
      userId: c.userId,
      customerName: c.customerName,
      address: c.address,
      amount: c.usdt,
      deploy: c.deploy,
      topup: c.topup,
      status: offline ? 'ready' : 'swept',
      txHash: null,
      failReason: null,
      rent: offline ? { status: 'none', energy: sw.TRON_RENT.energyPerItem, trx: '0.00', expiresAt: null } : null,
    }));
    const fee = ok.reduce((s, c) => s + Number(c.estFee), 0);
    const d: SweepBatchDetail = {
      no,
      chain,
      mode: SWEEP_MODE[chain],
      status: offline ? 'awaiting_signature' : 'confirmed',
      trigger: 'manual',
      addressCount: items.length,
      expectedUsdt: sumAmounts(items.map((x) => x.amount)),
      sweptUsdt: offline ? '0.00' : sumAmounts(items.map((x) => x.amount)),
      fee: offline ? '0.00' : sw.fmtFee(chain, fee),
      gasAsset: GAS_ASSET[chain],
      destination: offline ? sw.settings.tronTarget.address! : sw.settings.evm.parent,
      createdBy: currentAdmin().displayName,
      createdAt: now,
      finishedAt: offline ? null : now,
      expiresAt: offline ? addHours(now, sw.settings.tronExpireHours) : null,
      txHash: offline ? null : '0x' + mockHex(64, Number(no.slice(-6))),
      reason: r,
      items,
      unsignedSha256: offline ? mockHex(64, Number(no.slice(-5))) : null,
      signedSha256: null,
      events: offline
        ? [
            { time: now, actor: currentAdmin().displayName, text: 'created_offline' },
            { time: now, actor: 'system', text: 'topup_confirmed' },
          ]
        : [
            { time: now, actor: currentAdmin().displayName, text: 'created_contract' },
            { time: now, actor: 'system', text: 'all_swept' },
          ],
    };
    sw.batches.unshift({ ...d, items: undefined, reason: undefined, events: undefined, unsignedSha256: undefined, signedSha256: undefined } as unknown as SweepBatch);
    sw.details.set(no, d);
    syncBatch(d);
    if (!offline) {
      for (const it of items) {
        const h = sw.holdings.find((x) => x.chain === chain && x.userId === it.userId);
        if (h) {
          h.usdt = '0.00';
          h.ready = true;
        }
      }
    }
    audit('sweep.run', no, true, r, null, { chain, addresses: items.length, usdt: d.expectedUsdt });
    return { no };
  });
}

function addHours(time: string, hours: number): string {
  const ms = Date.parse(time.replace(' ', 'T') + 'Z');
  return new Date(ms + hours * 3600_000).toISOString().slice(0, 19).replace('T', ' ');
}

function mockHex(len: number, seed: number): string {
  const r = createRng(seed);
  return r.hex(len);
}

export function listSweepBatches(q: SweepBatchQuery): Promise<Page<SweepBatch>> {
  return respond(() => paginate(sw.batches.filter((b) => !q.chain || b.chain === q.chain), q.page, q.pageSize), {
    empty: () => emptyPage<SweepBatch>(q.page, q.pageSize),
  });
}

export function getSweepBatch(no: string): Promise<SweepBatchDetail> {
  return respond(() => sweepDetail(no));
}

/** TRON：下载待签名文件（格式见方案附录 B.6） */
export function downloadSweepUnsigned(no: string): Promise<{ fileName: string; content: string }> {
  return respond(
    () => {
      requirePerm(SWEEP_PERM.operate);
      const d = sweepDetail(no);
      if (d.mode !== 'offline') throw new ApiError('errors.sweepWrongMode');
      const body = {
        format: 'forecast-sweep/1',
        kind: 'unsigned',
        prototype: true,
        batchNo: d.no,
        chain: d.chain,
        createdAt: d.createdAt,
        expiresAt: d.expiresAt,
        coldAddress: d.destination,
        derivation: "m/44'/195'/0'/0/{index}",
        totals: { count: d.items.length, usdt: d.expectedUsdt },
        items: d.items.map((it) => ({ itemNo: it.itemNo, index: it.userId, from: it.address, amount: it.amount, tx: { txID: '（原型：没有真实交易）', raw_data_hex: '（原型）' } })),
        sha256: d.unsignedSha256,
      };
      return { fileName: `sweep-${d.no}-unsigned.json`, content: JSON.stringify(body, null, 2) };
    },
    { bypass: true },
  );
}

/** 原型专用：生成一份演示用的"签好的文件"（最后一笔故意改过，演示核对不通过） */
export function demoSweepSignedFile(no: string): Promise<string> {
  return respond(
    () => {
      const d = sweepDetail(no);
      return JSON.stringify({
        format: 'forecast-sweep/1',
        kind: 'signed',
        batchNo: d.no,
        unsignedSha256: d.unsignedSha256,
        items: d.items.map((it, i) => ({ itemNo: it.itemNo, tampered: i === d.items.length - 1 && d.items.length > 1 })),
      });
    },
    { bypass: true },
  );
}

export function importSweepSigned(no: string, text: string): Promise<SweepImportResult> {
  return mutate(() => {
    requirePerm(SWEEP_PERM.operate);
    const d = sweepDetail(no);
    if (d.mode !== 'offline') throw new ApiError('errors.sweepWrongMode');
    if (d.status !== 'awaiting_signature' && d.status !== 'signed') throw new ApiError('errors.sweepBatchState');
    let file: { kind?: string; batchNo?: string; unsignedSha256?: string; items?: { itemNo: number; tampered?: boolean }[] };
    try {
      file = JSON.parse(text);
    } catch {
      throw new ApiError('errors.sweepBadFile');
    }
    if (file.kind !== 'signed' || !Array.isArray(file.items)) throw new ApiError('errors.sweepBadFile');
    if (file.batchNo !== d.no || file.unsignedSha256 !== d.unsignedSha256) throw new ApiError('errors.sweepBatchMismatch');
    const rejected: SweepImportResult['rejected'] = [];
    let accepted = 0;
    for (const f of file.items) {
      const it = d.items.find((x) => x.itemNo === f.itemNo);
      if (!it || (it.status !== 'ready' && it.status !== 'sign_rejected')) continue;
      if (f.tampered) {
        it.status = 'sign_rejected';
        it.failReason = 'contentMismatch';
        rejected.push({ itemNo: it.itemNo, reason: 'contentMismatch' });
      } else {
        it.status = 'signed';
        it.failReason = null;
        accepted += 1;
      }
    }
    d.status = 'signed';
    d.signedSha256 = mockHex(64, text.length);
    d.events.push({ time: nowString(), actor: currentAdmin().displayName, text: 'imported' });
    syncBatch(d);
    audit('sweep.batch_import', no, true, null, null, { accepted, rejected: rejected.length });
    return { accepted, rejected };
  });
}

export function broadcastSweep(no: string, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm(SWEEP_PERM.operate);
    const r = requireReason(reason);
    const d = sweepDetail(no);
    if (d.status !== 'signed') throw new ApiError('errors.sweepBatchState');
    const signed = d.items.filter((x) => x.status === 'signed');
    if (!signed.length) throw new ApiError('errors.sweepNothing');
    const now = nowString();
    const te = sw.settings.tronEnergy;
    const rentTrx = sw.rentTrxOf(sw.TRON_RENT.priceSun);
    const burnTopup = 7.72; // 64,285 能量 × 100 sun × 1.2
    let rentSpent = 0;
    let burnSpent = 0;
    let fallback = 0;
    signed.forEach((it, i) => {
      it.txHash = mockHex(64, Number(no.slice(-4)) + i);
      const expiresAt = addHours(now, te.durationMin / 60);
      if (te.mode === 'burn') {
        it.rent = { status: 'fallbackBurn', energy: 0, trx: burnTopup.toFixed(2), expiresAt: null };
        burnSpent += burnTopup;
      } else if (i === 2 && signed.length > 2 && te.fallbackBurn) {
        // 原型：第三笔演示"能量 2 分钟内没到账 → 回退到热钱包补 TRX、烧 TRX"（平台没扣钱）
        it.rent = { status: 'fallbackBurn', energy: sw.TRON_RENT.energyPerItem, trx: burnTopup.toFixed(2), expiresAt: null };
        burnSpent += burnTopup;
        fallback += 1;
      } else {
        it.rent = { status: 'delivered', energy: sw.TRON_RENT.energyPerItem, trx: rentTrx.toFixed(2), expiresAt };
        rentSpent += rentTrx;
      }
      // 原型：第二笔演示"租到了能量，但链上执行失败（动态能量涨了，能量不够）"
      if (i === 1 && signed.length > 2) {
        it.status = 'failed';
        it.failReason = 'outOfEnergy';
      } else {
        it.status = 'swept';
        const h = sw.holdings.find((x) => x.chain === d.chain && x.userId === it.userId);
        if (h) h.usdt = '0.00';
      }
    });
    te.spentTodayTrx = (Number(te.spentTodayTrx) + rentSpent).toFixed(2);
    te.providerBalance = Math.max(0, Number(te.providerBalance) - rentSpent).toFixed(2);
    for (const it of d.items) if (it.status === 'ready') it.status = 'expired';
    const swept = d.items.filter((x) => x.status === 'swept');
    d.sweptUsdt = sumAmounts(swept.map((x) => x.amount));
    d.fee = (rentSpent + burnSpent).toFixed(2);
    d.status = swept.length === d.items.length ? 'confirmed' : swept.length ? 'partial' : 'failed';
    d.finishedAt = now;
    d.events.push({ time: now, actor: currentAdmin().displayName, text: 'broadcast' });
    if (te.mode === 'rent') d.events.push({ time: now, actor: 'system', text: 'rent_ordered' }, { time: now, actor: 'system', text: 'rent_delivered' });
    if (fallback) d.events.push({ time: now, actor: 'system', text: 'rent_fallback' });
    d.events.push({ time: now, actor: 'system', text: d.status === 'confirmed' ? 'all_swept' : 'some_failed' });
    syncBatch(d);
    audit('sweep.batch_broadcast', no, true, r, null, { swept: swept.length, failed: d.items.length - swept.length });
  });
}

export function cancelSweepBatch(no: string, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm(SWEEP_PERM.operate);
    const r = requireReason(reason);
    const d = sweepDetail(no);
    if (d.status !== 'awaiting_signature' && d.status !== 'signed') throw new ApiError('errors.sweepBatchState');
    for (const it of d.items) if (it.status !== 'swept' && it.status !== 'failed') it.status = 'cancelled';
    d.status = 'cancelled';
    d.finishedAt = nowString();
    d.events.push({ time: d.finishedAt, actor: currentAdmin().displayName, text: 'cancelled' });
    syncBatch(d);
    audit('sweep.batch_cancel', no, true, r, { status: 'awaiting_signature' }, { status: 'cancelled' });
  });
}

/** 以太坊、BSC：失败的地址重新归集（新批次） */
export function retrySweepBatch(no: string, reason: string): Promise<{ no: string }> {
  return mutate(() => {
    requirePerm(SWEEP_PERM.operate);
    const r = requireReason(reason);
    const d = sweepDetail(no);
    if (d.mode !== 'contract' || (d.status !== 'partial' && d.status !== 'failed' && d.status !== 'deferred')) throw new ApiError('errors.sweepBatchState');
    const redo = d.items.filter((x) => x.status === 'failed' || x.status === 'pending');
    const nn = sw.nextBatchNo();
    const now = nowString();
    const items = redo.map((it, i) => ({ ...it, itemNo: i + 1, status: 'swept' as const, failReason: null }));
    const nd: SweepBatchDetail = {
      ...d,
      no: nn,
      status: 'confirmed',
      addressCount: items.length,
      expectedUsdt: sumAmounts(items.map((x) => x.amount)),
      sweptUsdt: sumAmounts(items.map((x) => x.amount)),
      fee: sw.fmtFee(d.chain, items.reduce((s, it) => s + sw.feeOf(d.chain, it.deploy), 0)),
      createdBy: currentAdmin().displayName,
      createdAt: now,
      finishedAt: now,
      txHash: '0x' + mockHex(64, Number(nn.slice(-6))),
      reason: r,
      items,
      events: [
        { time: now, actor: currentAdmin().displayName, text: 'retry_of' },
        { time: now, actor: 'system', text: 'all_swept' },
      ],
    };
    sw.details.set(nn, nd);
    sw.batches.unshift({ ...nd, items: undefined, reason: undefined, events: undefined, unsignedSha256: undefined, signedSha256: undefined } as unknown as SweepBatch);
    audit('sweep.retry', nn, true, r, null, { retryOf: no, addresses: items.length });
    return { no: nn };
  });
}

export function getSweepSettings(): Promise<SweepSettings> {
  return respond(() => {
    const p = sw.settings.tronTarget.pending;
    // 原型：到了生效时间就生效
    if (p && p.effectiveAt <= nowString()) {
      sw.settings.tronTarget = { address: p.address, pending: null };
    }
    return sw.settings;
  });
}

const SWEEP_FIELDS = ['maxFeeRatioPct', 'ethMaxBaseFeeGwei', 'tronTopupPerItem', 'tronTopupPerDay'] as const;
const TRON_ENERGY_NUMS = ['maxPriceSun', 'perDayTrx', 'providerBalanceMin'] as const;

export function updateSweepSettings(input: SweepSettingsInput): Promise<void> {
  return mutate(() => {
    requirePerm(SWEEP_PERM.config);
    const r = requireReason(input.reason);
    const cur = sw.settings;
    if (input.expectedVersion !== cur.version) throw new ApiError('errors.configChanged', undefined, { status: 409, code: 'CONFIG_CHANGED' });
    const nums = [
      ...SWEEP_CHAINS.map((c) => input.thresholds[c]),
      ...SWEEP_CHAINS.map((c) => input.remind[c]),
      input.operatorMin.ERC20,
      input.operatorMin.BSC,
      ...SWEEP_FIELDS.map((k) => input[k]),
      input.evmCaps.perAddress,
      input.evmCaps.perBatch,
      ...TRON_ENERGY_NUMS.map((k) => input.tronEnergy[k]),
    ];
    const te = input.tronEnergy;
    // 占比上限 0–100%；租能量单价不能高于烧 TRX（100 sun）；每笔租 10–1440 分钟
    if (Number(input.maxFeeRatioPct) > 100 || Number(te.maxPriceSun) > 100 || !(te.durationMin >= 10 && te.durationMin <= 1440) || (te.mode !== 'rent' && te.mode !== 'burn')) {
      throw new ApiError('errors.validationFailed');
    }
    if (nums.some((v) => !isSweepNumber(v)) || !(input.autoHourUtc >= 0 && input.autoHourUtc <= 23) || !(input.tronExpireHours >= 1 && input.tronExpireHours <= 23)) {
      throw new ApiError('errors.validationFailed');
    }
    const before: Record<string, string> = {};
    const after: Record<string, string> = {};
    const put = (k: string, a: string | number | boolean, b: string | number | boolean) => {
      if (String(a) !== String(b)) {
        before[k] = String(a);
        after[k] = String(b);
      }
    };
    for (const c of SWEEP_CHAINS) put(`threshold.${c}`, cur.thresholds[c], input.thresholds[c].trim());
    for (const c of SWEEP_CHAINS) put(`remind.${c}`, cur.remind[c], input.remind[c].trim());
    put('operatorMin.ERC20', cur.operatorMin.ERC20, input.operatorMin.ERC20.trim());
    put('operatorMin.BSC', cur.operatorMin.BSC, input.operatorMin.BSC.trim());
    for (const k of SWEEP_FIELDS) put(k, cur[k], input[k].trim());
    put('autoEnabled', cur.autoEnabled, input.autoEnabled);
    put('autoHourUtc', cur.autoHourUtc, input.autoHourUtc);
    put('tronExpireHours', cur.tronExpireHours, input.tronExpireHours);
    put('evmCaps.perAddress', cur.evmCaps.perAddress, input.evmCaps.perAddress.trim());
    put('evmCaps.perBatch', cur.evmCaps.perBatch, input.evmCaps.perBatch.trim());
    put('tronEnergy.mode', cur.tronEnergy.mode, te.mode);
    for (const k of TRON_ENERGY_NUMS) put(`tronEnergy.${k}`, cur.tronEnergy[k], te[k].trim());
    put('tronEnergy.fallbackBurn', cur.tronEnergy.fallbackBurn, te.fallbackBurn);
    put('tronEnergy.durationMin', cur.tronEnergy.durationMin, te.durationMin);
    if (!Object.keys(after).length) throw new ApiError('errors.sweepNoChange');
    Object.assign(cur, {
      thresholds: { ...input.thresholds },
      remind: { ...input.remind },
      operatorMin: { ...input.operatorMin },
      maxFeeRatioPct: input.maxFeeRatioPct.trim(),
      ethMaxBaseFeeGwei: input.ethMaxBaseFeeGwei.trim(),
      tronTopupPerItem: input.tronTopupPerItem.trim(),
      tronTopupPerDay: input.tronTopupPerDay.trim(),
      autoEnabled: input.autoEnabled,
      autoHourUtc: input.autoHourUtc,
      tronExpireHours: input.tronExpireHours,
      evmCaps: { perAddress: input.evmCaps.perAddress.trim(), perBatch: input.evmCaps.perBatch.trim() },
      tronEnergy: {
        ...cur.tronEnergy,
        mode: te.mode,
        maxPriceSun: te.maxPriceSun.trim(),
        perDayTrx: te.perDayTrx.trim(),
        providerBalanceMin: te.providerBalanceMin.trim(),
        fallbackBurn: te.fallbackBurn,
        durationMin: te.durationMin,
      },
      version: cur.version + 1,
      updatedAt: nowString(),
      updatedBy: currentAdmin().displayName,
    });
    audit('sweep.settings_update', `v${cur.version}`, true, r, before, after);
  });
}

/** TRON 冷钱包地址：提交修改，24 小时后生效（方案附录 B.7） */
export function requestTronSweepTarget(address: string, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm(SWEEP_PERM.config);
    const r = requireReason(reason);
    const a = address.trim();
    if (!isTronAddress(a)) throw new ApiError('errors.sweepAddressInvalid');
    const forbidden = [...db.hotWallets.map((h) => h.address), ...sw.holdings.filter((h) => h.chain === 'TRC20').map((h) => sw.addressOf('TRC20', h.userId))];
    if (forbidden.includes(a)) throw new ApiError('errors.sweepAddressForbidden');
    if (a === sw.settings.tronTarget.address) throw new ApiError('errors.sweepNoChange');
    if (sw.settings.tronTarget.pending) throw new ApiError('errors.sweepTargetPending');
    const now = nowString();
    sw.settings.tronTarget.pending = { address: a, requestedAt: now, effectiveAt: addHours(now, sw.settings.tronTargetDelayHours), requestedBy: currentAdmin().displayName, reason: r };
    audit('sweep.target_request', 'TRC20', true, r, { address: sw.settings.tronTarget.address }, { address: a });
  });
}

export function cancelTronSweepTarget(reason: string): Promise<void> {
  return mutate(() => {
    requirePerm(SWEEP_PERM.config);
    const r = requireReason(reason);
    const p = sw.settings.tronTarget.pending;
    if (!p) throw new ApiError('errors.sweepBatchState');
    sw.settings.tronTarget.pending = null;
    audit('sweep.target_cancel', 'TRC20', true, r, { pending: p.address }, { pending: null });
  });
}
