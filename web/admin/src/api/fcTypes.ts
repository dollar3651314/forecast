/**
 * forecast S9 后台页面的数据类型：字段和后端返回的 record 完全一致（以后端为准，M1 原型里提议的字段已经删掉或改成后端的样子）。
 * 编号一律字符串；金额是 2 位小数的字符串；费率是百分比字符串（"5" = 5%）；时间是 ISO-8601 UTC 字符串。
 * 后端位置：market/api/MarketAdminApi、MarketAutogenAdminApi，pool/api/PoolAdminApi，resolve/api/ResolveDeskApi、SportsAdminApi、ResolveAdminApi，
 * settle/api/SettleAdminApi，admin/ops/MarketDashboardOps、ResolveOps、BetAdminController。
 */
import type { FieldError, PageQuery } from './types';

export interface I18nText {
  en: string;
  zhCN: string;
  zhTW: string;
}

export type MarketCategory = 'FOOTBALL' | 'NBA' | 'CRYPTO_15M' | 'CRYPTO_1H' | 'MACRO';
export const CATEGORIES: MarketCategory[] = ['FOOTBALL', 'NBA', 'CRYPTO_15M', 'CRYPTO_1H', 'MACRO'];
export type MarketStatus = 'draft' | 'listed' | 'paused' | 'closed' | 'pending_resolution' | 'publicizing' | 'disputed' | 'settled' | 'cancelled' | 'voided';
export const MARKET_STATUSES: MarketStatus[] = ['draft', 'listed', 'paused', 'closed', 'pending_resolution', 'publicizing', 'disputed', 'settled', 'cancelled', 'voided'];

// ---------------------------------------------------------------- 市场管理（MarketAdminApi）

/** 列表的一行（S9 加 resolutionMode、origin、pool、bettors） */
export interface MarketSummary {
  publicNo: string;
  category: MarketCategory;
  title: I18nText;
  status: MarketStatus;
  submitted: boolean;
  openAt: string;
  closeAt: string;
  eventStartAt: string;
  eventEndAt: string;
  feeRate: string;
  createdAt: string;
  updatedAt: string;
  resolutionMode: 'auto' | 'platform' | null;
  origin: 'manual' | 'autogen' | 'sports';
  pool: string;
  bettors: number;
  /** 列入今日场次（S13，MF-3） */
  todaySlate: boolean;
}

export interface MarketQuery extends PageQuery {
  /** 小写状态，可以逗号分隔多个 */
  status?: string;
  category?: MarketCategory;
  submitted?: boolean;
  /** 只看列入今日场次的（S13） */
  todaySlate?: boolean;
  keyword?: string;
}

export interface MarketCounts {
  byStatus: Record<MarketStatus, number>;
  review: number;
}

export interface OptionInput {
  label: I18nText;
  winCondition: I18nText;
}

/** 新建、编辑草稿（MarketAdminApi.DraftInput）。费率不在这里：新建时按品类默认值，之后只能"改费率" */
export interface DraftInput {
  category: MarketCategory;
  title: I18nText;
  rules: I18nText;
  resolutionMode: 'AUTO' | 'PLATFORM' | null;
  sourcePrimary: string;
  sourceBackup: string;
  sourceRef: Record<string, unknown> | null;
  openAt: string;
  closeAt: string;
  eventStartAt: string;
  eventEndAt: string;
  userCap: string | null;
  disputeWindowMinutes: number | null;
  coverPath: string | null;
  options: OptionInput[];
  /** 列入今日场次（S13，MF-3；编辑草稿时勾选） */
  todaySlate?: boolean;
}

export interface MarketOption {
  optionNo: string;
  seq: number;
  label: I18nText;
  winCondition: I18nText;
}

export interface StatusLog {
  from: string | null;
  to: string;
  actor: string;
  reason: string | null;
  time: string;
}

export interface FeeChange {
  oldRate: string;
  newRate: string;
  reason: string;
  changedBy: number;
  time: string;
  notified: boolean;
}

export interface Clarification {
  text: I18nText;
  createdBy: number;
  time: string;
}

export interface MarketDetail {
  publicNo: string;
  category: MarketCategory;
  title: I18nText;
  rules: I18nText;
  status: MarketStatus;
  submitted: boolean;
  settlePhase: string;
  resolutionMode: 'AUTO' | 'PLATFORM' | null;
  sourcePrimary: string;
  sourceBackup: string;
  sourceRef: Record<string, unknown> | null;
  openAt: string;
  closeAt: string;
  eventStartAt: string;
  eventEndAt: string;
  feeRate: string;
  userCap: string;
  disputeWindowMinutes: number | null;
  coverPath: string | null;
  cancelReason: string | null;
  voidReason: string | null;
  createdBy: number | null;
  listedBy: number | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  options: MarketOption[];
  /** 现在提交上架 / 上架会缺哪些必填项（空 = 齐全） */
  missing: FieldError[];
  statusLog: StatusLog[];
  feeChanges: FeeChange[];
  clarifications: Clarification[];
  /** 列入今日场次（S13） */
  todaySlate: boolean;
  /** manual 手动 / autogen 加密自动生成 / sports 从赛程生成（S13 起详情也有） */
  origin: 'manual' | 'autogen' | 'sports';
}

/** 同时开放的市场数和上限（S13，MF-3，GET /api/admin/markets/open-limit；limit 0 = 不限） */
export interface OpenLimit {
  open: number;
  limit: number;
}

/** 上架（S13 起可以同时勾选"列入今日场次"、超过同时开放上限时"仍要上架"） */
export interface PublishInput {
  checked: boolean;
  todaySlate?: boolean;
  overLimit?: boolean;
}

export interface FeeChangeResult {
  oldRate: string;
  newRate: string;
  notified: number;
  market: MarketDetail;
}

// ---------------------------------------------------------------- 下注明细（BetAdminController）

export interface BetRow {
  betNo: string;
  userId: number;
  optionNo: string;
  seq: number;
  amount: string;
  receivedAt: string;
  status: 'active' | 'refunded';
  refundReason: string | null;
  clientReqId: string;
}

export interface OptionPool {
  optionNo: string;
  seq: number;
  amount: string;
  bettors: number;
  finalAmount: string | null;
  finalBettors: number | null;
}

export interface MarketBets {
  marketNo: string;
  status: MarketStatus;
  total: string;
  activeTotal: string;
  bettors: number;
  frozen: boolean;
  options: OptionPool[];
  bets: { items: Array<{ bet: BetRow; email: string | null; name: string | null }>; total: number; page: number; pageSize: number };
}

// ---------------------------------------------------------------- 加密自动生成（MarketAutogenAdminApi）

export interface AutogenWindow {
  windowStart: string;
  windowEnd: string;
  openAt: string;
  closeAt: string;
  generated: boolean;
  marketNo: string | null;
}

export interface SampleOption {
  outcome: string;
  label: I18nText;
  winCondition: I18nText;
}

export interface Autogen {
  autogenNo: string;
  symbol: string;
  period: '15M' | '1H';
  category: MarketCategory;
  mode: 'schedule' | 'rolling';
  slots: string[];
  rollingCount: number;
  generateAheadMinutes: number;
  templateNo: string;
  userCap: string | null;
  enabled: boolean;
  checkedBy: number | null;
  checkedAt: string | null;
  checkedTemplateVersion: number | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  upcoming: AutogenWindow[];
  sample: { title: I18nText; rules: I18nText; sourcePrimary: string; sourceBackup: string; options: SampleOption[] };
  missing: FieldError[];
}

export interface AutogenInput {
  symbol: string;
  period: '15M' | '1H';
  mode: 'schedule' | 'rolling';
  slots: string[];
  rollingCount: number | null;
  generateAheadMinutes: number | null;
  templateNo: string | null;
  userCap: string | null;
}

export interface RuleTemplate {
  templateNo: string;
  kind: string;
  name: string;
  title: I18nText;
  rules: I18nText;
  sourcePrimary: string;
  sourceBackup: string;
  options: SampleOption[];
  version: number;
  usedBy: string[];
  usedByEnabled: boolean;
  variables: string[];
  updatedAt: string;
}

// ---------------------------------------------------------------- 体育（SportsAdminApi）

export type Sport = 'FOOTBALL' | 'NBA';
export const PLAYS = ['FOOTBALL_1X2', 'FOOTBALL_OU', 'NBA_ML', 'NBA_TOTAL'] as const;
export type Play = (typeof PLAYS)[number];

export interface Fixture {
  fixtureNo: string;
  sport: Sport;
  competition: string;
  competitionName: string;
  home: { name: string; shortName: string };
  away: { name: string; shortName: string };
  kickoffAt: string;
  originalKickoffAt: string;
  kickoffChanged: boolean;
  status: string;
  rawStatus: string;
  primarySource: string;
  primaryRef: string;
  mapping: { source: string; ref: string; swapped: boolean; home: string; away: string; kickoffAt: string; confirmedBy: number | null; confirmedAt: string | null } | null;
  candidates: Array<{ source: string; ref: string; home: string; away: string; kickoffAt: string; league: string; score: number; swapped: boolean; suggested: boolean }>;
  candidatesAt: string | null;
  candidatesError: string | null;
  markets: Array<{ marketNo: string; play: Play; line: string | null; status: MarketStatus }>;
  fetchedAt: string;
}

export interface FixtureQuery extends PageQuery {
  sport?: Sport;
  mapping?: 'confirmed' | 'unconfirmed';
}

export interface SyncResult {
  sport: Sport;
  source: string;
  outcome: 'ok' | 'disabled' | 'failed' | 'throttled' | 'circuit_open';
  fetched: number;
  created: number;
  updated: number;
  kickoffChanged: number;
  candidatesSearched: number;
  error: string | null;
}

export interface PlayInput {
  play: Play;
  line: string | null;
}

export interface Generated {
  fixtureNo: string;
  created: Array<{ marketNo: string; play: Play; line: string | null; status: MarketStatus }>;
  warnings: string[];
}

export interface SportsTemplate {
  kind: Play;
  name: string;
  title: I18nText;
  rules: I18nText;
  sourcePrimary: string;
  sourceBackup: string;
  options: SampleOption[];
  version: number;
  updatedBy: number | null;
  updatedAt: string;
  variables: string[];
}

// ---------------------------------------------------------------- 裁决台（ResolveDeskApi、ResolveOps）

export type CaseTab = 'all' | 'awaiting_proposal' | 'awaiting_confirm' | 'auto_failed' | 'on_hold' | 'overdue' | 'publicizing' | 'disputed';
export const CASE_TABS: CaseTab[] = ['all', 'awaiting_proposal', 'awaiting_confirm', 'auto_failed', 'on_hold', 'overdue', 'publicizing', 'disputed'];

export interface Proposal {
  proposalId: string;
  outcome: 'result' | 'void';
  optionNo: string | null;
  basis: string;
  actualStartAt: string | null;
  evidenceUrls: string[];
  attachmentNos: string[];
  note: string;
  proposedBy: string;
  proposedAt: string;
  confirmDueAt: string;
  confirmOverdue: boolean;
}

export interface CaseItem {
  marketNo: string;
  category: MarketCategory;
  title: I18nText;
  marketStatus: MarketStatus;
  origin: 'platform' | 'auto_failed' | 'no_auto';
  stage: 'open' | 'publicizing' | 'disputed' | 'closed';
  hold: 'postponed' | 'abandoned' | null;
  holdUntil: string | null;
  proposeDueAt: string | null;
  proposeOverdue: boolean;
  proposal: Proposal | null;
  resultOptionNo: string | null;
  disputeDeadlineAt: string | null;
  openDisputes: number;
  disputeDueAt: string | null;
  disputeOverdue: boolean;
  overdue: boolean;
  eventEndAt: string;
  updatedAt: string;
}

export interface ResolveRecord {
  id: string;
  kind: string;
  optionNo: string | null;
  refId: string | null;
  basis: string | null;
  actualStartAt: string | null;
  evidenceUrls: string[];
  attachmentNos: string[];
  note: string | null;
  actor: string;
  time: string;
  detail: unknown;
}

export interface Dispute {
  disputeNo: string;
  marketNo: string;
  userId: number;
  claimedOptionNo: string | null;
  claimVoid: boolean;
  reason: string;
  link: string | null;
  status: string;
  decisionNote: string | null;
  handledBy: number | null;
  handledAt: string | null;
  receivedAt: string;
}

export interface Attachment {
  attachmentNo: string;
  contentType: string;
  size: number;
  sha256: string;
  uploadedBy: number;
  createdAt: string;
}

export interface CaseDetail {
  summary: CaseItem;
  options: Array<{ optionNo: string; seq: number; label: I18nText }>;
  actualStartAt: string | null;
  resolvedBy: string | null;
  confirmedAt: string | null;
  decision: 'uphold' | 'overturn' | 'void' | null;
  records: ResolveRecord[];
  disputes: Dispute[];
  attachments: Attachment[];
}

/** 结算预览（SettleAdminApi.Preview） */
export interface SettlePreview {
  marketNo: string;
  optionNo: string;
  actualStartAt: string | null;
  outcome: 'settle' | 'void_no_winner' | 'void_no_pool';
  feeRate: string;
  poolTotal: string;
  winnerPool: string;
  loserPool: string;
  sumGross: string;
  sumFee: string;
  dust: string;
  sumPayout: string;
  platformIncome: string;
  winnerPositions: number;
  loserPositions: number;
  conserved: boolean;
  lateBets: number;
  lateAmount: string;
  staffBets: number;
  staffAmount: string;
}

export interface CaseView {
  detail: CaseDetail;
  proposalPreview: SettlePreview | null;
}

export interface ProposalInput {
  optionNo: string | null;
  voidMarket: boolean;
  basis: string | null;
  actualStartAt: string | null;
  evidenceUrls: string[];
  attachmentNos: string[];
  note: string;
}

export const RESULT_BASES = ['NORMAL', 'E6_COMPLETED', 'E7_OFFICIAL', 'E11_USUAL'];
export const VOID_BASES = ['E5_CANCELLED', 'E6_TIMEOUT', 'E7_NO_RESULT', 'E10_UNDETERMINED', 'E11_UNDETERMINABLE', 'OTHER'];

/** 自动裁决的依据（ResolveAdminApi.Evidence），裁决台"两个来源的原始数据"用 */
export interface Evidence {
  marketNo: string;
  task: { status: string; attempts: number; lastReason: string | null; deadlineAt: string | null } | null;
  records: Array<{ id: number; kind: string; optionNo: string | null; detail: unknown; note: string | null; actor: string; time: string }>;
  fetches: Array<{
    id: number;
    source: string;
    attempt: number;
    requestUrl: string;
    httpStatus: number | null;
    outcome: string;
    parsed: unknown;
    error: string | null;
    bodySha256: string | null;
    fetchedAt: string;
    elapsedMs: number;
  }>;
  sports: { status: string; backupRef: string | null; lastReason: string | null; actualStartAt: string | null } | null;
}

// ---------------------------------------------------------------- 结算、补偿、对账、报表（SettleAdminApi）

export interface SettleRun {
  runNo: string;
  marketNo: string;
  kind: 'payout' | 'refund';
  status: 'paying' | 'done' | 'abnormal';
  reason: string | null;
  winningOptionNo: string | null;
  feeRate: string;
  poolTotal: string;
  winnerPool: string;
  loserPool: string;
  sumGross: string;
  sumFee: string;
  dust: string;
  sumPayout: string;
  platformIncome: string;
  winnerPositions: number;
  loserPositions: number;
  itemCount: number;
  pendingItems: number;
  conservationOk: boolean;
  abnormalReason: string | null;
  batchSize: number;
  resolvedBy: string | null;
  calculatedAt: string;
  doneAt: string | null;
}

export interface SettleRunDetail {
  run: SettleRun;
  payoutItems: { items: Array<{ userId: number; optionNo: string; win: boolean; stake: string; gross: string; fee: string; payout: string; status: string }>; total: number; page: number; pageSize: number } | null;
  refundItems: { items: Array<{ userId: number; betNo: string; amount: string; reason: string; status: string }>; total: number; page: number; pageSize: number } | null;
}

export interface PoolIssue {
  id: number;
  marketNo: string | null;
  refKey: string;
  checkDate: string;
  kind: string;
  source: string;
  expected: string;
  actual: string;
  diff: string;
  detail: string | null;
  status: 'open' | 'resolved';
  resolvedBy: number | null;
  resolvedAt: string | null;
  note: string | null;
  createdAt: string;
}

export interface CompLine {
  userId: number;
  shouldPayout: string;
  actualPayout: string;
  amount: string;
}

export interface CompPlan {
  marketNo: string;
  originalOptionNo: string;
  correctOptionNo: string;
  feeRate: string;
  total: string;
  userCount: number;
  lines: CompLine[];
}

export interface Compensation extends CompPlan {
  compensationNo: string;
  reason: string;
  createdBy: number;
  createdAt: string;
}

export interface SettleOutcome {
  marketNo: string;
  marketStatus: MarketStatus;
  settlePhase: string;
  payout: SettleRun | null;
  refund: SettleRun | null;
}

export interface ReportDay {
  date: string;
  feeIncome: string;
  dust: string;
  revenue: string;
  compensation: string;
  compensations: number;
  /** S17A 测试入金（只在测试环境有；单列，不是平台收入、不是真实充值、不是补偿） */
  testCredit: string;
  testCredits: number;
  /** S17B 人工加款、人工扣款（单列，不进平台收入、不冲减补偿） */
  manualCredit: string;
  manualCredits: number;
  manualDebit: string;
  manualDebits: number;
}

export interface Report {
  from: string;
  to: string;
  feeIncome: string;
  dust: string;
  revenue: string;
  compensationExpense: string;
  compensations: number;
  testCredit: string;
  testCredits: number;
  manualCredit: string;
  manualCredits: number;
  manualDebit: string;
  manualDebits: number;
  days: ReportDay[];
}

// ---------------------------------------------------------------- 人工调账（S17B，后端 admin/ops/AdjustOps）

export type AdjustDirection = 'credit' | 'debit';
export type AdjustCategory = 'deposit_missing' | 'wrong_credit' | 'other';
/** 每个方向能选的类别（和后端 ManualAdjustApi.Category.allows 一致） */
export const ADJUST_CATEGORIES: Record<AdjustDirection, AdjustCategory[]> = { credit: ['deposit_missing', 'other'], debit: ['wrong_credit', 'other'] };

/** 一笔人工调整（列表和结果共用） */
export interface Adjustment {
  adjustNo: string;
  customerId: number;
  customerEmail: string | null;
  customerName: string | null;
  direction: AdjustDirection;
  category: AdjustCategory;
  amount: string;
  availableBefore: string;
  availableAfter: string;
  refNo: string | null;
  reason: string;
  overLimit: boolean;
  operator: string;
  createdAt: string;
}

export interface AdjustInput {
  direction: AdjustDirection;
  category: AdjustCategory;
  amount: string;
  refNo: string;
  reason: string;
  requestId: string;
  overLimitConfirmed: boolean;
}

export interface AdjustQuery {
  customerId?: string;
  direction?: AdjustDirection;
  category?: AdjustCategory;
  from?: string;
  to?: string;
  page: number;
  pageSize: number;
}

/** 对账页"平台账户"（S17A）：balance 余额、ledgerTotal 账本合计，matches = 两者相等 */
export interface PlatformAccount {
  kind: 'revenue' | 'compensation' | 'test_credit' | string;
  balance: string;
  ledgerTotal: string;
  entries: number;
  matches: boolean;
}

// ---------------------------------------------------------------- 仪表盘（MarketDashboardOps）

export interface DashPeriod {
  from: string;
  to: string;
  betAmount: string;
  bets: number;
  bettors: number;
  revenue: string;
  compensation: string;
  registrations: number;
  deposit: string;
  depositCount: number;
  withdraw: string;
  withdrawCount: number;
}

export interface DashPending {
  review: number;
  awaitingProposal: number;
  awaitingConfirm: number;
  autoFailed: number;
  overdue: number;
  publicizing: number;
  disputed: number;
  settleAbnormal: number;
  poolReconOpen: number;
  withdrawalsPending: number;
}

export interface MarketDashboard {
  today: DashPeriod;
  week: DashPeriod;
  pending: DashPending;
  /** S13（SQ-21）：同时开放的市场数和上限（0 = 不限）、今天（UTC）列入今日场次的市场数 */
  slate: { open: number; limit: number; today: number };
  updatedAt: string;
}

// ---------- S13：来源统计（MF-1、MF-10；后端 growth.api.GrowthAdminApi） ----------
export interface GrowthCohort {
  week: string;
  src: string;
  cmp: string;
  ref: string;
  /** S15：分享人的客户编号（ref 是分享码时换算出来的；旧链接 src=share 的纯数字 ref 原样；渠道编号为空） */
  refCustomer?: string | null;
  visits: number;
  registrations: number;
  firstDeposits: number;
  activated: number;
  week2Active: number;
  week2Eligible: number;
}
export interface GrowthMarketWeek {
  week: string;
  ended: number;
  settled: number;
  voided: number;
  cancelled: number;
  validRate: string | null;
  medianBettors: string | null;
}
export interface GrowthMarketRow {
  marketNo: string;
  category: MarketCategory;
  titleZhCN: string;
  titleEn: string;
  status: 'settled' | 'voided' | 'cancelled';
  endedAt: string;
  bettors: number;
  pool: string;
  reason: string | null;
}
export interface GrowthReport {
  from: string;
  to: string;
  basis: 'first' | 'reg';
  cohorts: GrowthCohort[];
  totals: Omit<GrowthCohort, 'week' | 'src' | 'cmp' | 'ref' | 'refCustomer'>;
  marketWeeks: GrowthMarketWeek[];
  reasons: Array<{ status: 'voided' | 'cancelled'; reason: string | null; count: number }>;
  markets: GrowthMarketRow[];
  truncated: boolean;
}
export interface GrowthQuery {
  from?: string;
  to?: string;
  basis?: 'first' | 'reg';
}
export type GrowthExportKind = 'sources' | 'markets' | 'customers';

// ---------------------------------------------------------------- 风控告警、员工账户（forecast S10，后端 admin/ops/RiskOps、CustomerOps）

/** 告警类型：尾盘大额、关联账户对押、新账户押中冷门、充了不玩就提（需求第 8.1 节） */
export type RiskAlertKind = 'late_large_bet' | 'linked_opposite' | 'new_account_longshot' | 'deposit_no_bet' | 'manual_credit_large';
export const RISK_ALERT_KINDS: RiskAlertKind[] = ['late_large_bet', 'linked_opposite', 'new_account_longshot', 'deposit_no_bet', 'manual_credit_large'];
/** 处理状态：新、已查看、已处理、误报 */
export type RiskAlertStatus = 'new' | 'seen' | 'handled' | 'false_positive';

export interface RiskAlert {
  id: string;
  kind: RiskAlertKind;
  userId: string;
  customer: string | null;
  marketNo: string | null;
  market: string | null;
  amount: string | null;
  /** 判定时的数字（键随类型：poolAfter / pct / limitPct / secondsBeforeClose；linkedUsers / options；odds / accountDays；deposit / stake / multiple / withdrawalNo） */
  detail: Record<string, string>;
  status: RiskAlertStatus;
  note: string | null;
  handledBy: string | null;
  handledAt: string | null;
  createdAt: string;
}

export interface RiskAlertQuery {
  kind?: RiskAlertKind;
  status?: RiskAlertStatus;
  keyword?: string;
  page: number;
  pageSize: number;
}

export interface RiskAlertCounts {
  /** 待处理（新 + 已查看）合计 */
  open: number;
  openByKind: Record<RiskAlertKind, number>;
  byStatus: Record<RiskAlertStatus, number>;
}

/** 关联账户的一行（客户详情） */
export interface CustomerLink {
  userId: string;
  customer: string | null;
  reason: 'device' | 'ip' | 'deposit_from' | 'withdraw_to';
  evidence: string;
  firstSeenAt: string;
}

/** 员工登记的结果：退回的下注（E15，只有登记时有） */
export interface StaffRefund {
  markets: number;
  bets: number;
  amount: string;
}

// ---------------------------------------------------------------- 内容中心（forecast S15，事件和文章自动化程序；后端 content.api.ContentAdminApi）

export type ContentKind = 'PREVIEW' | 'BRIEF' | 'SEO_INTRO' | 'ARTICLE' | 'POST_X' | 'POST_TG' | 'REPLY';
export const CONTENT_KINDS: ContentKind[] = ['PREVIEW', 'BRIEF', 'SEO_INTRO', 'ARTICLE', 'POST_X', 'POST_TG', 'REPLY'];
/** 能手动生成的（回复草稿在"工具"里生成） */
export const GENERATE_KINDS: ContentKind[] = ['PREVIEW', 'BRIEF', 'SEO_INTRO', 'ARTICLE', 'POST_X', 'POST_TG'];
export type DraftStatus = 'GENERATING' | 'PENDING' | 'PUBLISHED' | 'REJECTED' | 'EXPIRED' | 'FAILED';
export const DRAFT_STATUSES: DraftStatus[] = ['PENDING', 'GENERATING', 'PUBLISHED', 'FAILED', 'REJECTED', 'EXPIRED'];

export interface ContentRef {
  key: string;
  label: string;
  labelEn: string;
  value: string;
  source: string;
  at: string;
}
export interface NumberHit {
  field: 'title' | 'body';
  text: string;
  start: number;
  end: number;
}
export interface DraftChecks {
  numbers: NumberHit[];
  banned: string[];
  tendency: string[];
  polymarketNoteMissing: boolean;
  maxLength: number;
  actualLength: number;
  missing: string[];
  summary: string;
}
export interface ContentDraft {
  draftNo: string;
  groupNo: string;
  kind: ContentKind;
  lang: 'zh' | 'en';
  title: string;
  body: string;
  refs: ContentRef[];
  checks: DraftChecks | null;
  checkOk: boolean;
  status: DraftStatus;
  failReason: string | null;
  rejectReason: string | null;
  marketNo: string | null;
  pageSlug: string | null;
  topic: string | null;
  expiresAt: string | null;
  expired: boolean;
  aiCalls: number;
  createdAt: string;
  createdBy: number | null;
  publishedAt: string | null;
  publishedBy: number | null;
  version: number;
  canPublish: boolean;
  copyOnly: boolean;
  canCopy: boolean;
  link: string | null;
  siteUrl: string | null;
}
export interface DraftQuery {
  status?: DraftStatus;
  kind?: ContentKind;
  lang?: 'zh' | 'en';
  page: number;
  pageSize: number;
}
export interface GenerateInput {
  kind: ContentKind;
  marketNo?: string;
  pageSlug?: string;
  topic?: string;
}
export interface ContentStatus {
  contentEnabled: boolean;
  geminiConfigured: boolean;
  geminiState: 'ok' | 'unconfigured' | 'paused' | 'quota' | 'disabled';
  model: string;
  usedToday: number;
  limit: number;
  contentCap: number;
  contentUsedToday: number;
  scoreUsedToday: number;
  failuresToday: number;
  monthCalls: number;
  monthCostUsd: string;
  costAlertUsd: string;
  costAlert: boolean;
  stopped: boolean;
  pagesToday: number;
  pagesDaily: number;
  articlesThisWeek: number;
  articlesWeekly: number;
  polymarketEnabled: boolean;
  queue: number;
  pausedUntil: string | null;
  settings: Record<string, string>;
}
export type ContentWordKind = 'BANNED' | 'TENDENCY' | 'SENSITIVE';
export interface ContentWord {
  id: string;
  kind: ContentWordKind;
  word: string;
  builtIn: boolean;
}
export type EconKind = 'FOMC' | 'CPI';
export type EconStatus = 'SCHEDULED' | 'DRAFTING' | 'DRAFTED' | 'FILTERED' | 'FAILED' | 'CANCELLED' | 'EXPIRED';
export interface EconEvent {
  id: string;
  kind: EconKind;
  period: string;
  releaseAt: string;
  prevValue: string | null;
  sourceUrl: string | null;
  status: EconStatus;
  marketNo: string | null;
  marketStatus: string | null;
  note: string | null;
  draftDueAt: string;
  closeAt: string;
  version: number;
}
export interface EconInput {
  kind: EconKind;
  period: string;
  releaseAt: string;
  prevValue?: string;
  sourceUrl?: string;
  version?: number;
}
export interface FocusSuggestion {
  fixtureNo: string;
  sport: string;
  competition: string;
  competitionName: string;
  home: string;
  away: string;
  kickoffAt: string;
  mappingConfirmed: boolean;
  markets: number;
  suggestedPlay: string;
  reasons: string[];
}
export interface CandidateFixture {
  fixtureNo: string;
  sport: string;
  competitionName: string;
  home: string;
  away: string;
  kickoffAt: string;
  mappingConfirmed: boolean;
  markets: number;
  filtered: boolean;
  filteredBy: string | null;
}
export interface FilteredItem {
  source: 'SPORTS' | 'ECON';
  ref: string;
  title: string;
  reason: 'CATEGORY' | 'KEYWORD';
  matched: string;
  createdAt: string;
}
export interface ContentCandidates {
  focus: FocusSuggestion[];
  fixtures: CandidateFixture[];
  econ: EconEvent[];
  filtered: FilteredItem[];
  sportsError: string | null;
}
export interface EconTemplateOption {
  label: I18nText;
  winCondition: I18nText;
}
export interface EconTemplate {
  code: EconKind;
  title: I18nText;
  rules: I18nText;
  options: EconTemplateOption[];
  sourcePrimary: string;
  sourceBackup: string;
  version: number;
  updatedBy: number | null;
  updatedAt: string | null;
  variables: string[];
}
export interface EconTemplateInput {
  title: I18nText;
  rules: I18nText;
  options: EconTemplateOption[];
  sourcePrimary: string;
  sourceBackup: string;
  version: number;
}
export interface ContentPageRow {
  slug: string;
  kind: 'FOCUS' | 'BTC_STATS' | 'ARTICLE';
  marketNo: string | null;
  titleZhCN: string;
  titleEn: string;
  introLangs: Array<'zh' | 'en'>;
  bodyLangs: Array<'zh' | 'en'>;
  dataAt: string | null;
  createdDay: string;
  status: 'VISIBLE' | 'HIDDEN';
  path: string;
}
export interface ContentPages {
  items: ContentPageRow[];
  createdToday: number;
  dailyLimit: number;
}
export interface ToolResult {
  /** PNG 的 data URL（只能下载） */
  image: string;
  text: string;
  refs: ContentRef[];
  replies: ContentDraft[];
  notes: string[];
}
export interface ContentLogRow {
  id: string;
  action: string;
  kind: string | null;
  ref: string | null;
  lang: string | null;
  outcome: 'OK' | 'FAILED' | 'BLOCKED' | 'SKIPPED' | 'PENDING';
  detail: string;
  aiCalls: number;
  adminId: number | null;
  createdAt: string;
}
export interface ContentLogQuery {
  from?: string;
  to?: string;
  action?: string;
  page: number;
  pageSize: number;
}
// ---------- S16：等候页订阅（MF-8；后端 growth.api.WaitlistAdminApi）、Telegram 机器人（MF-7；growth.api.TelegramAdminApi） ----------
export type WaitlistStatus = 'pending' | 'confirmed' | 'notified' | 'unsubscribed';
export interface WaitlistItem {
  id: number;
  /** 打码后的邮箱（a***@example.com） */
  email: string;
  lang: 'en' | 'zh-CN' | 'zh-TW';
  status: WaitlistStatus;
  src: string | null;
  cmp: string | null;
  ref: string | null;
  start: string | null;
  createdAt: string;
  confirmedAt: string | null;
  notifiedAt: string | null;
  unsubscribedAt: string | null;
}
export interface WaitlistCounts {
  pending: number;
  confirmed: number;
  notified: number;
  unsubscribed: number;
}
/** 上线通知（全站只发一次）：none 还没发 / sending 发送中 / done 发完了 */
export interface WaitlistLaunch {
  status: 'none' | 'sending' | 'done';
  at: string | null;
  adminId: number | null;
  reason: string | null;
  recipients: number;
  sent: number;
  finishedAt: string | null;
}
export interface WaitlistPage {
  items: WaitlistItem[];
  total: number;
  page: number;
  pageSize: number;
  counts: WaitlistCounts;
  launch: WaitlistLaunch;
}
/**
 * forecast S19（P15）：从 Cloudflare 导入等候页名单。文件是 Cloudflare 上 /api/admin/export.json 下载的（格式 fc-cf-waitlist-v1，cf/src/admin.ts），
 * 页面读文件、解析后原样提交；后端 POST /api/admin/waitlist/import（content:publish ★）。
 */
export const CF_IMPORT_FORMAT = 'fc-cf-waitlist-v1';
export interface CfImportItem {
  email: string;
  lang: string;
  status: 'confirmed' | 'unsubscribed';
  src: string | null;
  cmp: string | null;
  ref: string | null;
  start: string | null;
  createdAt: string | null;
  confirmedAt: string | null;
  unsubscribedAt: string | null;
  unsubToken: string | null;
  ipHash: string | null;
}
export interface CfImportFile {
  format: string;
  exportedAt?: string;
  /** 这一份后面还有（Cloudflare 每份最多 2000 个）：导入后再下载 next 那一份 */
  next?: string | null;
  items: CfImportItem[];
}
/** 文件里第 row 个（从 1 起）的 field 不对：emailInvalid / statusInvalid / timeInvalid / missing */
export interface CfImportProblem {
  row: number;
  field: string;
  code: string;
}
export interface CfImportResult {
  total: number;
  added: number;
  confirmed: number;
  unsubscribed: number;
  unchanged: number;
  notAdded: number;
  invalid: number;
  /** 上线通知已经发完以后才变成已确认的：不会再收到上线通知 */
  afterLaunch: number;
  problems: CfImportProblem[];
  launch: WaitlistLaunch;
}
export interface WaitlistQuery {
  status?: WaitlistStatus;
  page: number;
  pageSize: number;
}

export interface TelegramStatus {
  /** 配置了 FC_TELEGRAM_BOT_TOKEN（没配置时机器人不启动） */
  configured: boolean;
  botUsername: string | null;
  healthy: boolean;
  lastError: string | null;
  lastOkAt: string | null;
  emergencyStop: boolean;
  stopReason: string | null;
  stopAt: string | null;
  stopBy: number | null;
}
export type TelegramTargetKey = 'CHANNEL_ZH' | 'CHANNEL_EN';
export interface TelegramTarget {
  key: TelegramTargetKey;
  lang: 'zh-CN' | 'en';
  chatId: string | null;
  enabled: boolean;
  enabledAt: string | null;
  failStreak: number;
  disabledReason: string | null;
  updatedAt: string | null;
}
export interface TelegramGroup {
  chatId: string;
  title: string;
  type: string;
  status: 'active' | 'left';
  broadcast: boolean;
  lang: 'zh-CN' | 'en';
  startParam: string | null;
  addedAt: string;
  broadcastAt: string | null;
}
export interface TelegramTemplate {
  kind: 'RESULT';
  lang: 'zh-CN' | 'en';
  status: 'draft' | 'approved' | 'disabled';
  /** 已审定，但审定以后模板文字改过（按草稿处理，要重新审定） */
  changed: boolean;
  approvedAt: string | null;
  approvedBy: number | null;
}
export interface TelegramOverview {
  status: TelegramStatus;
  targets: TelegramTarget[];
  groups: TelegramGroup[];
  templates: TelegramTemplate[];
}
export type TelegramPostStatus = 'pending' | 'sent' | 'failed' | 'skipped' | 'stopped';
export interface TelegramPost {
  id: number;
  marketNo: string;
  kind: string;
  target: string;
  lang: string;
  status: TelegramPostStatus;
  attempts: number;
  postUrl: string | null;
  note: string | null;
  createdAt: string;
  sentAt: string | null;
}
export interface TelegramPreview {
  kind: 'RESULT';
  lang: 'zh-CN' | 'en';
  marketNo: string;
  text: string;
  /** 这一版模板文字的指纹，审定时原样带回 */
  fingerprint: string;
}
