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
}

export interface MarketQuery extends PageQuery {
  /** 小写状态，可以逗号分隔多个 */
  status?: string;
  category?: MarketCategory;
  submitted?: boolean;
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
}

export interface Report {
  from: string;
  to: string;
  feeIncome: string;
  dust: string;
  revenue: string;
  compensationExpense: string;
  compensations: number;
  days: ReportDay[];
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
  updatedAt: string;
}

// ---------------------------------------------------------------- 风控告警、员工账户（forecast S10，后端 admin/ops/RiskOps、CustomerOps）

/** 告警类型：尾盘大额、关联账户对押、新账户押中冷门、充了不玩就提（需求第 8.1 节） */
export type RiskAlertKind = 'late_large_bet' | 'linked_opposite' | 'new_account_longshot' | 'deposit_no_bet';
export const RISK_ALERT_KINDS: RiskAlertKind[] = ['late_large_bet', 'linked_opposite', 'new_account_longshot', 'deposit_no_bet'];
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
