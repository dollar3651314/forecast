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
} from './fcTypes';

const enc = encodeURIComponent;

// ---- 仪表盘 ----
export const getMarketDashboard = (): Promise<MarketDashboard> => request('/dashboard/markets');

// ---- 市场管理 ----
export const listMarkets = (q: MarketQuery): Promise<Page<MarketSummary>> =>
  request('/markets', { query: { status: q.status, category: q.category, submitted: q.submitted, keyword: q.keyword, page: q.page, pageSize: q.pageSize } });
export const getMarketCounts = (): Promise<MarketCounts> => request('/markets/counts');
export const getMarket = (no: string): Promise<MarketDetail> => request(`/markets/${enc(no)}`);
export const createMarket = (d: DraftInput): Promise<MarketDetail> => request('/markets', { method: 'POST', body: d });
export const updateMarket = (no: string, d: DraftInput): Promise<MarketDetail> => request(`/markets/${enc(no)}`, { method: 'PUT', body: d });
export const deleteMarket = async (no: string): Promise<void> => {
  await request(`/markets/${enc(no)}`, { method: 'DELETE' });
};
export const submitMarket = (no: string): Promise<MarketDetail> => request(`/markets/${enc(no)}/submit`, { method: 'POST' });
export const returnMarket = (no: string, reason: string): Promise<MarketDetail> => request(`/markets/${enc(no)}/return`, { method: 'POST', body: { reason } });
export const publishMarket = (no: string, checked: boolean): Promise<MarketDetail> => request(`/markets/${enc(no)}/publish`, { method: 'POST', body: { checked } });
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
