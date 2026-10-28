/**
 * forecast S9 后台页面的真实接口（路径、参数、字段以后端为准：backend/src/main/java/com/forecast/app/admin/ops/*Controller.java）。
 * 高危操作的 reason / checked 原样传给后端，后端检查并写审计。
 */
import { ApiError, type Page } from './types';
import { API_BASE, request, toApiError } from './http';
import type {
  Autogen,
  AutogenInput,
  CaseDetail,
  CaseItem,
  CaseTab,
  CaseView,
  Compensation,
  CompPlan,
  Dispute,
  DraftInput,
  Evidence,
  FeeChangeResult,
  Fixture,
  FixtureQuery,
  Generated,
  I18nText,
  MarketBets,
  MarketCounts,
  MarketDashboard,
  MarketDetail,
  MarketQuery,
  MarketSummary,
  OpenLimit,
  PublishInput,
  GrowthQuery,
  GrowthReport,
  GrowthExportKind,
  TelegramGroup,
  TelegramOverview,
  TelegramPost,
  TelegramPostStatus,
  TelegramPreview,
  TelegramStatus,
  TelegramTarget,
  TelegramTargetKey,
  TelegramTemplate,
  WaitlistLaunch,
  CfImportFile,
  CfImportResult,
  WaitlistPage,
  WaitlistQuery,
  PlayInput,
  PoolIssue,
  ProposalInput,
  Report,
  RuleTemplate,
  SettleOutcome,
  SettlePreview,
  SettleRun,
  SettleRunDetail,
  SportsTemplate,
  SyncResult,
  CustomerLink,
  RiskAlert,
  RiskAlertCounts,
  RiskAlertQuery,
  RiskAlertStatus,
  StaffRefund,
  PlatformAccount,
  Adjustment,
  AdjustInput,
  AdjustQuery,
} from './fcTypes';
import type { CustomerDetail, TestCreditResult } from './types';

const enc = encodeURIComponent;

// ---- 仪表盘 ----
export const getMarketDashboard = (): Promise<MarketDashboard> => request('/dashboard/markets');

// ---- 市场管理 ----
export const listMarkets = (q: MarketQuery): Promise<Page<MarketSummary>> =>
  request('/markets', { query: { status: q.status, category: q.category, submitted: q.submitted, todaySlate: q.todaySlate, keyword: q.keyword, page: q.page, pageSize: q.pageSize } });
export const getMarketCounts = (): Promise<MarketCounts> => request('/markets/counts');
export const getMarket = (no: string): Promise<MarketDetail> => request(`/markets/${enc(no)}`);
export const createMarket = (d: DraftInput): Promise<MarketDetail> => request('/markets', { method: 'POST', body: d });
export const updateMarket = (no: string, d: DraftInput): Promise<MarketDetail> => request(`/markets/${enc(no)}`, { method: 'PUT', body: d });
export const deleteMarket = async (no: string): Promise<void> => {
  await request(`/markets/${enc(no)}`, { method: 'DELETE' });
};
export const submitMarket = (no: string): Promise<MarketDetail> => request(`/markets/${enc(no)}/submit`, { method: 'POST' });
export const returnMarket = (no: string, reason: string): Promise<MarketDetail> => request(`/markets/${enc(no)}/return`, { method: 'POST', body: { reason } });
/** 上架（S13：todaySlate 列入今日场次；overLimit 超过同时开放上限时"仍要上架"，不勾时后端 400 overLimit:required） */
export const publishMarket = (no: string, input: PublishInput): Promise<MarketDetail> => request(`/markets/${enc(no)}/publish`, { method: 'POST', body: input });
/** 改"列入今日场次"（S13；草稿、已上架、已暂停） */
export const setTodaySlate = (no: string, todaySlate: boolean): Promise<MarketDetail> => request(`/markets/${enc(no)}/today-slate`, { method: 'POST', body: { todaySlate } });
/** 同时开放的市场数和上限（S13，上架前提示"当前已开放 X 个，上限 Y 个"） */
export const getOpenLimit = (): Promise<OpenLimit> => request('/markets/open-limit');
export const pauseMarket = (no: string, reason: string): Promise<MarketDetail> => request(`/markets/${enc(no)}/pause`, { method: 'POST', body: { reason } });
export const resumeMarket = (no: string, reason: string): Promise<MarketDetail> => request(`/markets/${enc(no)}/resume`, { method: 'POST', body: { reason } });
export const cancelMarket = (no: string, reason: string): Promise<MarketDetail> => request(`/markets/${enc(no)}/cancel`, { method: 'POST', body: { reason } });
export const advanceClose = (no: string, closeAt: string, reason: string): Promise<MarketDetail> =>
  request(`/markets/${enc(no)}/advance-close`, { method: 'POST', body: { closeAt, reason } });
export const clarifyMarket = (no: string, text: I18nText, reason: string): Promise<MarketDetail> =>
  request(`/markets/${enc(no)}/clarifications`, { method: 'POST', body: { text, reason } });
export const changeFee = (no: string, feeRate: string, reason: string): Promise<FeeChangeResult> =>
  request(`/markets/${enc(no)}/fee`, { method: 'PUT', body: { feeRate, reason } });
export const voidMarket = (no: string, reason: string): Promise<SettleOutcome> => request(`/markets/${enc(no)}/void`, { method: 'POST', body: { reason } });
export const getMarketBets = (no: string, q: { sort?: 'time' | 'amount'; status?: string; page: number; pageSize: number }): Promise<MarketBets> =>
  request(`/markets/${enc(no)}/bets`, { query: q });
export const getSettlement = (no: string): Promise<SettleOutcome> => request(`/markets/${enc(no)}/settlement`);
export const getEvidence = (no: string): Promise<Evidence> => request(`/markets/${enc(no)}/resolution`);

// ---- S13 来源统计（growth:view） ----
export const getGrowthReport = (q: GrowthQuery): Promise<GrowthReport> => request('/growth/report', { query: { from: q.from, to: q.to, basis: q.basis } });
/**
 * CSV 导出（服务器生成，UTF-8 带 BOM；只有客户编号，没有邮箱和 KYC 信息，AC-94；后端写审计 growth.export）。
 * 用 fetch 取文件再下载：出错时（没权限、日期不对）能按普通错误提示，不会把浏览器带到一个 JSON 页面。
 */
export async function exportGrowth(kind: GrowthExportKind, q: GrowthQuery, lang: string): Promise<void> {
  const p = new URLSearchParams({ kind, lang });
  if (q.from) p.set('from', q.from);
  if (q.to) p.set('to', q.to);
  if (q.basis) p.set('basis', q.basis);
  let res: Response;
  try {
    res = await fetch(`${API_BASE}/growth/export?${p.toString()}`, { credentials: 'same-origin', headers: { Accept: 'text/csv, application/json' } });
  } catch {
    throw new ApiError('errors.network');
  }
  if (!res.ok) {
    let body = null;
    try {
      body = await res.json();
    } catch {
      body = null;
    }
    throw toApiError(res.status, body);
  }
  const blob = await res.blob();
  const name = /filename="?([^";]+)"?/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? `growth-${kind}.csv`;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// ---- 加密自动生成 ----
export const listAutogens = (): Promise<Autogen[]> => request('/market-autogens');
export const createAutogen = (i: AutogenInput, reason: string): Promise<Autogen> => request('/market-autogens', { method: 'POST', body: { ...i, reason } });
export const updateAutogen = (no: string, i: AutogenInput, reason: string): Promise<Autogen> =>
  request(`/market-autogens/${enc(no)}`, { method: 'PUT', body: { ...i, reason } });
export const enableAutogen = (no: string, checked: boolean, reason?: string): Promise<Autogen> =>
  request(`/market-autogens/${enc(no)}/enable`, { method: 'POST', body: { checked, reason } });
export const disableAutogen = (no: string, reason: string): Promise<Autogen> => request(`/market-autogens/${enc(no)}/disable`, { method: 'POST', body: { reason } });
export const listRuleTemplates = (): Promise<RuleTemplate[]> => request('/market-templates');
export const updateRuleTemplate = (no: string, t: Pick<RuleTemplate, 'name' | 'title' | 'rules' | 'sourcePrimary' | 'sourceBackup' | 'options'>, reason: string): Promise<RuleTemplate> =>
  request(`/market-templates/${enc(no)}`, { method: 'PUT', body: { ...t, reason } });

// ---- 体育 ----
export const listFixtures = (q: FixtureQuery): Promise<Page<Fixture>> => request('/sports/fixtures', { query: { sport: q.sport, mapping: q.mapping, page: q.page, pageSize: q.pageSize } });
export const getFixture = (no: string): Promise<Fixture> => request(`/sports/fixtures/${enc(no)}`);
export const syncFixtures = (sport?: string): Promise<SyncResult[]> => request('/sports/fixtures/sync', { method: 'POST', body: { sport } });
export const searchCandidates = (no: string, ref?: string): Promise<Fixture> => request(`/sports/fixtures/${enc(no)}/candidates`, { method: 'POST', body: { ref } });
export const confirmMapping = (no: string, ref: string, reason?: string): Promise<Fixture> =>
  request(`/sports/fixtures/${enc(no)}/mapping`, { method: 'POST', body: { ref, reason } });
export const clearMapping = (no: string, reason?: string): Promise<Fixture> => request(`/sports/fixtures/${enc(no)}/mapping`, { method: 'DELETE', body: { reason } });
export const generateDrafts = (no: string, plays: PlayInput[]): Promise<Generated> => request(`/sports/fixtures/${enc(no)}/drafts`, { method: 'POST', body: { plays } });
export const listSportsTemplates = (): Promise<SportsTemplate[]> => request('/sports/templates');
export const updateSportsTemplate = (kind: string, t: Pick<SportsTemplate, 'name' | 'title' | 'rules' | 'sourcePrimary' | 'sourceBackup' | 'options'>, reason: string): Promise<SportsTemplate> =>
  request(`/sports/templates/${enc(kind)}`, { method: 'PUT', body: { ...t, reason } });

// ---- 裁决台 ----
export const listCases = (tab: CaseTab, page: number, pageSize: number): Promise<Page<CaseItem>> =>
  request('/resolve/cases', { query: { tab: tab === 'all' ? undefined : tab, page, pageSize } });
export const getCaseCounts = (): Promise<Record<CaseTab, number>> => request('/resolve/counts');
export const getCase = (no: string): Promise<CaseView> => request(`/resolve/cases/${enc(no)}`);
export const previewSettle = (no: string, optionNo: string, actualStartAt?: string | null): Promise<SettlePreview> =>
  request(`/markets/${enc(no)}/settle-preview`, { query: { optionNo, actualStartAt: actualStartAt ?? undefined } });
export const propose = (no: string, p: ProposalInput): Promise<CaseView> => request(`/resolve/cases/${enc(no)}/proposals`, { method: 'POST', body: p });
export const confirmProposal = (no: string, proposalId: string, reason: string): Promise<CaseView> =>
  request(`/resolve/cases/${enc(no)}/proposals/${enc(proposalId)}/confirm`, { method: 'POST', body: { checked: true, reason } });
export const returnProposal = (no: string, proposalId: string, reason: string): Promise<CaseView> =>
  request(`/resolve/cases/${enc(no)}/proposals/${enc(proposalId)}/return`, { method: 'POST', body: { reason } });
export const decideDirect = (no: string, p: ProposalInput, reason: string): Promise<CaseView> =>
  request(`/resolve/cases/${enc(no)}/decide`, { method: 'POST', body: { ...p, checked: true, reason } });
export const holdCase = (no: string, kind: 'postponed' | 'abandoned', reason: string): Promise<CaseView> =>
  request(`/resolve/cases/${enc(no)}/hold`, { method: 'POST', body: { kind, reason } });
export const releaseCase = (no: string, reason: string): Promise<CaseView> => request(`/resolve/cases/${enc(no)}/release`, { method: 'POST', body: { reason } });
export const attachmentUrl = (attachmentNo: string): string => `${API_BASE}/resolve/attachments/${enc(attachmentNo)}`;

/** 上传存档截图（multipart，字段 file；PNG / JPG ≤ 2 MB） */
export async function uploadAttachment(no: string, file: File): Promise<{ attachmentNo: string }> {
  const form = new FormData();
  form.append('file', file);
  let res: Response;
  try {
    res = await fetch(`${API_BASE}/resolve/cases/${enc(no)}/attachments`, { method: 'POST', credentials: 'same-origin', body: form });
  } catch {
    throw new ApiError('errors.network');
  }
  const text = await res.text();
  let data: unknown = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (!res.ok) throw toApiError(res.status, data as Parameters<typeof toApiError>[1]);
  return data as { attachmentNo: string };
}

// ---- 公示与争议 ----
export const listDisputes = (status: 'open' | 'handled' | undefined, page: number, pageSize: number): Promise<Page<Dispute>> =>
  request('/resolve/disputes', { query: { status, page, pageSize } });
export const getDisputeCase = (no: string): Promise<CaseDetail> => request(`/resolve/disputes/${enc(no)}`);
export const decideDisputes = (no: string, decision: 'uphold' | 'overturn' | 'void', optionNo: string | null, note: string, reason: string): Promise<CaseDetail> =>
  request(`/resolve/cases/${enc(no)}/disputes/decide`, { method: 'POST', body: { decision, optionNo, note, checked: true, reason } });

// ---- 结算、对账、补偿、报表 ----
export const listSettleRuns = (q: { status?: string; kind?: string; page: number; pageSize: number }): Promise<Page<SettleRun>> => request('/settle/runs', { query: q });
export const getSettleRun = (runNo: string, page: number, pageSize: number): Promise<SettleRunDetail> => request(`/settle/runs/${enc(runNo)}`, { query: { page, pageSize } });
export const retrySettleRun = (runNo: string, reason: string): Promise<SettleRun> => request(`/settle/runs/${enc(runNo)}/retry`, { method: 'POST', body: { reason } });
export const listPoolIssues = (status: 'open' | 'resolved' | undefined, page: number, pageSize: number): Promise<Page<PoolIssue>> =>
  request('/pool-recon/issues', { query: { status, page, pageSize } });
export const resolvePoolIssue = (id: number, note: string): Promise<PoolIssue> => request(`/pool-recon/issues/${id}/resolve`, { method: 'POST', body: { note } });
export const previewCompensation = (no: string, correctOptionNo: string): Promise<CompPlan> => request(`/markets/${enc(no)}/compensation-preview`, { query: { correctOptionNo } });
export const compensate = (no: string, correctOptionNo: string, reason: string): Promise<Compensation> =>
  request(`/markets/${enc(no)}/compensations`, { method: 'POST', body: { correctOptionNo, checked: true, reason } });
export const listCompensations = (page: number, pageSize: number): Promise<Page<Compensation>> => request('/settle/compensations', { query: { page, pageSize } });
export const getCompensation = (no: string): Promise<Compensation> => request(`/settle/compensations/${enc(no)}`);
export const getReport = (from: string, to: string): Promise<Report> => request('/reports/settle', { query: { from, to } });

// ---- 风控告警、员工账户、关联账户（forecast S10，后端 admin/ops/RiskAdminController、OpsController） ----
export const listRiskAlerts = (q: RiskAlertQuery): Promise<Page<RiskAlert>> =>
  request('/risk-alerts', { query: { kind: q.kind, status: q.status, keyword: q.keyword, page: q.page, pageSize: q.pageSize } });
export const getRiskAlertCounts = (): Promise<RiskAlertCounts> => request('/risk-alerts/counts');
/** 改处理状态；已处理、误报要写处理说明（后端校验） */
export const setRiskAlertStatus = (id: string, status: RiskAlertStatus, note: string | null): Promise<RiskAlert> =>
  request(`/risk-alerts/${enc(id)}/status`, { method: 'POST', body: { status, note } });
export const getCustomerLinks = (id: number): Promise<CustomerLink[]> => request(`/customers/${id}/links`);
/** 登记 / 取消登记员工账户（customer:edit，高危，原因必填）；登记时退回已有下注（E15），返回退回的结果 */
export const setCustomerStaff = async (id: number, staff: boolean, reason: string): Promise<StaffRefund> =>
  (await request<{ refunded: StaffRefund }>(`/customers/${id}/staff`, { method: 'POST', body: { staff, reason } })).refunded;

// ---- S17A 测试入金（balance:test_credit ★，只在测试环境有接口；生产 404） ----
/** 给客户入一笔测试金：requestId 每次打开确认框生成一个，重复点击只入一次（后端返回 duplicate） */
export const testCredit = (id: number, amount: string, reason: string, requestId: string): Promise<{ customer: CustomerDetail; credit: TestCreditResult }> =>
  request(`/customers/${id}/test-credit`, { method: 'POST', body: { amount, reason, requestId, checked: true } });
/** 对账页"平台账户"（recon:view）：平台收入、补偿支出、测试入金的余额和账本合计 */
export const listPlatformAccounts = (): Promise<PlatformAccount[]> => request('/recon/platform-accounts');

// ---- S17B 人工调账（加款、扣款 balance:adjust ★；记录和导出 recon:view） ----
export const adjustBalance = (id: number, input: AdjustInput): Promise<{ customer: CustomerDetail; adjustment: Adjustment; duplicate: boolean }> =>
  request(`/customers/${id}/adjust`, { method: 'POST', body: { ...input, refNo: input.refNo.trim() || null, checked: true } });
export const listAdjustments = (q: AdjustQuery): Promise<Page<Adjustment>> =>
  request('/balance-adjustments', { query: { customerId: q.customerId, direction: q.direction, category: q.category, from: q.from, to: q.to, page: q.page, pageSize: q.pageSize } });
/** 导出 CSV（后端生成、写审计 adjust.export；和来源统计的导出同一个做法：出错按普通错误提示） */
export async function exportAdjustments(q: Omit<AdjustQuery, 'page' | 'pageSize'>, lang: string): Promise<void> {
  const p = new URLSearchParams({ lang });
  for (const [k, v] of Object.entries(q)) if (v) p.set(k, String(v));
  let res: Response;
  try {
    res = await fetch(`${API_BASE}/balance-adjustments/export?${p.toString()}`, { credentials: 'same-origin', headers: { Accept: 'text/csv, application/json' } });
  } catch {
    throw new ApiError('errors.network');
  }
  if (!res.ok) {
    let body = null;
    try {
      body = await res.json();
    } catch {
      body = null;
    }
    throw toApiError(res.status, body);
  }
  const blob = await res.blob();
  const name = /filename="?([^";]+)"?/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? 'balance-adjustments.csv';
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// ---- S16 等候页订阅（growth:view；发送上线通知 content:publish ★） ----
export const listWaitlist = (q: WaitlistQuery): Promise<WaitlistPage> =>
  request('/waitlist', { query: { status: q.status, page: q.page, pageSize: q.pageSize } });
/** 发送上线通知（只能一次；第二次 409 LAUNCH_ALREADY_SENT） */
export const launchWaitlist = (reason: string): Promise<WaitlistLaunch> => request('/waitlist/launch', { method: 'POST', body: { checked: true, reason } });
/** S19（P15）：从 Cloudflare 导入名单（content:publish ★；不发任何邮件，可以重复导入） */
export const importWaitlist = (reason: string, file: CfImportFile): Promise<CfImportResult> =>
  request('/waitlist/import', { method: 'POST', body: { checked: true, reason, file: { format: file.format, items: file.items } } });

// ---- S16 Telegram 机器人（content:view；改动 content:publish ★） ----
export const getTelegram = (): Promise<TelegramOverview> => request('/telegram');
export const listTelegramPosts = (status: TelegramPostStatus | undefined, page: number, pageSize: number): Promise<Page<TelegramPost>> =>
  request('/telegram/posts', { query: { status, page, pageSize } });
export const updateTelegramTarget = (key: TelegramTargetKey, chatId: string | null, enabled: boolean, reason: string): Promise<TelegramTarget> =>
  request(`/telegram/targets/${enc(key)}`, { method: 'PUT', body: { chatId, enabled, reason } });
export const stopTelegramGroup = (chatId: string, reason: string): Promise<TelegramGroup> =>
  request(`/telegram/groups/${enc(chatId)}/broadcast-off`, { method: 'POST', body: { reason } });
export const getTelegramPreview = (kind: 'RESULT', lang: string): Promise<TelegramPreview> => request(`/telegram/templates/${enc(kind)}/${enc(lang)}/preview`);
/** 预览图的地址（img 直接用，带后台的登录 Cookie） */
export const telegramPreviewUrl = (kind: 'RESULT', lang: string): string => `${API_BASE}/telegram/templates/${enc(kind)}/${enc(lang)}/preview.png`;
export const approveTelegramTemplate = (kind: 'RESULT', lang: string, fingerprint: string, reason: string): Promise<TelegramTemplate> =>
  request(`/telegram/templates/${enc(kind)}/${enc(lang)}/approve`, { method: 'POST', body: { fingerprint, checked: true, reason } });
export const disableTelegramTemplate = (kind: 'RESULT', lang: string, reason: string): Promise<TelegramTemplate> =>
  request(`/telegram/templates/${enc(kind)}/${enc(lang)}/disable`, { method: 'POST', body: { reason } });
export const setTelegramStop = (stop: boolean, reason: string): Promise<TelegramStatus> => request('/telegram/stop', { method: 'POST', body: { stop, reason } });
