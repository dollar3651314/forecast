/**
 * 后台真实接口：S18a 登录、当前管理员、菜单角标、系统菜单、客户、KYC、资金、通知；S18b 仪表盘、交易监控、风控、品种与行情、交易参数。
 * 路径、参数、字段以后端为准（`backend/src/main/java/com/forecast/app/admin/`，《项目索引》第 2.8 节）；
 * 和原型字段不同的地方按《技术方案/v1-后台方案.md》第 7、8、9 节在这里转换，页面拿到的仍是 api/types.ts 的类型。
 *
 * Real admin API. Shapes follow the backend; prototype differences are adapted here.
 */
import { API_BASE, request } from './http';
import type {
  AutoApproveChange,
  AutoApproveSettings,
  AutoApproveSettingsInput,
  AdminInput,
  AdminSession,
  AdminUser,
  AuditLog,
  AuditQuery,
  CurrentAdmin,
  CustomerDetail,
  CustomerProfileInput,
  CustomerQuery,
  CustomerSummary,
  DashboardStats,
  Deposit,
  DepositQuery,
  KycApplication,
  KycQuery,
  ManualNotifyInput,
  MenuBadges,
  MenuNode,
  NotifyRecord,
  NotifyRecordQuery,
  NotifyTemplate,
  Page,
  PageQuery,
  PermissionDef,
  HotWalletBalance,
  ReconItem,
  ReconQuery,
  Role,
  RoleInput,
  SystemConfig,
  ParamItem,
  ParamSection,
  SweepBatch,
  SweepBatchDetail,
  SweepBatchQuery,
  SweepChain,
  SweepOverview,
  SweepPreview,
  SweepSettings,
  SweepSettingsInput,
  TaskItem,
  TaskQuery,
  TemporaryPassword,
  Withdrawal,
  WithdrawalDetail,
  WithdrawalQuery,
  WithdrawalSummary,
} from './types';

const enc = encodeURIComponent;

/** 后端的叶子菜单 children 是空数组，表格和菜单按"没有子菜单"处理 */
export function normalizeMenus(nodes: MenuNode[] | null | undefined): MenuNode[] {
  return (nodes ?? []).map((n) => {
    const children = n.children && n.children.length ? normalizeMenus(n.children) : undefined;
    const { children: _c, ...rest } = n;
    void _c;
    return children ? { ...rest, children } : rest;
  });
}

// ---------- 认证 ----------
export function login(username: string, password: string): Promise<AdminSession> {
  return request<AdminSession>('/auth/login', { method: 'POST', body: { username, password }, silentAuth: true });
}

export async function logout(): Promise<void> {
  await request<void>('/auth/logout', { method: 'POST', silentAuth: true });
}

/** 改密码：成功后其他设备退出，当前设备的 Cookie 换新 */
export async function changePassword(oldPassword: string, newPassword: string): Promise<void> {
  await request<void>('/auth/change-password', { method: 'POST', body: { oldPassword, newPassword } });
}

export async function getCurrentAdmin(): Promise<CurrentAdmin> {
  const me = await request<CurrentAdmin>('/me', { silentAuth: true });
  return { ...me, menus: normalizeMenus(me.menus) };
}

export function getMenuBadges(): Promise<MenuBadges> {
  return request<MenuBadges>('/menu-badges');
}

// ---------- 客户 ----------
export function listCustomers(q: CustomerQuery): Promise<Page<CustomerSummary>> {
  return request('/customers', { query: { keyword: q.keyword, status: q.status, kycStatus: q.kycStatus, page: q.page, pageSize: q.pageSize } });
}

export function getCustomer(id: number): Promise<CustomerDetail> {
  return request(`/customers/${id}`);
}

/** 冻结 / 解冻（必须填原因，AC-04） */
export async function setCustomerFrozen(id: number, frozen: boolean, reason: string): Promise<void> {
  await request(`/customers/${id}/freeze`, { method: 'POST', body: { frozen, reason } });
}

/** 编辑客户资料（v2 E1，必须填原因）：姓名、出生日期、国籍；返回更新后的详情 */
export function updateCustomerProfile(id: number, input: CustomerProfileInput, reason: string): Promise<CustomerDetail> {
  return request(`/customers/${id}/profile`, { method: 'PUT', body: { ...input, reason } });
}

/** 发通知时选客户（权限点 notify:send） */
export function searchCustomers(keyword: string): Promise<CustomerSummary[]> {
  return request('/customers/search', { query: { keyword } });
}

// ---------- KYC ----------
export function listKyc(q: KycQuery): Promise<Page<KycApplication>> {
  return request('/kyc', { query: { status: q.status, keyword: q.keyword, page: q.page, pageSize: q.pageSize } });
}

export function getKyc(id: string): Promise<KycApplication> {
  return request(`/kyc/${enc(id)}`);
}

/** 通过，或驳回（驳回必须填原因，AC-05） */
export async function reviewKyc(id: string, approve: boolean, reason?: string): Promise<void> {
  await request(`/kyc/${enc(id)}/review`, { method: 'POST', body: approve ? { approve: true } : { approve: false, reason } });
}

/** 证件照片地址（后台方案第 8 节）：同源 Cookie 鉴权，不允许浏览器缓存 */
export function kycImageUrl(id: string, side: 'front' | 'back' | 'selfie'): string | null {
  return `${API_BASE}/kyc/${enc(id)}/images/${side}`;
}

// ---------- 资金 ----------
export function listDeposits(q: DepositQuery): Promise<Page<Deposit>> {
  return request('/deposits', { query: { chain: q.chain, status: q.status, keyword: q.keyword, page: q.page, pageSize: q.pageSize } });
}

export function listWithdrawals(q: WithdrawalQuery): Promise<Page<Withdrawal>> {
  return request('/withdrawals', {
    query: {
      status: q.status,
      chain: q.chain,
      minAmount: q.minAmount,
      maxAmount: q.maxAmount,
      keyword: q.keyword,
      autoReview: q.autoReview,
      page: q.page,
      pageSize: q.pageSize,
    },
  });
}

export function getWithdrawalSummary(): Promise<WithdrawalSummary> {
  return request('/withdrawals/summary');
}

/** 后端详情是 {withdrawal: {...}, kycStatus, ...}，原型是平铺的，这里展开 */
export async function getWithdrawal(id: string): Promise<WithdrawalDetail> {
  const d = await request<Omit<WithdrawalDetail, keyof Withdrawal> & { withdrawal: Withdrawal }>(`/withdrawals/${enc(id)}`);
  const { withdrawal, ...rest } = d;
  return { ...withdrawal, ...rest };
}

export function approveWithdrawal(id: string): Promise<Withdrawal> {
  return request(`/withdrawals/${enc(id)}/approve`, { method: 'POST' });
}

export function rejectWithdrawal(id: string, reason: string): Promise<Withdrawal> {
  return request(`/withdrawals/${enc(id)}/reject`, { method: 'POST', body: { reason } });
}

/** 大额延迟期内紧急取消（必须填原因，AC-21） */
export function emergencyCancelWithdrawal(id: string, reason: string): Promise<Withdrawal> {
  return request(`/withdrawals/${enc(id)}/emergency-cancel`, { method: 'POST', body: { reason } });
}

// ---------- v2 提现自动审批（技术方案/v2-提现自动审批.md 第 7.2 节） ----------
export function getAutoApproveSettings(): Promise<AutoApproveSettings> {
  return request('/withdraw-auto-approve');
}

/** 最近 20 次修改（后端从审计日志 withdrawal.auto_rule_update 取） */
export function listAutoApproveChanges(): Promise<AutoApproveChange[]> {
  return request('/withdraw-auto-approve/changes');
}

/** 一次保存总开关和全部规则；原因必填，expectedVersion 不对返回 409 CONFIG_CHANGED，没有变化返回 NO_CHANGE */
export function updateAutoApproveSettings(input: AutoApproveSettingsInput): Promise<AutoApproveSettings> {
  return request('/withdraw-auto-approve', { method: 'PUT', body: input });
}

/** 对账问题（字段按后台方案第 8 节）；后端只支持按状态筛选 */
export function listRecon(q: ReconQuery): Promise<Page<ReconItem>> {
  return request('/recon', { query: { status: q.status, page: q.page, pageSize: q.pageSize } });
}

/** 热钱包当前余额和告警下限（对账页上方）；下限在系统配置页改 */
export function listHotWallets(): Promise<HotWalletBalance[]> {
  return request('/hot-wallets');
}

// ---------- v2 资金归集第一阶段（以太坊、BSC；技术方案/v2-半自动归集.md v1.2 第 4.9 节，后端 admin/ops/SweepOps） ----------
// TRON 专用的字段（激活、租能量、离线签名）后端这一阶段不返回，这里补成"没有"，页面在真实接口模式下把 TRON 显示成"下一阶段"。

const DASH = '—';

/** GET /api/admin/sweep/overview：三条链，TRC20 的 available 为 false（下一阶段） */
export function getSweepOverview(): Promise<SweepOverview[]> {
  return request('/sweep/overview');
}

interface RealPreview extends Omit<SweepPreview, 'candidates' | 'totals' | 'tronRent'> {
  candidates: { userId: number; customerName: string | null; address: string; usdt: string; deploy: boolean; estFee: string; estFeeUsd: string | null; skip: SweepPreview['candidates'][number]['skip'] }[];
  totals: { count: number; usdt: string; fee: string; feeUsd: string | null; feePct: string | null; skipped: number };
}

/** POST /api/admin/sweep/preview：实时查链上余额 */
export async function previewSweep(chain: SweepChain): Promise<SweepPreview> {
  const p = await request<RealPreview>('/sweep/preview', { method: 'POST', body: { chain } });
  return {
    ...p,
    candidates: p.candidates.map((c) => ({ ...c, customerName: c.customerName ?? DASH, index: 0, topup: '0', activate: false, rentTrx: '0', estFeeUsd: c.estFeeUsd ?? DASH })),
    totals: { ...p.totals, feeUsd: p.totals.feeUsd ?? DASH, feePct: p.totals.feePct ?? DASH, topup: '0', rent: '0' },
    tronRent: null,
  };
}

/** POST /api/admin/sweep/batches（高危，sweep:operate）：以太坊、BSC 执行归集，返回批次号 */
export function runSweep(chain: SweepChain, reason: string): Promise<{ no: string }> {
  return request('/sweep/batches', { method: 'POST', body: { chain, reason } });
}

/** GET /api/admin/sweep/batches */
export function listSweepBatches(q: SweepBatchQuery): Promise<Page<SweepBatch>> {
  return request('/sweep/batches', { query: { chain: q.chain, page: q.page, pageSize: q.pageSize } });
}

/** GET /api/admin/sweep/batches/{no} */
export async function getSweepBatch(no: string): Promise<SweepBatchDetail> {
  const d = await request<SweepBatchDetail>(`/sweep/batches/${enc(no)}`);
  return {
    ...d,
    reason: d.reason ?? '',
    items: d.items.map((i) => ({ ...i, customerName: i.customerName ?? DASH, topup: '0', rent: null })),
    unsignedSha256: null,
    signedSha256: null,
  };
}

/** POST /api/admin/sweep/batches/{no}/retry（高危）：没成功的地址重新执行，返回新批次号 */
export function retrySweepBatch(no: string, reason: string): Promise<{ no: string }> {
  return request(`/sweep/batches/${enc(no)}/retry`, { method: 'POST', body: { reason } });
}

interface RealSweepSettings {
  thresholds: Record<SweepChain, string>;
  maxFeeRatioPct: string;
  evmCaps: { perAddress: string; perBatch: string };
  operatorMin: { ERC20: string; BSC: string };
  evm: {
    configured: boolean;
    problem: string | null;
    parent: string | null;
    factory: string | null;
    implementation: string | null;
    batcher: string | null;
    operator: string | null;
    operatorReady: boolean;
    operatorProblem: string | null;
    operatorBalances: Partial<Record<'ERC20' | 'BSC', string>>;
    readAt: string | null;
  };
  version: number;
  updatedAt: string | null;
  updatedBy: string | null;
}

/** 后端只有第一阶段的设置；TRON、自动归集等下一阶段的项补成默认值（页面在真实接口模式下不显示它们） */
function toSweepSettings(s: RealSweepSettings): SweepSettings {
  return {
    thresholds: s.thresholds,
    maxFeeRatioPct: s.maxFeeRatioPct,
    evmCaps: s.evmCaps,
    operatorMin: s.operatorMin,
    autoEnabled: false,
    autoHourUtc: 18,
    ethMaxBaseFeeGwei: '2',
    remind: { ERC20: '0', BSC: '0', TRC20: '0' },
    tronTopupPerItem: '0',
    tronTopupPerDay: '0',
    tronExpireHours: 20,
    tronTargetDelayHours: 24,
    tronEnergy: { mode: 'rent', provider: 'TronSave', maxPriceSun: '90', perDayTrx: '500', fallbackBurn: true, durationMin: 60, providerBalance: '0', providerBalanceMin: '0', spentTodayTrx: '0' },
    tronTarget: { address: null, pending: null },
    evm: {
      parent: s.evm.parent ?? DASH,
      safeThreshold: '',
      factory: s.evm.factory ?? DASH,
      implementation: s.evm.implementation ?? DASH,
      batcher: s.evm.batcher ?? DASH,
      operator: s.evm.operator ?? DASH,
      operatorBalances: { ERC20: s.evm.operatorBalances.ERC20 ?? DASH, BSC: s.evm.operatorBalances.BSC ?? DASH },
      readAt: s.evm.readAt ?? '',
      configured: s.evm.configured,
      problem: s.evm.problem,
      operatorReady: s.evm.operatorReady,
      operatorProblem: s.evm.operatorProblem,
    },
    version: s.version,
    updatedAt: s.updatedAt,
    updatedBy: s.updatedBy,
  };
}

/** GET /api/admin/sweep/settings */
export async function getSweepSettings(): Promise<SweepSettings> {
  return toSweepSettings(await request<RealSweepSettings>('/sweep/settings'));
}

/** PUT /api/admin/sweep/settings（高危，sweep:config；版本不对 409）：只提交第一阶段的项 */
export async function updateSweepSettings(input: SweepSettingsInput): Promise<void> {
  await request('/sweep/settings', {
    method: 'PUT',
    body: {
      thresholds: input.thresholds,
      maxFeeRatioPct: input.maxFeeRatioPct,
      evmCaps: input.evmCaps,
      operatorMin: input.operatorMin,
      reason: input.reason,
      expectedVersion: input.expectedVersion,
    },
  });
}

/** 标记已处理：说明字段是 note（F5） */
export async function resolveRecon(id: string, note: string): Promise<void> {
  await request(`/recon/${enc(id)}/resolve`, { method: 'POST', body: { note } });
}

export function listTasks(q: TaskQuery): Promise<Page<TaskItem>> {
  return request('/tasks', { query: { type: q.type, status: q.status, page: q.page, pageSize: q.pageSize } });
}

export async function retryTask(id: string): Promise<void> {
  await request(`/tasks/${enc(id)}/retry`, { method: 'POST' });
}

// ---------- 通知 ----------
export function listTemplates(): Promise<NotifyTemplate[]> {
  return request('/notify/templates');
}

/** id 就是模板编号 */
export async function updateTemplate(id: string, patch: Partial<Pick<NotifyTemplate, 'titleZh' | 'bodyZh' | 'titleEn' | 'bodyEn' | 'titleTw' | 'bodyTw' | 'enabled'>>): Promise<void> {
  await request(`/notify/templates/${enc(id)}`, { method: 'PUT', body: patch });
}

export async function sendManualNotification(input: ManualNotifyInput): Promise<void> {
  await request('/notify/send', { method: 'POST', body: input });
}

export function listNotifyRecords(q: NotifyRecordQuery): Promise<Page<NotifyRecord>> {
  return request('/notify/records', { query: { channel: q.channel, status: q.status, keyword: q.keyword, page: q.page, pageSize: q.pageSize } });
}

// ---------- 系统 ----------
export function listAdmins(q: PageQuery & { keyword?: string }): Promise<Page<AdminUser>> {
  return request('/admins', { query: { keyword: q.keyword, page: q.page, pageSize: q.pageSize } });
}

/** 新建管理员：返回临时密码（只返回这一次） */
export function createAdmin(input: AdminInput): Promise<TemporaryPassword> {
  return request('/admins', { method: 'POST', body: input });
}

export async function updateAdmin(id: number, input: AdminInput): Promise<void> {
  await request(`/admins/${id}`, { method: 'PUT', body: input });
}

export async function setAdminStatus(id: number, active: boolean, reason: string): Promise<void> {
  await request(`/admins/${id}/status`, { method: 'POST', body: { active, reason } });
}

/** 重置密码：返回新的临时密码（只返回这一次） */
export function resetAdminPassword(id: number, reason: string): Promise<TemporaryPassword> {
  return request(`/admins/${id}/reset-password`, { method: 'POST', body: { reason } });
}

export function listRoles(): Promise<Role[]> {
  return request('/roles');
}

export async function saveRole(id: string | null, input: RoleInput): Promise<void> {
  if (id) await request(`/roles/${enc(id)}`, { method: 'PUT', body: input });
  else await request('/roles', { method: 'POST', body: input });
}

export async function deleteRole(id: string): Promise<void> {
  await request(`/roles/${enc(id)}`, { method: 'DELETE' });
}

export async function listMenus(): Promise<MenuNode[]> {
  return normalizeMenus(await request<MenuNode[]>('/menus'));
}

/** 菜单只能改顺序和是否显示（后台方案第 7 节）；后端要求每次都带 order */
export async function updateMenu(key: string, patch: Pick<MenuNode, 'order'> & Partial<Pick<MenuNode, 'visible'>>): Promise<void> {
  await request(`/menus/${enc(key)}`, { method: 'PUT', body: { order: patch.order, visible: patch.visible } });
}

export function listPermissions(): Promise<PermissionDef[]> {
  return request('/permissions');
}

export function listAuditLogs(q: AuditQuery): Promise<Page<AuditLog>> {
  return request('/audit-logs', {
    query: { action: q.action, admin: q.admin, highRiskOnly: q.highRiskOnly ? true : undefined, keyword: q.keyword, page: q.page, pageSize: q.pageSize },
  });
}

export function listAuditActions(): Promise<string[]> {
  return request('/audit-logs/actions');
}

export function listSystemConfigs(): Promise<SystemConfig[]> {
  return request('/configs');
}

export async function updateSystemConfig(key: string, value: string, reason: string): Promise<void> {
  await request(`/configs/${enc(key)}`, { method: 'PUT', body: { value, reason } });
}

/** 参数设置（forecast S3）：一组参数 */
export function listParams(section: ParamSection): Promise<ParamItem[]> {
  return request(`/params/${section}`);
}

/**
 * 改一项参数（高危，原因必填）。expected 是打开页面时看到的值：别人已经改过时返回 409 CONFIG_CHANGED，不覆盖。
 * 单笔最小提现（withdraw.minAmount）S3 起在这里改（betting_params:edit），不再在系统配置页。
 */
export async function updateParam(section: ParamSection, key: string, value: string, reason: string, expected?: string): Promise<void> {
  await request(`/params/${section}/${enc(key)}`, { method: 'PUT', body: { value, reason, expected } });
}

// ================= S18b：仪表盘、交易监控、风控、品种与行情、交易参数（后台方案第 9 节） =================

/** 仪表盘 */
export function getDashboard(): Promise<DashboardStats> {
  return request('/dashboard');
}

