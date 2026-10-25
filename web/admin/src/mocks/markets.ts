/**
 * 市场管理、裁决台的模拟层（forecast M1 原型）。和客户端原型同一批市场（英超、BTC、NBA、欧冠、美联储、CPI……），
 * 另外有草稿、待上架、自动生成的加密市场，以及裁决台的 4 种情况：待提议、待确认（需求第 3.5 节的例子，结算预览 9,799.99 + 199.99 + 0.02 = 10,000.00）、
 * 自动裁决失败（两个来源不一致）、超时。结算预览按需求第 3.5 节逐个持仓算（盈利向下取整、抽成四舍五入、尾差归平台）。
 */
import {
  ApiError,
  type AdminMarketQuery,
  type AdminMarketRow,
  type I18nText,
  type MarketDraft,
  type Page,
  type ResolveCase,
  type ResolveSummary,
  type SettlePreview,
} from '../api/types';
import { fromScaled, toScaled } from '../utils/money';
import { mutate, paginate, respond } from './runtime';

const L = (zhCN: string, en: string, zhTW: string): I18nText => ({ zhCN, en, zhTW });
const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;
const iso = (ms: number) => new Date(ms).toISOString();
const ceilTo = (ms: number, step: number) => Math.ceil(ms / step) * step;
const hm = (ms: number) => iso(ms).slice(11, 16);

function rows(): AdminMarketRow[] {
  const now = Date.now();
  const btc = ceilTo(now + 20 * MIN, HOUR);
  const eth = ceilTo(now + 20 * MIN, 15 * MIN);
  const r = (
    publicNo: string,
    category: AdminMarketRow['category'],
    title: I18nText,
    status: AdminMarketRow['status'],
    closeAt: number,
    pool: string,
    bettors: number,
    extra: Partial<AdminMarketRow> = {},
  ): AdminMarketRow => ({
    publicNo,
    category,
    title,
    status,
    submitted: false,
    openAt: iso(closeAt - 3 * DAY),
    closeAt: iso(closeAt),
    eventStartAt: iso(closeAt + 5 * MIN),
    feeRate: '5',
    pool,
    bettors,
    resolutionMode: category === 'MACRO' ? 'PLATFORM' : 'AUTO',
    origin: category.startsWith('CRYPTO') ? 'autogen' : category === 'MACRO' ? 'manual' : 'schedule',
    createdBy: category.startsWith('CRYPTO') ? 'system' : 'A02',
    ...extra,
  });
  return [
    r('105901339852805101', 'NBA', L('勇士 vs 湖人（胜负，含加时）', 'Warriors vs Lakers (winner, incl. OT)', '勇士 vs 湖人（勝負，含加時）'), 'draft', now + 4 * DAY, '0.00', 0, { submitted: true }),
    r('105901339852805102', 'FOOTBALL', L('阿森纳 vs 热刺（90 分钟胜平负）', 'Arsenal vs Tottenham (90-min result)', '阿森納 vs 熱刺（90 分鐘勝平負）'), 'draft', now + 6 * DAY, '0.00', 0, { submitted: true }),
    r('105901339852805103', 'MACRO', L('欧洲央行 12 月议息会议宣布降息吗？', 'Will the ECB cut rates at its December meeting?', '歐洲央行 12 月議息會議宣佈降息嗎？'), 'draft', now + 40 * DAY, '0.00', 0),
    r('105814740172805011', 'CRYPTO_15M', L(`ETH 涨还是跌？${hm(eth)}–${hm(eth + 15 * MIN)} UTC`, `ETH up or down? ${hm(eth)}–${hm(eth + 15 * MIN)} UTC`, `ETH 漲還是跌？${hm(eth)}–${hm(eth + 15 * MIN)} UTC`), 'listed', eth - 30_000, '0.00', 0),
    r('105771958272005004', 'CRYPTO_1H', L(`BTC 涨还是跌？${hm(btc)}–${hm(btc + HOUR)} UTC`, `BTC up or down? ${hm(btc)}–${hm(btc + HOUR)} UTC`, `BTC 漲還是跌？${hm(btc)}–${hm(btc + HOUR)} UTC`), 'listed', btc - 30_000, '3420.00', 128),
    r('105454868889605008', 'NBA', L('湖人 vs 凯尔特人：总分大于 224.5？', 'Lakers vs Celtics: total points over 224.5?', '湖人 vs 凱爾特人：總分大於 224.5？'), 'listed', now + 2 * DAY, '2215.00', 77),
    r('105092481024005001', 'FOOTBALL', L('曼城 vs 利物浦（90 分钟胜平负）', 'Man City vs Liverpool (90-min result)', '曼城 vs 利物浦（90 分鐘勝平負）'), 'listed', now + 7 * DAY, '10000.00', 67),
    r('104730093158405018', 'FOOTBALL', L('皇马 vs 拜仁：总进球大于 2.5？', 'Real Madrid vs Bayern: over 2.5 goals?', '皇馬 vs 拜仁：總進球大於 2.5？'), 'paused', now + 11 * DAY, '4480.00', 149),
    r('103642929561605015', 'MACRO', L('美联储 12 月议息会议宣布降息吗？', 'Will the Fed cut rates at its December meeting?', '美聯儲 12 月議息會議宣佈降息嗎？'), 'listed', now + 45 * DAY, '1860.00', 75, { feeRate: '5' }),
    r('104730093158405022', 'NBA', L('勇士 vs 掘金（胜负，含加时）', 'Warriors vs Nuggets (winner, incl. OT)', '勇士 vs 掘金（勝負，含加時）'), 'closed', now - 75 * MIN, '5600.00', 177),
    r('104005317427205027', 'FOOTBALL', L('布伦特福德 vs 富勒姆（90 分钟胜平负）', 'Brentford vs Fulham (90-min result)', '布倫特福德 vs 富勒姆（90 分鐘勝平負）'), 'publicizing', now - 4 * HOUR, '4830.00', 136),
    r('104005317427205030', 'FOOTBALL', L('切尔西 vs 纽卡斯尔（90 分钟胜平负）', 'Chelsea vs Newcastle (90-min result)', '切爾西 vs 紐卡斯爾（90 分鐘勝平負）'), 'pending_resolution', now - 3 * HOUR, '10000.00', 6),
    r('98569499443205031', 'MACRO', L('美国 9 月 CPI 同比 ≥ 3.0%？', 'US September CPI year-over-year ≥ 3.0%?', '美國 9 月 CPI 同比 ≥ 3.0%？'), 'settled', now - 11 * DAY, '8000.00', 227),
    r('102918153830405040', 'FOOTBALL', L('赫罗纳 vs 塞维利亚（90 分钟胜平负）', 'Girona vs Sevilla (90-min result)', '赫羅納 vs 塞維利亞（90 分鐘勝平負）'), 'voided', now - 3 * DAY, '2660.00', 87),
  ];
}

let markets = rows();

const TAB: Record<NonNullable<AdminMarketQuery['tab']>, (r: AdminMarketRow) => boolean> = {
  all: () => true,
  review: (r) => r.status === 'draft' && r.submitted,
  draft: (r) => r.status === 'draft',
  active: (r) => r.status === 'listed' || r.status === 'paused',
  waiting: (r) => ['closed', 'pending_resolution', 'publicizing', 'disputed'].includes(r.status),
  ended: (r) => ['settled', 'cancelled', 'voided'].includes(r.status),
};

export function listAdminMarkets(q: AdminMarketQuery): Promise<Page<AdminMarketRow> & { counts: Record<string, number> }> {
  return respond(
    () => {
      const k = (q.keyword ?? '').trim().toLowerCase();
      const items = markets.filter(
        (r) => TAB[q.tab ?? 'all'](r) && (!q.category || r.category === q.category) && (!k || r.publicNo.includes(k) || [r.title.zhCN, r.title.en].some((s) => s.toLowerCase().includes(k))),
      );
      const counts = Object.fromEntries(Object.entries(TAB).map(([t, f]) => [t, markets.filter(f).length]));
      return { ...paginate(items, q.page, q.pageSize), counts };
    },
    { empty: () => ({ items: [], total: 0, page: 1, pageSize: q.pageSize, counts: { all: 0, review: 0, draft: 0, active: 0, waiting: 0, ended: 0 } }) },
  );
}

/** 编辑页的草稿：勇士 vs 湖人（赛程生成），繁体规则还没填，演示"上架检查清单" */
function draftOf(publicNo: string | null): MarketDraft {
  const now = Date.now();
  const start = ceilTo(now + 4 * DAY, HOUR) + 30 * MIN;
  const base: MarketDraft = {
    publicNo,
    category: 'NBA',
    title: L('勇士 vs 湖人（胜负，含加时）', 'Warriors vs Lakers (winner, incl. OT)', '勇士 vs 湖人（勝負，含加時）'),
    options: [
      { label: L('勇士', 'Warriors', '勇士'), winCondition: L('勇士获胜（含加时）', 'Warriors win (incl. OT)', '勇士獲勝（含加時）') },
      { label: L('湖人', 'Lakers', '湖人'), winCondition: L('湖人获胜（含加时）', 'Lakers win (incl. OT)', '湖人獲勝（含加時）') },
    ],
    rules: L(
      '以全场比赛（含加时）的最终比分为准。NBA 没有平局。完赛 30 分钟后两个比分源一致时自动结算；不一致转平台裁决。',
      'Based on the final score including overtime. There are no draws in the NBA. Settles automatically 30 minutes after the final when both score sources agree; otherwise resolved by the platform.',
      '',
    ),
    resolutionMode: 'AUTO',
    sourcePrimary: 'balldontlie',
    sourceBackup: 'API-NBA',
    openAt: iso(now + HOUR),
    closeAt: iso(start - 5 * MIN),
    eventStartAt: iso(start),
    eventEndAt: iso(start + 3 * HOUR),
    voidRules: L('原定开赛后 48 小时内没完赛的，作废、全额退回。', 'If the game is not completed within 48 hours of the original tip-off, the market is voided and refunded.', '原定開賽後 48 小時內沒完賽的，作廢、全額退回。'),
    correction: L('以完赛后 1 小时的官方比分为准，之后的修正不算。', 'The official score 1 hour after the final counts; later corrections do not.', '以完賽後 1 小時的官方比分為準，之後的修正不算。'),
    disputeWindowMinutes: null,
    feeRate: '5',
    userCap: '10000',
    missing: ['rules.zhTW'],
    status: 'draft',
    submitted: true,
    version: 3,
  };
  if (publicNo) return base;
  return {
    ...base,
    title: L('', '', ''),
    options: [
      { label: L('', '', ''), winCondition: L('', '', '') },
      { label: L('', '', ''), winCondition: L('', '', '') },
    ],
    rules: L('', '', ''),
    sourcePrimary: '',
    sourceBackup: '',
    missing: ['title', 'options', 'rules', 'sourcePrimary'],
    submitted: false,
    version: 0,
  };
}

const drafts = new Map<string, MarketDraft>();

export function getMarketDraft(publicNo: string | null): Promise<MarketDraft> {
  return respond(() => (publicNo && drafts.get(publicNo)) || draftOf(publicNo));
}

/** 第 4.1 节：哪些必填项还没填（三种语言都要有） */
export function missingFields(d: MarketDraft): string[] {
  const miss: string[] = [];
  const all = (t: I18nText) => !!(t.zhCN.trim() && t.en.trim() && t.zhTW.trim());
  if (!all(d.title)) miss.push('title');
  if (d.options.length < 2 || d.options.some((o) => !all(o.label) || !all(o.winCondition))) miss.push('options');
  if (!d.rules.zhCN.trim() || !d.rules.en.trim()) miss.push('rules');
  else if (!d.rules.zhTW.trim()) miss.push('rules.zhTW');
  if (!d.sourcePrimary.trim()) miss.push('sourcePrimary');
  if (d.resolutionMode === 'PLATFORM' && !d.disputeWindowMinutes) miss.push('disputeWindow');
  return miss;
}

export function saveMarketDraft(d: MarketDraft, submit: boolean): Promise<MarketDraft> {
  return mutate(() => {
    const missing = missingFields(d);
    if (submit && missing.length) throw new ApiError('errors.marketMissing', { n: missing.length });
    const no = d.publicNo ?? `1059013398528${Math.floor(Math.random() * 90000 + 10000)}`;
    const saved: MarketDraft = { ...d, publicNo: no, missing, submitted: submit || d.submitted, version: d.version + 1 };
    drafts.set(no, saved);
    const row = markets.find((r) => r.publicNo === no);
    if (row) Object.assign(row, { title: d.title, submitted: saved.submitted });
    else markets.unshift({ publicNo: no, category: d.category, title: d.title, status: 'draft', submitted: saved.submitted, openAt: d.openAt, closeAt: d.closeAt, eventStartAt: d.eventStartAt, feeRate: d.feeRate, pool: '0.00', bettors: 0, resolutionMode: d.resolutionMode, origin: 'manual', createdBy: 'A01' });
    return saved;
  });
}

// ---------------- 裁决台 ----------------
function cases(): ResolveCase[] {
  const now = Date.now();
  const opt = (optionNo: string, label: I18nText, pool: string, bettors: number, positions: number) => ({ optionNo, label, pool, bettors, positions });
  const homeDrawAway = (h: I18nText, a: I18nText, pools: [string, string, string], people: [number, number, number], no: string) => [
    opt(`${no}1`, L(`主胜 ${h.zhCN}`, h.en, `主勝 ${h.zhTW}`), pools[0], people[0], people[0]),
    opt(`${no}2`, L('平局', 'Draw', '平局'), pools[1], people[1], people[1]),
    opt(`${no}3`, L(`客胜 ${a.zhCN}`, a.en, `客勝 ${a.zhTW}`), pools[2], people[2], people[2]),
  ];
  return [
    {
      publicNo: '104005317427205030',
      category: 'FOOTBALL',
      title: L('切尔西 vs 纽卡斯尔（90 分钟胜平负）', 'Chelsea vs Newcastle (90-min result)', '切爾西 vs 紐卡斯爾（90 分鐘勝平負）'),
      mode: 'PLATFORM',
      stage: 'toConfirm',
      eventEndAt: iso(now - 70 * MIN),
      dueAt: iso(now + 72 * MIN),
      pool: '10000.00',
      bettors: 6,
      feeRate: '5',
      disputeWindowMinutes: 120,
      options: homeDrawAway(L('切尔西', 'Chelsea', '切爾西'), L('纽卡斯尔', 'Newcastle', '紐卡斯爾'), ['6000.00', '1500.00', '2500.00'], [3, 1, 2], 'C'),
      sources: [
        { name: 'premierleague.com', value: '2 : 1', fetchedAt: iso(now - 40 * MIN), ok: true },
        { name: 'API-Football', value: '2 : 1（状态：AET）', fetchedAt: iso(now - 40 * MIN), ok: false },
      ],
      proposal: {
        by: 'A03',
        at: iso(now - 48 * MIN),
        optionNo: 'C1',
        score: '2 : 1',
        actualStartAt: iso(now - 3 * HOUR - 2 * MIN),
        evidence: ['https://www.premierleague.com/match/115873', 'https://www.bbc.com/sport/football/live'],
        screenshots: 2,
        note: 'API-Football 把比赛状态标成 AET（加时），实际英超没有加时，以官网 90 分钟赛果 2 : 1 为准。',
      },
      rulesExcerpt: L('以 90 分钟常规时间（含伤停补时）结束时的比分为准，不含加时和点球。首选英超官网，备用 API-Football；不一致转平台裁决。', 'Score after 90 minutes including stoppage time; no extra time or penalties. premierleague.com first, API-Football backup; disagreement goes to platform resolution.', '以 90 分鐘常規時間（含傷停補時）結束時的比分為準，不含加時和點球。首選英超官網，備用 API-Football；不一致轉平台裁決。'),
    },
    {
      publicNo: '105726659788805036',
      category: 'CRYPTO_1H',
      title: L('BTC 涨还是跌？04:00–05:00 UTC', 'BTC up or down? 04:00–05:00 UTC', 'BTC 漲還是跌？04:00–05:00 UTC'),
      mode: 'AUTO',
      stage: 'autoFailed',
      eventEndAt: iso(now - 2 * HOUR),
      dueAt: iso(now + 20 * MIN),
      pool: '3880.00',
      bettors: 141,
      feeRate: '5',
      disputeWindowMinutes: 0,
      options: [opt('B1', L('涨', 'Up', '漲'), '2010.00', 74, 74), opt('B2', L('跌', 'Down', '跌'), '1870.00', 67, 67)],
      sources: [
        { name: 'Binance BTC/USDT 1m', value: '67,500.10 → 67,500.10（相等，判涨）', fetchedAt: iso(now - 118 * MIN), ok: true },
        { name: 'OKX BTC-USDT 1m', value: '67,498.70 → 67,497.90（跌）', fetchedAt: iso(now - 118 * MIN), ok: false },
      ],
      rulesExcerpt: L('结束价 ≥ 开始价判"涨"。两个来源方向一致才自动结算；不一致时以首选（币安）页面数据为准人工判定。', '"Up" if end ≥ start. Settles automatically only when both sources agree; otherwise resolved manually using the primary source (Binance).', '結束價 ≥ 開始價判"漲"。兩個來源方向一致才自動結算；不一致時以首選（幣安）頁面數據為準人工判定。'),
    },
    {
      publicNo: '104005317427205041',
      category: 'MACRO',
      title: L('日本央行 10 月会议宣布加息吗？', 'Will the BoJ raise rates at its October meeting?', '日本央行 10 月會議宣佈加息嗎？'),
      mode: 'PLATFORM',
      stage: 'toPropose',
      eventEndAt: iso(now - 5 * HOUR),
      dueAt: iso(now + 19 * HOUR),
      pool: '2340.00',
      bettors: 88,
      feeRate: '5',
      disputeWindowMinutes: 1440,
      options: [opt('J1', L('是', 'Yes', '是'), '760.00', 31, 31), opt('J2', L('否', 'No', '否'), '1580.00', 57, 57)],
      rulesExcerpt: L('以日本央行官网公布的政策声明为准。', 'Based on the policy statement published on boj.or.jp.', '以日本央行官網公佈的政策聲明為準。'),
    },
    {
      publicNo: '104005317427205043',
      category: 'NBA',
      title: L('雄鹿 vs 76 人（胜负，含加时）', 'Bucks vs 76ers (winner, incl. OT)', '公鹿 vs 76 人（勝負，含加時）'),
      mode: 'AUTO',
      stage: 'overdue',
      eventEndAt: iso(now - 6 * HOUR),
      dueAt: iso(now - 25 * MIN),
      pool: '6120.00',
      bettors: 203,
      feeRate: '5',
      disputeWindowMinutes: 120,
      options: [opt('N1', L('雄鹿', 'Bucks', '公鹿'), '3300.00', 110, 110), opt('N2', L('76 人', '76ers', '76 人'), '2820.00', 93, 93)],
      sources: [
        { name: 'balldontlie', value: '比赛状态：Postponed', fetchedAt: iso(now - 5 * HOUR), ok: false },
        { name: 'API-NBA', value: '比赛状态：Not Started', fetchedAt: iso(now - 5 * HOUR), ok: false },
      ],
      rulesExcerpt: L('延期：原定开赛后 48 小时内完赛的按实际结果，否则作废、全额退回。', 'Postponed: the actual result counts if completed within 48 hours of the original tip-off; otherwise voided and refunded.', '延期：原定開賽後 48 小時內完賽的按實際結果，否則作廢、全額退回。'),
    },
  ];
}

let queue = cases();

export function resetAdminMarkets(): void {
  markets = rows();
  queue = cases();
  drafts.clear();
}

export function listResolveQueue(): Promise<{ items: ResolveCase[]; summary: ResolveSummary }> {
  return respond(
    () => {
      const summary: ResolveSummary = { toPropose: 0, toConfirm: 0, autoFailed: 0, overdue: 0 };
      queue.forEach((c) => (summary[c.stage] += 1));
      return { items: queue, summary };
    },
    { empty: () => ({ items: [], summary: { toPropose: 0, toConfirm: 0, autoFailed: 0, overdue: 0 } }) },
  );
}

/** 切尔西 vs 纽卡斯尔的持仓（需求第 3.5 节的例子：Alice 100、Bob 2,500、Carol 3,400；Dave 1,500；Eve 2,000、Frank 500） */
const POSITIONS: Record<string, string[]> = {
  C1: ['100', '2500', '3400'],
  C2: ['1500'],
  C3: ['2000', '500'],
};

/** 按某个结果的结算预览：逐个持仓 G 向下取整、F 四舍五入（到 0.01），尾差 = 输方奖池 − ΣG */
export function previewSettle(publicNo: string, optionNo: string): Promise<SettlePreview> {
  return respond(() => {
    const c = queue.find((x) => x.publicNo === publicNo);
    if (!c) throw new ApiError('errors.notFound');
    const win = c.options.find((o) => o.optionNo === optionNo)!;
    const total = toScaled(c.pool, 2)!;
    const pw = toScaled(win.pool, 2)!;
    const loser = total - pw;
    // 没有明细的市场按"每人押得一样多"拆成持仓（原型）
    const pos = (POSITIONS[optionNo] ?? Array.from({ length: win.positions }, () => fromScaled(pw / BigInt(win.positions), 2))).map((x) => toScaled(x, 2)!);
    const rate = BigInt(Math.round(Number(c.feeRate) * 100)); // 万分之几
    let g = 0n;
    let f = 0n;
    let pay = 0n;
    for (const b of pos) {
      const gi = pw === 0n ? 0n : (b * loser) / pw; // 分，向下取整
      const fi = (gi * rate * 2n + 10000n) / 20000n; // 四舍五入
      g += gi;
      f += fi;
      pay += b + gi - fi;
    }
    const dust = loser - g;
    const sum = pay + f + dust;
    return {
      optionNo,
      winnerPool: fromScaled(pw, 2),
      loserPool: fromScaled(loser, 2),
      winners: pos.length,
      payoutTotal: fromScaled(pay, 2),
      feeTotal: fromScaled(f, 2),
      dust: fromScaled(dust, 2),
      total: fromScaled(sum, 2),
      balanced: sum === total,
    };
  });
}

export function decideResolve(publicNo: string, action: 'confirm' | 'return' | 'void', optionNo: string | null, reason: string): Promise<void> {
  return mutate(() => {
    if (!reason.trim()) throw new ApiError('errors.reasonRequired');
    const c = queue.find((x) => x.publicNo === publicNo);
    if (!c) throw new ApiError('errors.notFound');
    if (action === 'confirm' && !optionNo) throw new ApiError('errors.badRequest');
    queue = queue.filter((x) => x.publicNo !== publicNo);
    const row = markets.find((r) => r.publicNo === publicNo);
    if (row) row.status = action === 'confirm' ? (c.mode === 'AUTO' && c.stage === 'autoFailed' ? 'settled' : 'publicizing') : action === 'void' ? 'voided' : row.status;
  });
}
