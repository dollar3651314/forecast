/**
 * 市场、下注的模拟层（M1 原型）。数据形状和后端 S3、S4 的接口一致（api/types.ts），赔率、预计回报用和后端同一套规则的 lib/pool.ts 现算，
 * 所以原型上看到的概率、倍数、"押 100 → 赢了约回 162.29"和真实接口逐分一致。
 *
 * 场景（规则 C50：数据要像真的；C32：要有空状态）：
 * - 英超 曼城 vs 利物浦（胜平负）：需求第 3.5 节的例子，奖池 6,000 / 1,500 / 2,500，本人押了主胜 100（2 笔）；
 * - BTC 1 小时涨跌：下一个整点开始的窗口，窗口开始前 30 秒截止（倒计时）；
 * - ETH 15 分钟涨跌：刚开放、还没有人押（奖池为 0，概率和赔率显示"—"）；
 * - NBA 湖人 vs 凯尔特人（总分大小 224.5）、欧冠 皇马 vs 拜仁（大小 2.5 球）、美联储 12 月议息（时事）；
 * - 勇士 vs 掘金：已截止、等待结果（最终赔率）；
 * - 布伦特福德 vs 富勒姆：两个比分源不一致转平台裁决，公示中，本人押了主胜，可以"提交争议"；
 * - 美国 9 月 CPI：平台裁决、已结算（结果、依据、裁决人编号 A01），本人押中；
 * - BTC 1 小时涨跌（上一个窗口）：自动裁决、已结算（开始价、结束价、两个来源），本人没押中；
 * - 赫罗纳 vs 塞维利亚：比赛延期超过 48 小时，已作废、全额退回。
 * "空数据"场景下列表和我的下注都为空。
 */
import { ApiError } from '../api/errors';
import type {
  BetRequest,
  BetResult,
  AutoResolutionSource,
  DisputeReceipt,
  DisputeRequest,
  I18nText,
  MarketCard,
  MarketCategory,
  MarketDetail,
  MarketPool,
  MarketQuery,
  MarketResolution,
  MarketStatus,
  MyBet,
  MyBets,
  MyBetTab,
} from '../api/types';
import { expectedReturn, multiplier, payout, percent, rateFromPercent } from '../lib/pool';
import { add, dec, sub, sum, toFixed } from '../lib/decimal';
import { emit } from './bus';
import { db } from './db';
import { mutate, respond } from './delay';
import { snowflakeAt } from './ids';

/** 三种语言的文字：简体、英文、繁体 */
const L = (zhCN: string, en: string, zhTW: string): I18nText => ({ zhCN, en, zhTW });

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

/** 固定的日期（需求例子里的时间）：已经过去时改成相对现在 */
function at(iso: string, fallbackFromNow: number, now: number): number {
  const t = Date.parse(iso);
  return t > now ? t : now + fallbackFromNow;
}
const iso = (ms: number): string => new Date(ms).toISOString();
const ceilTo = (ms: number, step: number): number => Math.ceil(ms / step) * step;
const utcHm = (ms: number): string => iso(ms).slice(11, 16);

interface SeedOption {
  label: I18nText;
  win: I18nText;
  /** 选项奖池 */
  pool: string;
  bettors: number;
}

/** 本人的一笔下注（下注时的预计赔率是快照，只做展示） */
interface SeedBet {
  optionSeq: number;
  amount: string;
  minutesAgo: number;
  multAtBet: string;
}

interface StoredMarket {
  publicNo: string;
  category: MarketCategory;
  league: I18nText;
  title: I18nText;
  status: MarketStatus;
  openAt: number;
  closeAt: number;
  eventStartAt: number;
  eventEndAt: number;
  feeRate: string;
  resolutionMode: 'AUTO' | 'PLATFORM';
  sourcePrimary: string;
  sourceBackup: string;
  disputeWindowMinutes?: number;
  rules: I18nText;
  options: Array<SeedOption & { optionNo: string; seq: number }>;
  clarifications: Array<{ text: I18nText; time: number }>;
  feeChanges: Array<{ oldRate: string; newRate: string; time: number }>;
  /** 结果（公示中是公示的结果）是第几个选项 */
  winSeq?: number;
  /** 裁决：字段和后端 S6 / S7 的 resolution 一致（见 resolutionOf） */
  resolution?: StoredResolution;
  voidReason?: string;
  bets: Array<{ betNo: string; optionSeq: number; amount: string; receivedAt: number; multAtBet: string; feeRateAtBet: string }>;
  createdAt: number;
}

/** 自动裁决（S6 ResolutionViews）：结果由 winSeq 定（1 = 涨） */
interface StoredAuto {
  mode: 'auto';
  resolvedAt: number;
  symbol: string;
  windowStart: number;
  windowEnd: number;
  sources: AutoResolutionSource[];
}

/** 平台裁决（S7 PlatformViews.clientView） */
interface StoredPlatform {
  mode: 'platform';
  /** 员工编号（数字），不公开姓名 */
  resolvedBy: string;
  resolvedAt: number;
  basis: string;
  explanation: string;
  evidenceUrls: string[];
  disputeDeadlineAt?: number;
  /** 本人提交的争议 */
  myDispute?: DisputeReceipt;
}

type StoredResolution = StoredAuto | StoredPlatform;

const CATEGORY_CAP: Record<MarketCategory, string> = { FOOTBALL: '10000.00', NBA: '10000.00', CRYPTO_15M: '2000.00', CRYPTO_1H: '2000.00', MACRO: '10000.00' };

// ---------- 规则全文（第 4.1 节模板） ----------
const footballRules = (home: I18nText, away: I18nText, kickoff: number, close: number): I18nText => ({
  zhCN: `问题：${home.zhCN} 对 ${away.zhCN}，90 分钟常规时间（含伤停补时）的赛果。不含加时赛和点球大战。\n开赛 ${iso(kickoff).slice(0, 16).replace('T', ' ')} UTC；截止下注 ${iso(close).slice(0, 16).replace('T', ' ')} UTC（开赛前 5 分钟）。\n裁决来源：首选英超官网 premierleague.com 的赛果，备用 API-Football；两个来源比分一致时自动结算，不一致转平台裁决。\n延期：原定开赛后 48 小时内完赛的按实际结果；超过 48 小时作废、全额退回。腰斩：联赛官方宣布比分有效的按官方比分，否则作废。\n数据修正：以完赛后 1 小时的官方比分为准，之后的修正不算。\n抽成：盈利部分的 5%。`,
  en: `Question: result of ${home.en} vs ${away.en} after 90 minutes of regular time (including stoppage time). Extra time and penalties are not included.\nKick-off ${iso(kickoff).slice(0, 16).replace('T', ' ')} UTC; betting closes ${iso(close).slice(0, 16).replace('T', ' ')} UTC (5 minutes before kick-off).\nSources: premierleague.com first, API-Football as backup. Settled automatically when both sources agree; otherwise resolved by the platform.\nPostponed: if the match finishes within 48 hours of the original kick-off, the actual result counts; otherwise the market is voided and all bets are refunded. Abandoned: the official score counts if the league declares it valid; otherwise voided.\nCorrections: the official score 1 hour after full time is final.\nFee: 5% of winnings.`,
  zhTW: `問題：${home.zhTW} 對 ${away.zhTW}，90 分鐘常規時間（含傷停補時）的賽果。不含加時賽和點球大戰。\n開賽 ${iso(kickoff).slice(0, 16).replace('T', ' ')} UTC；截止下注 ${iso(close).slice(0, 16).replace('T', ' ')} UTC（開賽前 5 分鐘）。\n裁決來源：首選英超官網 premierleague.com 的賽果，備用 API-Football；兩個來源比分一致時自動結算，不一致轉平台裁決。\n延期：原定開賽後 48 小時內完賽的按實際結果；超過 48 小時作廢、全額退回。腰斬：聯賽官方宣佈比分有效的按官方比分，否則作廢。\n資料修正：以完賽後 1 小時的官方比分為準，之後的修正不算。\n抽成：盈利部分的 5%。`,
});

const cryptoRules = (coin: string, start: number, end: number, close: number): I18nText => ({
  zhCN: `问题：${coin}/USDT 在 ${utcHm(start)}–${utcHm(end)} UTC 这一小时里是涨还是跌。\n以币安现货 ${coin}/USDT 的 1 分钟 K 线为准：窗口内第一根 K 线的开盘价为开始价，最后一根的收盘价为结束价；结束价 ≥ 开始价判"涨"，否则判"跌"。\n截止下注：${utcHm(close)}:30 UTC（窗口开始前 30 秒）。窗口结束后 1 分钟取币安和 OKX 的 K 线，两个来源方向一致时自动结算。\n抽成：盈利部分的 5%。`,
  en: `Question: will ${coin}/USDT go up or down during ${utcHm(start)}–${utcHm(end)} UTC?\nBased on Binance spot ${coin}/USDT 1-minute candles: the open of the first candle in the window is the start price and the close of the last candle is the end price. End ≥ start resolves "Up", otherwise "Down".\nBetting closes ${utcHm(close)}:30 UTC (30 seconds before the window starts). One minute after the window ends, Binance and OKX candles are fetched; the market settles automatically when both agree.\nFee: 5% of winnings.`,
  zhTW: `問題：${coin}/USDT 在 ${utcHm(start)}–${utcHm(end)} UTC 這一小時裡是漲還是跌。\n以幣安現貨 ${coin}/USDT 的 1 分鐘 K 線為準：時段內第一根 K 線的開盤價為開始價，最後一根的收盤價為結束價；結束價 ≥ 開始價判"漲"，否則判"跌"。\n截止下注：${utcHm(close)}:30 UTC（時段開始前 30 秒）。時段結束後 1 分鐘取幣安和 OKX 的 K 線，兩個來源方向一致時自動結算。\n抽成：盈利部分的 5%。`,
});

let seq = 0;
/** 编号用固定的基准时间生成：刷新页面后同一个市场的编号不变（地址栏里的 /markets/编号 仍然有效） */
const ID_BASE = Date.parse('2026-10-20T00:00:00Z');
const no = (msAgo: number): string => snowflakeAt(ID_BASE - msAgo, 900 + seq++);

function option(label: I18nText, win: I18nText, pool: string, bettors: number): SeedOption {
  return { label, win, pool, bettors };
}

function market(m: Omit<StoredMarket, 'publicNo' | 'options' | 'bets' | 'clarifications' | 'feeChanges' | 'createdAt'> & {
  options: SeedOption[];
  mine?: SeedBet[];
  clarifications?: StoredMarket['clarifications'];
  feeChanges?: StoredMarket['feeChanges'];
  createdAgo?: number;
}): StoredMarket {
  const now = Date.now();
  const options = m.options.map((o, i) => ({ ...o, optionNo: no(DAY * 3 + i), seq: i + 1 }));
  const bets = (m.mine ?? []).map((b) => ({
    betNo: no(b.minutesAgo * MIN),
    optionSeq: b.optionSeq,
    amount: b.amount,
    receivedAt: now - b.minutesAgo * MIN,
    multAtBet: b.multAtBet,
    feeRateAtBet: m.feeRate,
  }));
  const { mine: _mine, createdAgo, ...rest } = m;
  void _mine;
  return { ...rest, publicNo: no(createdAgo ?? DAY), options, bets, clarifications: m.clarifications ?? [], feeChanges: m.feeChanges ?? [], createdAt: now - (createdAgo ?? DAY) };
}

function seedMarkets(): StoredMarket[] {
  const now = Date.now();
  seq = 0;
  // 足球：需求第 3.5 节的例子
  const epl = at('2026-11-01T15:00:00Z', 7 * DAY, now);
  const mci = L('曼城', 'Man City', '曼城');
  const liv = L('利物浦', 'Liverpool', '利物浦');
  // BTC 1 小时：下一个整点开始（离现在至少 20 分钟，免得截图时已经截止）
  const btcStart = ceilTo(now + 20 * MIN, HOUR);
  // ETH 15 分钟：下下个刻钟开始
  const ethStart = ceilTo(now + 20 * MIN, 15 * MIN);
  const nba = at('2026-10-27T23:30:00Z', 2 * DAY, now);
  const ucl = at('2026-11-05T20:00:00Z', 11 * DAY, now);
  const fomc = at('2026-12-09T19:00:00Z', 45 * DAY, now);
  const gsw = now - 70 * MIN;
  const bre = now - 4 * HOUR;
  const prevBtcStart = ceilTo(now, HOUR) - 3 * HOUR;
  const gir = now - 3 * DAY;

  const list: StoredMarket[] = [
    market({
      category: 'FOOTBALL',
      league: L('英超 · 第 10 轮', 'Premier League · Matchweek 10', '英超 · 第 10 輪'),
      title: L('曼城 vs 利物浦（90 分钟胜平负）', 'Man City vs Liverpool (90-min result)', '曼城 vs 利物浦（90 分鐘勝平負）'),
      status: 'listed',
      openAt: now - 2 * DAY,
      closeAt: epl - 5 * MIN,
      eventStartAt: epl,
      eventEndAt: epl + 2 * HOUR,
      feeRate: '5',
      resolutionMode: 'AUTO',
      sourcePrimary: 'premierleague.com',
      sourceBackup: 'API-Football',
      rules: footballRules(mci, liv, epl, epl - 5 * MIN),
      options: [
        option(L('主胜 曼城', 'Man City', '主勝 曼城'), L('90 分钟结束时曼城进球多', 'Man City scores more after 90 minutes', '90 分鐘結束時曼城進球多'), '6000.00', 41),
        option(L('平局', 'Draw', '平局'), L('90 分钟结束时比分相同', 'Level after 90 minutes', '90 分鐘結束時比分相同'), '1500.00', 9),
        option(L('客胜 利物浦', 'Liverpool', '客勝 利物浦'), L('90 分钟结束时利物浦进球多', 'Liverpool scores more after 90 minutes', '90 分鐘結束時利物浦進球多'), '2500.00', 17),
      ],
      mine: [
        { optionSeq: 1, amount: '60.00', minutesAgo: 60 * 30, multAtBet: '1.66' },
        { optionSeq: 1, amount: '40.00', minutesAgo: 60 * 5, multAtBet: '1.63' },
      ],
      clarifications: [
        {
          text: L('"90 分钟"包括上下半场的伤停补时，以第四官员公布、裁判实际执行的补时为准。', '"90 minutes" includes stoppage time in both halves, as actually played.', '"90 分鐘"包括上下半場的傷停補時，以第四官員公佈、裁判實際執行的補時為準。'),
          time: now - 20 * HOUR,
        },
      ],
      createdAgo: 2 * DAY,
    }),
    market({
      category: 'CRYPTO_1H',
      league: L('BTC · 1 小时', 'BTC · 1 hour', 'BTC · 1 小時'),
      title: L(`BTC 涨还是跌？${utcHm(btcStart)}–${utcHm(btcStart + HOUR)} UTC`, `BTC up or down? ${utcHm(btcStart)}–${utcHm(btcStart + HOUR)} UTC`, `BTC 漲還是跌？${utcHm(btcStart)}–${utcHm(btcStart + HOUR)} UTC`),
      status: 'listed',
      openAt: btcStart - 3 * HOUR,
      closeAt: btcStart - 30_000,
      eventStartAt: btcStart,
      eventEndAt: btcStart + HOUR,
      feeRate: '5',
      resolutionMode: 'AUTO',
      sourcePrimary: 'Binance BTC/USDT 1m',
      sourceBackup: 'OKX BTC-USDT 1m',
      rules: cryptoRules('BTC', btcStart, btcStart + HOUR, btcStart - 30_000),
      options: [
        option(L('涨', 'Up', '漲'), L('结束价 ≥ 开始价', 'End price ≥ start price', '結束價 ≥ 開始價'), '1984.00', 74),
        option(L('跌', 'Down', '跌'), L('结束价 < 开始价', 'End price < start price', '結束價 < 開始價'), '1436.00', 54),
      ],
      createdAgo: 3 * HOUR,
    }),
    market({
      category: 'NBA',
      league: L('NBA · 常规赛', 'NBA · Regular season', 'NBA · 常規賽'),
      title: L('湖人 vs 凯尔特人：总分大于 224.5？', 'Lakers vs Celtics: total points over 224.5?', '湖人 vs 凱爾特人：總分大於 224.5？'),
      status: 'listed',
      openAt: now - DAY,
      closeAt: nba - 5 * MIN,
      eventStartAt: nba,
      eventEndAt: nba + 3 * HOUR,
      feeRate: '5',
      resolutionMode: 'AUTO',
      sourcePrimary: 'balldontlie',
      sourceBackup: 'API-NBA',
      rules: L(
        '问题：湖人对凯尔特人全场总得分（含加时）是否大于 224.5 分。分界线是 .5，不会出现正好相等。\n裁决来源：首选 balldontlie，备用 API-NBA；完赛 30 分钟后两个来源比分一致时自动结算。\n延期：原定开赛后 48 小时内完赛的按实际结果，否则作废、全额退回。\n抽成：盈利部分的 5%。',
        'Question: will the combined score of Lakers vs Celtics (including overtime) be over 224.5? The line ends in .5, so there is no push.\nSources: balldontlie first, API-NBA as backup; settles automatically 30 minutes after the final when both agree.\nPostponed: the actual result counts if the game finishes within 48 hours of the original tip-off; otherwise voided and refunded.\nFee: 5% of winnings.',
        '問題：湖人對凱爾特人全場總得分（含加時）是否大於 224.5 分。分界線是 .5，不會出現正好相等。\n裁決來源：首選 balldontlie，備用 API-NBA；完賽 30 分鐘後兩個來源比分一致時自動結算。\n延期：原定開賽後 48 小時內完賽的按實際結果，否則作廢、全額退回。\n抽成：盈利部分的 5%。',
      ),
      options: [
        option(L('大 224.5', 'Over 224.5', '大 224.5'), L('总分 ≥ 225', 'Total points ≥ 225', '總分 ≥ 225'), '1270.00', 46),
        option(L('小 224.5', 'Under 224.5', '小 224.5'), L('总分 ≤ 224', 'Total points ≤ 224', '總分 ≤ 224'), '945.00', 31),
      ],
      mine: [{ optionSeq: 1, amount: '50.00', minutesAgo: 60 * 9, multAtBet: '1.71' }],
      createdAgo: DAY,
    }),
    market({
      category: 'CRYPTO_15M',
      league: L('ETH · 15 分钟', 'ETH · 15 min', 'ETH · 15 分鐘'),
      title: L(`ETH 涨还是跌？${utcHm(ethStart)}–${utcHm(ethStart + 15 * MIN)} UTC`, `ETH up or down? ${utcHm(ethStart)}–${utcHm(ethStart + 15 * MIN)} UTC`, `ETH 漲還是跌？${utcHm(ethStart)}–${utcHm(ethStart + 15 * MIN)} UTC`),
      status: 'listed',
      openAt: ethStart - HOUR,
      closeAt: ethStart - 30_000,
      eventStartAt: ethStart,
      eventEndAt: ethStart + 15 * MIN,
      feeRate: '5',
      resolutionMode: 'AUTO',
      sourcePrimary: 'Binance ETH/USDT 1m',
      sourceBackup: 'OKX ETH-USDT 1m',
      rules: cryptoRules('ETH', ethStart, ethStart + 15 * MIN, ethStart - 30_000),
      options: [
        option(L('涨', 'Up', '漲'), L('结束价 ≥ 开始价', 'End price ≥ start price', '結束價 ≥ 開始價'), '0.00', 0),
        option(L('跌', 'Down', '跌'), L('结束价 < 开始价', 'End price < start price', '結束價 < 開始價'), '0.00', 0),
      ],
      createdAgo: 10 * MIN,
    }),
    market({
      category: 'MACRO',
      league: L('时事 · 美联储', 'Macro · Fed', '時事 · 美聯儲'),
      title: L('美联储 12 月议息会议宣布降息吗？', 'Will the Fed cut rates at its December meeting?', '美聯儲 12 月議息會議宣佈降息嗎？'),
      status: 'listed',
      openAt: now - 6 * DAY,
      closeAt: fomc - HOUR,
      eventStartAt: fomc,
      eventEndAt: fomc + HOUR,
      feeRate: '5',
      resolutionMode: 'PLATFORM',
      sourcePrimary: 'federalreserve.gov',
      sourceBackup: 'Reuters',
      disputeWindowMinutes: 1440,
      rules: L(
        '问题：美联储 12 月议息会议结束时公布的联邦基金利率目标区间，上限是否低于会议前。\n以 federalreserve.gov 发布的会议声明为准，备用路透社报道。截止下注：声明发布前 1 小时。\n平台裁决：裁决员提议、超管确认后公示 24 小时，期间有下注的客户可以提交争议。\n会议改期到 12 月 31 日以后的，作废、全额退回。\n抽成：盈利部分的 5%。',
        'Question: will the upper bound of the federal funds target range announced at the end of the Fed’s December meeting be lower than before the meeting?\nSource: the FOMC statement on federalreserve.gov, Reuters as backup. Betting closes 1 hour before the statement.\nPlatform resolution: proposed by a resolver, confirmed by an administrator, then published for 24 hours during which anyone who bet can dispute.\nIf the meeting is moved past December 31, the market is voided and refunded.\nFee: 5% of winnings.',
        '問題：美聯儲 12 月議息會議結束時公佈的聯邦基金利率目標區間，上限是否低於會議前。\n以 federalreserve.gov 釋出的會議宣告為準，備用路透社報道。截止下注：宣告發布前 1 小時。\n平台裁決：裁決員提議、超管確認後公示 24 小時，期間有下注的客戶可以提交爭議。\n會議改期到 12 月 31 日以後的，作廢、全額退回。\n抽成：盈利部分的 5%。',
      ),
      options: [
        option(L('是', 'Yes', '是'), L('利率上限低于会议前', 'Upper bound is lower than before', '利率上限低於會議前'), '1360.00', 52),
        option(L('否', 'No', '否'), L('利率上限不变或提高', 'Upper bound unchanged or higher', '利率上限不變或提高'), '500.00', 23),
      ],
      mine: [{ optionSeq: 1, amount: '120.00', minutesAgo: 60 * 50, multAtBet: '1.36' }],
      feeChanges: [{ oldRate: '6', newRate: '5', time: now - 5 * DAY }],
      createdAgo: 6 * DAY,
    }),
    market({
      category: 'FOOTBALL',
      league: L('欧冠 · 联赛阶段', 'Champions League · League phase', '歐冠 · 聯賽階段'),
      title: L('皇马 vs 拜仁：总进球大于 2.5？', 'Real Madrid vs Bayern: over 2.5 goals?', '皇馬 vs 拜仁：總進球大於 2.5？'),
      status: 'listed',
      openAt: now - 3 * DAY,
      closeAt: ucl - 5 * MIN,
      eventStartAt: ucl,
      eventEndAt: ucl + 2 * HOUR,
      feeRate: '5',
      resolutionMode: 'AUTO',
      sourcePrimary: 'uefa.com',
      sourceBackup: 'API-Football',
      rules: footballRules(L('皇马', 'Real Madrid', '皇馬'), L('拜仁', 'Bayern', '拜仁'), ucl, ucl - 5 * MIN),
      options: [
        option(L('大 2.5 球', 'Over 2.5', '大 2.5 球'), L('90 分钟总进球 ≥ 3', '3 or more goals in 90 minutes', '90 分鐘總進球 ≥ 3'), '2560.00', 88),
        option(L('小 2.5 球', 'Under 2.5', '小 2.5 球'), L('90 分钟总进球 ≤ 2', '2 or fewer goals in 90 minutes', '90 分鐘總進球 ≤ 2'), '1920.00', 61),
      ],
      createdAgo: 3 * DAY,
    }),
    market({
      category: 'NBA',
      league: L('NBA · 常规赛', 'NBA · Regular season', 'NBA · 常規賽'),
      title: L('勇士 vs 掘金（胜负，含加时）', 'Warriors vs Nuggets (winner, incl. OT)', '勇士 vs 掘金（勝負，含加時）'),
      status: 'closed',
      openAt: now - 3 * DAY,
      closeAt: gsw - 5 * MIN,
      eventStartAt: gsw,
      eventEndAt: gsw + 3 * HOUR,
      feeRate: '5',
      resolutionMode: 'AUTO',
      sourcePrimary: 'balldontlie',
      sourceBackup: 'API-NBA',
      rules: L('问题：勇士对掘金，含加时的最终胜方。NBA 没有平局。\n裁决来源：首选 balldontlie，备用 API-NBA。\n抽成：盈利部分的 5%。', 'Question: winner of Warriors vs Nuggets including overtime. There are no draws in the NBA.\nSources: balldontlie first, API-NBA as backup.\nFee: 5% of winnings.', '問題：勇士對掘金，含加時的最終勝方。NBA 沒有平局。\n裁決來源：首選 balldontlie，備用 API-NBA。\n抽成：盈利部分的 5%。'),
      options: [
        option(L('勇士', 'Warriors', '勇士'), L('勇士获胜', 'Warriors win', '勇士獲勝'), '3120.00', 97),
        option(L('掘金', 'Nuggets', '掘金'), L('掘金获胜', 'Nuggets win', '掘金獲勝'), '2480.00', 80),
      ],
      mine: [{ optionSeq: 1, amount: '200.00', minutesAgo: 60 * 26, multAtBet: '1.77' }],
      createdAgo: 3 * DAY,
    }),
    market({
      category: 'FOOTBALL',
      league: L('英超 · 第 9 轮', 'Premier League · Matchweek 9', '英超 · 第 9 輪'),
      title: L('布伦特福德 vs 富勒姆（90 分钟胜平负）', 'Brentford vs Fulham (90-min result)', '布倫特福德 vs 富勒姆（90 分鐘勝平負）'),
      status: 'publicizing',
      openAt: now - 5 * DAY,
      closeAt: bre - 5 * MIN,
      eventStartAt: bre,
      eventEndAt: bre + 2 * HOUR,
      feeRate: '5',
      resolutionMode: 'AUTO',
      sourcePrimary: 'premierleague.com',
      sourceBackup: 'API-Football',
      disputeWindowMinutes: 120,
      rules: footballRules(L('布伦特福德', 'Brentford', '布倫特福德'), L('富勒姆', 'Fulham', '富勒姆'), bre, bre - 5 * MIN),
      options: [
        option(L('主胜 布伦特福德', 'Brentford', '主勝 布倫特福德'), L('90 分钟结束时布伦特福德进球多', 'Brentford scores more after 90 minutes', '90 分鐘結束時布倫特福德進球多'), '2210.00', 64),
        option(L('平局', 'Draw', '平局'), L('90 分钟结束时比分相同', 'Level after 90 minutes', '90 分鐘結束時比分相同'), '980.00', 27),
        option(L('客胜 富勒姆', 'Fulham', '客勝 富勒姆'), L('90 分钟结束时富勒姆进球多', 'Fulham scores more after 90 minutes', '90 分鐘結束時富勒姆進球多'), '1640.00', 45),
      ],
      mine: [{ optionSeq: 1, amount: '80.00', minutesAgo: 60 * 30, multAtBet: '2.04' }],
      winSeq: 2,
      resolution: {
        mode: 'platform',
        resolvedBy: '1027',
        resolvedAt: now - 28 * MIN,
        basis: 'NORMAL',
        explanation: '90 分钟比分 1 : 1。API-Football 一度记为 2 : 1（第 88 分钟进球后被 VAR 取消），两个来源不一致，转平台裁决；以英超官网赛果和比赛报告为准。',
        evidenceUrls: ['https://www.premierleague.com/results', 'https://www.api-football.com'],
        disputeDeadlineAt: now + 92 * MIN,
      },
      createdAgo: 5 * DAY,
    }),
    market({
      category: 'MACRO',
      league: L('时事 · 美国通胀', 'Macro · US inflation', '時事 · 美國通脹'),
      title: L('美国 9 月 CPI 同比 ≥ 3.0%？', 'US September CPI year-over-year ≥ 3.0%?', '美國 9 月 CPI 同比 ≥ 3.0%？'),
      status: 'settled',
      openAt: now - 20 * DAY,
      closeAt: now - 11 * DAY,
      eventStartAt: now - 11 * DAY + HOUR,
      eventEndAt: now - 11 * DAY + 2 * HOUR,
      feeRate: '5',
      resolutionMode: 'PLATFORM',
      sourcePrimary: 'bls.gov',
      sourceBackup: 'Reuters',
      disputeWindowMinutes: 1440,
      rules: L('问题：美国劳工统计局公布的 9 月 CPI（未季调）同比涨幅是否 ≥ 3.0%。以首次公布值为准，之后的修正不算。\n平台裁决，公示 24 小时。\n抽成：盈利部分的 5%。', 'Question: will the BLS September CPI (NSA) year-over-year change be 3.0% or higher? The first release counts; later revisions do not.\nPlatform resolution with a 24-hour dispute window.\nFee: 5% of winnings.', '問題：美國勞工統計局公佈的 9 月 CPI（未季調）同比漲幅是否 ≥ 3.0%。以首次公佈值為準，之後的修正不算。\n平台裁決，公示 24 小時。\n抽成：盈利部分的 5%。'),
      options: [
        option(L('是', 'Yes', '是'), L('同比 ≥ 3.0%', 'YoY ≥ 3.0%', '同比 ≥ 3.0%'), '3150.00', 96),
        option(L('否', 'No', '否'), L('同比 < 3.0%', 'YoY < 3.0%', '同比 < 3.0%'), '4850.00', 131),
      ],
      mine: [{ optionSeq: 2, amount: '200.00', minutesAgo: 60 * 24 * 13, multAtBet: '1.58' }],
      winSeq: 2,
      resolution: {
        mode: 'platform',
        resolvedBy: '1027',
        resolvedAt: now - 10 * DAY,
        basis: 'NORMAL',
        explanation: '美国劳工统计局 CPI 新闻稿：9 月 CPI 同比 2.9%，低于 3.0%。公示 24 小时内没有争议，按"否"结算。',
        evidenceUrls: ['https://www.bls.gov/cpi/'],
        disputeDeadlineAt: now - 9 * DAY,
      },
      createdAgo: 20 * DAY,
    }),
    market({
      category: 'CRYPTO_1H',
      league: L('BTC · 1 小时', 'BTC · 1 hour', 'BTC · 1 小時'),
      title: L(`BTC 涨还是跌？${utcHm(prevBtcStart)}–${utcHm(prevBtcStart + HOUR)} UTC`, `BTC up or down? ${utcHm(prevBtcStart)}–${utcHm(prevBtcStart + HOUR)} UTC`, `BTC 漲還是跌？${utcHm(prevBtcStart)}–${utcHm(prevBtcStart + HOUR)} UTC`),
      status: 'settled',
      openAt: prevBtcStart - 3 * HOUR,
      closeAt: prevBtcStart - 30_000,
      eventStartAt: prevBtcStart,
      eventEndAt: prevBtcStart + HOUR,
      feeRate: '5',
      resolutionMode: 'AUTO',
      sourcePrimary: 'Binance BTC/USDT 1m',
      sourceBackup: 'OKX BTC-USDT 1m',
      rules: cryptoRules('BTC', prevBtcStart, prevBtcStart + HOUR, prevBtcStart - 30_000),
      options: [
        option(L('涨', 'Up', '漲'), L('结束价 ≥ 开始价', 'End price ≥ start price', '結束價 ≥ 開始價'), '2650.00', 92),
        option(L('跌', 'Down', '跌'), L('结束价 < 开始价', 'End price < start price', '結束價 < 開始價'), '2210.00', 77),
      ],
      mine: [{ optionSeq: 2, amount: '30.00', minutesAgo: 60 * 3 + 40, multAtBet: '2.08' }],
      winSeq: 1,
      resolution: {
        mode: 'auto',
        resolvedAt: prevBtcStart + HOUR + 70_000,
        symbol: 'BTCUSDT',
        windowStart: prevBtcStart,
        windowEnd: prevBtcStart + HOUR,
        sources: [
          { source: 'BINANCE', label: 'Binance BTC/USDT', startPrice: '67412.50000000', endPrice: '67630.10000000', direction: 'up', sha256: '3f1c9a0e5b7d2c4a8e6f0b1d3c5a7e9f2b4d6c8a0e1f3b5d7c9a2e4f6b8d0c1a' },
          { source: 'OKX', label: 'OKX BTC-USDT', startPrice: '67409.8', endPrice: '67627.4', direction: 'up', sha256: '9e2b4d6f8a0c1e3b5d7f9a2c4e6b8d0f1a3c5e7b9d2f4a6c8e0b1d3f5a7c9e2b' },
        ],
      },
      createdAgo: 6 * HOUR,
    }),
    market({
      category: 'FOOTBALL',
      league: L('西甲 · 第 8 轮', 'LaLiga · Matchday 8', '西甲 · 第 8 輪'),
      title: L('赫罗纳 vs 塞维利亚（90 分钟胜平负）', 'Girona vs Sevilla (90-min result)', '赫羅納 vs 塞維利亞（90 分鐘勝平負）'),
      status: 'voided',
      openAt: now - 8 * DAY,
      closeAt: gir - 5 * MIN,
      eventStartAt: gir,
      eventEndAt: gir + 2 * HOUR,
      feeRate: '5',
      resolutionMode: 'AUTO',
      sourcePrimary: 'laliga.com',
      sourceBackup: 'API-Football',
      rules: footballRules(L('赫罗纳', 'Girona', '赫羅納'), L('塞维利亚', 'Sevilla', '塞維利亞'), gir, gir - 5 * MIN),
      options: [
        option(L('主胜 赫罗纳', 'Girona', '主勝 赫羅納'), L('90 分钟结束时赫罗纳进球多', 'Girona scores more after 90 minutes', '90 分鐘結束時赫羅納進球多'), '1150.00', 38),
        option(L('平局', 'Draw', '平局'), L('90 分钟结束时比分相同', 'Level after 90 minutes', '90 分鐘結束時比分相同'), '620.00', 19),
        option(L('客胜 塞维利亚', 'Sevilla', '客勝 塞維利亞'), L('90 分钟结束时塞维利亚进球多', 'Sevilla scores more after 90 minutes', '90 分鐘結束時塞維利亞進球多'), '890.00', 30),
      ],
      mine: [{ optionSeq: 3, amount: '50.00', minutesAgo: 60 * 24 * 4, multAtBet: '2.84' }],
      voidReason: 'POSTPONED_48H',
      createdAgo: 8 * DAY,
    }),
  ];
  return list;
}

let markets: StoredMarket[] = seedMarkets();
const disputes = new Map<string, DisputeRequest>();
const winNo = (m: StoredMarket): string | undefined => (m.winSeq ? m.options[m.winSeq - 1]?.optionNo : undefined);

/** 原型：重置（和 db 的重置一起调用） */
export function resetMarkets(): void {
  markets = seedMarkets();
  disputes.clear();
}

// ---------- 视图 ----------
function poolOf(m: StoredMarket, withMine: boolean): MarketPool {
  const total = toFixed(sum(m.options.map((o) => dec(o.pool))), 2);
  const rate = rateFromPercent(m.feeRate);
  const options = m.options.map((o) => ({
    optionNo: o.optionNo,
    seq: o.seq,
    amount: o.pool,
    bettors: o.bettors,
    pct: percent(o.pool, total, 1),
    pctList: percent(o.pool, total, 0),
    mult: multiplier(o.pool, total, rate),
  }));
  const pool: MarketPool = {
    total,
    bettors: m.options.reduce((s, o) => s + o.bettors, 0),
    frozen: m.status !== 'listed' && m.status !== 'paused',
    feeRate: m.feeRate,
    options,
  };
  if (withMine) {
    const active = m.status === 'voided' || m.status === 'cancelled' ? [] : m.bets;
    pool.mine = m.options
      .map((o) => {
        const bs = active.filter((b) => b.optionSeq === o.seq);
        if (!bs.length) return null;
        const amount = toFixed(sum(bs.map((b) => dec(b.amount))), 2);
        return { optionNo: o.optionNo, seq: o.seq, amount, betCount: bs.length, ifWin: payout(amount, o.pool, total, rate).payout };
      })
      .filter((x): x is NonNullable<typeof x> => !!x);
    const used = toFixed(sum(active.map((b) => dec(b.amount))), 2);
    const cap = CATEGORY_CAP[m.category];
    const left = sub(dec(cap), dec(used));
    pool.limits = { min: '1.00', max: '5000.00', userCap: cap, used, remaining: toFixed(left < 0n ? 0n : left, 2) };
  }
  return pool;
}

function cardOf(m: StoredMarket): MarketCard {
  return {
    publicNo: m.publicNo,
    category: m.category,
    title: m.title,
    status: m.status,
    openAt: iso(m.openAt),
    closeAt: iso(m.closeAt),
    eventStartAt: iso(m.eventStartAt),
    eventEndAt: iso(m.eventEndAt),
    feeRate: m.feeRate,
    options: m.options.map((o) => ({ optionNo: o.optionNo, seq: o.seq, label: o.label })),
    pool: poolOf(m, false),
    league: m.league,
    winningOptionNo: m.status === 'settled' ? winNo(m) : undefined,
  };
}

/** 详情的 resolution：结构和后端一致（S6 自动裁决只在已结算时有；S7 平台裁决有确认记录就有） */
function resolutionOf(m: StoredMarket): MarketResolution | undefined {
  const r = m.resolution;
  if (!r) return undefined;
  const win = winNo(m) ?? null;
  if (r.mode === 'auto') {
    if (m.status !== 'settled') return undefined;
    const basis = r.sources[0];
    return {
      mode: 'auto',
      result: m.winSeq === 1 ? 'up' : 'down',
      resolvedBy: 'AUTO',
      resolvedAt: iso(r.resolvedAt),
      symbol: r.symbol,
      windowStart: iso(r.windowStart),
      windowEnd: iso(r.windowEnd),
      startPrice: basis?.startPrice ?? null,
      endPrice: basis?.endPrice ?? null,
      source: basis?.label ?? null,
      sources: r.sources,
    };
  }
  const deadline = r.disputeDeadlineAt;
  const open = (m.status === 'publicizing' || m.status === 'disputed') && deadline !== undefined && deadline > Date.now();
  return {
    mode: 'platform',
    voided: m.status === 'voided',
    resultOptionNo: m.status === 'voided' ? null : win,
    publicizedOptionNo: win,
    basis: r.basis,
    evidenceUrls: r.evidenceUrls,
    explanation: r.explanation,
    actualStartAt: null,
    resolvedBy: r.resolvedBy,
    resolvedAt: iso(r.resolvedAt),
    disputeDeadlineAt: deadline === undefined ? null : iso(deadline),
    disputeCount: m.status === 'disputed' ? 1 : 0,
    dispute: null,
    myDispute: r.myDispute
      ? { disputeNo: r.myDispute.disputeNo, claimedOptionNo: r.myDispute.claimedOptionNo, claimVoid: r.myDispute.claimVoid, status: r.myDispute.status, receivedAt: r.myDispute.receivedAt }
      : null,
    canDispute: open && !r.myDispute && m.bets.length > 0,
  };
}

/** M1 提议的结算合计和本人派彩（后端没有；原型按需求第 3.5 节现算，合计把赢方奖池当一个持仓近似） */
function settleOf(m: StoredMarket, pool: MarketPool): Pick<MarketDetail, 'settleSummary' | 'myPayout'> {
  const no = winNo(m);
  if (m.status !== 'settled' || !no) return {};
  const win = m.options.find((o) => o.optionNo === no)!;
  const rate = rateFromPercent(m.feeRate);
  const p = payout(win.pool, win.pool, pool.total, rate);
  const loser = sub(dec(pool.total), dec(win.pool));
  const out: Pick<MarketDetail, 'settleSummary' | 'myPayout'> = {
    settleSummary: { feeRate: m.feeRate, payoutTotal: p.payout, feeTotal: p.fee, dust: toFixed(sub(loser, dec(p.gross)), 2) },
  };
  const mine = pool.mine?.find((x) => x.optionNo === win.optionNo);
  if (mine) {
    const pm = payout(mine.amount, win.pool, pool.total, rate);
    out.myPayout = { payout: pm.payout, fee: pm.fee };
  }
  return out;
}

function detailOf(m: StoredMarket): MarketDetail {
  const pool = poolOf(m, true);
  return {
    ...cardOf(m),
    pool,
    rules: m.rules,
    resolutionMode: m.resolutionMode,
    sourcePrimary: m.sourcePrimary,
    sourceBackup: m.sourceBackup,
    userCap: CATEGORY_CAP[m.category],
    disputeWindowMinutes: m.disputeWindowMinutes,
    voidReason: m.voidReason,
    options: m.options.map((o) => ({ optionNo: o.optionNo, seq: o.seq, label: o.label, winCondition: o.win })),
    clarifications: m.clarifications.map((c) => ({ text: c.text, time: iso(c.time) })),
    feeChanges: m.feeChanges.map((c) => ({ ...c, time: iso(c.time) })),
    winningOptionNo: m.status === 'settled' ? winNo(m) : null,
    settling: false,
    resolution: resolutionOf(m),
    ...settleOf(m, pool),
  };
}

const ACTIVE: MarketStatus[] = ['listed', 'paused'];
const CLOSED: MarketStatus[] = ['closed', 'pending_resolution', 'publicizing', 'disputed'];

export function listMarkets(q: MarketQuery): Promise<MarketCard[]> {
  return respond(
    () => {
      const status = q.status ?? 'active';
      const k = (q.keyword ?? '').trim().toLowerCase();
      const items = markets.filter((m) => {
        if (q.category && m.category !== q.category) return false;
        if (status === 'active' && !ACTIVE.includes(m.status)) return false;
        if (status === 'closed' && !CLOSED.includes(m.status)) return false;
        if (status === 'ended' && (ACTIVE.includes(m.status) || CLOSED.includes(m.status))) return false;
        return !k || [m.title.zhCN, m.title.en, m.title.zhTW].some((s) => s.toLowerCase().includes(k));
      });
      const sorted = [...items];
      if (q.sort === 'newest') sorted.sort((a, b) => b.createdAt - a.createdAt);
      else if (q.sort === 'pool') sorted.sort((a, b) => Number(poolOf(b, false).total) - Number(poolOf(a, false).total));
      else sorted.sort((a, b) => (status === 'ended' ? b.closeAt - a.closeAt : a.closeAt - b.closeAt));
      return sorted.map(cardOf);
    },
    () => [],
  );
}

function find(publicNo: string): StoredMarket {
  const m = markets.find((x) => x.publicNo === publicNo);
  if (!m) throw new ApiError('notFound');
  return m;
}

export function getMarket(publicNo: string): Promise<MarketDetail> {
  return respond(() => detailOf(find(publicNo)));
}

const placed = new Map<string, BetResult>();

export function placeBet(publicNo: string, req: BetRequest): Promise<BetResult> {
  return mutate(() => {
    const dup = placed.get(req.clientReqId);
    if (dup) return { ...dup, duplicate: true };
    const m = find(publicNo);
    if (m.status === 'paused') throw new ApiError('marketPaused');
    if (m.status !== 'listed') throw new ApiError('marketClosed');
    if (Date.now() >= m.closeAt) throw new ApiError('marketClosed');
    const o = m.options.find((x) => x.optionNo === req.optionNo);
    if (!o) throw new ApiError('optionNotFound');
    const b = dec(req.amount);
    const limits = poolOf(m, true).limits!;
    if (b < dec(limits.min)) throw new ApiError('betBelowMin');
    if (b > dec(limits.max)) throw new ApiError('betAboveMax');
    if (b > dec(limits.remaining)) throw new ApiError('betOverUserCap');
    if (b > dec(db.account.available)) throw new ApiError('insufficientFunds');
    if (db.kyc.status !== 'approved' && dec(db.account.available) + dec(db.account.betLocked) > dec('1000')) throw new ApiError('kycRequired');
    const before = poolOf(m, false);
    const mult = multiplier(toFixed(add(dec(o.pool), b), 2), toFixed(add(dec(before.total), b), 2), rateFromPercent(m.feeRate));
    if (!m.bets.some((x) => x.optionSeq === o.seq)) o.bettors += 1;
    o.pool = toFixed(add(dec(o.pool), b), 2);
    const now = Date.now();
    const betNo = snowflakeAt(now, 1000 + seq++);
    m.bets.push({ betNo, optionSeq: o.seq, amount: toFixed(b, 2), receivedAt: now, multAtBet: mult, feeRateAtBet: m.feeRate });
    db.account.available = toFixed(sub(dec(db.account.available), b), 2);
    db.account.betLocked = toFixed(add(dec(db.account.betLocked), b), 2);
    db.ledger.unshift({ id: `L${betNo}`, type: 'bet', amount: `-${toFixed(b, 2)}`, ref: '', time: iso(now), market: { publicNo: m.publicNo, title: m.title } });
    emit({ type: 'account' });
    const result: BetResult = {
      betNo,
      marketNo: m.publicNo,
      optionNo: o.optionNo,
      amount: toFixed(b, 2),
      receivedAt: iso(now),
      status: 'active',
      duplicate: false,
      available: db.account.available,
      betLocked: db.account.betLocked,
      pool: poolOf(m, true),
    };
    placed.set(req.clientReqId, result);
    return result;
  });
}

function tabOf(s: MarketStatus): MyBetTab {
  if (ACTIVE.includes(s)) return 'active';
  if (CLOSED.includes(s)) return 'pending';
  return s === 'settled' ? 'settled' : 'refunded';
}

export function listMyBets(tab: MyBetTab, page: number, pageSize: number): Promise<MyBets> {
  const empty = (): MyBets => ({
    items: [],
    total: 0,
    page,
    pageSize,
    summary: { active: { count: 0, amount: '0.00' }, pending: { count: 0, amount: '0.00' }, settled: { count: 0, amount: '0.00' }, refunded: { count: 0, amount: '0.00' } },
    totals: { staked: '0.00', payout: '0.00', net: '0.00' },
  });
  return respond(() => {
    const all: MyBet[] = [];
    for (const m of markets) {
      const pool = poolOf(m, false);
      const rate = rateFromPercent(m.feeRate);
      for (const b of m.bets) {
        const o = m.options.find((x) => x.seq === b.optionSeq)!;
        const t = tabOf(m.status);
        const item: MyBet = {
          betNo: b.betNo,
          marketNo: m.publicNo,
          market: m.title,
          marketStatus: m.status,
          optionNo: o.optionNo,
          seq: o.seq,
          option: o.label,
          amount: b.amount,
          receivedAt: iso(b.receivedAt),
          tab: t,
          status: t === 'refunded' ? 'refunded' : 'active',
          refundReason: t === 'refunded' ? 'VOIDED' : undefined,
          multAtBet: b.multAtBet,
          feeRateAtBet: b.feeRateAtBet,
          category: m.category,
        };
        if (pool.frozen && t !== 'refunded') item.finalMult = pool.options.find((x) => x.optionNo === o.optionNo)!.mult;
        if (t === 'settled') {
          const won = winNo(m) === o.optionNo;
          item.won = won;
          if (won) {
            const p = payout(b.amount, o.pool, pool.total, rate);
            item.payout = p.payout;
            item.fee = p.fee;
          } else {
            item.payout = '0.00';
            item.fee = '0.00';
          }
        }
        all.push(item);
      }
    }
    all.sort((a, b) => Date.parse(b.receivedAt) - Date.parse(a.receivedAt));
    const out = empty();
    for (const k of ['active', 'pending', 'settled', 'refunded'] as MyBetTab[]) {
      const xs = all.filter((x) => x.tab === k);
      out.summary[k] = { count: xs.length, amount: toFixed(sum(xs.map((x) => dec(x.amount))), 2) };
    }
    const settled = all.filter((x) => x.tab === 'settled');
    const staked = sum(settled.map((x) => dec(x.amount)));
    const paid = sum(settled.map((x) => dec(x.payout ?? '0')));
    out.totals = { staked: toFixed(sum(all.filter((x) => x.tab !== 'refunded').map((x) => dec(x.amount))), 2), payout: toFixed(paid, 2), net: toFixed(sub(paid, staked), 2) };
    const items = all.filter((x) => x.tab === tab);
    out.items = items.slice((page - 1) * pageSize, page * pageSize);
    out.total = items.length;
    return out;
  }, empty);
}

/** 提交争议（规则和后端 S7 一样：公示期内、有下注、每人一次、理由必填 ≤ 1,000 字、主张不能和公示的结果一样） */
export function submitDispute(publicNo: string, req: DisputeRequest): Promise<DisputeReceipt> {
  return mutate(() => {
    const m = find(publicNo);
    const r = m.resolution;
    if ((m.status !== 'publicizing' && m.status !== 'disputed') || r?.mode !== 'platform' || !r.disputeDeadlineAt || r.disputeDeadlineAt <= Date.now()) throw new ApiError('disputeClosed');
    if (m.bets.length === 0) throw new ApiError('disputeNotBettor');
    if (r.myDispute) throw new ApiError('disputeExists');
    const claimVoid = req.claimedOptionNo === 'void';
    if (!req.reason.trim() || req.reason.length > 1000 || (!claimVoid && (!m.options.some((o) => o.optionNo === req.claimedOptionNo) || req.claimedOptionNo === winNo(m)))) {
      throw new ApiError('badRequest');
    }
    disputes.set(publicNo, req);
    r.myDispute = { disputeNo: no(0), marketNo: publicNo, claimedOptionNo: claimVoid ? null : req.claimedOptionNo, claimVoid, status: 'open', receivedAt: iso(Date.now()) };
    m.status = 'disputed';
    return r.myDispute;
  });
}

/** 本注预计回报（下注抽屉用，和 lib/pool 同一个函数；测试用） */
export const previewReturn = (pool: string, total: string, stake: string, feeRate: string): string => expectedReturn(pool, total, stake, rateFromPercent(feeRate));
