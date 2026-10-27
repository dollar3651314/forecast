/**
 * forecast S9 后台页面的模拟层（原型预览站、单元测试）：数据结构和后端完全一致（api/fcTypes.ts），只是放在内存里。
 * 数据包含每种状态的市场（待上架、草稿、可下注、暂停、截止、待裁决、公示中、争议中、已结算、已取消、已作废），
 * 加密自动生成和赛程生成的市场；裁决台的待提议 / 待确认 / 自动裁决失败 / 等待中 / 超时；争议、结算单、对账差异、补偿、报表。
 * 结算预览按需求第 3.5 节逐个持仓算（盈利向下取整、抽成四舍五入、尾差归平台），和后端 SettleMath 同一口径。
 * 市场数量超过一页（30 个），用来看分页。场景（空 / 慢 / 出错）由 runtime.ts 控制。
 */
import { ApiError, type Page } from '../api/types';
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
  MarketCategory,
  MarketCounts,
  MarketDashboard,
  MarketDetail,
  MarketQuery,
  MarketStatus,
  MarketSummary,
  OpenLimit,
  PublishInput,
  GrowthQuery,
  GrowthReport,
  GrowthExportKind,
  GrowthCohort,
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
  RiskAlertKind,
  RiskAlertQuery,
  RiskAlertStatus,
  StaffRefund,
  TelegramGroup,
  TelegramOverview,
  TelegramPost,
  TelegramPostStatus,
  TelegramPreview,
  TelegramStatus,
  TelegramTarget,
  TelegramTargetKey,
  TelegramTemplate,
  WaitlistItem,
  WaitlistLaunch,
  WaitlistPage,
  WaitlistQuery,
  PlatformAccount,
  Adjustment,
  AdjustInput,
  AdjustQuery,
} from '../api/fcTypes';
import type { CustomerDetail, TestCreditResult } from '../api/types';
import { getCustomer as mockGetCustomer } from './handlers';
import { MARKET_STATUSES } from '../api/fcTypes';
import { fromScaled, toScaled } from '../utils/money';
import { downloadCsv } from '../utils/csv';
import { normalizeSlot, slotProblems } from '../utils/market';
import { emptyPage, matches, mutate, paginate, respond } from './runtime';
import { customers as mockCustomers, params as mockParams } from './data/db';

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;
/** 固定基准时间（刷新后编号、时间不变）：2026-10-25 08:00 UTC */
const BASE = Date.UTC(2026, 9, 25, 8, 0, 0);
const iso = (ms: number) => new Date(ms).toISOString().replace('.000Z', 'Z');
const L = (zhCN: string, en: string, zhTW: string): I18nText => ({ zhCN, en, zhTW });
const cents = (v: string) => toScaled(v, 2) ?? 0n;
const money = (c: bigint) => fromScaled(c, 2);

// ---------------------------------------------------------------- 市场

interface Pos {
  userId: number;
  option: number;
  amount: string;
}

interface Mkt {
  d: MarketDetail;
  origin: MarketSummary['origin'];
  positions: Pos[];
}

let seq = 0;
const no = (prefix: string) => `${prefix}${String(++seq).padStart(4, '0')}`;

function opt(n: number, label: I18nText, win: I18nText) {
  return { optionNo: `${n}`, seq: 0, label, winCondition: win };
}

function makeMarket(p: {
  category: MarketCategory;
  title: I18nText;
  status: MarketStatus;
  closeIn: number;
  options: Array<[I18nText, I18nText]>;
  mode?: 'AUTO' | 'PLATFORM';
  submitted?: boolean;
  origin?: MarketSummary['origin'];
  todaySlate?: boolean;
  positions?: Array<[number, number, string]>;
  feeRate?: string;
  missing?: Array<{ field: string; code: string }>;
}): Mkt {
  const publicNo = `1079${no('0000000000')}`;
  const close = BASE + p.closeIn;
  const mode = p.mode ?? (p.category === 'MACRO' ? 'PLATFORM' : 'AUTO');
  const options = p.options.map(([label, win], i) => ({ ...opt(0, label, win), optionNo: `${publicNo}${i + 1}`, seq: i + 1 }));
  const d: MarketDetail = {
    publicNo,
    category: p.category,
    title: p.title,
    rules: L(
      '判定：以首选来源公布的官方结果为准，首选来源不可用时用备用来源。\n取消 / 作废：比赛取消、延期超过 48 小时未完赛、无法判定的，全额退款。\n数据修正：结算后来源修正数据的，按 E13 由平台补偿，只补不扣。',
      'Resolution: the official result published by the primary source; the backup source is used if the primary is unavailable.\nCancel / void: cancelled events, events not completed within 48 hours of a postponement, or undeterminable results are fully refunded.\nCorrections: if the source corrects the data after settlement, the platform compensates under E13 (top-ups only).',
      '判定：以首選來源公布的官方結果為準，首選來源不可用時用備用來源。\n取消 / 作廢：比賽取消、延期超過 48 小時未完賽、無法判定的，全額退款。\n數據修正：結算後來源修正數據的，按 E13 由平台補償，只補不扣。',
    ),
    status: p.status,
    submitted: !!p.submitted,
    settlePhase: p.status === 'settled' ? 'DONE' : 'NONE',
    resolutionMode: mode,
    sourcePrimary: p.category.startsWith('CRYPTO') ? 'Binance spot 1m klines' : p.category === 'MACRO' ? 'federalreserve.gov' : 'football-data.org',
    sourceBackup: p.category.startsWith('CRYPTO') ? 'OKX spot 1m candles' : p.category === 'MACRO' ? 'reuters.com' : 'thesportsdb.com',
    sourceRef: null,
    openAt: iso(close - 3 * DAY),
    closeAt: iso(close),
    eventStartAt: iso(close + 5 * MIN),
    eventEndAt: iso(close + 2 * HOUR),
    feeRate: p.feeRate ?? '5',
    userCap: p.category.startsWith('CRYPTO') ? '2000.00' : '5000.00',
    disputeWindowMinutes: mode === 'PLATFORM' ? 120 : null,
    coverPath: null,
    cancelReason: p.status === 'cancelled' ? '主办方宣布比赛取消' : null,
    voidReason: p.status === 'voided' ? 'POSTPONED_48H' : null,
    createdBy: p.origin === 'autogen' ? null : 2,
    listedBy: p.status === 'draft' || p.origin === 'autogen' ? null : 1,
    version: 3,
    createdAt: iso(close - 5 * DAY),
    updatedAt: iso(close - 2 * DAY),
    options,
    missing: p.missing ?? [],
    statusLog: [],
    feeChanges: [],
    clarifications: [],
    todaySlate: p.todaySlate ?? false,
    origin: p.origin ?? 'manual',
  };
  const log = (from: string | null, to: string, actor: string, at: number, reason: string | null = null) => d.statusLog.push({ from, to, actor, reason, time: iso(at) });
  if (p.status !== 'draft') log('draft', 'listed', p.origin === 'autogen' ? 'AUTOGEN' : '1', close - 3 * DAY);
  if (p.status === 'paused') log('listed', 'paused', '1', close - DAY, '数据源中断，暂停下注');
  if (['closed', 'pending_resolution', 'publicizing', 'disputed', 'settled', 'voided'].includes(p.status)) log('listed', 'closed', 'SYSTEM', close);
  if (['pending_resolution', 'publicizing', 'disputed', 'settled', 'voided'].includes(p.status)) log('closed', 'pending_resolution', 'SYSTEM', close + 2 * HOUR);
  if (['publicizing', 'disputed'].includes(p.status) || (p.status === 'settled' && mode === 'PLATFORM')) log('pending_resolution', 'publicizing', '1', close + 3 * HOUR);
  if (p.status === 'disputed') log('publicizing', 'disputed', 'CUSTOMER', close + 4 * HOUR);
  if (p.status === 'settled') log(mode === 'PLATFORM' ? 'publicizing' : 'pending_resolution', 'settled', mode === 'PLATFORM' ? 'SYSTEM' : 'AUTO', close + 6 * HOUR);
  if (p.status === 'cancelled') log('listed', 'cancelled', '1', close - DAY, '主办方宣布比赛取消');
  if (p.status === 'voided') log('pending_resolution', 'voided', 'SYSTEM', close + 50 * HOUR, 'POSTPONED_48H');
  const positions = (p.positions ?? []).map(([userId, option, amount]) => ({ userId, option, amount }));
  return { d, origin: p.origin ?? 'manual', positions };
}

const T = {
  home: L('主胜', 'Home', '主勝'),
  draw: L('平局', 'Draw', '平局'),
  away: L('客胜', 'Away', '客勝'),
  over: L('大（≥ 3 球）', 'Over (3+ goals)', '大（≥ 3 球）'),
  under: L('小（≤ 2 球）', 'Under (≤ 2 goals)', '小（≤ 2 球）'),
  up: L('涨', 'Up', '漲'),
  down: L('跌', 'Down', '跌'),
  yes: L('是', 'Yes', '是'),
  no: L('否', 'No', '否'),
};
const W = (s: string) => L(`${s}时赢`, `Wins if ${s}`, `${s}時贏`);
const POS = (n: number, base = 100): Array<[number, number, string]> =>
  Array.from({ length: n }, (_, i) => [100200 + i, i % 2, String(base + (i * 37) % 500)] as [number, number, string]);

function seedMarkets(): Mkt[] {
  // 先建一批已结算的加密市场（编号小、排在列表后面），让列表超过一页
  const older: Mkt[] = [];
  for (let i = 2; i <= 16; i++) {
    older.push(
      makeMarket({
        category: 'CRYPTO_1H',
        title: L(`BTC 涨还是跌：2026-10-${String(25 - i).padStart(2, '0')} 13:00–14:00 UTC`, `BTC Up or Down: 2026-10-${String(25 - i).padStart(2, '0')} 13:00–14:00 UTC`, `BTC 漲還是跌：2026-10-${String(25 - i).padStart(2, '0')} 13:00–14:00 UTC`),
        status: 'settled',
        closeIn: -i * DAY,
        origin: 'autogen',
        options: [[T.up, W('结束价 ≥ 开始价')], [T.down, W('结束价 < 开始价')]],
        positions: POS(4 + (i % 5), 30),
      }),
    );
  }
  const list: Mkt[] = [
    makeMarket({ category: 'NBA', title: L('勇士 vs 湖人（胜负，含加时）', 'Warriors vs Lakers (winner, incl. OT)', '勇士 vs 湖人（勝負，含加時）'), status: 'draft', submitted: true, closeIn: 4 * DAY, origin: 'sports', options: [[L('勇士', 'Warriors', '勇士'), W('勇士胜')], [L('湖人', 'Lakers', '湖人'), W('湖人胜')]] }),
    makeMarket({ category: 'FOOTBALL', title: L('阿森纳 vs 热刺（90 分钟胜平负）', 'Arsenal vs Tottenham (90-min result)', '阿森納 vs 熱刺（90 分鐘勝平負）'), status: 'draft', submitted: true, closeIn: 6 * DAY, origin: 'sports', options: [[T.home, W('主队胜')], [T.draw, W('打平')], [T.away, W('客队胜')]] }),
    makeMarket({ category: 'MACRO', title: L('美国 11 月非农就业 ≥ 15 万？', 'US November payrolls ≥ 150k?', '美國 11 月非農就業 ≥ 15 萬？'), status: 'draft', closeIn: 9 * DAY, options: [[T.yes, W('≥ 15 万')], [T.no, W('< 15 万')]], missing: [{ field: 'rules.zhTW', code: 'required' }, { field: 'sourceBackup', code: 'required' }] }),
    makeMarket({ category: 'FOOTBALL', title: L('曼城 vs 利物浦（90 分钟胜平负）', 'Man City vs Liverpool (90-min result)', '曼城 vs 利物浦（90 分鐘勝平負）'), status: 'listed', closeIn: 2 * DAY, origin: 'sports', todaySlate: true, options: [[T.home, W('主队胜')], [T.draw, W('打平')], [T.away, W('客队胜')]], positions: POS(24, 200) }),
    makeMarket({ category: 'CRYPTO_1H', title: L('BTC 涨还是跌：2026-10-25 13:00–14:00 UTC', 'BTC Up or Down: 2026-10-25 13:00–14:00 UTC', 'BTC 漲還是跌：2026-10-25 13:00–14:00 UTC'), status: 'listed', closeIn: 5 * HOUR - 30_000, origin: 'autogen', todaySlate: true, options: [[T.up, W('结束价 ≥ 开始价')], [T.down, W('结束价 < 开始价')]], positions: POS(12, 50) }),
    makeMarket({ category: 'NBA', title: L('凯尔特人 vs 尼克斯（总分 224.5）', 'Celtics vs Knicks (total 224.5)', '塞爾提克 vs 尼克（總分 224.5）'), status: 'paused', closeIn: DAY, origin: 'sports', options: [[L('大', 'Over', '大'), W('总分 ≥ 225')], [L('小', 'Under', '小'), W('总分 ≤ 224')]], positions: POS(6) }),
    makeMarket({ category: 'MACRO', title: L('美联储 12 月议息会议降息？', 'Fed cuts rates in December?', '聯準會 12 月議息會議降息？'), status: 'listed', closeIn: 30 * DAY, feeRate: '5', options: [[T.yes, W('降息')], [T.no, W('不降息')]], positions: POS(9, 300) }),
    makeMarket({ category: 'NBA', title: L('勇士 vs 掘金（胜负）', 'Warriors vs Nuggets (winner)', '勇士 vs 金塊（勝負）'), status: 'closed', closeIn: -HOUR, origin: 'sports', options: [[L('勇士', 'Warriors', '勇士'), W('勇士胜')], [L('掘金', 'Nuggets', '金塊'), W('掘金胜')]], positions: POS(8) }),
    makeMarket({ category: 'FOOTBALL', title: L('切尔西 vs 纽卡斯尔（90 分钟胜平负）', 'Chelsea vs Newcastle (90-min result)', '切爾西 vs 紐卡索（90 分鐘勝平負）'), status: 'pending_resolution', closeIn: -5 * HOUR, mode: 'PLATFORM', options: [[T.home, W('主队胜')], [T.draw, W('打平')], [T.away, W('客队胜')]], positions: [[100301, 0, '9000'], [100302, 0, '999.99'], [100303, 1, '9799.99'], [100304, 2, '199.99'], [100305, 2, '0.02']] }),
    makeMarket({ category: 'MACRO', title: L('美国 10 月 CPI 同比 ≥ 3.0%？', 'US October CPI YoY ≥ 3.0%?', '美國 10 月 CPI 年增 ≥ 3.0%？'), status: 'pending_resolution', closeIn: -30 * HOUR, options: [[T.yes, W('≥ 3.0%')], [T.no, W('< 3.0%')]], positions: POS(10, 120) }),
    makeMarket({ category: 'CRYPTO_15M', title: L('ETH 涨还是跌：2026-10-25 06:00–06:15 UTC', 'ETH Up or Down: 2026-10-25 06:00–06:15 UTC', 'ETH 漲還是跌：2026-10-25 06:00–06:15 UTC'), status: 'pending_resolution', closeIn: -2 * HOUR, origin: 'autogen', options: [[T.up, W('结束价 ≥ 开始价')], [T.down, W('结束价 < 开始价')]], positions: POS(5, 40) }),
    makeMarket({ category: 'FOOTBALL', title: L('布伦特福德 vs 富勒姆（90 分钟胜平负）', 'Brentford vs Fulham (90-min result)', '布倫特福德 vs 富勒姆（90 分鐘勝平負）'), status: 'publicizing', closeIn: -8 * HOUR, mode: 'PLATFORM', origin: 'sports', options: [[T.home, W('主队胜')], [T.draw, W('打平')], [T.away, W('客队胜')]], positions: POS(14) }),
    makeMarket({ category: 'MACRO', title: L('英国央行 10 月维持利率？', 'BoE holds rates in October?', '英國央行 10 月維持利率？'), status: 'disputed', closeIn: -20 * HOUR, options: [[T.yes, W('维持')], [T.no, W('调整')]], positions: POS(7) }),
    makeMarket({ category: 'MACRO', title: L('美国 9 月 CPI 同比 ≥ 3.0%？', 'US September CPI YoY ≥ 3.0%?', '美國 9 月 CPI 年增 ≥ 3.0%？'), status: 'settled', closeIn: -10 * DAY, options: [[T.yes, W('≥ 3.0%')], [T.no, W('< 3.0%')]], positions: POS(11, 150) }),
    makeMarket({ category: 'CRYPTO_1H', title: L('BTC 涨还是跌：2026-10-24 13:00–14:00 UTC', 'BTC Up or Down: 2026-10-24 13:00–14:00 UTC', 'BTC 漲還是跌：2026-10-24 13:00–14:00 UTC'), status: 'settled', closeIn: -DAY, origin: 'autogen', options: [[T.up, W('结束价 ≥ 开始价')], [T.down, W('结束价 < 开始价')]], positions: POS(10, 60) }),
    makeMarket({ category: 'FOOTBALL', title: L('赫罗纳 vs 塞维利亚（大小 2.5 球）', 'Girona vs Sevilla (over/under 2.5)', '赫羅納 vs 塞維利亞（大小 2.5 球）'), status: 'voided', closeIn: -4 * DAY, origin: 'sports', options: [[T.over, W('≥ 3 球')], [T.under, W('≤ 2 球')]], positions: POS(4) }),
    makeMarket({ category: 'NBA', title: L('快船 vs 太阳（胜负）', 'Clippers vs Suns (winner)', '快艇 vs 太陽（勝負）'), status: 'cancelled', closeIn: -3 * DAY, origin: 'sports', options: [[L('快船', 'Clippers', '快艇'), W('快船胜')], [L('太阳', 'Suns', '太陽'), W('太阳胜')]], positions: POS(3) }),
  ];
  list.push(...older);
  const macro = list.find((m) => m.d.title.en.startsWith('Fed cuts'))!;
  macro.d.feeChanges.push({ oldRate: '6', newRate: '5', reason: '和其他时事市场一致', changedBy: 1, time: iso(BASE - 2 * DAY), notified: true });
  macro.d.clarifications.push({ text: L('"降息"指联邦基金利率目标区间上限下调。', '"Cut" means the upper bound of the federal funds target range is lowered.', '「降息」指聯邦基金利率目標區間上限下調。'), createdBy: 1, time: iso(BASE - DAY) });
  return list;
}

let markets: Mkt[] = seedMarkets();

function poolOf(m: Mkt): { total: bigint; bettors: number; perOption: bigint[]; people: number[] } {
  const perOption = m.d.options.map(() => 0n);
  const people = m.d.options.map(() => new Set<number>());
  for (const p of m.positions) {
    if (m.d.status === 'cancelled' || m.d.status === 'voided') continue;
    perOption[p.option] += cents(p.amount);
    people[p.option].add(p.userId);
  }
  const all = new Set(m.positions.map((p) => p.userId));
  return { total: perOption.reduce((a, b) => a + b, 0n), bettors: m.d.status === 'cancelled' || m.d.status === 'voided' ? 0 : all.size, perOption, people: people.map((s) => s.size) };
}

function summary(m: Mkt): MarketSummary {
  const pool = poolOf(m);
  const d = m.d;
  return {
    publicNo: d.publicNo,
    category: d.category,
    title: d.title,
    status: d.status,
    submitted: d.submitted,
    openAt: d.openAt,
    closeAt: d.closeAt,
    eventStartAt: d.eventStartAt,
    eventEndAt: d.eventEndAt,
    feeRate: d.feeRate,
    createdAt: d.createdAt,
    updatedAt: d.updatedAt,
    resolutionMode: d.resolutionMode === null ? null : d.resolutionMode === 'AUTO' ? 'auto' : 'platform',
    origin: m.origin,
    pool: money(pool.total),
    bettors: pool.bettors,
    todaySlate: d.todaySlate,
  };
}

function find(publicNo: string): Mkt {
  const m = markets.find((x) => x.d.publicNo === publicNo);
  if (!m) throw new ApiError('errors.notFound', undefined, { status: 404, code: 'NOT_FOUND' });
  return m;
}

const illegal = () => new ApiError('errors.fc.ILLEGAL_TRANSITION', undefined, { status: 409, code: 'ILLEGAL_TRANSITION' });
const needReason = (reason: string) => {
  if (!reason?.trim()) throw new ApiError('errors.reasonRequired', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'reason', code: 'required' }] });
};

function missingOf(d: DraftInput): Array<{ field: string; code: string }> {
  const out: Array<{ field: string; code: string }> = [];
  const full = (t: I18nText) => !!(t.en.trim() && t.zhCN.trim() && t.zhTW.trim());
  for (const k of ['en', 'zhCN', 'zhTW'] as const) if (!d.title[k].trim()) out.push({ field: `title.${k}`, code: 'required' });
  for (const k of ['en', 'zhCN', 'zhTW'] as const) if (!d.rules[k].trim()) out.push({ field: `rules.${k}`, code: 'required' });
  d.options.forEach((o, i) => {
    if (!full(o.label)) out.push({ field: `options[${i}].label`, code: 'required' });
    if (!full(o.winCondition)) out.push({ field: `options[${i}].winCondition`, code: 'required' });
  });
  if (!d.resolutionMode) out.push({ field: 'resolutionMode', code: 'required' });
  if (!d.sourcePrimary.trim()) out.push({ field: 'sourcePrimary', code: 'required' });
  if (!d.sourceBackup.trim()) out.push({ field: 'sourceBackup', code: 'required' });
  if (d.resolutionMode === 'PLATFORM' && !d.disputeWindowMinutes) out.push({ field: 'disputeWindowMinutes', code: 'required' });
  return out;
}

function checkDraft(d: DraftInput) {
  const fields: Array<{ field: string; code: string }> = [];
  if (d.options.length < 2 || d.options.length > 10) fields.push({ field: 'options', code: 'count' });
  const t = [d.openAt, d.closeAt, d.eventStartAt, d.eventEndAt].map((x) => Date.parse(x));
  if (t.some((x) => Number.isNaN(x))) fields.push({ field: 'closeAt', code: 'required' });
  else if (!(t[0] < t[1] && t[1] <= t[2] && t[2] < t[3])) fields.push({ field: 'closeAt', code: 'order' });
  if (fields.length) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields });
}

export function listMarkets(q: MarketQuery): Promise<Page<MarketSummary>> {
  return respond(
    () => {
      const st = q.status ? q.status.split(',') : null;
      const rows = markets
        .filter((m) => (!st || st.includes(m.d.status)) && (!q.category || m.d.category === q.category) && (q.submitted === undefined || m.d.submitted === q.submitted) && (q.todaySlate === undefined || m.d.todaySlate === q.todaySlate))
        .filter((m) => matches(q.keyword, m.d.publicNo, m.d.title.zhCN, m.d.title.en, m.d.title.zhTW))
        .map(summary)
        .sort((a, b) => b.publicNo.localeCompare(a.publicNo));
      return paginate(rows, q.page, q.pageSize);
    },
    { empty: () => emptyPage<MarketSummary>(q.page, q.pageSize) },
  );
}

export function getMarketCounts(): Promise<MarketCounts> {
  return respond(
    () => {
      const byStatus = Object.fromEntries(MARKET_STATUSES.map((s) => [s, markets.filter((m) => m.d.status === s).length])) as MarketCounts['byStatus'];
      return { byStatus, review: markets.filter((m) => m.d.status === 'draft' && m.d.submitted).length };
    },
    { empty: () => ({ byStatus: Object.fromEntries(MARKET_STATUSES.map((s) => [s, 0])) as MarketCounts['byStatus'], review: 0 }) },
  );
}

export function getMarket(no: string): Promise<MarketDetail> {
  return respond(() => find(no).d);
}

function apply(m: Mkt, d: DraftInput) {
  m.d = {
    ...m.d,
    ...d,
    resolutionMode: d.resolutionMode,
    userCap: d.userCap ?? m.d.userCap,
    todaySlate: d.todaySlate ?? m.d.todaySlate,
    options: d.options.map((o, i) => ({ optionNo: `${m.d.publicNo}${i + 1}`, seq: i + 1, label: o.label, winCondition: o.winCondition })),
    missing: missingOf(d),
    version: m.d.version + 1,
    updatedAt: iso(Date.now()),
  };
}

export function createMarket(d: DraftInput): Promise<MarketDetail> {
  return mutate(() => {
    checkDraft(d);
    const m = makeMarket({ category: d.category, title: d.title, status: 'draft', closeIn: 0, options: [] });
    m.d.feeRate = '5';
    apply(m, d);
    markets = [m, ...markets];
    return m.d;
  });
}

export function updateMarket(no: string, d: DraftInput): Promise<MarketDetail> {
  return mutate(() => {
    const m = find(no);
    if (m.d.status !== 'draft') throw new ApiError('errors.fc.MARKET_NOT_EDITABLE', undefined, { status: 409, code: 'MARKET_NOT_EDITABLE' });
    checkDraft(d);
    apply(m, d);
    return m.d;
  });
}

export function deleteMarket(no: string): Promise<void> {
  return mutate(() => {
    const m = find(no);
    if (m.d.status !== 'draft') throw new ApiError('errors.fc.MARKET_NOT_EDITABLE', undefined, { status: 409, code: 'MARKET_NOT_EDITABLE' });
    markets = markets.filter((x) => x !== m);
  });
}

export function submitMarket(no: string): Promise<MarketDetail> {
  return mutate(() => {
    const m = find(no);
    if (m.d.missing.length) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: m.d.missing });
    m.d.submitted = true;
    return m.d;
  });
}

export function returnMarket(no: string, reason: string): Promise<MarketDetail> {
  return mutate(() => {
    needReason(reason);
    const m = find(no);
    m.d.submitted = false;
    return m.d;
  });
}

function move(m: Mkt, to: MarketStatus, reason: string | null) {
  m.d.statusLog.push({ from: m.d.status, to, actor: '1', reason, time: iso(Date.now()) });
  m.d.status = to;
  m.d.updatedAt = iso(Date.now());
}

/** 同时开放上限（参数 market.maxOpen，0 = 不限） */
function maxOpen(): number {
  return Number(mockParams.find((p) => p.key === 'market.maxOpen')?.value ?? '2');
}
const openCount = () => markets.filter((m) => m.d.status === 'listed' || m.d.status === 'paused').length;

export function setTodaySlate(no: string, todaySlate: boolean): Promise<MarketDetail> {
  return mutate(() => {
    const m = find(no);
    if (!['draft', 'listed', 'paused'].includes(m.d.status)) throw illegal();
    m.d.todaySlate = todaySlate;
    return m.d;
  });
}

export function getOpenLimit(): Promise<OpenLimit> {
  return respond(() => ({ open: openCount(), limit: maxOpen() }), { empty: () => ({ open: 0, limit: maxOpen() }) });
}

export function publishMarket(no: string, input: PublishInput): Promise<MarketDetail> {
  return mutate(() => {
    const m = find(no);
    const fields = [...m.d.missing];
    if (!input.checked) fields.push({ field: 'checked', code: 'required' });
    const limit = maxOpen();
    if (limit > 0 && openCount() + 1 > limit && !input.overLimit) fields.push({ field: 'overLimit', code: 'required' });
    if (fields.length) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields });
    if (m.d.status !== 'draft') throw illegal();
    move(m, 'listed', null);
    m.d.submitted = false;
    if (input.todaySlate !== undefined) m.d.todaySlate = input.todaySlate;
    return m.d;
  });
}

function transition(from: MarketStatus[], to: MarketStatus) {
  return (no: string, reason: string): Promise<MarketDetail> =>
    mutate(() => {
      needReason(reason);
      const m = find(no);
      if (!from.includes(m.d.status)) throw illegal();
      move(m, to, reason);
      if (to === 'cancelled') m.d.cancelReason = reason;
      return m.d;
    });
}

export const pauseMarket = transition(['listed'], 'paused');
export const resumeMarket = transition(['paused'], 'listed');
export const cancelMarket = transition(['listed', 'paused'], 'cancelled');

export function advanceClose(no: string, closeAt: string, reason: string): Promise<MarketDetail> {
  return mutate(() => {
    needReason(reason);
    const m = find(no);
    if (!['listed', 'paused'].includes(m.d.status)) throw new ApiError('errors.fc.MARKET_CLOSED', undefined, { status: 409, code: 'MARKET_CLOSED' });
    if (!(Date.parse(closeAt) < Date.parse(m.d.closeAt))) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'closeAt', code: 'notEarlier' }] });
    m.d.closeAt = closeAt;
    return m.d;
  });
}

export function clarifyMarket(no: string, text: I18nText, reason: string): Promise<MarketDetail> {
  return mutate(() => {
    needReason(reason);
    const m = find(no);
    const fields = (['en', 'zhCN', 'zhTW'] as const).filter((k) => !text[k]?.trim()).map((k) => ({ field: `text.${k}`, code: 'required' }));
    if (fields.length) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields });
    if (!['listed', 'paused'].includes(m.d.status)) throw new ApiError('errors.fc.MARKET_CLOSED', undefined, { status: 409, code: 'MARKET_CLOSED' });
    m.d.clarifications.push({ text, createdBy: 1, time: iso(Date.now()) });
    return m.d;
  });
}

export function changeFee(no: string, feeRate: string, reason: string): Promise<FeeChangeResult> {
  return mutate(() => {
    needReason(reason);
    const m = find(no);
    if (!/^\d{1,2}(\.\d{1,2})?$/.test(feeRate) || Number(feeRate) > 20) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'feeRate', code: 'invalid' }] });
    if (m.d.settlePhase !== 'NONE' || ['settled', 'cancelled', 'voided'].includes(m.d.status)) throw new ApiError('errors.fc.FEE_LOCKED', undefined, { status: 409, code: 'FEE_LOCKED' });
    const old = m.d.feeRate;
    const notified = m.d.status === 'draft' ? 0 : new Set(m.positions.map((p) => p.userId)).size;
    m.d.feeChanges.push({ oldRate: old, newRate: String(Number(feeRate)), reason, changedBy: 1, time: iso(Date.now()), notified: notified > 0 });
    m.d.feeRate = String(Number(feeRate));
    return { oldRate: old, newRate: m.d.feeRate, notified, market: m.d };
  });
}

export function voidMarket(no: string, reason: string): Promise<SettleOutcome> {
  return mutate(() => {
    needReason(reason);
    const m = find(no);
    if (!['pending_resolution', 'publicizing', 'disputed'].includes(m.d.status)) throw new ApiError('errors.fc.MARKET_NOT_VOIDABLE', undefined, { status: 409, code: 'MARKET_NOT_VOIDABLE' });
    move(m, 'voided', reason);
    m.d.voidReason = reason;
    return { marketNo: no, marketStatus: 'voided', settlePhase: 'NONE', payout: null, refund: null };
  });
}

export function getMarketBets(no: string, q: { sort?: 'time' | 'amount'; status?: string; page: number; pageSize: number }): Promise<MarketBets> {
  return respond(() => {
    const m = find(no);
    const pool = poolOf(m);
    const refunded = m.d.status === 'cancelled' || m.d.status === 'voided';
    let bets = m.positions.map((p, i) => ({
      bet: {
        betNo: `${m.d.publicNo.slice(0, 10)}${String(i + 1).padStart(6, '0')}`,
        userId: p.userId,
        optionNo: m.d.options[p.option].optionNo,
        seq: p.option + 1,
        amount: money(cents(p.amount)),
        receivedAt: iso(Date.parse(m.d.closeAt) - (i + 1) * 17 * MIN),
        status: refunded ? ('refunded' as const) : ('active' as const),
        refundReason: refunded ? (m.d.status === 'cancelled' ? 'CANCELLED' : 'VOIDED') : null,
        clientReqId: `req-${i}`,
      },
      email: `user${p.userId}@example.com`,
      name: `客户 ${p.userId}`,
    }));
    if (q.sort === 'amount') bets = [...bets].sort((a, b) => Number(cents(b.bet.amount) - cents(a.bet.amount)));
    const frozen = !['draft', 'listed', 'paused'].includes(m.d.status);
    return {
      marketNo: no,
      status: m.d.status,
      total: money(pool.total),
      activeTotal: money(pool.total),
      bettors: pool.bettors,
      frozen,
      options: m.d.options.map((o, i) => ({ optionNo: o.optionNo, seq: o.seq, amount: money(pool.perOption[i]), bettors: pool.people[i], finalAmount: frozen ? money(pool.perOption[i]) : null, finalBettors: frozen ? pool.people[i] : null })),
      bets: paginate(bets, q.page, q.pageSize),
    };
  });
}

export function getSettlement(no: string): Promise<SettleOutcome> {
  return respond(() => {
    const m = find(no);
    const run = runs.find((r) => r.marketNo === no && r.kind === 'payout') ?? null;
    const refund = runs.find((r) => r.marketNo === no && r.kind === 'refund') ?? null;
    return { marketNo: no, marketStatus: m.d.status, settlePhase: m.d.settlePhase.toLowerCase(), payout: run, refund };
  });
}

export function getEvidence(no: string): Promise<Evidence> {
  return respond(() => {
    const m = find(no);
    const auto = m.d.resolutionMode === 'AUTO' && m.d.status === 'pending_resolution';
    const at = Date.parse(m.d.eventEndAt) + MIN;
    return {
      marketNo: no,
      task: auto ? { status: 'manual', attempts: 31, lastReason: '两个来源方向不一致：币安 涨（3,812.40 → 3,812.55），OKX 跌（3,812.61 → 3,812.38）', deadlineAt: iso(at + 30 * MIN) } : null,
      records: auto ? [{ id: 1, kind: 'AUTO_FAILED', optionNo: null, detail: null, note: '30 分钟内两个来源仍不一致，转人工', actor: 'AUTO', time: iso(at + 30 * MIN) }] : [],
      fetches: auto
        ? [
            { id: 11, source: 'binance', attempt: 1, requestUrl: 'https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1m', httpStatus: 200, outcome: 'OK', parsed: { open: '3812.40', close: '3812.55' }, error: null, bodySha256: '9f2c…a1', fetchedAt: iso(at), elapsedMs: 182 },
            { id: 12, source: 'okx', attempt: 1, requestUrl: 'https://www.okx.com/api/v5/market/history-candles?instId=ETH-USDT&bar=1m', httpStatus: 200, outcome: 'OK', parsed: { open: '3812.61', close: '3812.38' }, error: null, bodySha256: '41be…07', fetchedAt: iso(at), elapsedMs: 240 },
          ]
        : [],
      sports: null,
    };
  });
}

// ---------------------------------------------------------------- 结算预览（需求第 3.5 节）

function preview(m: Mkt, optionNo: string): SettlePreview {
  const idx = m.d.options.findIndex((o) => o.optionNo === optionNo);
  if (idx < 0) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'optionNo', code: 'invalid' }] });
  const pool = poolOf(m);
  const winners = m.positions.filter((p) => p.option === idx);
  const W0 = pool.perOption[idx];
  const Lp = pool.total - W0;
  const rate = toScaled(m.d.feeRate, 4) ?? 0n; // 5% → 50000（4 位小数的百分比）
  let gross = 0n;
  let fee = 0n;
  for (const w of winners) {
    const s = cents(w.amount);
    const g = W0 === 0n ? 0n : (s * Lp) / W0; // 向下取整到分
    const f = (g * rate + 500000n) / 1000000n; // 四舍五入到分
    gross += g;
    fee += f;
  }
  const dust = Lp - gross;
  const payout = W0 + gross - fee;
  return {
    marketNo: m.d.publicNo,
    optionNo,
    actualStartAt: null,
    outcome: W0 === 0n ? 'void_no_winner' : 'settle',
    feeRate: m.d.feeRate,
    poolTotal: money(pool.total),
    winnerPool: money(W0),
    loserPool: money(Lp),
    sumGross: money(gross),
    sumFee: money(fee),
    dust: money(dust),
    sumPayout: money(payout),
    platformIncome: money(fee + dust),
    winnerPositions: winners.length,
    loserPositions: m.positions.length - winners.length,
    conserved: payout + fee + dust === pool.total,
    lateBets: 0,
    lateAmount: '0.00',
    staffBets: 0,
    staffAmount: '0.00',
  };
}

export function previewSettle(no: string, optionNo: string): Promise<SettlePreview> {
  return respond(() => preview(find(no), optionNo));
}

// ---------------------------------------------------------------- 裁决台

interface CaseState {
  marketNo: string;
  origin: CaseItem['origin'];
  hold: CaseItem['hold'];
  proposal: CaseItem['proposal'];
  proposeDueAt: string;
  disputes: Dispute[];
  records: CaseDetail['records'];
  resultOptionNo: string | null;
}

let cases: CaseState[] = [];

function seedCases() {
  const at = (name: string) => markets.find((m) => m.d.title.en.startsWith(name))!;
  const chelsea = at('Chelsea');
  const cpi = at('US October CPI');
  const eth = at('ETH Up');
  const brent = at('Brentford');
  const boe = at('BoE');
  const nuggets = at('Warriors vs Nuggets');
  cases = [
    {
      marketNo: chelsea.d.publicNo,
      origin: 'platform',
      hold: null,
      proposal: {
        proposalId: '501',
        outcome: 'result',
        optionNo: chelsea.d.options[0].optionNo,
        basis: 'NORMAL',
        actualStartAt: iso(Date.parse(chelsea.d.eventStartAt)),
        evidenceUrls: ['https://www.premierleague.com/match/115880', 'https://www.bbc.com/sport/football/live/c0k4'],
        attachmentNos: ['9001'],
        note: '官方比分 2:1，主队胜；两个比分源一致，已截图存档。',
        proposedBy: '7',
        proposedAt: iso(BASE - HOUR),
        confirmDueAt: iso(BASE + HOUR),
        confirmOverdue: false,
      },
      proposeDueAt: iso(BASE - 3 * HOUR),
      disputes: [],
      records: [],
      resultOptionNo: null,
    },
    { marketNo: cpi.d.publicNo, origin: 'platform', hold: null, proposal: null, proposeDueAt: iso(BASE - 6 * HOUR), disputes: [], records: [], resultOptionNo: null },
    { marketNo: eth.d.publicNo, origin: 'auto_failed', hold: null, proposal: null, proposeDueAt: iso(BASE + 90 * MIN), disputes: [], records: [], resultOptionNo: null },
    { marketNo: nuggets.d.publicNo, origin: 'auto_failed', hold: 'postponed', proposal: null, proposeDueAt: iso(BASE + 40 * HOUR), disputes: [], records: [], resultOptionNo: null },
    { marketNo: brent.d.publicNo, origin: 'auto_failed', hold: null, proposal: null, proposeDueAt: iso(BASE - 10 * HOUR), disputes: [], records: [], resultOptionNo: brent.d.options[1].optionNo },
    {
      marketNo: boe.d.publicNo,
      origin: 'platform',
      hold: null,
      proposal: null,
      proposeDueAt: iso(BASE - 18 * HOUR),
      disputes: [
        { disputeNo: '880001', marketNo: boe.d.publicNo, userId: 100204, claimedOptionNo: boe.d.options[1].optionNo, claimVoid: false, reason: '央行声明里写了上调 25 个基点，公示的结果不对。', link: 'https://www.bankofengland.co.uk/monetary-policy-summary', status: 'open', decisionNote: null, handledBy: null, handledAt: null, receivedAt: iso(BASE - 4 * HOUR) },
        { disputeNo: '880002', marketNo: boe.d.publicNo, userId: 100205, claimedOptionNo: null, claimVoid: true, reason: '会议延期到下周，应作废。', link: null, status: 'open', decisionNote: null, handledBy: null, handledAt: null, receivedAt: iso(BASE - 3 * HOUR) },
      ],
      records: [],
      resultOptionNo: boe.d.options[0].optionNo,
    },
  ];
  // 公示中的市场的争议截止
  void brent;
}
seedCases();

function caseItem(c: CaseState): CaseItem {
  const m = find(c.marketNo);
  const now = Date.now();
  const stage: CaseItem['stage'] = m.d.status === 'publicizing' ? 'publicizing' : m.d.status === 'disputed' ? 'disputed' : m.d.status === 'pending_resolution' ? 'open' : 'closed';
  const proposeOverdue = stage === 'open' && !c.proposal && !c.hold && Date.parse(c.proposeDueAt) < now;
  const disputeDueAt = stage === 'disputed' ? iso(Date.parse(c.disputes[0]?.receivedAt ?? m.d.updatedAt) + DAY) : null;
  const disputeOverdue = !!disputeDueAt && Date.parse(disputeDueAt) < now;
  const proposal = c.proposal ? { ...c.proposal, confirmOverdue: Date.parse(c.proposal.confirmDueAt) < now } : null;
  return {
    marketNo: m.d.publicNo,
    category: m.d.category,
    title: m.d.title,
    marketStatus: m.d.status,
    origin: c.origin,
    stage,
    hold: c.hold,
    holdUntil: c.hold ? iso(Date.parse(m.d.eventStartAt) + 48 * HOUR) : null,
    proposeDueAt: c.proposeDueAt,
    proposeOverdue,
    proposal,
    resultOptionNo: c.resultOptionNo,
    disputeDeadlineAt: stage === 'publicizing' ? iso(BASE + 75 * MIN) : null,
    openDisputes: c.disputes.filter((d) => d.status === 'open').length,
    disputeDueAt,
    disputeOverdue,
    overdue: proposeOverdue || !!proposal?.confirmOverdue || disputeOverdue,
    eventEndAt: m.d.eventEndAt,
    updatedAt: m.d.updatedAt,
  };
}

function inTab(i: CaseItem, tab: CaseTab): boolean {
  if (i.stage === 'closed') return false;
  switch (tab) {
    case 'awaiting_proposal':
      return i.stage === 'open' && !i.proposal && !i.hold;
    case 'awaiting_confirm':
      return i.stage === 'open' && !!i.proposal;
    case 'auto_failed':
      return i.stage === 'open' && i.origin === 'auto_failed';
    case 'on_hold':
      return !!i.hold;
    case 'overdue':
      return i.overdue;
    case 'publicizing':
      return i.stage === 'publicizing';
    case 'disputed':
      return i.stage === 'disputed';
    default:
      return true;
  }
}

export function listCases(tab: CaseTab, page: number, pageSize: number): Promise<Page<CaseItem>> {
  return respond(
    () => {
      const items = cases.map(caseItem).filter((i) => inTab(i, tab)).sort((a, b) => Number(b.overdue) - Number(a.overdue));
      return paginate(items, page, pageSize);
    },
    { empty: () => emptyPage<CaseItem>(page, pageSize) },
  );
}

export function getCaseCounts(): Promise<Record<CaseTab, number>> {
  const tabs: CaseTab[] = ['all', 'awaiting_proposal', 'awaiting_confirm', 'auto_failed', 'on_hold', 'overdue', 'publicizing', 'disputed'];
  return respond(
    () => Object.fromEntries(tabs.map((t) => [t, cases.map(caseItem).filter((i) => inTab(i, t)).length])) as Record<CaseTab, number>,
    { empty: () => Object.fromEntries(tabs.map((t) => [t, 0])) as Record<CaseTab, number> },
  );
}

function findCase(no: string): CaseState {
  const c = cases.find((x) => x.marketNo === no);
  if (!c) throw new ApiError('errors.notFound', undefined, { status: 404, code: 'NOT_FOUND' });
  return c;
}

function detailOf(c: CaseState): CaseDetail {
  const m = find(c.marketNo);
  return {
    summary: caseItem(c),
    options: m.d.options.map((o) => ({ optionNo: o.optionNo, seq: o.seq, label: o.label })),
    actualStartAt: c.proposal?.actualStartAt ?? null,
    resolvedBy: null,
    confirmedAt: null,
    decision: null,
    records: c.records,
    disputes: c.disputes,
    attachments: c.proposal?.attachmentNos.map((a) => ({ attachmentNo: a, contentType: 'image/png', size: 182_331, sha256: '3b8f…c2', uploadedBy: 7, createdAt: c.proposal!.proposedAt })) ?? [],
  };
}

function view(c: CaseState): CaseView {
  const m = find(c.marketNo);
  return { detail: detailOf(c), proposalPreview: c.proposal?.optionNo ? preview(m, c.proposal.optionNo) : null };
}

export function getCase(no: string): Promise<CaseView> {
  return respond(() => view(findCase(no)));
}

function checkProposal(p: ProposalInput) {
  const fields: Array<{ field: string; code: string }> = [];
  if (!p.voidMarket && !p.optionNo) fields.push({ field: 'optionNo', code: 'required' });
  if (!p.evidenceUrls.length) fields.push({ field: 'evidenceUrls', code: 'required' });
  if (p.evidenceUrls.some((u) => !/^https?:\/\//.test(u))) fields.push({ field: 'evidenceUrls', code: 'invalid' });
  if (!p.note.trim()) fields.push({ field: 'note', code: 'required' });
  if (fields.length) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields });
}

export function propose(no: string, p: ProposalInput): Promise<CaseView> {
  return mutate(() => {
    const c = findCase(no);
    if (c.hold) throw new ApiError('errors.fc.RESOLVE_ON_HOLD', undefined, { status: 409, code: 'RESOLVE_ON_HOLD' });
    if (c.proposal) throw new ApiError('errors.fc.PROPOSAL_PENDING', undefined, { status: 409, code: 'PROPOSAL_PENDING' });
    checkProposal(p);
    c.proposal = {
      proposalId: String(600 + c.records.length),
      outcome: p.voidMarket ? 'void' : 'result',
      optionNo: p.voidMarket ? null : p.optionNo,
      basis: p.basis ?? 'NORMAL',
      actualStartAt: p.actualStartAt,
      evidenceUrls: p.evidenceUrls,
      attachmentNos: p.attachmentNos,
      note: p.note,
      proposedBy: '1',
      proposedAt: iso(Date.now()),
      confirmDueAt: iso(Date.now() + 2 * HOUR),
      confirmOverdue: false,
    };
    c.records.push({ id: c.proposal.proposalId, kind: 'PROPOSE', optionNo: c.proposal.optionNo, refId: null, basis: c.proposal.basis, actualStartAt: p.actualStartAt, evidenceUrls: p.evidenceUrls, attachmentNos: p.attachmentNos, note: p.note, actor: '1', time: iso(Date.now()), detail: null });
    return view(c);
  });
}

function toPublicity(c: CaseState, optionNo: string | null, reason: string) {
  const m = find(c.marketNo);
  if (optionNo === null) {
    move(m, 'voided', reason);
  } else {
    move(m, 'publicizing', null);
    c.resultOptionNo = optionNo;
  }
  c.proposal = null;
}

export function confirmProposal(no: string, proposalId: string, reason: string): Promise<CaseView> {
  return mutate(() => {
    needReason(reason);
    const c = findCase(no);
    if (!c.proposal || c.proposal.proposalId !== proposalId) throw new ApiError('errors.fc.PROPOSAL_CHANGED', undefined, { status: 409, code: 'PROPOSAL_CHANGED' });
    c.records.push({ id: String(700 + c.records.length), kind: 'CONFIRM', optionNo: c.proposal.optionNo, refId: proposalId, basis: null, actualStartAt: null, evidenceUrls: [], attachmentNos: [], note: reason, actor: '1', time: iso(Date.now()), detail: null });
    toPublicity(c, c.proposal.optionNo, reason);
    return view(c);
  });
}

export function returnProposal(no: string, proposalId: string, reason: string): Promise<CaseView> {
  return mutate(() => {
    needReason(reason);
    const c = findCase(no);
    if (!c.proposal || c.proposal.proposalId !== proposalId) throw new ApiError('errors.fc.PROPOSAL_CHANGED', undefined, { status: 409, code: 'PROPOSAL_CHANGED' });
    c.records.push({ id: String(800 + c.records.length), kind: 'RETURN', optionNo: null, refId: proposalId, basis: null, actualStartAt: null, evidenceUrls: [], attachmentNos: [], note: reason, actor: '1', time: iso(Date.now()), detail: null });
    c.proposal = null;
    return view(c);
  });
}

export function decideDirect(no: string, p: ProposalInput, reason: string): Promise<CaseView> {
  return mutate(() => {
    needReason(reason);
    const c = findCase(no);
    if (c.hold) throw new ApiError('errors.fc.RESOLVE_ON_HOLD', undefined, { status: 409, code: 'RESOLVE_ON_HOLD' });
    checkProposal(p);
    toPublicity(c, p.voidMarket ? null : p.optionNo, reason);
    return view(c);
  });
}

export function holdCase(no: string, kind: 'postponed' | 'abandoned', reason: string): Promise<CaseView> {
  return mutate(() => {
    needReason(reason);
    const c = findCase(no);
    c.hold = kind;
    return view(c);
  });
}

export function releaseCase(no: string, reason: string): Promise<CaseView> {
  return mutate(() => {
    needReason(reason);
    const c = findCase(no);
    if (!c.hold) throw new ApiError('errors.fc.NOT_ON_HOLD', undefined, { status: 409, code: 'NOT_ON_HOLD' });
    c.hold = null;
    return view(c);
  });
}

const SVG_NS = `http://www.w3.org/2000/svg`;
export const attachmentUrl = (attachmentNo: string): string => `data:image/svg+xml;utf8,${encodeURIComponent(`<svg xmlns="${SVG_NS}" width="320" height="180"><rect width="100%" height="100%" fill="#e8eef7"/><text x="16" y="96" font-size="14" fill="#33415c">存档截图 ${attachmentNo}（原型）</text></svg>`)}`;

export function uploadAttachment(no: string, file: File): Promise<{ attachmentNo: string }> {
  return mutate(() => {
    findCase(no);
    if (file.size > 2 * 1024 * 1024) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'file', code: 'tooLarge' }] });
    return { attachmentNo: String(9000 + Math.floor(Math.random() * 999)) };
  });
}

// ---------------------------------------------------------------- 公示与争议

export function listDisputes(status: 'open' | 'handled' | undefined, page: number, pageSize: number): Promise<Page<Dispute>> {
  return respond(
    () => {
      const all = cases.flatMap((c) => c.disputes).filter((d) => !status || (status === 'open' ? d.status === 'open' : d.status !== 'open'));
      return paginate(all.sort((a, b) => b.receivedAt.localeCompare(a.receivedAt)), page, pageSize);
    },
    { empty: () => emptyPage<Dispute>(page, pageSize) },
  );
}

export function getDisputeCase(no: string): Promise<CaseDetail> {
  return respond(() => detailOf(findCase(no)));
}

export function decideDisputes(no: string, decision: 'uphold' | 'overturn' | 'void', optionNo: string | null, note: string, reason: string): Promise<CaseDetail> {
  return mutate(() => {
    needReason(reason);
    const c = findCase(no);
    const m = find(no);
    if (m.d.status !== 'disputed') throw new ApiError('errors.fc.DISPUTE_NOT_OPEN', undefined, { status: 409, code: 'DISPUTE_NOT_OPEN' });
    const fields: Array<{ field: string; code: string }> = [];
    if (!note.trim()) fields.push({ field: 'note', code: 'required' });
    if (decision === 'overturn' && (!optionNo || optionNo === c.resultOptionNo)) fields.push({ field: 'optionNo', code: optionNo ? 'sameAsResult' : 'required' });
    if (fields.length) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields });
    const st = decision === 'uphold' ? 'upheld' : decision === 'overturn' ? 'overturned' : 'voided';
    c.disputes = c.disputes.map((d) => (d.status === 'open' ? { ...d, status: st, decisionNote: note, handledBy: 1, handledAt: iso(Date.now()) } : d));
    move(m, decision === 'void' ? 'voided' : 'settled', note);
    return detailOf(c);
  });
}

// ---------------------------------------------------------------- 结算单、对账差异

function seedRuns(): SettleRun[] {
  const out: SettleRun[] = [];
  let n = 0;
  for (const m of markets.filter((x) => x.d.status === 'settled')) {
    const p = preview(m, m.d.options[0].optionNo);
    out.push({
      runNo: `61${String(++n).padStart(6, '0')}`,
      marketNo: m.d.publicNo,
      kind: 'payout',
      status: 'done',
      reason: null,
      winningOptionNo: p.optionNo,
      feeRate: p.feeRate,
      poolTotal: p.poolTotal,
      winnerPool: p.winnerPool,
      loserPool: p.loserPool,
      sumGross: p.sumGross,
      sumFee: p.sumFee,
      dust: p.dust,
      sumPayout: p.sumPayout,
      platformIncome: p.platformIncome,
      winnerPositions: p.winnerPositions,
      loserPositions: p.loserPositions,
      itemCount: p.winnerPositions + p.loserPositions,
      pendingItems: 0,
      conservationOk: true,
      abnormalReason: null,
      batchSize: 500,
      resolvedBy: m.d.resolutionMode === 'AUTO' ? 'AUTO' : '1',
      calculatedAt: iso(Date.parse(m.d.eventEndAt) + 4 * HOUR),
      doneAt: iso(Date.parse(m.d.eventEndAt) + 4 * HOUR + 3000),
    });
  }
  for (const m of markets.filter((x) => x.d.status === 'cancelled' || x.d.status === 'voided')) {
    const pool = poolOf({ ...m, d: { ...m.d, status: 'listed' } });
    out.push({ ...out[0], runNo: `61${String(++n).padStart(6, '0')}`, marketNo: m.d.publicNo, kind: 'refund', reason: m.d.status === 'cancelled' ? '主办方宣布比赛取消' : 'POSTPONED_48H', winningOptionNo: null, poolTotal: money(pool.total), winnerPool: '0.00', loserPool: '0.00', sumGross: '0.00', sumFee: '0.00', dust: '0.00', sumPayout: money(pool.total), platformIncome: '0.00', winnerPositions: 0, loserPositions: 0, itemCount: m.positions.length });
  }
  // 一张结算异常：守恒没通过（停止派彩，等处理后重试）
  out.push({ ...out[1], runNo: `61${String(++n).padStart(6, '0')}`, status: 'abnormal', conservationOk: false, abnormalReason: '结算前对账：这个市场的下注锁定合计和奖池合计差 0.01，已停止派彩', pendingItems: out[1].itemCount, doneAt: null });
  return out;
}

let runs: SettleRun[] = seedRuns();

export function listSettleRuns(q: { status?: string; kind?: string; page: number; pageSize: number }): Promise<Page<SettleRun>> {
  return respond(
    () => paginate(runs.filter((r) => (!q.status || r.status === q.status) && (!q.kind || r.kind === q.kind)).sort((a, b) => b.runNo.localeCompare(a.runNo)), q.page, q.pageSize),
    { empty: () => emptyPage<SettleRun>(q.page, q.pageSize) },
  );
}

export function getSettleRun(runNo: string, page: number, pageSize: number): Promise<SettleRunDetail> {
  return respond(() => {
    const r = runs.find((x) => x.runNo === runNo);
    if (!r) throw new ApiError('errors.notFound', undefined, { status: 404, code: 'NOT_FOUND' });
    const m = find(r.marketNo);
    if (r.kind === 'refund') {
      const items = m.positions.map((p, i) => ({ userId: p.userId, betNo: `${m.d.publicNo.slice(0, 10)}${String(i + 1).padStart(6, '0')}`, amount: money(cents(p.amount)), reason: r.reason ?? '', status: 'done' }));
      return { run: r, payoutItems: null, refundItems: paginate(items, page, pageSize) };
    }
    const win = m.d.options.findIndex((o) => o.optionNo === r.winningOptionNo);
    const items = m.positions.map((p) => ({ userId: p.userId, optionNo: m.d.options[p.option].optionNo, win: p.option === win, stake: money(cents(p.amount)), gross: '0.00', fee: '0.00', payout: p.option === win ? money(cents(p.amount)) : '0.00', status: r.status === 'abnormal' ? 'pending' : 'done' }));
    return { run: r, payoutItems: paginate(items, page, pageSize), refundItems: null };
  });
}

export function retrySettleRun(runNo: string, reason: string): Promise<SettleRun> {
  return mutate(() => {
    needReason(reason);
    const r = runs.find((x) => x.runNo === runNo);
    if (!r || r.status !== 'abnormal') throw new ApiError('errors.fc.SETTLE_NOT_ABNORMAL', undefined, { status: 409, code: 'SETTLE_NOT_ABNORMAL' });
    if (issues.some((i) => i.status === 'open' && i.marketNo === r.marketNo)) throw new ApiError('errors.fc.POOL_RECON_OPEN', undefined, { status: 409, code: 'POOL_RECON_OPEN' });
    Object.assign(r, { status: 'done', conservationOk: true, abnormalReason: null, pendingItems: 0, doneAt: iso(Date.now()) });
    return r;
  });
}

const issues: PoolIssue[] = [
  { id: 31, marketNo: null, refKey: 'POOL_TOTAL', checkDate: '2026-10-24', kind: 'POOL_VS_LEDGER', source: 'pool_option_total', expected: '18,240.00', actual: '18,239.99', diff: '-0.01', detail: '全部市场的奖池合计和账本"下注锁定"合计差 0.01', status: 'open', resolvedBy: null, resolvedAt: null, note: null, createdAt: iso(BASE - 7 * HOUR) },
  { id: 30, marketNo: null, refKey: 'POOL_TOTAL', checkDate: '2026-10-21', kind: 'POOL_VS_BETS', source: 'pool_bet', expected: '9,120.00', actual: '9,120.00', diff: '0.00', detail: '复核后一致（当时一笔退款还在派）', status: 'resolved', resolvedBy: 1, resolvedAt: iso(BASE - 3 * DAY), note: '退款派完后复核一致', createdAt: iso(BASE - 4 * DAY) },
];

export function listPoolIssues(status: 'open' | 'resolved' | undefined, page: number, pageSize: number): Promise<Page<PoolIssue>> {
  return respond(() => paginate(issues.filter((i) => !status || i.status === status), page, pageSize), { empty: () => emptyPage<PoolIssue>(page, pageSize) });
}

export function resolvePoolIssue(id: number, note: string): Promise<PoolIssue> {
  return mutate(() => {
    if (!note.trim()) throw new ApiError('errors.reasonRequired', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'note', code: 'required' }] });
    const i = issues.find((x) => x.id === id);
    if (!i || i.status !== 'open') throw new ApiError('errors.fc.ISSUE_ALREADY_RESOLVED', undefined, { status: 409, code: 'ISSUE_ALREADY_RESOLVED' });
    Object.assign(i, { status: 'resolved', note, resolvedBy: 1, resolvedAt: iso(Date.now()) });
    return i;
  });
}

// ---------------------------------------------------------------- 补偿、报表

let compensations: Compensation[] = [];

function plan(no: string, correctOptionNo: string): CompPlan {
  const m = find(no);
  if (m.d.status !== 'settled') throw new ApiError('errors.fc.MARKET_NOT_COMPENSABLE', undefined, { status: 409, code: 'MARKET_NOT_COMPENSABLE' });
  const run = runs.find((r) => r.marketNo === no && r.kind === 'payout');
  if (correctOptionNo === run?.winningOptionNo) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'correctOptionNo', code: 'sameAsResult' }] });
  const idx = m.d.options.findIndex((o) => o.optionNo === correctOptionNo);
  const lines = m.positions
    .filter((p) => correctOptionNo === 'void' || p.option === idx)
    .map((p) => ({ userId: p.userId, shouldPayout: money(cents(p.amount) * (correctOptionNo === 'void' ? 1n : 2n)), actualPayout: '0.00', amount: money(cents(p.amount) * (correctOptionNo === 'void' ? 1n : 2n)) }));
  const total = lines.reduce((a, l) => a + cents(l.amount), 0n);
  return { marketNo: no, originalOptionNo: run?.winningOptionNo ?? '', correctOptionNo, feeRate: m.d.feeRate, total: money(total), userCount: lines.length, lines };
}

export function previewCompensation(no: string, correctOptionNo: string): Promise<CompPlan> {
  return respond(() => plan(no, correctOptionNo));
}

export function compensate(no: string, correctOptionNo: string, reason: string): Promise<Compensation> {
  return mutate(() => {
    needReason(reason);
    if (compensations.some((c) => c.marketNo === no)) throw new ApiError('errors.fc.COMPENSATION_EXISTS', undefined, { status: 409, code: 'COMPENSATION_EXISTS' });
    const c: Compensation = { ...plan(no, correctOptionNo), compensationNo: String(7700 + compensations.length + 1), reason, createdBy: 1, createdAt: iso(Date.now()) };
    compensations = [c, ...compensations];
    return c;
  });
}

function seedComp() {
  const m = markets.find((x) => x.d.status === 'settled' && x.d.category === 'MACRO')!;
  compensations = [{ ...plan(m.d.publicNo, m.d.options[1].optionNo), compensationNo: '7700', reason: '统计局修正了 9 月 CPI，正确结果应为"否"', createdBy: 1, createdAt: iso(BASE - 2 * DAY) }];
}
seedComp();

export function listCompensations(page: number, pageSize: number): Promise<Page<Compensation>> {
  return respond(() => paginate(compensations.map((c) => ({ ...c, lines: [] })), page, pageSize), { empty: () => emptyPage<Compensation>(page, pageSize) });
}

export function getCompensation(no: string): Promise<Compensation> {
  return respond(() => {
    const c = compensations.find((x) => x.compensationNo === no);
    if (!c) throw new ApiError('errors.notFound', undefined, { status: 404, code: 'NOT_FOUND' });
    return c;
  });
}

export function getReport(from: string, to: string): Promise<Report> {
  return respond(
    () => {
      const days = [];
      let fee = 0n;
      let dust = 0n;
      let comp = 0n;
      let test = 0n;
      let mcr = 0n;
      let mdb = 0n;
      for (let t = Date.parse(`${to}T00:00:00Z`); t >= Date.parse(`${from}T00:00:00Z`); t -= DAY) {
        const k = Math.round((BASE - t) / DAY);
        const f = BigInt(4000 + ((k * 731) % 9000));
        const d = BigInt((k * 7) % 13);
        const c = k === 2 ? cents(compensations[compensations.length - 1]?.total ?? '0') : 0n;
        fee += f;
        dust += d;
        comp += c;
        // S17A：原型里第 1 天有两笔测试入金（只在测试环境有）
        const tc = k === 1 ? 1500000n : 0n;
        test += tc;
        // S17B：原型里第 2 天一笔人工加款、第 3 天一笔人工扣款
        const mc = k === 2 ? 80000n : 0n;
        const md = k === 3 ? 12050n : 0n;
        mcr += mc;
        mdb += md;
        days.push({ date: new Date(t).toISOString().slice(0, 10), feeIncome: money(f), dust: money(d), revenue: money(f + d), compensation: money(c), compensations: c > 0n ? 1 : 0, testCredit: money(tc), testCredits: tc > 0n ? 2 : 0, manualCredit: money(mc), manualCredits: mc > 0n ? 1 : 0, manualDebit: money(md), manualDebits: md > 0n ? 1 : 0 });
      }
      return { from, to, feeIncome: money(fee), dust: money(dust), revenue: money(fee + dust), compensationExpense: money(comp), compensations: days.filter((d) => d.compensations).length, testCredit: money(test), testCredits: days.reduce((n, d) => n + d.testCredits, 0), manualCredit: money(mcr), manualCredits: days.reduce((n, d) => n + d.manualCredits, 0), manualDebit: money(mdb), manualDebits: days.reduce((n, d) => n + d.manualDebits, 0), days };
    },
    { empty: () => ({ from, to, feeIncome: '0.00', dust: '0.00', revenue: '0.00', compensationExpense: '0.00', compensations: 0, testCredit: '0.00', testCredits: 0, manualCredit: '0.00', manualCredits: 0, manualDebit: '0.00', manualDebits: 0, days: [] }) },
  );
}

// ---------------------------------------------------------------- 仪表盘

export function getMarketDashboard(): Promise<MarketDashboard> {
  const period = (from: string, to: string, k: number) => ({ from, to, betAmount: money(1_284_350n * BigInt(k)), bets: 412 * k, bettors: 97 * k + 12, revenue: money(31_240n * BigInt(k)), compensation: k > 1 ? '186.40' : '0.00', registrations: 23 * k, deposit: money(4_520_000n * BigInt(k)), depositCount: 31 * k, withdraw: money(1_830_000n * BigInt(k)), withdrawCount: 9 * k });
  const zero = { from: '2026-10-25', to: '2026-10-25', betAmount: '0.00', bets: 0, bettors: 0, revenue: '0.00', compensation: '0.00', registrations: 0, deposit: '0.00', depositCount: 0, withdraw: '0.00', withdrawCount: 0 };
  return respond(
    () => {
      const items = cases.map(caseItem);
      return {
        today: period('2026-10-25', '2026-10-25', 1),
        week: period('2026-10-19', '2026-10-25', 6),
        pending: {
          review: markets.filter((m) => m.d.status === 'draft' && m.d.submitted).length,
          awaitingProposal: items.filter((i) => inTab(i, 'awaiting_proposal')).length,
          awaitingConfirm: items.filter((i) => inTab(i, 'awaiting_confirm')).length,
          autoFailed: items.filter((i) => inTab(i, 'auto_failed')).length,
          overdue: items.filter((i) => i.overdue).length,
          publicizing: items.filter((i) => i.stage === 'publicizing').length,
          disputed: items.filter((i) => i.stage === 'disputed').length,
          settleAbnormal: runs.filter((r) => r.status === 'abnormal').length,
          poolReconOpen: issues.filter((i) => i.status === 'open').length,
          withdrawalsPending: 3,
        },
        slate: { open: openCount(), limit: maxOpen(), today: markets.filter((m) => m.d.todaySlate && m.d.eventStartAt.slice(0, 10) === iso(Date.now()).slice(0, 10)).length },
        updatedAt: iso(Date.now()),
      };
    },
    { empty: () => ({ today: zero, week: { ...zero, from: '2026-10-19' }, pending: { review: 0, awaitingProposal: 0, awaitingConfirm: 0, autoFailed: 0, overdue: 0, publicizing: 0, disputed: 0, settleAbnormal: 0, poolReconOpen: 0, withdrawalsPending: 0 }, slate: { open: 0, limit: maxOpen(), today: 0 }, updatedAt: iso(Date.now()) }) },
  );
}

// ---------------------------------------------------------------- 加密自动生成、规则文本模板

const sample = (title: I18nText) => ({
  title,
  rules: L('窗口开始前 30 秒截止；以币安现货 BTC/USDT 1 分钟 K 线为准，OKX 现货 BTC-USDT 为备用；结束价 ≥ 开始价判"涨"。', 'Closes 30 s before the window; Binance spot BTC/USDT 1m klines, OKX spot BTC-USDT as backup; Up if close ≥ open.', '窗口開始前 30 秒截止；以幣安現貨 BTC/USDT 1 分鐘 K 線為準，OKX 現貨 BTC-USDT 為備用；結束價 ≥ 開始價判「漲」。'),
  sourcePrimary: 'Binance spot BTC/USDT 1m klines (data-api.binance.vision)',
  sourceBackup: 'OKX spot BTC-USDT 1m candles',
  options: [
    { outcome: 'UP', label: T.up, winCondition: W('结束价 ≥ 开始价') },
    { outcome: 'DOWN', label: T.down, winCondition: W('结束价 < 开始价') },
  ],
});

function windows(slots: string[], period: '15M' | '1H', generatedFirst: boolean) {
  const len = period === '1H' ? HOUR : 15 * MIN;
  const day = Date.UTC(2026, 9, 25);
  return [0, 1, 2, 3].map((d) => {
    const [h, m] = (slots[0] ?? '13:00').slice(0, 5).split(':').map(Number);
    const start = day + d * DAY + h * HOUR + m * MIN;
    return { windowStart: iso(start), windowEnd: iso(start + len), openAt: iso(start - 3 * HOUR), closeAt: iso(start - 30_000), generated: generatedFirst && d < 2, marketNo: generatedFirst && d < 2 ? `10791000000${d}` : null };
  });
}

let autogens: Autogen[] = [
  { autogenNo: '1000000000000000002', symbol: 'BTCUSDT', period: '1H', category: 'CRYPTO_1H', mode: 'schedule', slots: ['13:00', '20:00@6,7'], rollingCount: 3, generateAheadMinutes: 1440, templateNo: '1000000000000000001', userCap: null, enabled: true, checkedBy: 1, checkedAt: iso(BASE - 3 * DAY), checkedTemplateVersion: 2, version: 4, createdAt: iso(BASE - 10 * DAY), updatedAt: iso(BASE - 3 * DAY), upcoming: windows(['13:00'], '1H', true), sample: sample(L('BTC 涨还是跌：2026-10-25 13:00–14:00 UTC', 'BTC Up or Down: 2026-10-25 13:00–14:00 UTC', 'BTC 漲還是跌：2026-10-25 13:00–14:00 UTC')), missing: [] },
  { autogenNo: '1000000000000000003', symbol: 'ETHUSDT', period: '15M', category: 'CRYPTO_15M', mode: 'rolling', slots: [], rollingCount: 4, generateAheadMinutes: 1440, templateNo: '1000000000000000001', userCap: '1000.00', enabled: false, checkedBy: null, checkedAt: null, checkedTemplateVersion: null, version: 1, createdAt: iso(BASE - 2 * DAY), updatedAt: iso(BASE - 2 * DAY), upcoming: windows(['06:00'], '15M', false), sample: sample(L('ETH 涨还是跌：2026-10-25 06:00–06:15 UTC', 'ETH Up or Down: 2026-10-25 06:00–06:15 UTC', 'ETH 漲還是跌：2026-10-25 06:00–06:15 UTC')), missing: [] },
];

export function listAutogens(): Promise<Autogen[]> {
  return respond(() => autogens, { empty: () => [] });
}

function checkAutogen(i: AutogenInput) {
  const fields: Array<{ field: string; code: string }> = [];
  if (!['BTCUSDT', 'ETHUSDT'].includes(i.symbol)) fields.push({ field: 'symbol', code: 'invalid' });
  if (i.mode === 'schedule') {
    if (!i.slots.length) fields.push({ field: 'slots', code: 'required' });
    // S12c：和后端同一套规则（宽松读入、逐行错误码），保存成标准写法
    slotProblems(i.slots, i.period).forEach((code, k) => {
      if (code) fields.push({ field: `slots[${k}]`, code });
    });
  }
  if (fields.length) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields });
}

export function createAutogen(i: AutogenInput, reason: string): Promise<Autogen> {
  return mutate(() => {
    needReason(reason);
    checkAutogen(i);
    i = { ...i, slots: i.slots.map(normalizeSlot).sort() };
    if (autogens.some((a) => a.symbol === i.symbol && a.period === i.period)) throw new ApiError('errors.fc.AUTOGEN_EXISTS', undefined, { status: 409, code: 'AUTOGEN_EXISTS' });
    const a: Autogen = { ...autogens[0], ...i, autogenNo: String(1000000000000000010n + BigInt(autogens.length)), category: i.period === '1H' ? 'CRYPTO_1H' : 'CRYPTO_15M', rollingCount: i.rollingCount ?? 3, generateAheadMinutes: i.generateAheadMinutes ?? 1440, templateNo: i.templateNo ?? '1000000000000000001', enabled: false, checkedBy: null, checkedAt: null, checkedTemplateVersion: null, version: 0, upcoming: windows(i.slots, i.period, false) };
    autogens = [...autogens, a];
    return a;
  });
}

export function updateAutogen(no: string, i: AutogenInput, reason: string): Promise<Autogen> {
  return mutate(() => {
    needReason(reason);
    const a = autogens.find((x) => x.autogenNo === no);
    if (!a) throw new ApiError('errors.notFound', undefined, { status: 404, code: 'NOT_FOUND' });
    if (a.enabled) throw new ApiError('errors.fc.AUTOGEN_ENABLED', undefined, { status: 409, code: 'AUTOGEN_ENABLED' });
    checkAutogen(i);
    i = { ...i, slots: i.slots.map(normalizeSlot).sort() };
    Object.assign(a, i, { rollingCount: i.rollingCount ?? a.rollingCount, generateAheadMinutes: i.generateAheadMinutes ?? a.generateAheadMinutes, version: a.version + 1, upcoming: windows(i.slots, i.period, false) });
    return a;
  });
}

export function enableAutogen(no: string, checked: boolean): Promise<Autogen> {
  return mutate(() => {
    const a = autogens.find((x) => x.autogenNo === no)!;
    if (!checked) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'checked', code: 'required' }] });
    Object.assign(a, { enabled: true, checkedBy: 1, checkedAt: iso(Date.now()), checkedTemplateVersion: 2 });
    return a;
  });
}

export function disableAutogen(no: string, reason: string): Promise<Autogen> {
  return mutate(() => {
    needReason(reason);
    const a = autogens.find((x) => x.autogenNo === no)!;
    a.enabled = false;
    return a;
  });
}

let ruleTemplates: RuleTemplate[] = [
  { templateNo: '1000000000000000001', kind: 'CRYPTO_UPDOWN', name: '加密涨跌（默认）', title: L('{asset} 涨还是跌：{date} {startTime}–{endTime} UTC', '{asset} Up or Down: {date} {startTime}–{endTime} UTC', '{asset} 漲還是跌：{date} {startTime}–{endTime} UTC'), rules: sample(T.up).rules, sourcePrimary: sample(T.up).sourcePrimary, sourceBackup: sample(T.up).sourceBackup, options: sample(T.up).options, version: 2, usedBy: ['1000000000000000002', '1000000000000000003'], usedByEnabled: true, variables: ['asset', 'symbol', 'date', 'startTime', 'endTime', 'period'], updatedAt: iso(BASE - 5 * DAY) },
];

export function listRuleTemplates(): Promise<RuleTemplate[]> {
  return respond(() => ruleTemplates, { empty: () => [] });
}

export function updateRuleTemplate(no: string, t: Pick<RuleTemplate, 'name' | 'title' | 'rules' | 'sourcePrimary' | 'sourceBackup' | 'options'>, reason: string): Promise<RuleTemplate> {
  return mutate(() => {
    needReason(reason);
    const r = ruleTemplates.find((x) => x.templateNo === no)!;
    if (autogens.some((a) => a.enabled && a.templateNo === no)) throw new ApiError('errors.fc.TEMPLATE_IN_USE', undefined, { status: 409, code: 'TEMPLATE_IN_USE' });
    Object.assign(r, t, { version: r.version + 1, updatedAt: iso(Date.now()) });
    ruleTemplates = [...ruleTemplates];
    return r;
  });
}

// ---------------------------------------------------------------- 体育

function fx(i: number, sport: 'FOOTBALL' | 'NBA', comp: string, home: string, away: string, inHours: number, mapped: boolean): Fixture {
  const k = BASE + inHours * HOUR;
  return {
    fixtureNo: `55${String(i).padStart(6, '0')}`,
    sport,
    competition: sport === 'NBA' ? 'NBA' : comp,
    competitionName: sport === 'NBA' ? 'NBA' : comp === 'PL' ? 'Premier League' : 'Primera Division',
    home: { name: home, shortName: home.split(' ')[0] },
    away: { name: away, shortName: away.split(' ')[0] },
    kickoffAt: iso(k),
    originalKickoffAt: iso(i === 3 ? k - 2 * HOUR : k),
    kickoffChanged: i === 3,
    status: 'scheduled',
    rawStatus: 'TIMED',
    primarySource: sport === 'NBA' ? 'balldontlie' : 'football-data',
    primaryRef: String(540000 + i),
    mapping: mapped ? { source: 'thesportsdb', ref: String(2494000 + i), swapped: false, home: home.replace(' FC', ''), away: away.replace(' FC', ''), kickoffAt: iso(k), confirmedBy: 2, confirmedAt: iso(BASE - DAY) } : null,
    candidates: [
      { source: 'thesportsdb', ref: String(2494000 + i), home: home.replace(' FC', ''), away: away.replace(' FC', ''), kickoffAt: iso(k), league: comp, score: 0.96, swapped: false, suggested: true },
      { source: 'thesportsdb', ref: String(2495000 + i), home: away.replace(' FC', ''), away: home.replace(' FC', ''), kickoffAt: iso(k + 3 * DAY), league: comp, score: 0.41, swapped: true, suggested: false },
    ],
    candidatesAt: iso(BASE - 2 * HOUR),
    candidatesError: null,
    markets: [],
    fetchedAt: iso(BASE - 2 * HOUR),
  };
}

const fixtures: Fixture[] = [
  fx(1, 'FOOTBALL', 'PL', 'Manchester United FC', 'AFC Bournemouth', 26, true),
  fx(2, 'FOOTBALL', 'PL', 'Liverpool FC', 'Brighton & Hove Albion FC', 28, false),
  fx(3, 'FOOTBALL', 'PD', 'Girona FC', 'Sevilla FC', 50, false),
  fx(4, 'NBA', 'NBA', 'Golden State Warriors', 'Los Angeles Lakers', 36, true),
  fx(5, 'NBA', 'NBA', 'Boston Celtics', 'New York Knicks', 60, false),
];

export function listFixtures(q: FixtureQuery): Promise<Page<Fixture>> {
  return respond(
    () => paginate(fixtures.filter((f) => (!q.sport || f.sport === q.sport) && (!q.mapping || (q.mapping === 'confirmed') === !!f.mapping)), q.page, q.pageSize),
    { empty: () => emptyPage<Fixture>(q.page, q.pageSize) },
  );
}

const findFx = (no: string) => {
  const f = fixtures.find((x) => x.fixtureNo === no);
  if (!f) throw new ApiError('errors.notFound', undefined, { status: 404, code: 'NOT_FOUND' });
  return f;
};

export function getFixture(no: string): Promise<Fixture> {
  return respond(() => findFx(no));
}

export function syncFixtures(sport?: string): Promise<SyncResult[]> {
  return mutate(() =>
    (['FOOTBALL', 'NBA'] as const)
      .filter((s) => !sport || s === sport)
      .map((s) => ({ sport: s, source: s === 'NBA' ? 'balldontlie' : 'football-data', outcome: s === 'NBA' ? ('disabled' as const) : ('ok' as const), fetched: s === 'NBA' ? 0 : 38, created: s === 'NBA' ? 0 : 2, updated: s === 'NBA' ? 0 : 1, kickoffChanged: s === 'NBA' ? 0 : 1, candidatesSearched: s === 'NBA' ? 0 : 3, error: s === 'NBA' ? '未开通：没有配置 FC_SRC_BALLDONTLIE_KEY' : null })),
  );
}

export function searchCandidates(no: string): Promise<Fixture> {
  return mutate(() => ({ ...findFx(no), candidatesAt: iso(Date.now()) }));
}

export function confirmMapping(no: string, ref: string): Promise<Fixture> {
  return mutate(() => {
    const f = findFx(no);
    const c = f.candidates.find((x) => x.ref === ref);
    if (!c) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'ref', code: 'notCandidate' }] });
    f.mapping = { source: c.source, ref: c.ref, swapped: c.swapped, home: c.home, away: c.away, kickoffAt: c.kickoffAt, confirmedBy: 1, confirmedAt: iso(Date.now()) };
    return f;
  });
}

export function clearMapping(no: string): Promise<Fixture> {
  return mutate(() => {
    const f = findFx(no);
    f.mapping = null;
    return f;
  });
}

export function generateDrafts(no: string, plays: PlayInput[]): Promise<Generated> {
  return mutate(() => {
    const f = findFx(no);
    if (!plays.length) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'plays', code: 'required' }] });
    const fields = plays.flatMap((p, i) => ((p.play === 'NBA_TOTAL' && !p.line) || (p.line && !/\.5$/.test(p.line)) ? [{ field: `plays[${i}].line`, code: 'invalid' }] : []));
    if (fields.length) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields });
    const created = plays.map((p) => {
      const m = makeMarket({ category: f.sport, title: L(`${f.home.name} 对 ${f.away.name}`, `${f.home.name} v ${f.away.name}`, `${f.home.name} 對 ${f.away.name}`), status: 'draft', closeIn: Date.parse(f.kickoffAt) - BASE - 5 * MIN, origin: 'sports', options: [[T.home, W('主队胜')], [T.away, W('客队胜')]] });
      markets = [m, ...markets];
      return { marketNo: m.d.publicNo, play: p.play, line: p.line ?? (p.play === 'FOOTBALL_OU' ? '2.5' : null), status: 'draft' as const };
    });
    f.markets = [...f.markets, ...created];
    return { fixtureNo: no, created, warnings: f.mapping ? [] : ['mappingNotConfirmed'] };
  });
}

let sportsTemplates: SportsTemplate[] = (['FOOTBALL_1X2', 'FOOTBALL_OU', 'NBA_ML', 'NBA_TOTAL'] as const).map((kind, i) => ({
  kind,
  name: ['足球胜平负', '足球大小球', 'NBA 胜负', 'NBA 大小分'][i],
  title: L('{home} 对 {away}', '{home} v {away}', '{home} 對 {away}'),
  rules: L('以 90 分钟常规时间（含补时）的比分为准。', 'Based on the score after 90 minutes plus stoppage time.', '以 90 分鐘常規時間（含補時）的比分為準。'),
  sourcePrimary: kind.startsWith('NBA') ? 'balldontlie.io' : 'football-data.org',
  sourceBackup: 'thesportsdb.com',
  options: [
    { outcome: 'HOME', label: T.home, winCondition: W('主队胜') },
    { outcome: 'AWAY', label: T.away, winCondition: W('客队胜') },
  ],
  version: 1,
  updatedBy: null,
  updatedAt: iso(BASE - 6 * DAY),
  variables: ['home', 'away', 'competition', 'kickoff', 'line'],
}));

export function listSportsTemplates(): Promise<SportsTemplate[]> {
  return respond(() => sportsTemplates, { empty: () => [] });
}

export function updateSportsTemplate(kind: string, t: Pick<SportsTemplate, 'name' | 'title' | 'rules' | 'sourcePrimary' | 'sourceBackup' | 'options'>, reason: string): Promise<SportsTemplate> {
  return mutate(() => {
    needReason(reason);
    const s = sportsTemplates.find((x) => x.kind === kind)!;
    Object.assign(s, t, { version: s.version + 1, updatedBy: 1, updatedAt: iso(Date.now()) });
    sportsTemplates = [...sportsTemplates];
    return s;
  });
}

/** 单元测试用：恢复初始数据 */
export function resetFcMocks(): void {
  seq = 0;
  markets = seedMarkets();
  seedCases();
  runs = seedRuns();
  seedComp();
  riskAlerts = seedRisk();
  adjustSeed = seedAdjust();
  adjustments.length = 0;
  seedS16();
}

// ---------------------------------------------------------------- 风控告警、员工账户（forecast S10，和后端 RiskOps 字段一致）

const RISK_DETAIL: Record<RiskAlertKind, (i: number) => Record<string, string>> = {
  late_large_bet: (i) => ({ betNo: `20${4810 + i}`, optionId: '3', poolAfter: '6420.00', pct: `${32 + (i % 9)}.5`, limitPct: '30', secondsBeforeClose: `${60 + i * 7}`, limitMinutes: '5', limitAmount: '100.00' }),
  linked_opposite: (i) => ({ linkedUsers: String(100489 + (i % 3) * 7), options: '1', linkedOptions: `${100489 + (i % 3) * 7}:2` }),
  new_account_longshot: () => ({ betNo: '204899', odds: '6.82', limitOdds: '5', limitAmount: '1000.00', accountDays: '2', limitDays: '7' }),
  deposit_no_bet: (i) => ({ withdrawalNo: `30${7700 + i}`, deposit: '2000.00', stake: `${120 + i}.00`, multiple: '1' }),
  // S17B：24 小时人工加款累计过大（原型的种子数据里没有这一类，只用于类型完整）
  manual_credit_large: (i) => ({ adjustNo: `108${9000 + i}`, total: '12000.00', limit: '10000.00' }),
};
const RISK_STATUSES: RiskAlertStatus[] = ['new', 'new', 'seen', 'new', 'handled', 'false_positive'];

const seedRisk = (): RiskAlert[] => Array.from({ length: 26 }, (_, i): RiskAlert => {
  const kind = (['late_large_bet', 'linked_opposite', 'new_account_longshot', 'deposit_no_bet'] as const)[i % 4];
  const c = mockCustomers[(i * 5) % mockCustomers.length];
  const status = RISK_STATUSES[i % RISK_STATUSES.length];
  const done = status === 'handled' || status === 'false_positive';
  return {
    id: String(500 - i),
    kind,
    userId: String(c.id),
    customer: c.name,
    marketNo: kind === 'deposit_no_bet' ? null : `10790000000${String(10 + (i % 6)).padStart(3, '0')}`,
    market: kind === 'deposit_no_bet' ? null : ['BTC 15 分钟涨跌 · 13:00', '曼城 vs 利物浦 · 胜平负', '美联储 11 月议息：降息 25 基点？'][i % 3],
    amount: kind === 'linked_opposite' ? null : kind === 'deposit_no_bet' ? '1800' : kind === 'new_account_longshot' ? '1200' : '2400',
    detail: RISK_DETAIL[kind](i),
    status,
    note: done ? (status === 'handled' ? '已联系客户，正常' : '同一家庭的两个账户，已核实') : null,
    handledBy: status === 'new' ? null : 'amos',
    handledAt: status === 'new' ? null : iso(BASE - i * HOUR + 30 * MIN),
    createdAt: iso(BASE - i * HOUR),
  };
});
let riskAlerts = seedRisk();

export function listRiskAlerts(q: RiskAlertQuery): Promise<Page<RiskAlert>> {
  return respond(
    () =>
      paginate(
        riskAlerts.filter((a) => (!q.kind || a.kind === q.kind) && (!q.status || a.status === q.status) && matches(q.keyword, a.userId, a.customer)),
        q.page,
        q.pageSize,
      ),
    { empty: () => emptyPage<RiskAlert>(q.page, q.pageSize) },
  );
}

export function getRiskAlertCounts(): Promise<RiskAlertCounts> {
  const count = (list: RiskAlert[]): RiskAlertCounts => {
    const openList = list.filter((a) => a.status === 'new' || a.status === 'seen');
    return {
      open: openList.length,
      openByKind: {
        late_large_bet: openList.filter((a) => a.kind === 'late_large_bet').length,
        linked_opposite: openList.filter((a) => a.kind === 'linked_opposite').length,
        new_account_longshot: openList.filter((a) => a.kind === 'new_account_longshot').length,
        deposit_no_bet: openList.filter((a) => a.kind === 'deposit_no_bet').length,
        manual_credit_large: openList.filter((a) => a.kind === 'manual_credit_large').length,
      },
      byStatus: {
        new: list.filter((a) => a.status === 'new').length,
        seen: list.filter((a) => a.status === 'seen').length,
        handled: list.filter((a) => a.status === 'handled').length,
        false_positive: list.filter((a) => a.status === 'false_positive').length,
      },
    };
  };
  return respond(() => count(riskAlerts), { empty: () => count([]) });
}

export function setRiskAlertStatus(id: string, status: RiskAlertStatus, note: string | null): Promise<RiskAlert> {
  return mutate(() => {
    const n = note?.trim() || null;
    if (!n && (status === 'handled' || status === 'false_positive')) {
      throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'note', code: 'required' }] });
    }
    const a = riskAlerts.find((x) => x.id === id);
    if (!a) throw new ApiError('errors.notFound', undefined, { status: 404, code: 'NOT_FOUND' });
    Object.assign(a, { status, note: n, handledBy: 'amos', handledAt: iso(Date.now()) });
    return a;
  });
}

export function getCustomerLinks(id: number): Promise<CustomerLink[]> {
  const others = mockCustomers.filter((c) => c.id !== id).slice(0, 3);
  return respond(
    (): CustomerLink[] =>
      id % 2 === 0
        ? [
            { userId: String(others[0].id), customer: others[0].name, reason: 'device', evidence: '9f3c…e21a', firstSeenAt: iso(BASE - 3 * DAY) },
            { userId: String(others[0].id), customer: others[0].name, reason: 'ip', evidence: '45.77.12.9', firstSeenAt: iso(BASE - 3 * DAY) },
            { userId: String(others[1].id), customer: others[1].name, reason: 'withdraw_to', evidence: 'TRC20:TQ5xV…k9Pd', firstSeenAt: iso(BASE - DAY) },
          ]
        : [],
    { empty: () => [] },
  );
}

export function setCustomerStaff(id: number, staff: boolean, reason: string): Promise<StaffRefund> {
  return mutate(() => {
    needReason(reason);
    const c = mockCustomers.find((x) => x.id === id);
    if (!c) throw new ApiError('errors.notFound', undefined, { status: 404, code: 'NOT_FOUND' });
    const was = c.staff;
    c.staff = staff;
    return staff && !was ? { markets: 2, bets: 3, amount: '450.00' } : { markets: 0, bets: 0, amount: '0.00' };
  });
}

// ---------------------------------------------------------------- S17A 测试入金（原型数据：原型当成测试环境，按钮显示）

const testCreditRequests = new Map<string, { id: number; amount: string; result: TestCreditResult }>();
let testCreditTotal = 0n;

export function testCredit(id: number, amount: string, reason: string, requestId: string): Promise<{ customer: CustomerDetail; credit: TestCreditResult }> {
  return mutate(() => {
    needReason(reason);
    const c = mockCustomers.find((x) => x.id === id);
    if (!c) throw new ApiError('errors.notFound', undefined, { status: 404, code: 'NOT_FOUND' });
    const max = mockParams.find((p) => p.key === 'adjust.testCreditMax')?.value ?? '10000';
    const amt = toScaled(amount, 8);
    if (amt === null || amt <= 0n) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'amount', code: 'invalid' }] });
    if (amt > (toScaled(max, 8) ?? 0n)) {
      throw new ApiError('errors.unknown', { detail: `超过测试入金的单笔上限 ${max} USDT（参数设置可改）` }, { status: 400, code: 'ADJUST_OVER_LIMIT' });
    }
    const old = testCreditRequests.get(requestId);
    if (old) {
      if (old.id !== id || toScaled(old.amount, 8) !== amt) throw new ApiError('errors.unknown', undefined, { status: 409, code: 'ADJUST_REQUEST_REUSED' });
      return { id, credit: { ...old.result, duplicate: true } };
    }
    const before = c.balance;
    c.balance = fromScaled(((toScaled(c.balance, 8) ?? 0n) + amt) / 1000000n, 2);
    testCreditTotal += amt;
    const result: TestCreditResult = { creditNo: `1079${Date.now()}`, amount: fromScaled(amt, 8).replace(/\.?0+$/, ''), availableBefore: before, availableAfter: c.balance, duplicate: false };
    testCreditRequests.set(requestId, { id, amount, result });
    return { id, credit: result };
  }).then(async (r) => ({ customer: await mockGetCustomer(r.id), credit: r.credit }));
}

export function listPlatformAccounts(): Promise<PlatformAccount[]> {
  return respond(
    (): PlatformAccount[] => {
      const test = fromScaled(-(toScaled('15000', 8) ?? 0n) - testCreditTotal, 8).replace(/\.?0+$/, '');
      return [
        { kind: 'revenue', balance: '18342.17', ledgerTotal: '18342.17', entries: 412, matches: true },
        { kind: 'compensation', balance: '-436.5', ledgerTotal: '-436.5', entries: 3, matches: true },
        { kind: 'test_credit', balance: test, ledgerTotal: test, entries: 2 + testCreditRequests.size, matches: true },
        { kind: 'manual_adjust', balance: manualBalance(), ledgerTotal: manualBalance(), entries: 2 + adjustments.length, matches: true },
      ];
    },
    { empty: () => [] },
  );
}

// ---------------------------------------------------------------- S17B 人工调账（原型数据）

const adjustments: Adjustment[] = [];
const adjustRequests = new Map<string, string>();

function manualBalance(): string {
  let v = -(toScaled('800', 8) ?? 0n) + (toScaled('120.5', 8) ?? 0n);
  for (const a of adjustments) v += (a.direction === 'credit' ? -1n : 1n) * (toScaled(a.amount, 8) ?? 0n);
  return fromScaled(v, 8).replace(/\.?0+$/, '');
}

const seedAdjust = (): Adjustment[] => [
  { adjustNo: '1089000000000002', customerId: mockCustomers[1].id, customerEmail: mockCustomers[1].email, customerName: mockCustomers[1].name, direction: 'debit', category: 'wrong_credit', amount: '120.5', availableBefore: '520.5', availableAfter: '400', refNo: 'TK-20261026-07', reason: '同一笔充值入账两次，扣回多入的一笔', overLimit: false, operator: 'amos', createdAt: iso(BASE - 3 * DAY) },
  { adjustNo: '1089000000000001', customerId: mockCustomers[0].id, customerEmail: mockCustomers[0].email, customerName: mockCustomers[0].name, direction: 'credit', category: 'deposit_missing', amount: '800', availableBefore: '6020.4', availableAfter: '6820.4', refNo: '0x9f3c5a1be21a77d0c4e8b2f6a1d3c7e9b5f2a8d4c6e1b3a5f7d9c2e4b6a8f0d1', reason: 'BEP20 充值到了旧地址，链上已核对', overLimit: false, operator: 'amos', createdAt: iso(BASE - 2 * DAY) },
];
let adjustSeed = seedAdjust();

export function adjustBalance(id: number, input: AdjustInput): Promise<{ customer: CustomerDetail; adjustment: Adjustment; duplicate: boolean }> {
  return mutate(() => {
    needReason(input.reason);
    const c = mockCustomers.find((x) => x.id === id);
    if (!c) throw new ApiError('errors.notFound', undefined, { status: 404, code: 'NOT_FOUND' });
    const dup = adjustRequests.get(input.requestId);
    if (dup) return { id, adjustment: adjustments.find((a) => a.adjustNo === dup)!, duplicate: true };
    const amt = toScaled(input.amount, 8) ?? 0n;
    const max = mockParams.find((p) => p.key === 'adjust.manualMax')?.value ?? '10000';
    const over = amt > (toScaled(max, 8) ?? 0n);
    if (over && !input.overLimitConfirmed) {
      throw new ApiError('errors.unknown', { detail: `超过人工调账的单笔上限 ${max} USDT：要在确认框里再勾"我确认超过单笔上限"` }, { status: 409, code: 'ADJUST_OVER_LIMIT_UNCONFIRMED' });
    }
    const before = toScaled(c.balance, 8) ?? 0n;
    if (input.direction === 'debit' && amt > before) {
      throw new ApiError('errors.unknown', { detail: `扣款不能超过可用余额（现在可用 ${c.balance} USDT；下注中、提现冻结的钱不能扣）` }, { status: 409, code: 'ADJUST_INSUFFICIENT' });
    }
    const after = input.direction === 'credit' ? before + amt : before - amt;
    c.balance = fromScaled(after / 1000000n, 2);
    const a: Adjustment = {
      adjustNo: `1089${Date.now()}`,
      customerId: id,
      customerEmail: c.email,
      customerName: c.name,
      direction: input.direction,
      category: input.category,
      amount: fromScaled(amt, 8).replace(/\.?0+$/, ''),
      availableBefore: fromScaled(before, 8).replace(/\.?0+$/, ''),
      availableAfter: fromScaled(after, 8).replace(/\.?0+$/, ''),
      refNo: input.refNo.trim() || null,
      reason: input.reason,
      overLimit: over,
      operator: 'amos',
      createdAt: iso(Date.now()),
    };
    adjustments.unshift(a);
    adjustRequests.set(input.requestId, a.adjustNo);
    return { id, adjustment: a, duplicate: false };
  }).then(async (r) => ({ customer: await mockGetCustomer(r.id), adjustment: r.adjustment, duplicate: r.duplicate }));
}

function adjustFiltered(q: Omit<AdjustQuery, 'page' | 'pageSize'>): Adjustment[] {
  return [...adjustments, ...adjustSeed].filter(
    (a) =>
      (!q.customerId || String(a.customerId) === q.customerId.trim()) &&
      (!q.direction || a.direction === q.direction) &&
      (!q.category || a.category === q.category) &&
      (!q.from || a.createdAt.slice(0, 10) >= q.from) &&
      (!q.to || a.createdAt.slice(0, 10) <= q.to),
  );
}

export function listAdjustments(q: AdjustQuery): Promise<Page<Adjustment>> {
  return respond(() => paginate(adjustFiltered(q), q.page, q.pageSize), { empty: () => emptyPage<Adjustment>(q.page, q.pageSize) });
}

export function exportAdjustments(q: Omit<AdjustQuery, 'page' | 'pageSize'>, lang: string): Promise<void> {
  return respond(() => {
    const zh = lang !== 'en';
    downloadCsv('balance-adjustments.csv', [
      zh ? ['调账编号', '时间（UTC）', '客户编号', '方向', '类别', '金额（USDT）', '参考号', '原因', '操作人'] : ['Adjustment no', 'Time (UTC)', 'Customer ID', 'Direction', 'Category', 'Amount (USDT)', 'Reference', 'Reason', 'Operator'],
      ...adjustFiltered(q).map((a) => [a.adjustNo, a.createdAt, String(a.customerId), a.direction, a.category, a.amount, a.refNo ?? '', a.reason, a.operator]),
    ]);
  });
}

// ---------------------------------------------------------------- S13 来源统计（原型数据）

const GROWTH_SOURCES: Array<[string, string, string, number]> = [
  ['tg_ch', 'w43', '', 38],
  ['x', 'launch', 'kol_amy', 21],
  // S15：客户分享链接的 ref 是随机分享码，统计里换算成分享人的客户编号
  ['share', 'card', 'k7mqx2hpre', 9],
  ['', '', '', 12],
];

export function getGrowthReport(q: GrowthQuery): Promise<GrowthReport> {
  const basis = q.basis ?? 'first';
  const weeks = ['2026-W43', '2026-W42', '2026-W41'];
  const cohorts: GrowthCohort[] = weeks.flatMap((week, wi) =>
    GROWTH_SOURCES.map(([src, cmp, ref, base]) => {
      const reg = Math.max(1, Math.round(base / (wi + 1)) - (basis === 'reg' && src === '' ? 2 : 0));
      const eligible = wi === 0 ? 0 : reg;
      return {
        week, src, cmp, ref,
        refCustomer: ref === 'k7mqx2hpre' ? '100023' : null,
        visits: src ? reg * 6 + wi : 0,
        registrations: reg,
        firstDeposits: Math.round(reg * 0.4),
        activated: Math.round(reg * 0.55),
        week2Active: eligible ? Math.round(reg * 0.3) : 0,
        week2Eligible: eligible,
      };
    }),
  );
  const sum = (k: keyof Omit<GrowthCohort, 'week' | 'src' | 'cmp' | 'ref' | 'refCustomer'>) => cohorts.reduce((a, c) => a + c[k], 0);
  return respond(
    () => {
      const ended = markets.filter((m) => ['settled', 'voided', 'cancelled'].includes(m.d.status));
      return {
        from: q.from ?? '2026-09-07',
        to: q.to ?? '2026-11-01',
        basis,
        cohorts,
        totals: { visits: sum('visits'), registrations: sum('registrations'), firstDeposits: sum('firstDeposits'), activated: sum('activated'), week2Active: sum('week2Active'), week2Eligible: sum('week2Eligible') },
        marketWeeks: [
          { week: '2026-W43', ended: 4, settled: 2, voided: 1, cancelled: 1, validRate: '50.0', medianBettors: '6' },
          { week: '2026-W42', ended: 10, settled: 10, voided: 0, cancelled: 0, validRate: '100.0', medianBettors: '7.5' },
        ],
        reasons: [
          { status: 'voided', reason: 'POSTPONED_48H', count: 1 },
          { status: 'cancelled', reason: '主办方宣布比赛取消', count: 1 },
        ],
        markets: ended.map((m) => {
          const s = summary(m);
          return {
            marketNo: m.d.publicNo,
            category: m.d.category,
            titleZhCN: m.d.title.zhCN,
            titleEn: m.d.title.en,
            status: m.d.status as 'settled' | 'voided' | 'cancelled',
            endedAt: m.d.statusLog[m.d.statusLog.length - 1]?.time ?? m.d.updatedAt,
            bettors: s.bettors,
            pool: s.pool,
            reason: m.d.voidReason ?? m.d.cancelReason,
          };
        }),
        truncated: false,
      };
    },
    {
      empty: () => ({ from: q.from ?? '2026-09-07', to: q.to ?? '2026-11-01', basis, cohorts: [], totals: { visits: 0, registrations: 0, firstDeposits: 0, activated: 0, week2Active: 0, week2Eligible: 0 }, marketWeeks: [], reasons: [], markets: [], truncated: false }),
    },
  );
}

/** 原型：在浏览器里生成一份同样表头的 CSV（只有客户编号，没有邮箱） */
export async function exportGrowth(kind: GrowthExportKind, q: GrowthQuery, lang: string): Promise<void> {
  const r = await getGrowthReport(q);
  const en = lang === 'en';
  if (kind === 'sources') {
    downloadCsv(`growth-sources.csv`, [
      en ? ['week', 'src', 'cmp', 'ref', 'visits', 'registrations', 'first_deposits', 'activated', 'week2_active', 'week2_eligible'] : ['注册周', '来源', '活动', '推荐人', '访问', '注册', '首充', '激活（首注）', '第 2 周仍下注', '第 2 周可算人数'],
      ...r.cohorts.map((c) => [c.week, c.src, c.cmp, c.ref, c.visits, c.registrations, c.firstDeposits, c.activated, c.week2Active, c.week2Eligible]),
    ]);
  } else if (kind === 'markets') {
    downloadCsv(`growth-markets.csv`, [en ? ['market_no', 'category', 'status', 'ended_at', 'bettors', 'pool', 'reason'] : ['市场编号', '品类', '状态', '结束时间', '下注人数', '奖池', '原因'], ...r.markets.map((m) => [m.marketNo, m.category, m.status, m.endedAt, m.bettors, m.pool, m.reason ?? ''])]);
  } else {
    downloadCsv(`growth-customers.csv`, [en ? ['customer_no', 'registered_at', 'first_src'] : ['客户编号', '注册时间', '首次来源'], ['100200', '2026-10-20T08:00:00Z', 'tg_ch']]);
  }
}

// ---------------------------------------------------------------- 等候页订阅、Telegram 机器人（forecast S16，和后端 WaitlistAdminApi、TelegramAdminApi 字段一致）

let waitlist: WaitlistItem[] = [];
let launch: WaitlistLaunch = { status: 'none', at: null, adminId: null, reason: null, recipients: 0, sent: 0, finishedAt: null };
let tg: TelegramOverview;
let tgPosts: TelegramPost[] = [];

function seedS16(): void {
  const langs: WaitlistItem['lang'][] = ['zh-CN', 'en', 'zh-TW'];
  const statuses: WaitlistItem['status'][] = ['confirmed', 'confirmed', 'pending', 'confirmed', 'unsubscribed', 'pending'];
  const srcs: Array<[string | null, string | null, string | null]> = [
    ['x', 'data-reply', null],
    [null, null, null],
    ['telegram', 'channel', 'zh'],
    ['share', 'card-market', '100231'],
  ];
  waitlist = Array.from({ length: 26 }, (_, i) => {
    const st = statuses[i % statuses.length];
    const created = BASE - (26 - i) * 5 * HOUR;
    const [src, cmp, ref] = srcs[i % srcs.length];
    return {
      id: 1000 + i,
      email: `${'abcdefghijklmnopqrstuvwxyz'[i]}***@${i % 3 === 0 ? 'gmail.com' : 'example.com'}`,
      lang: langs[i % 3],
      status: st,
      src,
      cmp,
      ref,
      start: null,
      createdAt: iso(created),
      confirmedAt: st === 'pending' ? null : iso(created + 20 * MIN),
      notifiedAt: null,
      unsubscribedAt: st === 'unsubscribed' ? iso(created + 2 * DAY) : null,
    };
  });
  launch = { status: 'none', at: null, adminId: null, reason: null, recipients: 0, sent: 0, finishedAt: null };
  tg = {
    status: {
      configured: true,
      botUsername: 'quickcome_bot',
      healthy: true,
      lastError: null,
      lastOkAt: iso(BASE - 2 * MIN),
      emergencyStop: false,
      stopReason: null,
      stopAt: null,
      stopBy: null,
    },
    targets: [
      { key: 'CHANNEL_ZH', lang: 'zh-CN', chatId: '@quickcome_cn', enabled: true, enabledAt: iso(BASE - 3 * DAY), failStreak: 0, disabledReason: null, updatedAt: iso(BASE - 3 * DAY) },
      { key: 'CHANNEL_EN', lang: 'en', chatId: null, enabled: false, enabledAt: null, failStreak: 0, disabledReason: null, updatedAt: iso(BASE - 3 * DAY) },
    ],
    groups: [
      { chatId: '-1001234567890', title: '币圈数据交流群', type: 'supergroup', status: 'active', broadcast: true, lang: 'zh-CN', startParam: 's-tg__c-g1', addedAt: iso(BASE - 2 * DAY), broadcastAt: iso(BASE - 2 * DAY) },
      { chatId: '-1009876543210', title: 'Football odds talk', type: 'supergroup', status: 'active', broadcast: false, lang: 'en', startParam: null, addedAt: iso(BASE - DAY), broadcastAt: null },
      { chatId: '-1005555555555', title: '已退出的群', type: 'group', status: 'left', broadcast: false, lang: 'zh-CN', startParam: null, addedAt: iso(BASE - 5 * DAY), broadcastAt: null },
    ],
    templates: [
      { kind: 'RESULT', lang: 'zh-CN', status: 'approved', changed: false, approvedAt: iso(BASE - 3 * DAY), approvedBy: 1 },
      { kind: 'RESULT', lang: 'en', status: 'draft', changed: false, approvedAt: null, approvedBy: null },
    ],
  };
  const st: TelegramPostStatus[] = ['sent', 'sent', 'skipped', 'sent', 'failed', 'stopped'];
  tgPosts = Array.from({ length: 24 }, (_, i) => {
    const s = st[i % st.length];
    const target = i % 3 === 2 ? 'G:-1001234567890' : i % 3 === 1 ? 'CHANNEL_EN' : 'CHANNEL_ZH';
    return {
      id: 500 + i,
      marketNo: String(108000000000000000 + i * 977),
      kind: 'result',
      target,
      lang: target === 'CHANNEL_EN' ? 'en' : 'zh-CN',
      status: s,
      attempts: s === 'failed' ? 3 : s === 'sent' ? 1 : 0,
      postUrl: s === 'sent' && target === 'CHANNEL_ZH' ? `https://t.me/quickcome_cn/${40 + i}` : null,
      note: s === 'skipped' ? '模板是草稿（还没审定），没有发' : s === 'failed' ? '重试 3 次都失败：sendPhoto：HTTP 500 Internal Server Error' : s === 'stopped' ? '紧急停止中，没有发' : null,
      createdAt: iso(BASE - i * 3 * HOUR),
      sentAt: s === 'sent' ? iso(BASE - i * 3 * HOUR + MIN) : null,
    };
  });
}
seedS16();

function requireReason(reason: string): void {
  if (!reason?.trim()) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'reason', code: 'required' }] });
}

export function listWaitlist(q: WaitlistQuery): Promise<WaitlistPage> {
  const counts = () => ({
    pending: waitlist.filter((w) => w.status === 'pending').length,
    confirmed: waitlist.filter((w) => w.status === 'confirmed').length,
    notified: waitlist.filter((w) => w.status === 'notified').length,
    unsubscribed: waitlist.filter((w) => w.status === 'unsubscribed').length,
  });
  return respond(
    () => ({ ...paginate(waitlist.filter((w) => !q.status || w.status === q.status).slice().reverse(), q.page, q.pageSize), counts: counts(), launch }),
    { empty: () => ({ ...emptyPage<WaitlistItem>(q.page, q.pageSize), counts: { pending: 0, confirmed: 0, notified: 0, unsubscribed: 0 }, launch }) },
  );
}

export function launchWaitlist(reason: string): Promise<WaitlistLaunch> {
  return mutate(() => {
    requireReason(reason);
    if (launch.status !== 'none') throw new ApiError('errors.fc.LAUNCH_ALREADY_SENT', undefined, { status: 409, code: 'LAUNCH_ALREADY_SENT' });
    const confirmed = waitlist.filter((w) => w.status === 'confirmed');
    const now = iso(BASE + HOUR);
    confirmed.forEach((w) => Object.assign(w, { status: 'notified', notifiedAt: now }));
    launch = { status: 'done', at: now, adminId: 1, reason: reason.trim(), recipients: confirmed.length, sent: confirmed.length, finishedAt: now };
    return launch;
  });
}

export function getTelegram(): Promise<TelegramOverview> {
  return respond(() => tg, { empty: () => ({ ...tg, groups: [] }) });
}

export function listTelegramPosts(status: TelegramPostStatus | undefined, page: number, pageSize: number): Promise<Page<TelegramPost>> {
  return respond(() => paginate(tgPosts.filter((p) => !status || p.status === status), page, pageSize), { empty: () => emptyPage<TelegramPost>(page, pageSize) });
}

export function updateTelegramTarget(key: TelegramTargetKey, chatId: string | null, enabled: boolean, reason: string): Promise<TelegramTarget> {
  return mutate(() => {
    requireReason(reason);
    const id = chatId?.trim() || null;
    if ((id && !/^(@[A-Za-z][A-Za-z0-9_]{4,31}|-?\d{5,20})$/.test(id)) || (enabled && !id)) {
      throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'chatId', code: id ? 'invalid' : 'required' }] });
    }
    const t = tg.targets.find((x) => x.key === key);
    if (!t) throw new ApiError('errors.notFound', undefined, { status: 404, code: 'NOT_FOUND' });
    const now = iso(BASE + HOUR);
    Object.assign(t, { chatId: id, enabledAt: enabled && !t.enabled ? now : t.enabledAt, enabled, updatedAt: now, failStreak: enabled ? 0 : t.failStreak, disabledReason: enabled ? null : t.disabledReason });
    return t;
  });
}

export function stopTelegramGroup(chatId: string, reason: string): Promise<TelegramGroup> {
  return mutate(() => {
    requireReason(reason);
    const g = tg.groups.find((x) => x.chatId === chatId);
    if (!g) throw new ApiError('errors.notFound', undefined, { status: 404, code: 'NOT_FOUND' });
    g.broadcast = false;
    return g;
  });
}

const PREVIEW_TEXT: Record<string, string> = {
  'zh-CN': '结果：涨\nBTC 涨还是跌：10-25 13:00–14:00 UTC\n✓ 涨 62.5% · 5 人 · 125.00 USDT · 最终赔率 ×1.57\n· 跌 37.5% · 3 人 · 75.00 USDT\n奖池 200.00 USDT · 派彩合计 197.50 USDT · 抽成 2.50 USDT · 尾差 0.00 USDT\n下注人数 8',
  en: 'Result: Up\nBTC Up or Down: 10-25 13:00–14:00 UTC\n✓ Up 62.5% · 5 bettors · 125.00 USDT · final odds ×1.57\n· Down 37.5% · 3 bettors · 75.00 USDT\nPool 200.00 USDT · Payouts 197.50 USDT · Fee 2.50 USDT · Dust 0.00 USDT\nBettors 8',
};

export function getTelegramPreview(kind: 'RESULT', lang: string): Promise<TelegramPreview> {
  return respond(() => ({ kind, lang: lang as TelegramPreview['lang'], marketNo: '108000000000000000', text: PREVIEW_TEXT[lang] ?? PREVIEW_TEXT.en, fingerprint: `fp-${lang}` }));
}

/** 原型里没有服务器画的图，用站点图标占位 */
export const telegramPreviewUrl = (): string => `${import.meta.env.BASE_URL}favicon.svg`;

export function approveTelegramTemplate(kind: 'RESULT', lang: string, fingerprint: string, reason: string): Promise<TelegramTemplate> {
  return mutate(() => {
    requireReason(reason);
    if (fingerprint !== `fp-${lang}`) throw new ApiError('errors.fc.TELEGRAM_TEMPLATE_CHANGED', undefined, { status: 409, code: 'TELEGRAM_TEMPLATE_CHANGED' });
    const t = tg.templates.find((x) => x.kind === kind && x.lang === lang)!;
    Object.assign(t, { status: 'approved', changed: false, approvedAt: iso(BASE + HOUR), approvedBy: 1 });
    return t;
  });
}

export function disableTelegramTemplate(kind: 'RESULT', lang: string, reason: string): Promise<TelegramTemplate> {
  return mutate(() => {
    requireReason(reason);
    const t = tg.templates.find((x) => x.kind === kind && x.lang === lang)!;
    t.status = 'disabled';
    return t;
  });
}

export function setTelegramStop(stop: boolean, reason: string): Promise<TelegramStatus> {
  return mutate(() => {
    requireReason(reason);
    Object.assign(tg.status, { emergencyStop: stop, stopReason: reason.trim(), stopAt: iso(BASE + HOUR), stopBy: 1 });
    return tg.status;
  });
}
